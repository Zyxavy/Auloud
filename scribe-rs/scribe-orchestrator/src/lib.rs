pub mod protocol;
pub mod supervisor;

pub use protocol::*;

use std::io::{self, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::time::Duration;

/// Longest wait for a worker's Ready reply (loading a model on CPU can be slow).
pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(300);
/// Longest wait for one batch reply; a slow CPU worker on a long batch must not be killed early.
pub const BATCH_TIMEOUT: Duration = Duration::from_secs(600);
/// Largest frame accepted from a worker. Checked before the payload is allocated.
pub const MAX_FRAME_BYTES: usize = 16 * 1024 * 1024;

/// One batch of work: chapter index, batch id, items.
pub type Batch = (usize, String, Vec<BatchItem>);
/// Per-batch totals for the progress loop: (sentences, audio_ms, cache_hits).
pub type Progress = (u64, u64, u64);
/// What a worker's reader thread hands over: one frame, or why the stream ended.
pub type Frame = Result<Envelope, FrameError>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FrameError {
    /// The worker closed its stdout (it exited, or died mid-frame).
    Closed,
    /// The stream failed to read.
    Io(String),
    /// A bad length prefix or an undecodable payload.
    Invalid(String),
}

/// Everything a worker thread needs: its index, its stdin, its reader channel and the reply timeout.
pub struct WorkerLink<W> {
    pub index: usize,
    pub stdin: W,
    pub frames: Receiver<Frame>,
    pub timeout: Duration,
}

pub fn encode_frame(env: &Envelope) -> Vec<u8> {
    let json = serde_json::to_vec(env).unwrap();
    let mut frame = Vec::with_capacity(4 + json.len());
    frame.extend_from_slice(&(json.len() as u32).to_le_bytes());
    frame.extend_from_slice(&json);
    frame
}

pub fn read_frame_sync<R: Read>(reader: &mut R) -> Result<Envelope, FrameError> {
    let mut hdr = [0u8; 4];
    reader.read_exact(&mut hdr).map_err(frame_io_error)?;
    let n = u32::from_le_bytes(hdr) as usize;
    if n == 0 || n > MAX_FRAME_BYTES {
        return Err(FrameError::Invalid(format!("bad frame size {n}")));
    }
    let mut buf = vec![0u8; n];
    reader.read_exact(&mut buf).map_err(frame_io_error)?;
    serde_json::from_slice(&buf).map_err(|e| FrameError::Invalid(e.to_string()))
}

fn frame_io_error(err: io::Error) -> FrameError {
    if err.kind() == io::ErrorKind::UnexpectedEof {
        FrameError::Closed
    } else {
        FrameError::Io(err.to_string())
    }
}

/// Reads frames on a background thread so the caller can wait with a timeout.
/// The thread ends after the first error, which it sends.
pub fn spawn_frame_reader<R: Read + Send + 'static>(mut reader: R) -> Receiver<Frame> {
    let (tx, rx) = mpsc::channel();
    std::thread::spawn(move || loop {
        let frame = read_frame_sync(&mut reader);
        let failed = frame.is_err();
        if tx.send(frame).is_err() || failed {
            break;
        }
    });
    rx
}

/// Waits for worker `index`'s next reply. An error names the worker; the caller kills the child.
pub fn next_frame(index: usize, frames: &Receiver<Frame>, timeout: Duration) -> Result<Envelope, String> {
    match frames.recv_timeout(timeout) {
        Ok(Ok(env)) => Ok(env),
        Ok(Err(FrameError::Closed)) | Err(RecvTimeoutError::Disconnected) => {
            Err(format!("worker {index} exited"))
        }
        Ok(Err(FrameError::Io(e))) => Err(format!("worker {index} read failed: {e}")),
        Ok(Err(FrameError::Invalid(e))) => Err(format!("worker {index} sent a bad frame: {e}")),
        Err(RecvTimeoutError::Timeout) => Err(format!("worker {index} timed out after {timeout:?}")),
    }
}

/// Runs one worker to the end of its share, returning `(chapter, results)` per batch.
/// A failure sets `stop` so the other workers stop before their next batch.
pub fn run_worker<W: Write>(
    mut link: WorkerLink<W>,
    work: Vec<Batch>,
    stop: &AtomicBool,
    progress: &Sender<Progress>,
) -> Result<Vec<(usize, Vec<ResultItem>)>, String> {
    let mut finished = Vec::new();
    let mut failure = None;
    for (chapter, batch_id, items) in work {
        if stop.load(Ordering::SeqCst) {
            break;
        }
        match synth_batch(&mut link, batch_id, items) {
            Ok(results) => {
                let audio_ms = results.iter().map(|r| r.duration_ms).sum();
                let hits = results.iter().filter(|r| r.cache_hit).count() as u64;
                let _ = progress.send((results.len() as u64, audio_ms, hits));
                finished.push((chapter, results));
            }
            Err(e) => {
                failure = Some(e);
                break;
            }
        }
    }
    if let Some(e) = failure {
        stop.store(true, Ordering::SeqCst);
        return Err(e);
    }
    let shutdown = Envelope {
        v: PROTOCOL_VERSION,
        id: "shutdown".to_string(),
        body: MessageBody::Shutdown,
    };
    let _ = link.stdin.write_all(&encode_frame(&shutdown));
    let _ = link.stdin.flush();
    Ok(finished)
}

fn synth_batch<W: Write>(
    link: &mut WorkerLink<W>,
    batch_id: String,
    items: Vec<BatchItem>,
) -> Result<Vec<ResultItem>, String> {
    let index = link.index;
    let batch = Envelope {
        v: PROTOCOL_VERSION,
        id: batch_id.clone(),
        body: MessageBody::SynthBatch { batch_id, items },
    };
    link.stdin
        .write_all(&encode_frame(&batch))
        .map_err(|e| format!("worker {index} write failed: {e}"))?;
    let _ = link.stdin.flush();
    match next_frame(index, &link.frames, link.timeout)? {
        Envelope { body: MessageBody::BatchDone { results, .. }, .. } => Ok(results),
        Envelope { body: MessageBody::BatchError { message, .. }, .. } => Err(message),
        _ => Err(format!("worker {index} sent an unexpected reply")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Read};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::mpsc;
    use std::time::Duration;

    /// A reader that blocks until the test drops its sender, then reports EOF.
    struct Silent(mpsc::Receiver<()>);

    impl Read for Silent {
        fn read(&mut self, _buf: &mut [u8]) -> std::io::Result<usize> {
            let _ = self.0.recv();
            Ok(0)
        }
    }

    fn batch_error(id: &str, message: &str) -> Frame {
        Ok(Envelope {
            v: PROTOCOL_VERSION,
            id: id.to_string(),
            body: MessageBody::BatchError {
                batch_id: id.to_string(),
                kind: "synth".to_string(),
                message: message.to_string(),
                retryable: false,
            },
        })
    }

    fn one_batch() -> Vec<Batch> {
        vec![(1, "ch1-b0".to_string(), Vec::new())]
    }

    #[test]
    fn zero_length_prefix_is_an_error() {
        let mut reader = Cursor::new(vec![0u8, 0, 0, 0]);
        assert!(matches!(read_frame_sync(&mut reader), Err(FrameError::Invalid(_))));
    }

    #[test]
    fn oversize_prefix_is_rejected_before_the_payload_is_read() {
        // Only the header follows. A size check that ran after reading the payload would say Closed.
        let mut reader = Cursor::new(vec![0xFFu8; 4]);
        match read_frame_sync(&mut reader) {
            Err(FrameError::Invalid(message)) => assert!(message.contains("4294967295"), "{message}"),
            other => panic!("expected Invalid, got {other:?}"),
        }
    }

    #[test]
    fn truncated_payload_is_an_error() {
        let mut bytes = 10u32.to_le_bytes().to_vec();
        bytes.extend_from_slice(b"{\"v\"");
        let mut reader = Cursor::new(bytes);
        assert_eq!(read_frame_sync(&mut reader).unwrap_err(), FrameError::Closed);
    }

    #[test]
    fn encoded_frame_round_trips() {
        let env = Envelope { v: PROTOCOL_VERSION, id: "x".to_string(), body: MessageBody::Shutdown };
        let mut reader = Cursor::new(encode_frame(&env));
        assert_eq!(read_frame_sync(&mut reader).unwrap().id, "x");
    }

    #[test]
    fn silent_worker_times_out_with_a_message_naming_it() {
        let (_keep_alive, silent) = mpsc::channel::<()>();
        let frames = spawn_frame_reader(Silent(silent));
        let err = next_frame(3, &frames, Duration::from_millis(50)).unwrap_err();
        assert_eq!(err, "worker 3 timed out after 50ms");
    }

    #[test]
    fn silent_batch_times_out_and_sets_stop() {
        let (_keep_alive, silent) = mpsc::channel::<()>();
        let link = WorkerLink {
            index: 2,
            stdin: Vec::new(),
            frames: spawn_frame_reader(Silent(silent)),
            timeout: Duration::from_millis(50),
        };
        let stop = AtomicBool::new(false);
        let (progress, _progress_rx) = mpsc::channel();
        let err = run_worker(link, one_batch(), &stop, &progress).unwrap_err();
        assert_eq!(err, "worker 2 timed out after 50ms");
        assert!(stop.load(Ordering::SeqCst));
    }

    #[test]
    fn worker_error_sets_stop_for_the_others() {
        let (tx, rx) = mpsc::channel();
        tx.send(batch_error("ch1-b0", "boom")).unwrap();
        let link = WorkerLink { index: 0, stdin: Vec::new(), frames: rx, timeout: Duration::from_secs(5) };
        let stop = AtomicBool::new(false);
        let (progress, _progress_rx) = mpsc::channel();
        assert_eq!(run_worker(link, one_batch(), &stop, &progress).unwrap_err(), "boom");
        assert!(stop.load(Ordering::SeqCst));
    }

    #[test]
    fn stopped_worker_sends_no_batch_only_shutdown() {
        let (_tx, rx) = mpsc::channel::<Frame>();
        let mut sent = Vec::new();
        let link = WorkerLink { index: 1, stdin: &mut sent, frames: rx, timeout: Duration::from_secs(5) };
        let stop = AtomicBool::new(true);
        let (progress, _progress_rx) = mpsc::channel();
        assert!(run_worker(link, one_batch(), &stop, &progress).unwrap().is_empty());
        let mut sink = Cursor::new(sent);
        let shutdown = read_frame_sync(&mut sink).unwrap();
        assert!(matches!(shutdown.body, MessageBody::Shutdown));
        assert_eq!(read_frame_sync(&mut sink).unwrap_err(), FrameError::Closed);
    }

    #[test]
    fn healthy_batch_reports_progress_and_results() {
        let (tx, rx) = mpsc::channel();
        let result = ResultItem {
            index: 1,
            cache_key: "k".to_string(),
            cache_hit: true,
            audio_path: "p.flac".to_string(),
            duration_ms: 500,
            sample_rate: 24000,
        };
        tx.send(Ok(Envelope {
            v: PROTOCOL_VERSION,
            id: "ch1-b0".to_string(),
            body: MessageBody::BatchDone { batch_id: "ch1-b0".to_string(), results: vec![result] },
        }))
        .unwrap();
        let mut sent = Vec::new();
        let link = WorkerLink { index: 0, stdin: &mut sent, frames: rx, timeout: Duration::from_secs(5) };
        let stop = AtomicBool::new(false);
        let (progress, progress_rx) = mpsc::channel();
        let finished = run_worker(link, one_batch(), &stop, &progress).unwrap();
        assert_eq!(finished.len(), 1);
        assert_eq!(finished[0].0, 1);
        assert_eq!(progress_rx.try_recv().unwrap(), (1, 500, 1));
        assert!(!stop.load(Ordering::SeqCst));
    }
}
