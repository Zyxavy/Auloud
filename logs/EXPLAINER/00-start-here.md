# Auloud contributor explainer: start here

New to the repo? Read these pages in order. Each answers one question and links to the next. Names and paths are current as of Player `2.1.0` / Scribe on `dev/v2`.

| Page | Question it answers | Minutes |
| --- | --- | --- |
| `01-what-and-why.md` | What is Auloud and why does it look like this? | 5 |
| `02-repo-tour.md` | Where is everything and how do I build it? | 5 |
| `03-scribe-pipeline.md` | How does an EPUB become a bundle on the PC? | 15 |
| `04-voices-and-gender.md` | How is dialogue found, who speaks it, and what does gender mean? | 10 |
| `05-bundle-contract.md` | What is the bundle format both sides obey? | 10 |
| `06-player.md` | How does the tablet ingest, render, stream, and play books? | 15 |
| `07-why-this-architecture.md` | Why these decisions and not the obvious alternatives? | 10 |
| `08-glossary.md` | What does this word mean here? | lookup |

Three reading paths:

- "I want to fix a Scribe bug": 01, 03, 04, 05.
- "I want to fix a Player bug": 01, 02, 05, 06.
- "I want to propose a change": all of them, then `docs/03-BundleSpec.md` (the law) and `docs/DECISIONS.md` (the history).

Rules that bind every page:

- The bundle spec (`docs/03-BundleSpec.md`, copy at `spec/bundle.md`) is the contract between Scribe and Player. Changing the format means updating the spec first plus a `docs/DECISIONS.md` entry.
- `minSdk 24`, no `INTERNET` permission, one chapter's JSON in memory at a time.
- `core` flavor is Apache-2.0, `full` is GPL-3.0. Never move GPL code into `core`.
- History files (`docs/test-log.md`, `docs/DECISIONS.md`, `docs/archive/`) stay verbatim. Quote them, do not rewrite them.
