// SPDX-License-Identifier: AGPL-3.0-or-later
//! Typed helper client: one method per route the native UI uses.
//!
//! Blocking calls over plain HTTP (the helper only serves loopback, so no
//! TLS and no async runtime). The token rides every request; unknown helper
//! versions fail loudly via [`Client::ensure_compatible`].

use std::io::Read as _;
use std::path::Path;

use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::error::{ApiError, Error};
use crate::sse::EventStream;
use crate::types::{
    BookDetail, BookSummary, BuildResult, CastPayload, Health, Job, PlanResult, SampleResult,
    Settings, UploadResult, ValidateResult, Versions, VoiceDetail, VoicesResponse,
};

/// Contract integer this client speaks (docs/api.md section 8).
pub const SUPPORTED_API_VERSION: u32 = 1;

/// Per-run token header (never logged, never put on a command line).
pub const TOKEN_HEADER: &str = "X-Auloud-Token";

/// Chapter/page/device selection for plan and build (opaque range strings).
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct Selection {
    /// Chapter range (`"3-5,7"`); mutually exclusive with `pages`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub chapters: Option<String>,
    /// PDF page range; renders the whole covering chapters.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pages: Option<String>,
    /// `auto | cpu | cuda` (build only; plan ignores it).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub device: Option<String>,
}

/// Audition-clip request (`POST /api/voices/sample` and per-voice variant).
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct SampleRequest {
    /// Voices to render (omit for all of the engine).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub voices: Option<Vec<String>>,
    /// Re-render even when cached.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub regenerate: Option<bool>,
    /// Engine id (default `kokoro` server-side when omitted).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub engine: Option<String>,
}

/// Typed client bound to one helper (`base_url` plus per-run token).
#[derive(Debug, Clone)]
pub struct Client {
    agent: ureq::Agent,
    base: String,
    token: String,
}

impl Client {
    /// Bind to a helper origin. Only loopback origins are accepted; anything
    /// else is refused before any byte is sent.
    ///
    /// # Errors
    ///
    /// Returns [`Error::BadUrl`] for malformed origins and
    /// [`Error::NonLoopback`] for off-machine hosts.
    pub fn new(base_url: &str, token: &str) -> Result<Self, Error> {
        let origin = base_url.trim().trim_end_matches('/');
        let rest = origin
            .strip_prefix("http://")
            .ok_or_else(|| Error::BadUrl(origin.to_owned()))?;
        // Userinfo has no legitimate use here (the token header carries
        // auth); reject it outright instead of parsing around it.
        if rest.contains('@') {
            return Err(Error::BadUrl(origin.to_owned()));
        }
        let host = rest.split(['/', '?', '#']).next().unwrap_or("");
        let name = host.split(':').next().unwrap_or("").to_ascii_lowercase();
        if name != "127.0.0.1" && name != "localhost" {
            return Err(Error::NonLoopback(origin.to_owned()));
        }
        if host == ":" || name.is_empty() {
            return Err(Error::BadUrl(origin.to_owned()));
        }
        // Pool idle age stays below the helper's 5 s keep-alive close: the
        // UI polls jobs every few seconds, and a pooled connection the
        // server already closed fails with a reset instead of an answer.
        let config = ureq::config::Config::builder()
            .http_status_as_error(false)
            .max_idle_age(std::time::Duration::from_secs(2))
            .build();
        Ok(Self {
            agent: ureq::Agent::new_with_config(config),
            base: origin.to_owned(),
            token: token.to_owned(),
        })
    }

    /// The bound origin (for logs; the token is never exposed).
    pub fn base_url(&self) -> &str {
        &self.base
    }

    fn get_json<T: DeserializeOwned>(&self, path: &str) -> Result<T, Error> {
        let response = self
            .agent
            .get(&format!("{}{}", self.base, path))
            .header(TOKEN_HEADER, &self.token)
            .call()?;
        self.read_json(response)
    }

    fn send_json<T: DeserializeOwned, B: Serialize>(
        &self,
        verb: &str,
        path: &str,
        body: Option<&B>,
    ) -> Result<T, Error> {
        let url = format!("{}{}", self.base, path);
        let text = body
            .map(serde_json::to_string)
            .transpose()
            .map_err(|err| Error::Json(err.to_string()))?;
        let response = if verb == "GET" {
            self.agent
                .get(&url)
                .header(TOKEN_HEADER, &self.token)
                .call()?
        } else {
            let request = match verb {
                "PUT" => self.agent.put(&url),
                "PATCH" => self.agent.patch(&url),
                "POST" => self.agent.post(&url),
                // `send_json` callers pass literals; anything else is a bug here.
                _ => unreachable!("scribe-client bug: unknown verb {verb:?}"),
            }
            .header(TOKEN_HEADER, &self.token);
            match text {
                Some(text) => request
                    .header("Content-Type", "application/json")
                    .send(text.as_str())?,
                None => request.send_empty()?,
            }
        };
        self.read_json(response)
    }

    fn read_json<T: DeserializeOwned>(
        &self,
        response: ureq::http::Response<ureq::Body>,
    ) -> Result<T, Error> {
        let status = response.status().as_u16();
        let text = response
            .into_body()
            .read_to_string()
            .map_err(|err| Error::Transport(err.to_string()))?;
        if !(200..300).contains(&status) {
            return Err(http_error(status, &text));
        }
        serde_json::from_str::<T>(&text).map_err(|err| Error::Json(err.to_string()))
    }

    /// `GET /api/health`.
    pub fn health(&self) -> Result<Health, Error> {
        self.get_json("/api/health")
    }

    /// `GET /api/version` (the ready-line versions object at runtime).
    pub fn version(&self) -> Result<Versions, Error> {
        self.get_json("/api/version")
    }

    /// Fail loudly unless the helper speaks [`SUPPORTED_API_VERSION`].
    /// A helper too old to have `/api/version` fails with its 404, which is
    /// loud enough (it names the upgrade, never degrades).
    ///
    /// # Errors
    ///
    /// Returns [`Error::VersionMismatch`] on a known-but-foreign contract.
    pub fn ensure_compatible(&self) -> Result<Versions, Error> {
        let versions = self.version()?;
        check_compatible(&versions)?;
        Ok(versions)
    }

    /// `GET /api/books`.
    pub fn list_books(&self) -> Result<Vec<BookSummary>, Error> {
        #[derive(serde::Deserialize)]
        struct Books {
            #[serde(default)]
            books: Vec<BookSummary>,
        }
        Ok(self.get_json::<Books>("/api/books")?.books)
    }

    /// `GET /api/books/{book_id}`.
    pub fn book_detail(&self, book_id: &str) -> Result<BookDetail, Error> {
        self.get_json(&format!("/api/books/{book_id}"))
    }

    /// `POST /api/books/upload` (queues a draft job).
    pub fn upload_book(&self, path: &Path) -> Result<UploadResult, Error> {
        let form = ureq::unversioned::multipart::Form::new()
            .file("file", path)
            .map_err(|err| Error::Io(err.to_string()))?;
        let response = self
            .agent
            .post(&format!("{}/api/books/upload", self.base))
            .header(TOKEN_HEADER, &self.token)
            .send(form)?;
        self.read_json(response)
    }

    /// `POST /api/books/{book_id}/plan` (pure preflight, no job).
    pub fn plan_build(&self, book_id: &str, selection: &Selection) -> Result<PlanResult, Error> {
        self.send_json(
            "POST",
            &format!("/api/books/{book_id}/plan"),
            Some(selection),
        )
    }

    /// `POST /api/books/{book_id}/build` (queues a detached build job).
    pub fn queue_build(&self, book_id: &str, selection: &Selection) -> Result<BuildResult, Error> {
        self.send_json(
            "POST",
            &format!("/api/books/{book_id}/build"),
            Some(selection),
        )
    }

    /// `GET /api/jobs` (newest first).
    pub fn list_jobs(&self) -> Result<Vec<Job>, Error> {
        #[derive(serde::Deserialize)]
        struct Jobs {
            #[serde(default)]
            jobs: Vec<Job>,
        }
        Ok(self.get_json::<Jobs>("/api/jobs")?.jobs)
    }

    /// `GET /api/jobs/{job_id}`.
    pub fn job(&self, job_id: &str) -> Result<Job, Error> {
        self.get_json(&format!("/api/jobs/{job_id}"))
    }

    /// `POST /api/jobs/{job_id}/pause`.
    pub fn pause_job(&self, job_id: &str) -> Result<Job, Error> {
        self.send_json::<Job, ()>("POST", &format!("/api/jobs/{job_id}/pause"), None)
    }

    /// `POST /api/jobs/{job_id}/resume`.
    pub fn resume_job(&self, job_id: &str) -> Result<Job, Error> {
        self.send_json::<Job, ()>("POST", &format!("/api/jobs/{job_id}/resume"), None)
    }

    /// `POST /api/jobs/{job_id}/cancel`.
    pub fn cancel_job(&self, job_id: &str) -> Result<Job, Error> {
        self.send_json::<Job, ()>("POST", &format!("/api/jobs/{job_id}/cancel"), None)
    }

    /// `GET /api/jobs/{job_id}/events` as a blocking event iterator. Pass
    /// the last seen `id` to resume; 0 starts from the first line. Ends when
    /// the server closes the stream (job terminal).
    pub fn events(
        &self,
        job_id: &str,
        last_event_id: u64,
    ) -> Result<EventStream<ureq::BodyReader<'static>>, Error> {
        let mut request = self
            .agent
            .get(&format!("{}/api/jobs/{job_id}/events", self.base))
            .header(TOKEN_HEADER, &self.token);
        if last_event_id > 0 {
            request = request.header("Last-Event-ID", last_event_id.to_string());
        }
        let response = request.call()?;
        let status = response.status().as_u16();
        if !(200..300).contains(&status) {
            let text = response
                .into_body()
                .read_to_string()
                .map_err(|err| Error::Transport(err.to_string()))?;
            return Err(http_error(status, &text));
        }
        Ok(EventStream::new(response.into_body().into_reader()))
    }

    /// `GET /api/settings`.
    pub fn settings(&self) -> Result<Settings, Error> {
        self.get_json("/api/settings")
    }

    /// `PUT /api/settings` (each `None` keeps the stored value).
    pub fn update_settings(
        &self,
        keep_awake: Option<bool>,
        device: Option<&str>,
    ) -> Result<Settings, Error> {
        #[derive(Serialize)]
        struct Update<'a> {
            #[serde(skip_serializing_if = "Option::is_none")]
            keep_awake: Option<bool>,
            #[serde(skip_serializing_if = "Option::is_none")]
            device: Option<&'a str>,
        }
        self.send_json("PUT", "/api/settings", Some(&Update { keep_awake, device }))
    }

    /// `GET /api/books/{book_id}/cast`.
    pub fn cast(&self, book_id: &str) -> Result<CastPayload, Error> {
        self.get_json(&format!("/api/books/{book_id}/cast"))
    }

    /// `PUT /api/books/{book_id}/cast` (full replace with conflict guard).
    /// The `cast` document round-trips opaquely; `mtime` comes from GET.
    pub fn save_cast(
        &self,
        book_id: &str,
        cast: &serde_json::Value,
        mtime: f64,
    ) -> Result<CastPayload, Error> {
        #[derive(Serialize)]
        struct Save<'a> {
            cast: &'a serde_json::Value,
            expected_mtime: f64,
        }
        self.send_json(
            "PUT",
            &format!("/api/books/{book_id}/cast"),
            Some(&Save {
                cast,
                expected_mtime: mtime,
            }),
        )
    }

    /// `GET /api/cast/voices?engine=`.
    pub fn voices(&self, engine: &str) -> Result<VoicesResponse, Error> {
        let response = self
            .agent
            .get(&format!("{}/api/cast/voices", self.base))
            .header(TOKEN_HEADER, &self.token)
            .query("engine", engine)
            .call()?;
        self.read_json(response)
    }

    /// Voice dropdown rows for an engine.
    pub fn voice_details(&self, engine: &str) -> Result<Vec<VoiceDetail>, Error> {
        Ok(self.voices(engine)?.details)
    }

    /// `POST /api/voices/sample` (cached clips inline, else a job to poll).
    pub fn sample_voices(&self, request: &SampleRequest) -> Result<SampleResult, Error> {
        self.send_json("POST", "/api/voices/sample", Some(request))
    }

    /// `GET /api/voices/{name}.wav` (raw audio bytes).
    pub fn voice_wav(&self, name: &str) -> Result<Vec<u8>, Error> {
        let response = self
            .agent
            .get(&format!("{}/api/voices/{name}.wav", self.base))
            .header(TOKEN_HEADER, &self.token)
            .call()?;
        let status = response.status().as_u16();
        if !(200..300).contains(&status) {
            let text = response
                .into_body()
                .read_to_string()
                .map_err(|err| Error::Transport(err.to_string()))?;
            return Err(http_error(status, &text));
        }
        let mut bytes = Vec::new();
        response
            .into_body()
            .into_reader()
            .read_to_end(&mut bytes)
            .map_err(|err| Error::Transport(err.to_string()))?;
        Ok(bytes)
    }

    /// `POST /api/books/{book_id}/validate` (inline, read-only).
    pub fn validate(&self, book_id: &str) -> Result<ValidateResult, Error> {
        self.send_json::<ValidateResult, ()>(
            "POST",
            &format!("/api/books/{book_id}/validate"),
            None,
        )
    }
}

/// Shaped `{file, rule, message}` when the helper sent one, the `{"detail"}`
/// middleware shape next (auth/Host failures), synthesized `http:<status>`
/// otherwise (truncated, never a whole dump).
fn http_error(status: u16, text: &str) -> Error {
    #[derive(serde::Deserialize)]
    struct Detail {
        #[serde(default)]
        detail: Option<String>,
    }
    let mut error = serde_json::from_str::<ApiError>(text).unwrap_or(ApiError {
        file: String::new(),
        rule: String::new(),
        message: String::new(),
    });
    if error.message.is_empty() {
        let detail = serde_json::from_str::<Detail>(text)
            .ok()
            .and_then(|detail| detail.detail);
        error = match detail {
            Some(message) => ApiError {
                file: "http".to_owned(),
                rule: format!("http-{status}"),
                message,
            },
            None => ApiError {
                file: "http".to_owned(),
                rule: format!("http-{status}"),
                message: text.chars().take(300).collect(),
            },
        };
    }
    Error::Http { status, error }
}

/// Pure version gate behind [`Client::ensure_compatible`] (offline-testable).
///
/// # Errors
///
/// Returns [`Error::VersionMismatch`] unless `versions.api` equals
/// [`SUPPORTED_API_VERSION`].
pub fn check_compatible(versions: &Versions) -> Result<(), Error> {
    if versions.api != SUPPORTED_API_VERSION {
        return Err(Error::VersionMismatch {
            have: versions.api,
            want: SUPPORTED_API_VERSION,
        });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shaped_body_passes_through() {
        let error = http_error(
            409,
            r#"{"file":"b","rule":"work-lock-held","message":"b: busy"}"#,
        );
        match error {
            Error::Http { status, error } => {
                assert_eq!(status, 409);
                assert_eq!(error.rule, "work-lock-held");
                assert_eq!(error.message, "b: busy");
            }
            _ => panic!("want Http, got {error:?}"),
        }
    }

    #[test]
    fn middleware_detail_becomes_message() {
        let error = http_error(403, r#"{"detail":"forbidden"}"#);
        match error {
            Error::Http { status, error } => {
                assert_eq!(status, 403);
                assert_eq!(error.rule, "http-403");
                assert_eq!(error.message, "forbidden");
            }
            _ => panic!("want Http, got {error:?}"),
        }
    }

    #[test]
    fn empty_body_synthesizes() {
        let error = http_error(500, "");
        match error {
            Error::Http { status, error } => {
                assert_eq!(status, 500);
                assert_eq!(error.rule, "http-500");
                assert!(error.message.is_empty());
            }
            _ => panic!("want Http, got {error:?}"),
        }
    }
}
