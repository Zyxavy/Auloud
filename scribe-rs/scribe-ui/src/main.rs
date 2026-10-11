// SPDX-License-Identifier: AGPL-3.0-or-later
//! Tauri shell (Slice 15: toolchain stub only). Opens a hello window so
//! RS0 proves the Tauri v2 + WebView2 path works; Slice 20 hosts the real UI.

fn main() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("unable to run the Auloud Scribe window");
}
