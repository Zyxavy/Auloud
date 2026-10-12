// SPDX-License-Identifier: AGPL-3.0-or-later
//! App settings (Slice 17 AP1): workspace location, device, helper path.
//!
//! Plain `key=value` lines (no new dependency for four fields), versioned
//! first line, atomic write. Lives in a fixed per-user spot so it survives
//! workspace switches; corrupt or missing files fall back to defaults.

use std::io;
use std::path::{Path, PathBuf};

/// Settings file version (bump on format change; old files reset).
pub const SETTINGS_VERSION: u32 = 1;
/// Compute device vocabulary (mirrors the helper's `auto|cpu|cuda`).
pub const DEVICES: [&str; 3] = ["auto", "cpu", "cuda"];

/// App settings (see module docs for the file format).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Settings {
    /// Book workspace (books, work dirs, bundles).
    pub workspace: PathBuf,
    /// Compute device (`auto|cpu|cuda`).
    pub device: String,
    /// Explicit helper exe (empty means auto-discover).
    pub helper_path: Option<PathBuf>,
}

impl Settings {
    /// Defaults: `~/Auloud` workspace, `auto` device, discovered helper.
    pub fn defaulted() -> Self {
        Self {
            workspace: home_dir().join("Auloud"),
            device: "auto".to_owned(),
            helper_path: None,
        }
    }

    /// Fixed per-user location (Windows `%APPDATA%`, else `~/.config`).
    pub fn config_path() -> PathBuf {
        config_dir().join("scribe-ui.settings")
    }

    /// Load from the fixed path; defaults on missing/corrupt files.
    pub fn load() -> Self {
        Self::load_from(&Self::config_path())
    }

    /// Load from an explicit path (tests use temp dirs).
    pub fn load_from(path: &Path) -> Self {
        let text = std::fs::read_to_string(path).unwrap_or_default();
        let mut settings = Self::defaulted();
        let mut version_ok = false;
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((key, value)) = line.split_once('=') else {
                continue;
            };
            match key.trim() {
                "version" => version_ok = value.trim() == SETTINGS_VERSION.to_string(),
                "workspace" if !value.trim().is_empty() => {
                    settings.workspace = PathBuf::from(value.trim());
                }
                "device" if DEVICES.contains(&value.trim()) => {
                    settings.device = value.trim().to_owned();
                }
                "helper_path" if !value.trim().is_empty() => {
                    settings.helper_path = Some(PathBuf::from(value.trim()));
                }
                _ => {}
            }
        }
        if version_ok {
            settings
        } else {
            Self::defaulted()
        }
    }

    /// Atomic save (temp file + rename) to the fixed path.
    pub fn save(&self) -> io::Result<()> {
        self.save_to(&Self::config_path())
    }

    /// Atomic save to an explicit path.
    pub fn save_to(&self, path: &Path) -> io::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut text = format!("# auloud-scribe-ui settings v{SETTINGS_VERSION}\n");
        text.push_str(&format!("version={SETTINGS_VERSION}\n"));
        text.push_str(&format!("workspace={}\n", self.workspace.display()));
        text.push_str(&format!("device={}\n", self.device));
        text.push_str(&format!(
            "helper_path={}\n",
            self.helper_path
                .as_ref()
                .map(|p| p.display().to_string())
                .unwrap_or_default()
        ));
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, text)?;
        std::fs::rename(&tmp, path)?;
        Ok(())
    }
}

/// Per-user config dir (Windows `%APPDATA%\Auloud`, else `~/.config/auloud`).
fn config_dir() -> PathBuf {
    #[cfg(windows)]
    {
        std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(home_dir)
            .join("Auloud")
    }
    #[cfg(not(windows))]
    {
        home_dir().join(".config").join("auloud")
    }
}

/// Home dir (`%USERPROFILE%` on Windows, `$HOME` elsewhere; cwd fallback).
fn home_dir() -> PathBuf {
    #[cfg(windows)]
    const HOME_VAR: &str = "USERPROFILE";
    #[cfg(not(windows))]
    const HOME_VAR: &str = "HOME";
    std::env::var_os(HOME_VAR)
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "scribe-ui-settings-{}-{}",
            std::process::id(),
            name
        ))
    }

    #[test]
    fn round_trip_preserves_everything() {
        let path = temp_path("roundtrip");
        let settings = Settings {
            workspace: PathBuf::from("C:\\books"),
            device: "cpu".to_owned(),
            helper_path: Some(PathBuf::from("C:\\helper\\scribe-helper.exe")),
        };
        settings.save_to(&path).expect("save");
        assert_eq!(Settings::load_from(&path), settings);
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn missing_file_gives_defaults() {
        let path = temp_path("missing");
        std::fs::remove_file(&path).ok();
        let settings = Settings::load_from(&path);
        assert_eq!(settings, Settings::defaulted());
    }

    #[test]
    fn corrupt_or_old_version_resets() {
        let path = temp_path("corrupt");
        std::fs::write(&path, "workspace=C:\\x\ndevice=cpu\n").expect("write");
        assert_eq!(Settings::load_from(&path), Settings::defaulted());
        std::fs::write(&path, "version=999\nworkspace=C:\\x\n").expect("write");
        assert_eq!(Settings::load_from(&path), Settings::defaulted());
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn unknown_device_falls_back_to_auto() {
        let path = temp_path("device");
        std::fs::write(&path, "version=1\ndevice=tpu\n").expect("write");
        assert_eq!(Settings::load_from(&path).device, "auto");
        std::fs::remove_file(&path).ok();
    }
}
