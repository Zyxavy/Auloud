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

/// Audition engines (the helper's `SUPPORTED_ENGINES`; nothing else answers).
pub const ENGINES: [&str; 2] = ["kokoro", "piper"];

/// Decode audition-clip bytes (the helper serves wav only). Used by the
/// player and pinned by the test below, so a format change fails here,
/// not mid-click.
pub fn decode_wav(bytes: &[u8]) -> Result<rodio::Decoder<std::io::Cursor<Vec<u8>>>, String> {
    rodio::Decoder::try_from(std::io::Cursor::new(bytes.to_vec()))
        .map_err(|err| format!("clip would not decode: {err:?}"))
}

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

    /// 100 ms of 8 kHz mono 16-bit silence, built by hand (no helper).
    fn silent_wav() -> Vec<u8> {
        let samples = [0i16; 800];
        let mut wav = Vec::with_capacity(44 + samples.len() * 2);
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36 + samples.len() as u32 * 2).to_le_bytes());
        wav.extend_from_slice(b"WAVEfmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&8000u32.to_le_bytes());
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(samples.len() as u32 * 2).to_le_bytes());
        for sample in samples {
            wav.extend_from_slice(&sample.to_le_bytes());
        }
        wav
    }

    #[test]
    fn clips_decode_and_garbage_does_not() {
        use rodio::Source as _;

        let wav = silent_wav();
        let decoder = decode_wav(&wav).expect("hand-built wav decodes");
        let ms = decoder
            .total_duration()
            .map(|duration| duration.as_millis() as u64)
            .expect("wav knows its length");
        assert!((95..=105).contains(&ms), "{ms}");
        assert!(decode_wav(b"not audio").is_err());
        assert!(decode_wav(&[]).is_err());
    }
}
