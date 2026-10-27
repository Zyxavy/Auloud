// SPDX-License-Identifier: AGPL-3.0-or-later
//! Slice 20 TR1 (PC half): push a rendered bundle to the tablet receiver.
//!
//! A send runs on its own thread so the UI never blocks while zipping or
//! uploading hundreds of megabytes: [`Sender::begin`] snapshots its inputs
//! and spawns the worker, [`Sender::poll`] drains [`SendEvent`]s (call it
//! every frame). Progress is phases, never percents: zipping, hashing and
//! sending each report honestly, because ureq exposes no upload byte
//! counter and a fake percent would lie.
//!
//! Wire shape (tablet contract, see the `receive` package on the Player):
//! `POST http://<ip>:8089/bundle` as multipart with the zip in the file
//! part named `bundle`, plus `X-Pairing-Code` and `X-Sha256` headers. The
//! zip holds the bundle *contents* at top level with `/` separators and
//! `Stored` (no) compression: chapter MP3s do not compress, so deflate
//! would burn CPU for nothing.

use std::io::{Read, Write};
use std::path::{Component, Path, PathBuf};
use std::sync::mpsc::{self, Receiver};

/// Multipart file part name the tablet reads.
pub const TABLET_PART: &str = "bundle";
/// Fixed LAN port the tablet listens on (shown on its Receive screen).
pub const DEFAULT_PORT: u16 = 8089;

/// Events from the send thread (drained by [`Sender::poll`]).
pub enum SendEvent {
    Phase(String),
    Done(SendOutcome),
}

/// Terminal send result (rendered by the send section, never a traceback).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SendOutcome {
    Sent {
        book_id: String,
        title: String,
        duplicate: bool,
    },
    Failed(String),
}

/// Tablet reply body (`ok` plus file-and-rule `error` on failure; field
/// names are the tablet's camelCase wire shape).
#[derive(Debug, Clone, serde::Deserialize)]
pub struct SendReply {
    #[serde(default)]
    pub ok: bool,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(default, rename = "bookId")]
    pub book_id: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub duplicate: bool,
}

/// Send-thread state owned by the App (the ip/code fields survive book
/// switches; phase and outcome reset per send).
#[derive(Default)]
pub struct Sender {
    /// Tablet address, remembered across books.
    pub ip: String,
    /// Pairing code, remembered across books.
    pub code: String,
    rx: Option<Receiver<SendEvent>>,
    /// Current phase text while busy (`None` when idle or settled).
    pub phase: Option<String>,
    /// Last terminal outcome (`None` until the first send settles).
    pub outcome: Option<SendOutcome>,
}

impl Sender {
    /// True while a send thread owns the channel.
    pub fn busy(&self) -> bool {
        self.rx.is_some()
    }

    /// Snapshot the inputs and spawn the send thread (no-op while busy).
    pub fn begin(&mut self, bundle_dir: PathBuf, ip: String, port: u16, code: String) {
        if self.busy() {
            return;
        }
        self.ip = ip.clone();
        self.code = code.clone();
        let (tx, rx) = mpsc::channel();
        self.rx = Some(rx);
        self.phase = Some("Zipping…".to_owned());
        self.outcome = None;
        std::thread::spawn(move || run_send(bundle_dir, ip, port, code, tx));
    }

    /// Drain finished events (call every frame; never blocks).
    pub fn poll(&mut self) {
        let mut settled = false;
        if let Some(rx) = &self.rx {
            while let Ok(event) = rx.try_recv() {
                match event {
                    SendEvent::Phase(text) => self.phase = Some(text),
                    SendEvent::Done(outcome) => {
                        self.outcome = Some(outcome);
                        self.phase = None;
                        settled = true;
                    }
                }
            }
        }
        if settled {
            self.rx = None;
        }
    }
}

/// `http://<ip>:<port>/bundle` from a bare address (no scheme, path or
/// userinfo; the tablet screen shows exactly what to type). A trailing
/// `:port` on the address wins over `port`.
///
/// # Errors
///
/// Returns a human sentence when the address is blank or shaped wrong.
pub fn bundle_url(ip: &str, port: u16) -> Result<String, String> {
    let address = ip.trim().trim_end_matches('/');
    let address = address.strip_prefix("http://").unwrap_or(address);
    if address.is_empty()
        || address.contains(char::is_whitespace)
        || address.contains('/')
        || address.contains('@')
        || address.contains("://")
    {
        return Err(
            "tablet address must be a bare IP or hostname (no scheme, path, or userinfo)"
                .to_owned(),
        );
    }
    let (host, port) = match address.split_once(':') {
        None => (address, port),
        Some((host, tail)) if !tail.contains(':') => {
            let typed: u16 = tail.parse().map_err(|_| {
                "tablet port must be a number (or leave it off for 8089)".to_owned()
            })?;
            if host.is_empty() {
                return Err("tablet address must be a bare IP or hostname".to_owned());
            }
            (host, typed)
        }
        Some(_) => {
            return Err("tablet address must be IPv4 or a hostname (no IPv6)".to_owned());
        }
    };
    Ok(format!("http://{host}:{port}/bundle"))
}

/// Zip a bundle dir into `dst` (contents at top level, `/` separators,
/// `Stored` compression, entries sorted for determinism).
///
/// # Errors
///
/// Returns the `io` error when the walk, read or write fails.
pub fn zip_bundle_dir(src: &Path, dst: &Path) -> std::io::Result<u64> {
    let mut names = Vec::new();
    collect_files(src, src, &mut names)?;
    names.sort();
    let file = std::fs::File::create(dst)?;
    let mut zip = zip::ZipWriter::new(file);
    let options =
        zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    let mut buffer = vec![0u8; 8192];
    for relative in &names {
        let full = src.join(relative);
        zip.start_file(relative.replace('\\', "/"), options)?;
        let mut input = std::fs::File::open(&full)?;
        loop {
            let read = input.read(&mut buffer)?;
            if read == 0 {
                break;
            }
            zip.write_all(&buffer[..read])?;
        }
    }
    zip.finish()?;
    Ok(std::fs::metadata(dst)?.len())
}

fn collect_files(base: &Path, dir: &Path, out: &mut Vec<String>) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if path.is_dir() {
            collect_files(base, &path, out)?;
        } else if path.is_file() {
            let relative = path.strip_prefix(base).map_err(|_| {
                std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "bundle path escapes its dir",
                )
            })?;
            let mut name = String::new();
            for component in relative.components() {
                match component {
                    Component::Normal(part) => {
                        if !name.is_empty() {
                            name.push('/');
                        }
                        name.push_str(&part.to_string_lossy());
                    }
                    _ => {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::InvalidInput,
                            "bundle path escapes its dir",
                        ))
                    }
                }
            }
            if !name.is_empty() {
                out.push(name);
            }
        }
    }
    Ok(())
}

/// Streaming sha256 of a file as lowercase hex.
///
/// # Errors
///
/// Returns the `io` error when the file cannot be read.
pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    use sha2::Digest as _;
    let mut digest = sha2::Sha256::new();
    let mut input = std::fs::File::open(path)?;
    let mut buffer = vec![0u8; 8192];
    loop {
        let read = input.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        digest.update(&buffer[..read]);
    }
    Ok(format!("{:x}", digest.finalize()))
}

/// Map an HTTP status plus body to the terminal outcome (pure; the
/// tablet's file-and-rule `error` passes through verbatim).
pub fn parse_send_response(status: u16, body: &str) -> SendOutcome {
    match status {
        200 => match serde_json::from_str::<SendReply>(body) {
            Ok(reply) if reply.ok => SendOutcome::Sent {
                book_id: reply.book_id.unwrap_or_default(),
                title: reply.title.unwrap_or_default(),
                duplicate: reply.duplicate,
            },
            Ok(reply) => SendOutcome::Failed(
                reply
                    .error
                    .unwrap_or_else(|| "tablet refused without detail".to_owned()),
            ),
            Err(_) => SendOutcome::Failed("tablet answered 200 with an unreadable body".to_owned()),
        },
        403 => SendOutcome::Failed("wrong pairing code (check the tablet screen)".to_owned()),
        409 => SendOutcome::Failed("hash mismatch in transit (resend the bundle)".to_owned()),
        400 => match serde_json::from_str::<SendReply>(body) {
            Ok(reply) => SendOutcome::Failed(
                reply
                    .error
                    .unwrap_or_else(|| "tablet rejected the bundle".to_owned()),
            ),
            Err(_) => SendOutcome::Failed(format!("tablet rejected the bundle: {}", snippet(body))),
        },
        other => SendOutcome::Failed(format!("tablet answered {other}: {}", snippet(body))),
    }
}

fn snippet(body: &str) -> String {
    body.chars().take(200).collect()
}

fn run_send(
    bundle_dir: PathBuf,
    ip: String,
    port: u16,
    code: String,
    tx: std::sync::mpsc::Sender<SendEvent>,
) {
    let send = |event: SendEvent| tx.send(event).ok();
    let url = match bundle_url(&ip, port) {
        Ok(url) => url,
        Err(detail) => {
            send(SendEvent::Done(SendOutcome::Failed(detail)));
            return;
        }
    };
    if code.trim().is_empty() {
        send(SendEvent::Done(SendOutcome::Failed(
            "pairing code is empty (read it off the tablet screen)".to_owned(),
        )));
        return;
    }
    let zip_path = std::env::temp_dir().join(format!(
        "auloud-send-{}.zip",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|elapsed| elapsed.as_millis())
            .unwrap_or(0)
    ));
    if let Err(err) = zip_bundle_dir(&bundle_dir, &zip_path) {
        send(SendEvent::Done(SendOutcome::Failed(format!(
            "cannot zip bundle: {err}"
        ))));
        return;
    }
    let bytes = std::fs::metadata(&zip_path)
        .map(|meta| meta.len())
        .unwrap_or(0);
    send(SendEvent::Phase(format!("Sending {}…", human_bytes(bytes))));
    let hex = match sha256_file(&zip_path) {
        Ok(hex) => hex,
        Err(err) => {
            std::fs::remove_file(&zip_path).ok();
            send(SendEvent::Done(SendOutcome::Failed(format!(
                "cannot hash bundle: {err}"
            ))));
            return;
        }
    };
    let outcome = push_zip(&url, &code, &hex, &zip_path);
    std::fs::remove_file(&zip_path).ok();
    send(SendEvent::Done(outcome));
}

fn push_zip(url: &str, code: &str, hex: &str, zip_path: &Path) -> SendOutcome {
    let form = match ureq::unversioned::multipart::Form::new().file(TABLET_PART, zip_path) {
        Ok(form) => form,
        Err(err) => return SendOutcome::Failed(format!("cannot stage upload: {err}")),
    };
    let response = http_agent()
        .post(url)
        .header("X-Pairing-Code", code)
        .header("X-Sha256", hex)
        .send(form);
    match response {
        Ok(response) => {
            let status = response.status().as_u16();
            let body = response.into_body().read_to_string().unwrap_or_default();
            parse_send_response(status, &body)
        }
        Err(err) => SendOutcome::Failed(format!("cannot reach tablet: {err}")),
    }
}

/// Shared HTTP agent (long transfers need a ceiling, never infinity:
/// thirty generous minutes, so true stalls fail loudly instead of
/// hanging the worker thread — and its UI button — forever).
pub fn http_agent() -> ureq::Agent {
    let config = ureq::config::Config::builder()
        .http_status_as_error(false)
        .max_idle_age(std::time::Duration::from_secs(2))
        // No per-byte progress exists, so a stalled connection would hang
        // the send thread (and the Send button) forever. Generous ceiling:
        // slow hotspots still finish, true stalls fail loudly instead.
        .timeout_global(Some(std::time::Duration::from_secs(30 * 60)))
        .build();
    ureq::Agent::new_with_config(config)
}

/// `128 MB` style sizes for the sending phase (one decimal, no fake percent).
pub fn human_bytes(bytes: u64) -> String {
    const UNITS: &[&str] = &["B", "KB", "MB", "GB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit + 1 < UNITS.len() {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{} {}", bytes, UNITS[unit])
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn urls_from_bare_addresses() {
        assert_eq!(
            bundle_url("192.168.1.5", 8089),
            Ok("http://192.168.1.5:8089/bundle".to_owned())
        );
        assert_eq!(
            bundle_url("  192.168.1.5/ ", 1),
            Ok("http://192.168.1.5:1/bundle".to_owned())
        );
        assert_eq!(
            bundle_url("http://192.168.1.5", 8089),
            Ok("http://192.168.1.5:8089/bundle".to_owned())
        );
        assert_eq!(
            bundle_url("192.168.1.5:1234", 8089),
            Ok("http://192.168.1.5:1234/bundle".to_owned())
        );
    }

    #[test]
    fn urls_reject_shaped_wrong() {
        for bad in [
            "",
            "   ",
            "a b",
            "host/path",
            "user@host",
            "https://host",
            "http://",
            "host:notaport",
            ":8089",
            "1:2:3",
        ] {
            assert!(bundle_url(bad, 8089).is_err(), "{bad:?} must fail");
        }
    }

    #[test]
    fn zip_round_trips_contents() {
        let root = std::env::temp_dir().join(format!(
            "auloud-send-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|e| e.as_nanos())
                .unwrap_or(0)
        ));
        let bundle = root.join("book");
        std::fs::create_dir_all(bundle.join("text")).unwrap();
        std::fs::write(bundle.join("manifest.json"), "{}").unwrap();
        std::fs::write(bundle.join("text").join("ch001.json"), "[]").unwrap();
        let zip_path = root.join("out.zip");
        let bytes = zip_bundle_dir(&bundle, &zip_path).unwrap();
        assert!(bytes > 0);
        let file = std::fs::File::open(&zip_path).unwrap();
        let mut archive = zip::ZipArchive::new(file).unwrap();
        assert_eq!(archive.len(), 2);
        let mut names: Vec<String> = (0..archive.len())
            // zip 9: `name` is fallible (non-UTF8 entries); ours are ASCII paths.
            .map(|i| archive.by_index(i).unwrap().name().unwrap().into_owned())
            .collect();
        names.sort();
        assert_eq!(names, vec!["manifest.json", "text/ch001.json"]);
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn sha256_matches_known_vector() {
        let root = std::env::temp_dir().join(format!(
            "auloud-send-hash-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|e| e.as_nanos())
                .unwrap_or(0)
        ));
        std::fs::create_dir_all(&root).unwrap();
        let file = root.join("abc.txt");
        std::fs::write(&file, "abc").unwrap();
        assert_eq!(
            sha256_file(&file).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn replies_map_to_outcomes() {
        assert_eq!(
            parse_send_response(
                200,
                r#"{"ok":true,"bookId":"b","title":"T","duplicate":true}"#
            ),
            SendOutcome::Sent {
                book_id: "b".to_owned(),
                title: "T".to_owned(),
                duplicate: true
            }
        );
        assert_eq!(
            parse_send_response(200, r#"{"ok":false,"error":"x: y: z"}"#),
            SendOutcome::Failed("x: y: z".to_owned())
        );
        assert_eq!(
            parse_send_response(403, "{}"),
            SendOutcome::Failed("wrong pairing code (check the tablet screen)".to_owned())
        );
        assert_eq!(
            parse_send_response(409, "{}"),
            SendOutcome::Failed("hash mismatch in transit (resend the bundle)".to_owned())
        );
        assert_eq!(
            parse_send_response(400, r#"{"ok":false,"error":"m: r: d"}"#),
            SendOutcome::Failed("m: r: d".to_owned())
        );
        assert!(matches!(
            parse_send_response(500, "boom"),
            SendOutcome::Failed(_)
        ));
    }

    #[test]
    fn byte_sizes_read_honestly() {
        assert_eq!(human_bytes(0), "0 B");
        assert_eq!(human_bytes(999), "999 B");
        assert_eq!(human_bytes(128 * 1024 * 1024), "128.0 MB");
    }
}
