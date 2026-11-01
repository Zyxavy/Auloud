// Scheduler: bounded work queue, dispatch, ordered completion, retries.

use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;
use tokio::sync::{mpsc, Mutex};

/// A batch of work items to process.
#[derive(Debug, Clone)]
pub struct Batch {
    pub batch_id: String,
    pub items: Vec<crate::protocol::BatchItem>,
}

/// Result of a completed batch.
#[derive(Debug, Clone)]
pub struct BatchResult {
    pub batch_id: String,
    pub results: Vec<crate::protocol::ResultItem>,
    pub error: Option<String>,
}

/// Configuration for the scheduler.
#[derive(Debug, Clone)]
pub struct SchedulerConfig {
    pub worker_count: usize,
    pub batch_size: usize,
    pub queue_capacity: usize,
    pub max_retries: u32,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            worker_count: 2,
            batch_size: 32,
            queue_capacity: 8, // workers * 4
            max_retries: 2,
        }
    }
}

/// The scheduler manages work batches, dispatch, and ordered completion.
pub struct Scheduler {
    config: SchedulerConfig,
    queue: mpsc::Sender<Batch>,
    queue_rx: Arc<Mutex<mpsc::Receiver<Batch>>>,
    in_flight: Arc<Mutex<HashMap<String, Batch>>>,
    reorder_buffer: Arc<Mutex<BTreeMap<usize, crate::protocol::ResultItem>>>,
    next_expected: Arc<Mutex<usize>>,
    completed: Arc<Mutex<Vec<crate::protocol::ResultItem>>>,
}

impl Scheduler {
    pub fn new(config: SchedulerConfig) -> Self {
        let (tx, rx) = mpsc::channel(config.queue_capacity);
        Self {
            config,
            queue: tx,
            queue_rx: Arc::new(Mutex::new(rx)),
            in_flight: Arc::new(Mutex::new(HashMap::new())),
            reorder_buffer: Arc::new(Mutex::new(BTreeMap::new())),
            next_expected: Arc::new(Mutex::new(0)),
            completed: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// Enqueue a batch for processing.
    pub async fn enqueue(&self, batch: Batch) -> Result<(), String> {
        self.queue
            .send(batch)
            .await
            .map_err(|e| format!("queue send error: {e}"))
    }

    /// Get the next batch from the queue (called by workers).
    pub async fn dequeue(&self) -> Option<Batch> {
        let mut rx = self.queue_rx.lock().await;
        rx.recv().await
    }

    /// Record that a batch is in flight.
    pub async fn mark_inflight(&self, batch: Batch) {
        let mut in_flight = self.in_flight.lock().await;
        in_flight.insert(batch.batch_id.clone(), batch);
    }

    /// Record batch completion and reorder results.
    pub async fn complete_batch(&self, result: BatchResult) {
        if let Some(err) = &result.error {
            // Retry or fail — handled by caller
            tracing::warn!("batch {} failed: {}", result.batch_id, err);
            return;
        }
        let mut reorder = self.reorder_buffer.lock().await;
        for r in result.results {
            reorder.insert(r.index, r);
        }
        // Emit contiguous completed items
        let mut next = self.next_expected.lock().await;
        let mut completed = self.completed.lock().await;
        while let Some(item) = reorder.remove(&*next) {
            completed.push(item);
            *next += 1;
        }
        // Remove from in-flight
        let mut in_flight = self.in_flight.lock().await;
        in_flight.remove(&result.batch_id);
    }

    /// Get all completed items in order.
    pub async fn completed_items(&self) -> Vec<crate::protocol::ResultItem> {
        self.completed.lock().await.clone()
    }

    /// Number of in-flight batches.
    pub async fn inflight_count(&self) -> usize {
        self.in_flight.lock().await.len()
    }

    /// Config access.
    pub fn config(&self) -> &SchedulerConfig {
        &self.config
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn enqueue_dequeue() {
        let sched = Scheduler::new(SchedulerConfig::default());
        let batch = Batch {
            batch_id: "b1".to_string(),
            items: vec![],
        };
        sched.enqueue(batch.clone()).await.unwrap();
        let got = sched.dequeue().await.unwrap();
        assert_eq!(got.batch_id, "b1");
    }

    #[tokio::test]
    async fn ordered_completion() {
        let sched = Scheduler::new(SchedulerConfig::default());
        let result = BatchResult {
            batch_id: "b1".to_string(),
            results: vec![
                crate::protocol::ResultItem {
                    index: 1,
                    cache_key: "k1".to_string(),
                    cache_hit: false,
                    audio_path: "a1.wav".to_string(),
                    duration_ms: 100,
                    sample_rate: 24000,
                },
                crate::protocol::ResultItem {
                    index: 0,
                    cache_key: "k0".to_string(),
                    cache_hit: false,
                    audio_path: "a0.wav".to_string(),
                    duration_ms: 100,
                    sample_rate: 24000,
                },
            ],
            error: None,
        };
        sched.complete_batch(result).await;
        let completed = sched.completed_items().await;
        assert_eq!(completed.len(), 2);
        assert_eq!(completed[0].index, 0);
        assert_eq!(completed[1].index, 1);
    }
}