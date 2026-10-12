// SPDX-License-Identifier: AGPL-3.0-or-later
//! Render selection form (Slice 18 JP2): which chapters or pages a build
//! covers, plus the device override. Pure state transitions (unit-tested);
//! the worker sends the resulting [`scribe_client::Selection`] to plan and
//! build. One form wins at a time: checking a box clears both text fields,
//! typing in either field clears the boxes and the other field (the server
//! rejects chapters-plus-pages, so the UI never sends both).

use std::collections::BTreeSet;

/// Render picker state for one book (reset on book switch).
#[derive(Debug, Clone, Default)]
pub struct Form {
    /// Checked chapter indexes (1-based, sorted by construction).
    pub picked: BTreeSet<u64>,
    /// Chapter range text (`"3-5,7"`); exclusive with `picked` and `pages`.
    pub range_text: String,
    /// PDF page range text; exclusive with `picked` and `range_text`.
    pub pages_text: String,
    /// Device override (`None` means the app setting).
    pub device_override: Option<String>,
}

/// Check/uncheck one chapter box (checkbox form wins: texts clear).
pub fn toggle(form: &mut Form, index: u64) {
    if !form.picked.remove(&index) {
        form.picked.insert(index);
    }
    form.range_text.clear();
    form.pages_text.clear();
}

/// Edit the chapter range field (range wins: boxes and pages clear).
pub fn set_range(form: &mut Form, text: &str) {
    form.range_text = text.to_owned();
    form.picked.clear();
    form.pages_text.clear();
}

/// Edit the PDF page range field (pages win: boxes and range clear).
pub fn set_pages(form: &mut Form, text: &str) {
    form.pages_text = text.to_owned();
    form.picked.clear();
    form.range_text.clear();
}

/// Chapters argument for plan/build: the range text when set, else the
/// picked boxes joined (`"1,3,4"`); `None` renders the whole book.
pub fn chapters_arg(form: &Form) -> Option<String> {
    if !form.range_text.trim().is_empty() {
        return Some(form.range_text.trim().to_owned());
    }
    if form.picked.is_empty() {
        return None;
    }
    Some(
        form.picked
            .iter()
            .map(u64::to_string)
            .collect::<Vec<_>>()
            .join(","),
    )
}

/// Worker selection: pages win over chapters (only one is ever set), and
/// the device falls back to the app setting (always sent: the helper has
/// no other way to learn it per render).
pub fn to_selection(form: &Form, app_device: &str) -> scribe_client::Selection {
    let pages = form.pages_text.trim();
    let chapters = if pages.is_empty() {
        chapters_arg(form)
    } else {
        None
    };
    scribe_client::Selection {
        chapters,
        pages: (!pages.is_empty()).then(|| pages.to_owned()),
        device: Some(
            form.device_override
                .clone()
                .unwrap_or_else(|| app_device.to_owned()),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn boxes_join_sorted_and_clear_texts() {
        let mut form = Form {
            range_text: "2-3".to_owned(),
            pages_text: "5-6".to_owned(),
            ..Default::default()
        };
        toggle(&mut form, 4);
        toggle(&mut form, 1);
        assert_eq!(chapters_arg(&form), Some("1,4".to_owned()));
        assert!(form.range_text.is_empty());
        assert!(form.pages_text.is_empty());
        toggle(&mut form, 4);
        assert_eq!(chapters_arg(&form), Some("1".to_owned()));
    }

    #[test]
    fn range_wins_over_boxes_and_pages() {
        let mut form = Form::default();
        toggle(&mut form, 1);
        set_range(&mut form, "2-3");
        assert!(form.picked.is_empty());
        assert!(form.pages_text.is_empty());
        assert_eq!(chapters_arg(&form), Some("2-3".to_owned()));
        let selection = to_selection(&form, "auto");
        assert_eq!(selection.chapters, Some("2-3".to_owned()));
        assert_eq!(selection.pages, None);
    }

    #[test]
    fn pages_win_over_everything() {
        let mut form = Form::default();
        toggle(&mut form, 1);
        set_range(&mut form, "2-3");
        set_pages(&mut form, "5-6");
        assert!(form.picked.is_empty());
        assert!(form.range_text.is_empty());
        let selection = to_selection(&form, "cpu");
        assert_eq!(selection.pages, Some("5-6".to_owned()));
        assert_eq!(selection.chapters, None);
        assert_eq!(selection.device, Some("cpu".to_owned()));
    }

    #[test]
    fn empty_form_renders_the_whole_book_on_app_device() {
        let form = Form::default();
        let selection = to_selection(&form, "auto");
        assert_eq!(selection.chapters, None);
        assert_eq!(selection.pages, None);
        assert_eq!(selection.device, Some("auto".to_owned()));
    }

    #[test]
    fn device_override_beats_app_setting_and_clears() {
        let overridden = Form {
            device_override: Some("cuda".to_owned()),
            ..Default::default()
        };
        assert_eq!(
            to_selection(&overridden, "auto").device,
            Some("cuda".to_owned())
        );
        assert_eq!(
            to_selection(&Form::default(), "auto").device,
            Some("auto".to_owned())
        );
    }

    #[test]
    fn recorded_plan_pins_preflight_shape() {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("scribe-client")
            .join("tests")
            .join("data")
            .join("plan.json");
        let text = std::fs::read_to_string(&path).expect("recorded fixture");
        let plan: scribe_client::PlanResult = serde_json::from_str(&text).expect("plan parses");
        assert_eq!(plan.selected_chapters, vec![1]);
        assert!(plan.is_range);
        assert_eq!(plan.to_render_sentences, 25);
        assert!(!plan.message.is_empty());
    }
}
