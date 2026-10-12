// SPDX-License-Identifier: AGPL-3.0-or-later
//! Cast table logic (Slice 19 CS1): pure helpers over the reported cast
//! payload. Voice assignments and speeds round-trip through PATCH ops;
//! anything about what the numbers mean stays Python.

/// Dropdown options: every voice the cast already uses first (a book may
/// render on an engine we never listed), then the listed engine voices.
/// Order kept, duplicates dropped.
pub fn merge_voice_options(currents: &[String], listed: &[String]) -> Vec<String> {
    let mut options = Vec::with_capacity(currents.len() + listed.len());
    for voice in currents.iter().chain(listed.iter()) {
        if !voice.is_empty() && !options.contains(voice) {
            options.push(voice.clone());
        }
    }
    options
}

/// Parse a speed field: a number above 0 (the server rejects the rest with
/// `bad-op`, so the panel refuses them first with the same words).
pub fn parse_speed(text: &str) -> Result<f64, String> {
    match text.trim().parse::<f64>() {
        Ok(speed) if speed.is_finite() && speed > 0.0 => Ok(speed),
        _ => Err(format!("speed must be a number above 0 (got {text:?})")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn currents_come_first_without_duplicates() {
        let options = merge_voice_options(
            &["am_adam".to_owned(), "jf_alpha".to_owned()],
            &["jf_alpha".to_owned(), "af_bella".to_owned(), "".to_owned()],
        );
        assert_eq!(options, ["am_adam", "jf_alpha", "af_bella"]);
    }

    #[test]
    fn empty_lists_stay_empty() {
        assert!(merge_voice_options(&[], &[]).is_empty());
    }

    #[test]
    fn speeds_take_positive_finite_only() {
        assert_eq!(parse_speed("1.1"), Ok(1.1));
        assert_eq!(parse_speed(" 2 "), Ok(2.0));
        assert!(parse_speed("0").is_err());
        assert!(parse_speed("-1").is_err());
        assert!(parse_speed("fast").is_err());
        assert!(parse_speed("").is_err());
    }
}
