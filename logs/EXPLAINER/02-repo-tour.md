# Repo tour

```
player/   Android app (Kotlin, Compose, Media3, Room)
scribe/   Python CLI plus library (the logic lives in the library)
spec/     bundle spec copy plus shared test fixtures
docs/     PRD, designs, roadmaps, test plan, decisions, slice plans
logs/     run outputs, fix plans, this explainer (gitignored; force-added on purpose)
```

## Player

`player/app/src/main/java/app/auloud/player/` holds the app: `bundle/` (parse and validate), `ingest/` (on-device EPUB import), `render/` (background rendering), `tts/` (engine abstraction and voices), `playback/` (service, stream player, sleep timer), `reader/` and `library/` (UI plus view models), `data/` (Room), `storage/` (the `BundleStorage` file-access interface). Flavor code lives in `src/core` (Apache-2.0 stub) and `src/full` (GPL Piper engine).

Build and check from `player/`:

```
gradlew.bat :app:assembleCoreRelease :app:assembleFullRelease
gradlew.bat :app:testCoreDebugUnitTest :app:testFullDebugUnitTest --no-daemon
gradlew.bat :app:lintCoreDebug :app:lintFullDebug
```

Release builds need the signing key in gitignored `player/local.properties` (see `docs/ReleaseSigning.md`). Without it the build signs with the debug key and warns.

## Scribe

`scribe/` holds the library (`extract/`, `text/`, `tts/`, `audio/`, `bundle/`, `ui/`) with `cli.py` as a thin wrapper. Pipeline detail is in `03-scribe-pipeline.md`.

```
cd scribe
uv sync --extra ui
uv run pytest
scribe validate <bundle-dir>
```

A plain `uv sync` prunes the `ui` extra and silently skips about 100 UI tests, so always sync with the extra.

## Docs that matter most

- `docs/03-BundleSpec.md`: the contract. Read before any bundle work.
- `docs/DECISIONS.md`: why things are the way they are. Do not contradict it without asking.
- `docs/09-V2Roadmap.md` plus `docs/plans/`: what was built and what is next.
- `AGENTS.md`: how agents (and humans) work in this repo.
