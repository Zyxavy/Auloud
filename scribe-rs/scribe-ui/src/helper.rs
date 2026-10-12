// SPDX-License-Identifier: AGPL-3.0-or-later
//! Helper process worker (Slice 17 AP1): resolve, spawn, verify, watch.
//!
//! One worker thread owns the child process end to end; the UI thread only
//! sends [`WorkerCmd`] and drains [`WorkerEvent`]. Blocking client calls run
//! here, never on the UI thread. The token travels by environment (never
//! argv, never logs).

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::settings::Settings;

/// Ready-line wait per spawn.
const READY_TIMEOUT: Duration = Duration::from_secs(120);
/// Death-watch poll interval.
const WATCH_POLL: Duration = Duration::from_millis(500);
/// Token header name (matches the helper).
const TOKEN_ENV: &str = "AULOUD_HELPER_TOKEN";

/// Spawn argv (token excluded: it travels by environment).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedCommand {
    /// Helper exe (or interpreter for the embedded layout).
    pub program: PathBuf,
    /// Arguments (`helper --workspace DIR --port 0`, plus `-m scribe` for
    /// interpreter layouts).
    pub args: Vec<String>,
}

/// Resolve the helper command: explicit setting, bundled layouts (the
/// `run_gate.ps1` layout), then the dev venv by walking up from the exe.
pub fn resolve_command(settings: &Settings, exe_dir: &Path) -> Result<ResolvedCommand, String> {
    if let Some(path) = &settings.helper_path {
        if !path.is_file() {
            return Err(format!(
                "helper path is not a file: {} (fix it in Settings)",
                path.display()
            ));
        }
        return Ok(command_for(path));
    }
    let bundled = [
        exe_dir
            .join("helper")
            .join("embedded")
            .join("python")
            .join(exe_python()),
        exe_dir
            .join("helper")
            .join("frozen")
            .join("scribe-helper")
            .join(exe_helper()),
    ];
    for candidate in &bundled {
        if candidate.is_file() {
            return Ok(command_for(candidate));
        }
    }
    let mut anchor = exe_dir.to_path_buf();
    for _ in 0..5 {
        let venv_python = anchor.join("scribe").join(".venv").join(exe_scripts());
        let venv_python = venv_python.join(exe_python());
        if venv_python.is_file() {
            return Ok(command_for(&venv_python));
        }
        if !anchor.pop() {
            break;
        }
    }
    Err(
        "no helper found: set a helper path in Settings (an installed \
         bundle, or the dev tree)"
            .to_owned(),
    )
}

/// Build argv for one exe: interpreters re-enter via `-m scribe` (same
/// stem rule the live client test uses), frozen exes are the CLI already.
fn command_for(exe: &Path) -> ResolvedCommand {
    let is_python = exe
        .file_stem()
        .is_some_and(|stem| stem.eq_ignore_ascii_case("python"));
    let mut args = Vec::new();
    if is_python {
        args.push("-m".to_owned());
        args.push("scribe".to_owned());
    }
    args.push("helper".to_owned());
    ResolvedCommand {
        program: exe.to_path_buf(),
        args,
    }
}

#[cfg(windows)]
fn exe_python() -> &'static str {
    "python.exe"
}

#[cfg(not(windows))]
fn exe_python() -> &'static str {
    "python"
}

#[cfg(windows)]
fn exe_helper() -> &'static str {
    "scribe-helper.exe"
}

#[cfg(not(windows))]
fn exe_helper() -> &'static str {
    "scribe-helper"
}

#[cfg(windows)]
fn exe_scripts() -> &'static str {
    "Scripts"
}

#[cfg(not(windows))]
fn exe_scripts() -> &'static str {
    "bin"
}

/// Per-run token (hex of pid, clock, and a counter). Not cryptographic, and
/// it does not need to be: loopback-only, single run, passed by environment
/// so it never appears in process lists or logs.
pub fn generate_token() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let count = COUNTER.fetch_add(1, Ordering::Relaxed);
    let mut hash = 0xcbf29ce484222325_u64;
    for word in [
        std::process::id() as u64,
        (nanos & 0xffff_ffff_ffff_ffff) as u64,
        (nanos >> 64) as u64,
        count,
    ] {
        hash ^= word;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    format!("{hash:016x}{:016x}", hash ^ (nanos as u64))
}

/// Port from a ready line (`{"ready": true, "port": 8137, ...}`); `None`
/// for log noise (including `"ready": false`). Hand-parsed (four fields
/// would not justify a JSON dep); the unit test pins the shapes.
pub fn parse_ready_port(line: &str) -> Option<u16> {
    if !line.contains("\"ready\": true") && !line.contains("\"ready\":true") {
        return None;
    }
    let start = line.find("\"port\"")?;
    let digits = line[start..]
        .chars()
        .skip_while(|c| !c.is_ascii_digit())
        .take_while(|c| c.is_ascii_digit())
        .collect::<String>();
    digits.parse().ok()
}

/// Commands the UI sends the worker.
#[derive(Debug)]
pub enum WorkerCmd {
    /// Resolve + spawn + verify (ignored while a child lives).
    Spawn,
    /// Kill the child (if any) and stop the thread.
    Shutdown,
    /// Refresh the book list (needs a verified helper; ignored before).
    RefreshBooks,
    /// Upload a staged book file (queues its draft job server-side).
    Upload {
        /// Workspace-local staged file.
        path: PathBuf,
    },
}

/// Worker findings for the state machine.
#[derive(Debug)]
pub enum WorkerEvent {
    /// Verified serving helper.
    Connected {
        /// Origin the client is bound to.
        base_url: String,
    },
    /// Startup failed (message renders on the missing screen).
    Failed {
        /// Human reason.
        message: String,
    },
    /// Contract the client does not speak.
    VersionWrong {
        /// `api` integer the helper reported.
        have: u32,
        /// `api` integer this client speaks.
        want: u32,
    },
    /// A verified child died.
    ChildDied,
    /// Current book list.
    Books {
        /// Newest-first as reported (order preserved).
        books: Vec<scribe_client::BookSummary>,
    },
    /// Book list refresh failed (transient; the old list stays).
    BooksFailed {
        /// Human reason.
        message: String,
    },
    /// Upload accepted; the draft job queues server-side.
    Uploaded {
        /// UUIDv5 book id.
        book_id: String,
        /// Stored filename.
        filename: String,
    },
    /// Upload refused (shaped file + rule when the helper sent one).
    UploadFailed {
        /// Human reason.
        message: String,
    },
}

/// Start the worker thread (owns the child; see module docs).
pub fn spawn_worker(settings: Settings) -> (Sender<WorkerCmd>, Receiver<WorkerEvent>) {
    let (cmd_tx, cmd_rx) = mpsc::channel();
    let (event_tx, event_rx) = mpsc::channel();
    std::thread::spawn(move || worker_loop(settings, cmd_rx, event_tx));
    (cmd_tx, event_rx)
}

fn worker_loop(settings: Settings, commands: Receiver<WorkerCmd>, events: Sender<WorkerEvent>) {
    let mut child: Option<Child> = None;
    let mut client: Option<scribe_client::Client> = None;
    loop {
        match commands.recv_timeout(WATCH_POLL) {
            Ok(WorkerCmd::Shutdown) | Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Ok(WorkerCmd::Spawn) => {
                if child.is_some() {
                    continue;
                }
                let token = generate_token();
                match spawn_verified(&settings, &token) {
                    Ok((process, verified)) => {
                        let base_url = verified.base_url().to_owned();
                        child = Some(process);
                        client = Some(verified);
                        let _ = events.send(WorkerEvent::Connected { base_url });
                    }
                    Err(event) => {
                        let _ = events.send(event);
                    }
                }
            }
            Ok(WorkerCmd::RefreshBooks) => {
                if let Some(client) = &client {
                    match client.list_books() {
                        Ok(books) => {
                            let _ = events.send(WorkerEvent::Books { books });
                        }
                        Err(err) => {
                            let _ = events.send(WorkerEvent::BooksFailed {
                                message: err.to_string(),
                            });
                        }
                    }
                } else {
                    // Dying window (child just cleared): say so instead of
                    // dropping the refresh silently; the next tick heals it.
                    let _ = events.send(WorkerEvent::BooksFailed {
                        message: "helper not connected".to_owned(),
                    });
                }
            }
            Ok(WorkerCmd::Upload { path }) => {
                if let Some(client) = &client {
                    match client.upload_book(&path) {
                        Ok(upload) => {
                            let _ = events.send(WorkerEvent::Uploaded {
                                book_id: upload.book_id,
                                filename: upload.filename,
                            });
                        }
                        Err(err) => {
                            let _ = events.send(WorkerEvent::UploadFailed {
                                message: err.to_string(),
                            });
                        }
                    }
                } else {
                    // Same dying window: a silent drop would leave the UI
                    // stuck on "uploading…" with nothing sent.
                    let _ = events.send(WorkerEvent::UploadFailed {
                        message: "helper not connected".to_owned(),
                    });
                }
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {}
        }
        if let Some(process) = child.as_mut() {
            match process.try_wait() {
                Ok(Some(_)) => {
                    child = None;
                    client = None;
                    let _ = events.send(WorkerEvent::ChildDied);
                }
                Ok(None) => {}
                // Unmonitorable counts as dead: the machine ignores Failed
                // while Running, which would freeze the UI in a phantom
                // Running state with no further events.
                Err(_) => {
                    child = None;
                    client = None;
                    let _ = events.send(WorkerEvent::ChildDied);
                }
            }
        }
    }
    if let Some(mut process) = child {
        process.kill().ok();
        process.wait().ok();
    }
}

/// Spawn + wait-ready + health + version; the child and bound client.
fn spawn_verified(
    settings: &Settings,
    token: &str,
) -> Result<(Child, scribe_client::Client), WorkerEvent> {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(Path::to_path_buf))
        .unwrap_or_default();
    let resolved =
        resolve_command(settings, &exe_dir).map_err(|message| WorkerEvent::Failed { message })?;
    let workspace = settings.workspace.clone();
    std::fs::create_dir_all(&workspace).map_err(|err| WorkerEvent::Failed {
        message: format!("cannot create workspace {}: {err}", workspace.display()),
    })?;
    let mut command = Command::new(&resolved.program);
    command
        .args(&resolved.args)
        .arg("--workspace")
        .arg(&workspace)
        .arg("--port")
        .arg("0")
        .env(TOKEN_ENV, token)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());
    let mut process = command.spawn().map_err(|err| WorkerEvent::Failed {
        message: format!(
            "cannot start {}: {err} (fix it in Settings)",
            resolved.program.display()
        ),
    })?;
    let stdout = process.stdout.take();
    let port = wait_ready(stdout, READY_TIMEOUT);
    let port = match port {
        Some(port) => port,
        None => {
            process.kill().ok();
            process.wait().ok();
            return Err(WorkerEvent::Failed {
                message: "no ready line in 120 s (see helper.log in the workspace)".to_owned(),
            });
        }
    };
    let base_url = format!("http://127.0.0.1:{port}");
    let client = match scribe_client::Client::new(&base_url, token) {
        Ok(client) => client,
        Err(err) => {
            process.kill().ok();
            process.wait().ok();
            return Err(WorkerEvent::Failed {
                message: format!("cannot bind {base_url}: {err}"),
            });
        }
    };
    match client.health() {
        Ok(health) if health.ok => {}
        _ => {
            process.kill().ok();
            process.wait().ok();
            return Err(WorkerEvent::Failed {
                message: "helper health check failed right after ready".to_owned(),
            });
        }
    }
    match client.ensure_compatible() {
        Ok(_) => Ok((process, client)),
        Err(scribe_client::Error::VersionMismatch { have, want }) => {
            process.kill().ok();
            process.wait().ok();
            Err(WorkerEvent::VersionWrong { have, want })
        }
        Err(err) => {
            process.kill().ok();
            process.wait().ok();
            Err(WorkerEvent::Failed {
                message: format!("version check failed: {err}"),
            })
        }
    }
}

/// First ready port from piped stdout (None on timeout or closed pipe).
fn wait_ready(stdout: Option<std::process::ChildStdout>, timeout: Duration) -> Option<u16> {
    let stdout = stdout?;
    let start = Instant::now();
    // Blocking line reads: the child logs steadily while starting (verified
    // across the packaging runs), and EOF means it died. A completely silent
    // hang past the ceiling is the accepted edge (documented, not hidden);
    // the UI thread stays usable throughout.
    let mut reader = BufReader::new(stdout);
    let mut line = String::new();
    loop {
        if start.elapsed() > timeout {
            return None;
        }
        line.clear();
        match reader.read_line(&mut line) {
            Ok(0) => return None,
            Ok(_) => {
                if let Some(port) = parse_ready_port(&line) {
                    return Some(port);
                }
            }
            Err(_) => return None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_differ_and_read_hex() {
        let first = generate_token();
        let second = generate_token();
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
        assert!(first.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn ready_port_parses_and_ignores_noise() {
        assert_eq!(parse_ready_port("INFO: starting"), None);
        assert_eq!(
            parse_ready_port(r#"{"ready": true, "port": 60545, "versions": {}}"#),
            Some(60545)
        );
        assert_eq!(parse_ready_port(r#"{"ready": false, "port": 1}"#), None);
    }

    #[test]
    fn explicit_path_rules() {
        let dir =
            std::env::temp_dir().join(format!("scribe-ui-resolve-{}-explicit", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let exe = dir.join(exe_helper());
        std::fs::write(&exe, b"fake").expect("write");
        let settings = Settings {
            workspace: dir.clone(),
            device: "auto".to_owned(),
            helper_path: Some(exe.clone()),
        };
        let resolved = resolve_command(&settings, &dir).expect("resolves");
        assert_eq!(resolved.program, exe);
        assert_eq!(resolved.args, ["helper"]);
        let missing = Settings {
            helper_path: Some(dir.join("nope.exe")),
            ..settings.clone()
        };
        assert!(resolve_command(&missing, &dir).is_err());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn python_layout_reenters_via_module() {
        let dir =
            std::env::temp_dir().join(format!("scribe-ui-resolve-{}-python", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let exe = dir.join(exe_python());
        std::fs::write(&exe, b"fake").expect("write");
        let settings = Settings {
            workspace: dir.clone(),
            device: "auto".to_owned(),
            helper_path: Some(exe),
        };
        let resolved = resolve_command(&settings, &dir).expect("resolves");
        assert_eq!(resolved.args, ["-m", "scribe", "helper"]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn dev_venv_found_walking_up() {
        let root =
            std::env::temp_dir().join(format!("scribe-ui-resolve-{}-venv", std::process::id()));
        let nested = root.join("a").join("b");
        let venv = root.join("scribe").join(".venv").join(exe_scripts());
        std::fs::create_dir_all(&venv).expect("dir");
        std::fs::create_dir_all(&nested).expect("dir");
        std::fs::write(venv.join(exe_python()), b"fake").expect("write");
        let settings = Settings {
            workspace: root.clone(),
            device: "auto".to_owned(),
            helper_path: None,
        };
        let resolved = resolve_command(&settings, &nested).expect("resolves");
        assert!(resolved.program.ends_with(exe_python()));
        assert_eq!(resolved.args, ["-m", "scribe", "helper"]);
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn nothing_found_explains_itself() {
        let dir =
            std::env::temp_dir().join(format!("scribe-ui-resolve-{}-empty", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        let settings = Settings {
            workspace: dir.clone(),
            device: "auto".to_owned(),
            helper_path: None,
        };
        let err = resolve_command(&settings, &dir).expect_err("must fail");
        assert!(err.contains("Settings"));
        std::fs::remove_dir_all(&dir).ok();
    }
}
