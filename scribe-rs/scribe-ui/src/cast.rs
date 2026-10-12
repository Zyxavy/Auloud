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

/// Quotes page size (the server default; one value everywhere).
pub const QUOTES_PER_PAGE: u64 = 100;

/// Picker window label (`1-100 of 384`; past-the-end says `0 of N`).
pub fn page_window(total: u64, page: u64, per_page: u64) -> String {
    if total == 0 || per_page == 0 {
        return "0 of 0".to_owned();
    }
    let first = (page.max(1).saturating_sub(1))
        .saturating_mul(per_page)
        .saturating_add(1);
    if first > total {
        return format!("0 of {total}");
    }
    let last = page.saturating_mul(per_page).min(total);
    format!("{first}-{last} of {total}")
}

/// Override speaker options: narrator plus real characters (generics fail
/// `set_first_person`/override validation server-side, so the panel never
/// offers them). Shared by the first-person and per-quote dropdowns.
pub fn speaker_options(stats: &[scribe_client::CastStat]) -> Vec<String> {
    let mut options = vec!["narrator".to_owned()];
    for stat in stats {
        if stat.kind == "character" && !options.contains(&stat.character) {
            options.push(stat.character.clone());
        }
    }
    options
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

    #[test]
    fn windows_clamp_past_the_end() {
        assert_eq!(page_window(384, 1, 100), "1-100 of 384");
        assert_eq!(page_window(384, 4, 100), "301-384 of 384");
        assert_eq!(page_window(384, 9, 100), "0 of 384");
        assert_eq!(page_window(0, 1, 100), "0 of 0");
    }

    #[test]
    fn speaker_options_skip_generics() {
        let stats = vec![
            scribe_client::CastStat {
                character: "narrator".to_owned(),
                kind: "narrator".to_owned(),
                ..Default::default()
            },
            scribe_client::CastStat {
                character: "Alice".to_owned(),
                kind: "character".to_owned(),
                ..Default::default()
            },
            scribe_client::CastStat {
                character: "default_female".to_owned(),
                kind: "generic".to_owned(),
                ..Default::default()
            },
        ];
        assert_eq!(speaker_options(&stats), ["narrator", "Alice"]);
    }
}
