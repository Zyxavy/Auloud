// SPDX-License-Identifier: AGPL-3.0-or-later
//! Book list helpers: row titles, draft progress, stepper state.
//! Uploads go straight from the picked file; the helper places the one
//! workspace copy. The dialog itself is owner-tested (native modal).

/// Row titles truncate (a 200-char title else pushes every chip off the
/// row at narrow widths; the full title heads the detail panel).
pub fn short_title(title: &str) -> String {
    const LIMIT: usize = 60;
    if title.chars().count() <= LIMIT {
        title.to_owned()
    } else {
        let mut short: String = title.chars().take(LIMIT - 1).collect();
        short.push('…');
        short
    }
}

/// Draft progress line for a book from the jobs list (AP3): the newest
/// non-terminal draft job's sentence count. `None` when no draft runs.
pub fn draft_progress(jobs: &[scribe_client::Job], book_id: &str) -> Option<String> {
    let job = draft_job(jobs, book_id)?;
    Some(format!(
        "{}: {} sentences",
        job.state,
        job.summary.rendered + job.summary.cached
    ))
}

/// Newest non-terminal draft job for a book, if any.
pub fn draft_job<'a>(
    jobs: &'a [scribe_client::Job],
    book_id: &str,
) -> Option<&'a scribe_client::Job> {
    jobs.iter().find(|job| {
        job.book_id == book_id
            && job.kind == "draft"
            && !matches!(job.state.as_str(), "done" | "failed" | "cancelled")
    })
}

/// Row title for a job: the book's short title, else the raw id (sample
/// jobs carry no book). Shared by the tray row and the quit panel so the
/// fallback changes once.
pub fn job_book_title(books: &[scribe_client::BookSummary], job: &scribe_client::Job) -> String {
    books
        .iter()
        .find(|book| book.id == job.book_id)
        .map(|book| {
            if book.title.is_empty() {
                job.book_id.clone()
            } else {
                short_title(&book.title)
            }
        })
        .unwrap_or_else(|| {
            if job.book_id.is_empty() {
                job.kind.clone()
            } else {
                job.book_id.clone()
            }
        })
}

/// One-line status chip from the scan state (rendering wins over draft).
pub fn book_status(book: &scribe_client::BookSummary) -> String {
    if book.rendering {
        match book.rendering_percent {
            Some(percent) => format!("rendering {percent}%"),
            None => "rendering".to_owned(),
        }
    } else {
        format!("draft: {}", book.draft_status)
    }
}

/// One stepper row (extract, draft, cast, render, validate, transfer).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StepState {
    /// Step name.
    pub label: &'static str,
    /// Done, pending, or failed.
    pub status: StepStatus,
    /// Counts, percents, and hints (stale cast, invalid bundle).
    pub note: String,
}

/// Stepper row state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StepStatus {
    /// Artifact present and good.
    Done,
    /// Not started or still running.
    Pending,
    /// Ran and failed.
    Failed,
}

/// Read-only stepper from a book detail payload plus the list's cast-edited
/// flag (the detail document does not carry it). Pure mapping over recorded
/// shapes; every branch has a unit test below.
pub fn stepper(detail: &scribe_client::BookDetail, cast_edited: bool) -> Vec<StepState> {
    let mut steps = Vec::with_capacity(6);
    steps.push(StepState {
        label: "extract",
        status: if detail.total_sentences > 0 {
            StepStatus::Done
        } else {
            StepStatus::Pending
        },
        note: format!("{} sentences", detail.total_sentences),
    });
    let (draft_status, draft_note) = match detail.draft_status.as_str() {
        "done" => (
            StepStatus::Done,
            format!("{} chapters", detail.chapters.len()),
        ),
        "failed" | "cancelled" => (StepStatus::Failed, format!("draft {}", detail.draft_status)),
        "drafting" => (StepStatus::Pending, "drafting…".to_owned()),
        _ => (StepStatus::Pending, "not drafted".to_owned()),
    };
    steps.push(StepState {
        label: "draft",
        status: draft_status,
        note: draft_note,
    });
    let has_narrator = detail
        .cast
        .get("has_narrator")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    steps.push(StepState {
        label: "cast",
        status: if has_narrator {
            StepStatus::Done
        } else {
            StepStatus::Pending
        },
        note: if cast_edited && has_narrator {
            "edited since draft: re-render to apply".to_owned()
        } else if has_narrator {
            "narrator assigned".to_owned()
        } else {
            "no cast yet".to_owned()
        },
    });
    steps.push(StepState {
        label: "render",
        status: if detail.bundles.is_empty() {
            StepStatus::Pending
        } else {
            StepStatus::Done
        },
        note: if detail.bundles.is_empty() {
            "no bundles".to_owned()
        } else {
            format!("{} bundles", detail.bundles.len())
        },
    });
    let (validate_status, validate_note) = if detail.valid {
        (StepStatus::Done, "bundle validates".to_owned())
    } else if detail.bundles.is_empty() {
        (StepStatus::Pending, "nothing to validate".to_owned())
    } else {
        (StepStatus::Failed, "bundle invalid".to_owned())
    };
    steps.push(StepState {
        label: "validate",
        status: validate_status,
        note: validate_note,
    });
    steps.push(StepState {
        label: "transfer",
        status: if detail.on_tablet {
            StepStatus::Done
        } else {
            StepStatus::Pending
        },
        note: if detail.on_tablet {
            "on tablet".to_owned()
        } else {
            "not transferred".to_owned()
        },
    });
    steps
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn draft_progress_names_state_and_count() {
        let running = scribe_client::Job {
            book_id: "b1".to_owned(),
            kind: "draft".to_owned(),
            state: "running".to_owned(),
            summary: scribe_client::JobSummary {
                rendered: 40,
                cached: 10,
                ..Default::default()
            },
            ..Default::default()
        };
        assert_eq!(
            draft_progress(std::slice::from_ref(&running), "b1"),
            Some("running: 50 sentences".to_owned())
        );
        assert_eq!(draft_progress(std::slice::from_ref(&running), "b2"), None);
        let mut done = running.clone();
        done.state = "done".to_owned();
        assert_eq!(draft_progress(std::slice::from_ref(&done), "b1"), None);
        let empty: Vec<scribe_client::Job> = Vec::new();
        assert_eq!(draft_progress(&empty, "b1"), None);
    }

    #[test]
    fn null_metadata_fields_parse() {
        // Owner case: fresh PDF upload with no readable metadata. The scan
        // emits null author/source fields; `#[serde(default)]` alone rejects
        // explicit null, so these must be Option.
        let row: scribe_client::BookSummary = serde_json::from_value(serde_json::json!({
            "id": "book-1",
            "title": "Kent Andrew Parejas-1",
            "author": null,
            "source_file": null,
            "source_type": null,
            "chapters": 0,
            "sentences": 0,
            "drafted": false,
            "cast_edited": false,
            "rendering": false,
            "rendering_percent": null,
            "draft_status": "drafting",
            "bundles": 0,
            "valid": false,
            "on_tablet": false,
        }))
        .expect("nulls parse");
        assert_eq!(row.author, None);
        assert_eq!(row.source_file, None);
        assert_eq!(row.source_type, None);
        let detail: scribe_client::BookDetail = serde_json::from_value(serde_json::json!({
            "id": "book-1",
            "title": "Kent Andrew Parejas-1",
            "author": null,
            "source_file": null,
            "source_sha256": null,
            "drafted": false,
            "draft_status": "drafting",
            "cast_edited": false,
        }))
        .expect("nulls parse");
        assert_eq!(detail.author, None);
        assert!(!detail.cast_edited);
    }

    #[test]
    fn long_titles_truncate_on_char_boundaries() {
        assert_eq!(short_title("Short"), "Short");
        let long = "A".repeat(200);
        let short = short_title(&long);
        assert_eq!(short.chars().count(), 60);
        assert!(short.ends_with('…'));
        // Multibyte safety: no split codepoint, still capped.
        let wide = "é".repeat(200);
        assert_eq!(short_title(&wide).chars().count(), 60);
    }

    #[test]
    fn job_titles_prefer_short_book_titles() {
        let book = scribe_client::BookSummary {
            id: "b1".to_owned(),
            title: "A Title".to_owned(),
            ..Default::default()
        };
        let books = std::slice::from_ref(&book);
        let job = scribe_client::Job {
            book_id: "b1".to_owned(),
            kind: "build".to_owned(),
            ..Default::default()
        };
        assert_eq!(job_book_title(books, &job), "A Title");
        let orphan = scribe_client::Job {
            book_id: "gone".to_owned(),
            kind: "build".to_owned(),
            ..Default::default()
        };
        assert_eq!(job_book_title(books, &orphan), "gone");
        let sample = scribe_client::Job {
            kind: "voices-sample".to_owned(),
            ..Default::default()
        };
        assert_eq!(job_book_title(&[], &sample), "voices-sample");
    }

    #[test]
    fn status_chip_prefers_rendering() {
        let mut book = scribe_client::BookSummary {
            draft_status: "done".to_owned(),
            rendering: true,
            rendering_percent: Some(42),
            ..Default::default()
        };
        assert_eq!(book_status(&book), "rendering 42%");
        book.rendering_percent = None;
        assert_eq!(book_status(&book), "rendering");
        book.rendering = false;
        assert_eq!(book_status(&book), "draft: done");
    }

    fn fixture(name: &str) -> serde_json::Value {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("scribe-client")
            .join("tests")
            .join("data")
            .join(name);
        let text = std::fs::read_to_string(&path).expect("recorded fixture");
        serde_json::from_str(&text).expect("fixture parses")
    }

    fn detail_of(payload: serde_json::Value) -> scribe_client::BookDetail {
        serde_json::from_value(payload).expect("detail parses")
    }

    #[test]
    fn recorded_drafted_book_steps_truthfully() {
        let detail = detail_of(fixture("book-detail.json"));
        assert!(detail.drafted);
        let steps = stepper(&detail, false);
        let state = |label: &str| {
            steps
                .iter()
                .find(|step| step.label == label)
                .map(|step| step.status)
        };
        assert_eq!(state("extract"), Some(StepStatus::Done));
        assert_eq!(state("draft"), Some(StepStatus::Done));
        assert_eq!(state("cast"), Some(StepStatus::Done));
        assert_eq!(state("render"), Some(StepStatus::Pending));
        assert_eq!(state("validate"), Some(StepStatus::Pending));
        assert_eq!(state("transfer"), Some(StepStatus::Pending));
    }

    #[test]
    fn fresh_book_is_all_pending() {
        let detail = detail_of(serde_json::json!({
            "id": "book-0",
            "draft_status": "none",
            "chapters": [],
            "total_sentences": 0,
            "cast": {},
            "bundles": [],
            "valid": false,
            "validation": [],
            "on_tablet": false,
        }));
        let steps = stepper(&detail, false);
        assert!(steps.iter().all(|step| step.status == StepStatus::Pending));
    }

    #[test]
    fn full_book_with_stale_cast_steps_truthfully() {
        let detail = detail_of(serde_json::json!({
            "id": "book-1",
            "draft_status": "done",
            "chapters": [{"index": 1, "title": "Ch 1", "sentences": 10, "est_seconds": 60.0}],
            "total_sentences": 10,
            "cast": {"has_narrator": true},
            "bundles": [{"id": "b1", "path": "bundles/b1", "chapters": 1,
                         "duration_ms": 60000, "valid": true, "errors": []}],
            "valid": true,
            "validation": [],
            "on_tablet": true,
        }));
        let steps = stepper(&detail, true);
        let state = |label: &str| {
            steps
                .iter()
                .find(|step| step.label == label)
                .map(|step| step.status)
        };
        assert_eq!(state("extract"), Some(StepStatus::Done));
        assert_eq!(state("draft"), Some(StepStatus::Done));
        assert_eq!(state("cast"), Some(StepStatus::Done));
        assert_eq!(state("render"), Some(StepStatus::Done));
        assert_eq!(state("validate"), Some(StepStatus::Done));
        assert_eq!(state("transfer"), Some(StepStatus::Done));
        let cast = steps.iter().find(|step| step.label == "cast").unwrap();
        assert!(cast.note.contains("edited since draft"), "{}", cast.note);
    }

    #[test]
    fn failed_draft_and_invalid_bundle_flag() {
        let detail = detail_of(serde_json::json!({
            "id": "book-2",
            "draft_status": "failed",
            "chapters": [],
            "total_sentences": 0,
            "cast": {},
            "bundles": [{"id": "b1", "path": "bundles/b1", "chapters": 0,
                         "duration_ms": 0, "valid": false,
                         "errors": [{"file": "audio", "rule": "x", "message": "bad"}]}],
            "valid": false,
            "validation": [],
            "on_tablet": false,
        }));
        let steps = stepper(&detail, false);
        let state = |label: &str| {
            steps
                .iter()
                .find(|step| step.label == label)
                .map(|step| step.status)
        };
        assert_eq!(state("draft"), Some(StepStatus::Failed));
        assert_eq!(state("validate"), Some(StepStatus::Failed));
        assert_eq!(state("render"), Some(StepStatus::Done));
    }
}
