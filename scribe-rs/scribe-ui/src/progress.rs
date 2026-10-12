// SPDX-License-Identifier: AGPL-3.0-or-later
//! Jobs tray math (Slice 18 JP1): pure predicates and formatting over the
//! reported job shape. No totals, no estimates: the jobs list carries no
//! plan totals and no estimate, so percent/ETA are honestly absent (counts,
//! RTF, and cache hits only). Anything about what the numbers mean stays
//! Python; this is arithmetic over reported counters.

use scribe_client::{Job, JobSummary};

/// Terminal states stay visible under history; everything else is live.
/// Unknown states count as live (never silently hide work).
pub fn is_terminal(state: &str) -> bool {
    matches!(state, "done" | "failed" | "cancelled")
}

/// Pause is graceful at a sentence boundary; only a running job takes it.
pub fn can_pause(state: &str) -> bool {
    state == "running"
}

/// Resume reuses cache; the helper owns which resumable states restart.
pub fn can_resume(state: &str) -> bool {
    matches!(state, "paused" | "interrupted" | "failed" | "cancelled")
}

/// Cancel takes queued, paused, and running; terminal jobs are already over.
pub fn can_cancel(state: &str) -> bool {
    matches!(state, "queued" | "running" | "paused")
}

/// Sentences done this job (rendered plus cache hits).
pub fn done_sentences(summary: &JobSummary) -> u64 {
    summary.rendered.saturating_add(summary.cached)
}

/// Real-time factor from reported counters (`None` until both move).
pub fn rtf(summary: &JobSummary) -> Option<f64> {
    if summary.audio_ms > 0 && summary.wall_s > 0.0 && summary.wall_s.is_finite() {
        Some(summary.audio_ms as f64 / 1000.0 / summary.wall_s)
    } else {
        None
    }
}

/// One decimal plus the unit (`2.4x`).
pub fn format_rtf(rtf: f64) -> String {
    format!("{rtf:.1}x")
}

/// Wall clock as `45s` or `3m 12s`.
pub fn format_wall(wall_s: f64) -> String {
    if !wall_s.is_finite() || wall_s < 0.0 {
        return "0s".to_owned();
    }
    let total = wall_s as u64;
    if total < 60 {
        format!("{total}s")
    } else {
        format!("{}m {:02}s", total / 60, total % 60)
    }
}

/// Split the server list (already newest-first; order preserved) into live
/// rows and settled history. The app keeps no job store; history is just
/// the terminal tail of the same feed.
pub fn split_jobs(jobs: &[Job]) -> (Vec<&Job>, Vec<&Job>) {
    let mut live = Vec::new();
    let mut history = Vec::new();
    for job in jobs {
        if is_terminal(&job.state) {
            history.push(job);
        } else {
            live.push(job);
        }
    }
    (live, history)
}

/// Tray label: kind plus chapter range when the job carries one.
pub fn job_label(job: &Job) -> String {
    match job.chapters.as_deref().filter(|c| !c.is_empty()) {
        Some(chapters) => format!("{} ch {chapters}", job.kind),
        None => job.kind.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn job(id: &str, kind: &str, state: &str) -> Job {
        Job {
            id: id.to_owned(),
            book_id: "book-1".to_owned(),
            kind: kind.to_owned(),
            state: state.to_owned(),
            ..Default::default()
        }
    }

    #[test]
    fn terminal_states_split_into_history_in_order() {
        let jobs = vec![
            job("a", "build", "running"),
            job("b", "draft", "done"),
            job("c", "build", "paused"),
            job("d", "draft", "failed"),
            job("e", "build", "cancelled"),
            job("f", "draft", "queued"),
            job("g", "build", "interrupted"),
        ];
        let (live, history) = split_jobs(&jobs);
        let live_ids: Vec<_> = live.iter().map(|j| j.id.as_str()).collect();
        let history_ids: Vec<_> = history.iter().map(|j| j.id.as_str()).collect();
        assert_eq!(live_ids, ["a", "c", "f", "g"]);
        assert_eq!(history_ids, ["b", "d", "e"]);
    }

    #[test]
    fn unknown_states_stay_visible_as_live() {
        assert!(!is_terminal("migrating"));
        let jobs = vec![job("a", "build", "migrating")];
        let (live, history) = split_jobs(&jobs);
        assert_eq!(live.len(), 1);
        assert!(history.is_empty());
    }

    #[test]
    fn control_matrix_matches_the_contract() {
        assert!(can_pause("running"));
        assert!(!can_pause("paused"));
        assert!(!can_pause("done"));
        for state in ["paused", "interrupted", "failed", "cancelled"] {
            assert!(can_resume(state), "{state}");
        }
        assert!(!can_resume("running"));
        assert!(!can_resume("done"));
        for state in ["queued", "running", "paused"] {
            assert!(can_cancel(state), "{state}");
        }
        assert!(!can_cancel("done"));
        assert!(!can_cancel("interrupted"));
    }

    #[test]
    fn rtf_needs_both_counters_moving() {
        let summary = JobSummary {
            audio_ms: 110_285,
            wall_s: 28.87,
            ..Default::default()
        };
        let value = rtf(&summary).expect("rtf");
        assert!((value - 3.82).abs() < 0.05, "{value}");
        assert_eq!(format_rtf(value).chars().last(), Some('x'));
        assert_eq!(rtf(&JobSummary::default()), None);
        assert_eq!(
            rtf(&JobSummary {
                audio_ms: 1000,
                wall_s: 0.0,
                ..Default::default()
            }),
            None
        );
    }

    #[test]
    fn counts_add_cache_hits() {
        let summary = JobSummary {
            cached: 12,
            rendered: 13,
            ..Default::default()
        };
        assert_eq!(done_sentences(&summary), 25);
    }

    #[test]
    fn wall_formats_without_decimals() {
        assert_eq!(format_wall(45.0), "45s");
        assert_eq!(format_wall(192.0), "3m 12s");
        assert_eq!(format_wall(f64::NAN), "0s");
    }

    #[test]
    fn labels_carry_chapters_when_present() {
        let mut job = job("a", "build", "running");
        assert_eq!(job_label(&job), "build");
        job.chapters = Some("1-3".to_owned());
        assert_eq!(job_label(&job), "build ch 1-3");
    }

    #[test]
    fn recorded_build_job_marks_done_with_cache_hits() {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("scribe-client")
            .join("tests")
            .join("data")
            .join("build-job.json");
        let text = std::fs::read_to_string(&path).expect("recorded fixture");
        let job: Job = serde_json::from_str(&text).expect("job parses");
        assert!(is_terminal(&job.state));
        assert_eq!(done_sentences(&job.summary), 25);
        assert!((rtf(&job.summary).expect("rtf") - 3.82).abs() < 0.05);
        let (live, history) = split_jobs(std::slice::from_ref(&job));
        assert!(live.is_empty());
        assert_eq!(history.len(), 1);
    }
}
