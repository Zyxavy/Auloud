// SPDX-License-Identifier: AGPL-3.0-or-later
//! `scribe-rs` command line front end (binary name stays `scribe-rs` until v3.0).
//!
//! RS5 wires `validate` and the `doctor` skeleton; until then both commands
//! say so and exit 2 (reserved for "not built yet", never a bundle verdict).

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "scribe-rs", about = "Auloud Scribe (Rust port, parity build)")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Run the spec checks over a bundle (RS5).
    Validate {
        /// Bundle directory to validate.
        bundle: String,
        /// Emit the normalized JSON form for the parity harness.
        #[arg(long)]
        json: bool,
    },
    /// Check ffmpeg/ffprobe, workspace and toolchain (RS5 skeleton).
    Doctor {
        /// Emit the normalized JSON form for the parity harness.
        #[arg(long)]
        json: bool,
    },
}

fn main() {
    let cli = Cli::parse();
    let _ = cli.command;
    eprintln!("scribe-rs: this command arrives in RS5");
    std::process::exit(2);
}
