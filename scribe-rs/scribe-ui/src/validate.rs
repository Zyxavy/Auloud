// SPDX-License-Identifier: AGPL-3.0-or-later
//! Slice 20 VT1: validate-list lines for the per-book Validate section.
//!
//! Pure rendering over the `POST /api/books/{id}/validate` document
//! (unit-tested): one tick line per bundle plus `file: rule: detail`
//! lines for every finding. No helper logic lives here.

/// Validate section lines: bundle ticks, then every finding as
/// `file: rule: detail`. Empty when the result carries nothing to show.
pub fn lines(result: &scribe_client::ValidateResult) -> Vec<String> {
    let mut out = Vec::new();
    for bundle in &result.bundles {
        out.push(format!(
            "{}: {}",
            bundle.id,
            if bundle.valid { "valid" } else { "invalid" },
        ));
        for error in &bundle.errors {
            out.push(format!("{}: {}: {}", error.file, error.rule, error.message));
        }
    }
    for finding in &result.validation {
        out.push(format!(
            "{}: {}: {}",
            finding.file, finding.rule, finding.message
        ));
    }
    if result.valid {
        out.push("book validates".to_owned());
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn finding(file: &str, rule: &str, message: &str) -> scribe_client::ApiError {
        scribe_client::ApiError {
            file: file.to_owned(),
            rule: rule.to_owned(),
            message: message.to_owned(),
        }
    }

    fn bundle(
        id: &str,
        valid: bool,
        errors: Vec<scribe_client::ApiError>,
    ) -> scribe_client::BundleEntry {
        scribe_client::BundleEntry {
            id: id.to_owned(),
            path: format!("bundles/{id}"),
            chapters: 1,
            duration_ms: 1000,
            valid,
            errors,
        }
    }

    fn result(
        valid: bool,
        validation: Vec<scribe_client::ApiError>,
        bundles: Vec<scribe_client::BundleEntry>,
    ) -> scribe_client::ValidateResult {
        scribe_client::ValidateResult {
            book_id: "b".to_owned(),
            valid,
            validation,
            bundles,
        }
    }

    #[test]
    fn valid_bundle_ticks_and_confirms() {
        assert_eq!(
            lines(&result(
                true,
                Vec::new(),
                vec![bundle("b1", true, Vec::new())]
            )),
            vec!["b1: valid".to_owned(), "book validates".to_owned()]
        );
    }

    #[test]
    fn invalid_bundle_names_every_finding() {
        let lines = lines(&result(
            false,
            vec![finding("manifest.json", "bad-shape", "no id")],
            vec![bundle(
                "b1",
                false,
                vec![finding("audio/ch001.mp3", "missing-audio", "gone")],
            )],
        ));
        assert_eq!(
            lines,
            vec![
                "b1: invalid".to_owned(),
                "audio/ch001.mp3: missing-audio: gone".to_owned(),
                "manifest.json: bad-shape: no id".to_owned(),
            ]
        );
    }
}
