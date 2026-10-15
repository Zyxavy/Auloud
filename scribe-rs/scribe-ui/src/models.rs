// SPDX-License-Identifier: AGPL-3.0-or-later
//! Slice 20 MM1: Kokoro model pair manager (same PC as the helper).
//!
//! Direct filesystem over `<workspace>/models` (the home D-163 gives the
//! helper: workspace/models wins, so whatever lands here is what renders
//! use). No helper round-trip: same machine, same files.
//!
//! A manager op runs on its own thread so the UI never blocks while
//! hashing or downloading hundreds of megabytes: [`Manager::begin`]
//! snapshots its inputs and spawns the worker, [`Manager::poll`] drains
//! [`ModelEvent`]s (call it every frame). Progress is bytes, never a fake
//! percent; ureq reports real downloaded counts.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc::{self, Receiver};

/// One pinned model file (source: kokoro-onnx `model-files-v1.1`,
/// digests verified 2026-10-14; see the Slice 20 plan).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PinnedFile {
    /// File name inside the models dir.
    pub name: &'static str,
    /// Full download URL.
    pub url: &'static str,
    /// Exact byte size.
    pub size: u64,
    /// Lowercase hex sha256.
    pub sha256: &'static str,
}

/// The Kokoro pair (onnx + voices).
pub const PAIR: [PinnedFile; 2] = [
    PinnedFile {
        name: "kokoro-v1.0.onnx",
        url: "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.onnx",
        size: 325_505_369,
        sha256: "beb0d1848dee9a49da392cc3df26958d46cfa35d321edf434f52949153f0df3a",
    },
    PinnedFile {
        name: "voices-v1.0.bin",
        url: "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/voices-v1.0.bin",
        size: 28_214_398,
        sha256: "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d",
    },
];

/// License line under the pair (docs/08-Licenses.md).
pub const LICENSE_LINE: &str = "Models: Apache-2.0 (https://huggingface.co/hexgrad/Kokoro-82M)";

/// Per-file state: missing, hash-pinned, or present-but-unpinned (works
/// with Scribe today; re-download offered, never forced).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FileState {
    Missing,
    Pinned,
    Unpinned { size: u64 },
}

/// Classify one models-dir file (pure; size is checked before the slow
/// hash, so mismatched files never pay for hashing).
pub fn file_state(models_dir: &Path, pinned: &PinnedFile) -> FileState {
    let path = models_dir.join(pinned.name);
    let size = match std::fs::metadata(&path) {
        Ok(meta) if meta.is_file() => meta.len(),
        _ => return FileState::Missing,
    };
    if size != pinned.size {
        return FileState::Unpinned { size };
    }
    match crate::send::sha256_file(&path) {
        Ok(hex) if hex == pinned.sha256 => FileState::Pinned,
        _ => FileState::Unpinned { size },
    }
}

/// Fresh states for the whole pair.
pub fn refresh_states(models_dir: &Path) -> Vec<FileState> {
    PAIR.iter()
        .map(|pinned| file_state(models_dir, pinned))
        .collect()
}

/// Models dir for a workspace (the D-163 home the helper prefers).
pub fn models_dir(workspace: &Path) -> PathBuf {
    workspace.join("models")
}

/// Manager op (snapshotted at [`Manager::begin`]).
#[derive(Debug, Clone)]
pub enum ModelOp {
    Refresh,
    Download(usize),
    Sideload(usize, PathBuf),
    Delete(usize),
}

/// Events from the manager thread (drained by [`Manager::poll`]).
pub enum ModelEvent {
    Progress { downloaded: u64, total: Option<u64> },
    Done(Settlement),
}

/// Terminal manager result (rendered by the models screen, never a
/// traceback). Every settle carries fresh states: hashing hundreds of
/// megabytes has no business on the UI thread, so the worker that just
/// touched the dir re-reads it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settlement {
    /// Human note (empty when there is nothing to say).
    pub note: String,
    /// Present on failure, absent on success.
    pub error: Option<String>,
    /// Fresh pair states.
    pub states: Vec<FileState>,
}

/// Manager-thread state owned by the App.
#[derive(Default)]
pub struct Manager {
    /// Fresh states after the last settle (`Missing` pair until checked).
    pub states: Vec<FileState>,
    rx: Option<Receiver<ModelEvent>>,
    /// Current phase text while busy (`None` when idle or settled).
    pub phase: Option<String>,
    /// Download progress `(bytes, total?)` while busy.
    pub progress: Option<(u64, Option<u64>)>,
    /// Last terminal settlement (`None` until the first op settles).
    pub outcome: Option<Settlement>,
}

impl Manager {
    /// Fresh manager (nothing checked yet).
    pub fn fresh() -> Self {
        Self {
            states: vec![FileState::Missing; PAIR.len()],
            ..Default::default()
        }
    }

    /// True while a manager thread owns the channel.
    pub fn busy(&self) -> bool {
        self.rx.is_some()
    }

    /// Snapshot the inputs and spawn the manager thread (no-op while busy).
    pub fn begin(&mut self, op: ModelOp, dir: PathBuf) {
        if self.busy() {
            return;
        }
        fn name(index: usize) -> &'static str {
            PAIR.get(index).map(|pinned| pinned.name).unwrap_or("model")
        }
        self.phase = Some(match &op {
            ModelOp::Refresh => "Checking models…".to_owned(),
            ModelOp::Download(index) => format!("Downloading {}…", name(*index)),
            ModelOp::Sideload(index, _) => format!("Copying {}…", name(*index)),
            ModelOp::Delete(index) => format!("Deleting {}…", name(*index)),
        });
        self.progress = None;
        self.outcome = None;
        let (tx, rx) = mpsc::channel();
        self.rx = Some(rx);
        std::thread::spawn(move || run_op(dir, op, tx));
    }

    /// Drain finished events (call every frame; never blocks).
    pub fn poll(&mut self) {
        let mut settled = false;
        if let Some(rx) = &self.rx {
            while let Ok(event) = rx.try_recv() {
                match event {
                    ModelEvent::Progress { downloaded, total } => {
                        self.progress = Some((downloaded, total));
                    }
                    ModelEvent::Done(outcome) => {
                        self.states = outcome.states.clone();
                        self.outcome = Some(outcome);
                        self.phase = None;
                        self.progress = None;
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

/// Hash-verify `temp` against the pin, then atomically rename over `dest`.
/// A mismatch deletes the temp (no half-installed models, ever).
pub fn verify_and_place(temp: &Path, dest: &Path, pinned: &PinnedFile) -> Result<String, String> {
    match crate::send::sha256_file(temp) {
        Ok(hex) if hex == pinned.sha256 => {}
        Ok(_) => {
            std::fs::remove_file(temp).ok();
            return Err(format!(
                "checksum mismatch for {} (deleted the bad file; try again)",
                pinned.name
            ));
        }
        Err(err) => {
            std::fs::remove_file(temp).ok();
            return Err(format!("cannot hash download: {err}"));
        }
    }
    std::fs::rename(temp, dest).map_err(|err| format!("cannot install {}: {err}", pinned.name))?;
    Ok(format!("{} ready (pinned)", pinned.name))
}

/// Stream `url` into `dst` with real byte progress (pure over the
/// network; the caller picks the URL, tests use loopback).
pub fn download_url(
    url: &str,
    dst: &Path,
    progress: &mut dyn FnMut(u64, Option<u64>),
) -> Result<(), String> {
    let response = crate::send::http_agent()
        .get(url)
        .call()
        .map_err(|err| format!("cannot reach server: {err}"))?;
    let status = response.status().as_u16();
    if status != 200 {
        return Err(format!("server answered {status}"));
    }
    let total = response
        .headers()
        .get("Content-Length")
        .and_then(|value| value.to_str().ok())
        .and_then(|text| text.trim().parse::<u64>().ok());
    let mut input = response.into_body().into_reader();
    let mut output =
        std::fs::File::create(dst).map_err(|err| format!("cannot write file: {err}"))?;
    let mut buffer = vec![0u8; 65536];
    let mut downloaded = 0u64;
    loop {
        let read = input
            .read(&mut buffer)
            .map_err(|err| format!("download stalled: {err}"))?;
        if read == 0 {
            break;
        }
        output
            .write_all(&buffer[..read])
            .map_err(|err| format!("cannot write file: {err}"))?;
        downloaded += read as u64;
        progress(downloaded, total);
    }
    Ok(())
}

fn temp_path(dir: &Path) -> PathBuf {
    dir.join(format!(
        ".auloud-dl-{}.part",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|elapsed| elapsed.as_nanos())
            .unwrap_or(0)
    ))
}

/// Delete orphaned download temps (killed runs leave them; the screen
/// never shows them, so no settle would clean them).
fn sweep_parts(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.starts_with(".auloud-dl-") && name.ends_with(".part") {
            std::fs::remove_file(entry.path()).ok();
        }
    }
}

fn run_op(dir: PathBuf, op: ModelOp, tx: std::sync::mpsc::Sender<ModelEvent>) {
    let send = |event: ModelEvent| tx.send(event).ok();
    // Every arm ends here: fresh states plus one human note.
    let settle = |note: String, error: Option<String>| {
        send(ModelEvent::Done(Settlement {
            note,
            error,
            states: refresh_states(&dir),
        }));
    };
    let settle_installed = |index: usize, note: String| {
        // The installed file just passed its hash: mark it without
        // paying for a second pass, refresh only the rest.
        let states = PAIR
            .iter()
            .enumerate()
            .map(|(i, pinned)| {
                if i == index {
                    FileState::Pinned
                } else {
                    file_state(&dir, pinned)
                }
            })
            .collect();
        send(ModelEvent::Done(Settlement {
            note,
            error: None,
            states,
        }));
    };
    let fail = |detail: String| {
        settle(String::new(), Some(detail));
    };
    match op {
        ModelOp::Refresh => {
            settle(String::new(), None);
        }
        ModelOp::Download(index) => {
            let Some(pinned) = PAIR.get(index) else {
                fail("unknown model file".to_owned());
                return;
            };
            if let Err(err) = std::fs::create_dir_all(&dir) {
                fail(format!("cannot create models dir: {err}"));
                return;
            }
            sweep_parts(&dir);
            let temp = temp_path(&dir);
            let mut progress = |downloaded: u64, total: Option<u64>| {
                send(ModelEvent::Progress { downloaded, total });
            };
            if let Err(detail) = download_url(pinned.url, &temp, &mut progress) {
                std::fs::remove_file(&temp).ok();
                fail(detail);
                return;
            }
            let dest = dir.join(pinned.name);
            match verify_and_place(&temp, &dest, pinned) {
                Ok(note) => settle_installed(index, note),
                Err(detail) => fail(detail),
            }
        }
        ModelOp::Sideload(index, picked) => {
            let Some(pinned) = PAIR.get(index) else {
                fail("unknown model file".to_owned());
                return;
            };
            if let Err(err) = std::fs::create_dir_all(&dir) {
                fail(format!("cannot create models dir: {err}"));
                return;
            }
            sweep_parts(&dir);
            let temp = temp_path(&dir);
            if let Err(err) = std::fs::copy(&picked, &temp) {
                fail(format!("cannot copy picked file: {err}"));
                return;
            }
            let dest = dir.join(pinned.name);
            match verify_and_place(&temp, &dest, pinned) {
                Ok(note) => settle_installed(index, note),
                Err(detail) => fail(detail),
            }
        }
        ModelOp::Delete(index) => {
            let Some(pinned) = PAIR.get(index) else {
                fail("unknown model file".to_owned());
                return;
            };
            match std::fs::remove_file(dir.join(pinned.name)) {
                Ok(()) => settle(format!("{} deleted", pinned.name), None),
                Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                    settle("already gone".to_owned(), None);
                }
                Err(err) => fail(format!("cannot delete {}: {err}", pinned.name)),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TINY_SHA: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    fn pinned(name: &'static str, bytes: &[u8]) -> PinnedFile {
        PinnedFile {
            name,
            url: "http://127.0.0.1:1/unused",
            size: bytes.len() as u64,
            sha256: TINY_SHA,
        }
    }

    fn sandbox(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "auloud-models-test-{}-{tag}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or(0)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn states_cover_missing_pinned_and_unpinned() {
        let dir = sandbox("states");
        let pin = pinned("tiny.bin", b"abc");
        assert_eq!(file_state(&dir, &pin), FileState::Missing);
        std::fs::write(dir.join("tiny.bin"), b"abc").unwrap();
        assert_eq!(file_state(&dir, &pin), FileState::Pinned);
        std::fs::write(dir.join("tiny.bin"), b"abcd").unwrap();
        assert_eq!(file_state(&dir, &pin), FileState::Unpinned { size: 4 });
        std::fs::write(dir.join("tiny.bin"), b"xyz").unwrap();
        assert_eq!(file_state(&dir, &pin), FileState::Unpinned { size: 3 });
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn verify_places_good_and_rejects_bad() {
        let dir = sandbox("verify");
        let pin = pinned("tiny.bin", b"abc");
        let temp = dir.join("dl.part");
        std::fs::write(&temp, b"abc").unwrap();
        let note = verify_and_place(&temp, &dir.join("tiny.bin"), &pin).unwrap();
        assert!(note.contains("pinned"));
        assert!(!temp.exists());
        std::fs::write(&temp, b"xyz").unwrap();
        let err = verify_and_place(&temp, &dir.join("tiny.bin"), &pin).expect_err("bad hash");
        assert!(err.contains("checksum mismatch"));
        assert!(!temp.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn sweep_removes_only_orphaned_parts() {
        let dir = sandbox("sweep");
        std::fs::write(dir.join(".auloud-dl-1.part"), b"orphan").unwrap();
        std::fs::write(dir.join("kokoro-v1.0.onnx"), b"keep").unwrap();
        std::fs::write(dir.join(".auloud-dl-keep.txt"), b"keep").unwrap();
        sweep_parts(&dir);
        assert!(!dir.join(".auloud-dl-1.part").exists());
        assert!(dir.join("kokoro-v1.0.onnx").is_file());
        assert!(dir.join(".auloud-dl-keep.txt").is_file());
        std::fs::remove_dir_all(&dir).ok();
    }

    /// One-shot loopback file server: one connection, fixed bytes.
    fn serve_once(body: Vec<u8>, status: &str) -> String {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!(
            "http://127.0.0.1:{}/file.bin",
            listener.local_addr().unwrap().port()
        );
        let status = status.to_owned();
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while head.len() < 8192 {
                if stream.read(&mut byte).unwrap_or(0) == 0 {
                    break;
                }
                head.push(byte[0]);
                if head.ends_with(b"\r\n\r\n") {
                    break;
                }
            }
            let reply = format!(
                "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            );
            stream.write_all(reply.as_bytes()).unwrap();
            stream.write_all(&body).unwrap();
        });
        url
    }

    #[test]
    fn download_streams_with_progress() {
        let dir = sandbox("download");
        let url = serve_once(b"abc".to_vec(), "200 OK");
        let dst = dir.join("out.bin");
        let mut seen: Vec<(u64, Option<u64>)> = Vec::new();
        {
            let mut record = |got: u64, total: Option<u64>| seen.push((got, total));
            download_url(&url, &dst, &mut record).unwrap();
        }
        assert_eq!(std::fs::read(&dst).unwrap(), b"abc");
        assert_eq!(seen.last(), Some(&(3, Some(3))));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn download_refuses_bad_status_and_dead_port() {
        let dir = sandbox("download-bad");
        let url = serve_once(b"nope".to_vec(), "404 Not Found");
        let mut ignore = |_: u64, _: Option<u64>| {};
        let err = download_url(&url, &dir.join("out.bin"), &mut ignore).expect_err("404");
        assert!(err.contains("404"));
        assert!(!dir.join("out.bin").exists());
        let err = download_url(
            "http://127.0.0.1:1/closed",
            &dir.join("out.bin"),
            &mut ignore,
        )
        .expect_err("refused");
        assert!(err.contains("cannot reach server"));
        std::fs::remove_dir_all(&dir).ok();
    }
}
