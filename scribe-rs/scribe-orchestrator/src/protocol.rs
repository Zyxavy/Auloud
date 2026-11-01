use serde::{Deserialize, Serialize};

/// Protocol version. Bump on breaking changes.
pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Envelope {
    pub v: u32,
    pub id: String,
    #[serde(flatten)]
    pub body: MessageBody,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum MessageBody {
    // Orchestrator -> Worker
    Hello { protocol_version: u32 },
    SynthBatch { batch_id: String, items: Vec<BatchItem> },
    Shutdown,

    // Worker -> Orchestrator
    Ready { providers: Vec<String>, model_hash: String, rss_mb: u64 },
    BatchDone { batch_id: String, results: Vec<ResultItem> },
    BatchError { batch_id: String, kind: String, message: String, retryable: bool },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchItem {
    pub index: usize,
    pub text: String,
    pub voice: String,
    pub speed: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResultItem {
    pub index: usize,
    pub cache_key: String,
    pub cache_hit: bool,
    pub audio_path: String,
    pub duration_ms: u64,
    pub sample_rate: u32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_hello() {
        let msg = Envelope {
            v: PROTOCOL_VERSION,
            id: "1".to_string(),
            body: MessageBody::Hello { protocol_version: PROTOCOL_VERSION },
        };
        let json = serde_json::to_string(&msg).unwrap();
        let decoded: Envelope = serde_json::from_str(&json).unwrap();
        assert_eq!(decoded.v, PROTOCOL_VERSION);
    }

    #[test]
    fn roundtrip_ready() {
        let msg = Envelope {
            v: PROTOCOL_VERSION,
            id: "2".to_string(),
            body: MessageBody::Ready {
                providers: vec!["CUDAExecutionProvider".to_string()],
                model_hash: "abc123".to_string(),
                rss_mb: 1024,
            },
        };
        let json = serde_json::to_string(&msg).unwrap();
        let decoded: Envelope = serde_json::from_str(&json).unwrap();
        match decoded.body {
            MessageBody::Ready { rss_mb, .. } => assert_eq!(rss_mb, 1024),
            _ => panic!("wrong body"),
        }
    }
}