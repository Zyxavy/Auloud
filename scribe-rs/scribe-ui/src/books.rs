// SPDX-License-Identifier: AGPL-3.0-or-later
//! Add-book staging (Slice 17 AP2): validate and copy a picked ebook into
//! the workspace. Pure function over temp dirs in tests; the dialog itself
//! is owner-tested (native modal).

use std::path::{Path, PathBuf};

/// Copy `source` into the workspace root for upload.
///
/// Rejects non-files and non-ebook extensions; dedupes the target name with
/// a `stem-N.ext` suffix (the server dedupes again on upload, so a clash
/// here can never collide there).
///
/// # Errors
///
/// Returns a human string (rendered verbatim) when the source is unusable
/// or the copy fails.
pub fn stage_book(workspace: &Path, source: &Path) -> Result<PathBuf, String> {
    if !source.is_file() {
        return Err(format!("cannot add {}: not a file", source.display()));
    }
    let extension = source
        .extension()
        .and_then(|ext| ext.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    if extension != "epub" && extension != "pdf" {
        return Err(format!(
            "cannot add {}: needs .epub or .pdf",
            source
                .file_name()
                .map(|n| n.to_string_lossy())
                .unwrap_or_default()
        ));
    }
    let file_name = source
        .file_name()
        .ok_or_else(|| "cannot add file without a name".to_owned())?;
    let mut target = workspace.join(file_name);
    if target.exists() {
        let stem = source
            .file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "book".to_owned());
        let mut n = 2;
        loop {
            let candidate = workspace.join(format!("{stem}-{n}.{extension}"));
            if !candidate.exists() {
                target = candidate;
                break;
            }
            n += 1;
        }
    }
    std::fs::copy(source, &target)
        .map_err(|err| format!("cannot copy {}: {err}", source.display()))?;
    Ok(target)
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

#[cfg(test)]
mod tests {
    use super::*;

    fn sandbox(name: &str) -> PathBuf {
        let dir =
            std::env::temp_dir().join(format!("scribe-ui-stage-{}-{name}", std::process::id()));
        std::fs::create_dir_all(&dir).expect("dir");
        dir
    }

    #[test]
    fn copies_epub_and_pdf() {
        let dir = sandbox("copy");
        let workspace = dir.join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        for name in ["book.epub", "scan.PDF"] {
            let source = dir.join(name);
            std::fs::write(&source, b"fake").expect("write");
            let staged = stage_book(&workspace, &source).expect("stages");
            assert_eq!(staged.parent(), Some(workspace.as_path()));
            assert!(staged.is_file());
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn dedupes_with_numbered_suffix() {
        let dir = sandbox("dedupe");
        let workspace = dir.join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let source = dir.join("book.epub");
        std::fs::write(&source, b"fake").expect("write");
        let first = stage_book(&workspace, &source).expect("first");
        let second = stage_book(&workspace, &source).expect("second");
        assert_ne!(first, second);
        assert_eq!(
            second.file_name().and_then(|n| n.to_str()),
            Some("book-2.epub")
        );
        std::fs::remove_dir_all(&dir).ok();
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

    #[test]
    fn rejects_non_ebooks_and_missing_files() {
        let dir = sandbox("reject");
        let workspace = dir.join("ws");
        std::fs::create_dir_all(&workspace).expect("ws");
        let text = dir.join("notes.txt");
        std::fs::write(&text, b"fake").expect("write");
        let err = stage_book(&workspace, &text).expect_err("must refuse");
        assert!(err.contains(".epub or .pdf"), "{err}");
        let err = stage_book(&workspace, &dir.join("ghost.epub")).expect_err("must refuse");
        assert!(err.contains("not a file"), "{err}");
        std::fs::remove_dir_all(&dir).ok();
    }
}
