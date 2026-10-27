// SPDX-License-Identifier: AGPL-3.0-or-later
//! In-app licenses (LG1): a read-only view of the files that ship beside
//! the app. `scribe/dev/gen_licenses.py` writes them into `licenses/` next
//! to `scribe-ui.exe`, so the screen shows exactly what this build carries.
//! A source tree has no such folder; the screen then names the generator
//! instead of showing an empty page.

use std::path::{Path, PathBuf};

/// The generated folder beside the executable.
pub fn dir_for(exe: &Path) -> PathBuf {
    exe.parent()
        .unwrap_or_else(|| Path::new(""))
        .join("licenses")
}

/// One file as the screen shows it: a title and its body, or why it is
/// absent (a partial folder stays visible instead of silently empty).
pub struct Document {
    pub title: &'static str,
    pub body: String,
}

/// The files the screen offers, with the generator's names.
const DOCUMENTS: [(&str, &str); 3] = [
    ("NOTICE.txt", "Notice"),
    ("THIRD_PARTY_LICENSES.txt", "Third-party components"),
    ("SOURCE_OFFER.txt", "Written offer of Corresponding Source"),
];

/// Folder holding one text per license in use.
pub const TEXTS_DIR: &str = "texts";

/// Read the documents from `dir`, in order.
pub fn load(dir: &Path) -> Vec<Document> {
    DOCUMENTS
        .iter()
        .map(|(file, title)| {
            let path = dir.join(file);
            Document {
                title,
                body: std::fs::read_to_string(&path).unwrap_or_else(|err| {
                    format!(
                        "cannot read {}: {err}\n\nA packaged build carries this file \
                         beside scribe-ui.exe; in a source tree run \
                         scribe/dev/gen_licenses.py.",
                        path.display()
                    )
                }),
            }
        })
        .collect()
}

/// The license text files this build carries (sorted).
pub fn texts(dir: &Path) -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(dir.join(TEXTS_DIR))
        .into_iter()
        .flatten()
        .flatten()
        .filter(|entry| entry.path().extension().is_some_and(|ext| ext == "txt"))
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    names
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sandbox(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "auloud-licenses-test-{}-{tag}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|elapsed| elapsed.as_nanos())
                .unwrap_or(0)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn the_folder_sits_beside_the_executable() {
        assert_eq!(
            dir_for(Path::new("/opt/auloud/scribe-ui.exe")),
            Path::new("/opt/auloud/licenses")
        );
    }

    #[test]
    fn load_reads_what_is_there_and_names_what_is_not() {
        let dir = sandbox("load");
        std::fs::write(dir.join("NOTICE.txt"), "Auloud Scribe").unwrap();

        let documents = load(&dir);

        assert_eq!(documents.len(), DOCUMENTS.len());
        assert_eq!(documents[0].title, "Notice");
        assert_eq!(documents[0].body, "Auloud Scribe");
        // A missing file keeps its row and says where it comes from.
        assert!(documents[1].body.contains("gen_licenses.py"));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn texts_lists_only_txt_files_in_order() {
        let dir = sandbox("texts");
        std::fs::create_dir_all(dir.join(TEXTS_DIR)).unwrap();
        std::fs::write(dir.join(TEXTS_DIR).join("MIT.txt"), "x").unwrap();
        std::fs::write(dir.join(TEXTS_DIR).join("AGPL-3.0-or-later.txt"), "x").unwrap();
        std::fs::write(dir.join(TEXTS_DIR).join("notes.md"), "x").unwrap();

        assert_eq!(
            texts(&dir),
            vec!["AGPL-3.0-or-later.txt".to_owned(), "MIT.txt".to_owned()]
        );
        std::fs::remove_dir_all(&dir).ok();
    }
}
