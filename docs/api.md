# Auloud Helper API Contract (v1)

The contract between the Python Scribe helper and the native Rust UI (Slice 16).
Written from `scribe/ui/` code; the `scribe-client` crate (API2) is its
executable companion: if a recorded-response test disagrees with this doc,
one of the two is wrong (contract-first rule, Slice16 decision 1).

## 1. Connection

- Transport: HTTP on `127.0.0.1` only. The helper takes no `--host` flag and
  refuses anything but loopback (`ui/server.py`). Random port (`--port 0`
  means OS-assigned); the UI learns it from the ready line, never by scanning.
- Ready line: the helper prints exactly one JSON line on stdout once it serves:
  `{"ready": true, "port": <picked>, "versions": {"api": 1, "scribe": "0.1.0",
  "spec": ["1.0", "1.1", "1.2", "2.0"]}}` (keys sorted; scribe/spec values
  follow the installed code). The ready line means "starting"; `/api/health`
  is truth. The parent retries it (Slice 16 owns the parent side).
- Version pin: the client speaks one `api` version (today `1`) and checks it on
  the ready line and `GET /api/version`. A helper version it does not know
  fails loudly with the upgrade message, never silently.
- Token: per-run secret from `AULOUD_HELPER_TOKEN`, sent as
  `X-Auloud-Token` on every request in helper mode (GET included). Wrong or
  missing token: `403 {"detail": "forbidden"}`. The token is never logged,
  never echoed, never passed on the command line.
- Host check runs before auth: only `127.0.0.1` / `localhost` with an optional
  `:port` (case-insensitive; first entry of a comma list). Anything else,
  including `127.0.0.1.evil.com`, IPv6 literals, or a missing header:
  `421 {"detail": "misdirected host"}`. The Host value is never echoed.
- No CORS headers anywhere. Auto docs (`/docs`, `/redoc`, `/openapi.json`)
  are disabled; this file is the API reference.
- `GET /` serves the legacy web skeleton (HTML with the per-run token
  embedded); the native UI does not use it.

## 2. Conventions

- Success is `200` unless a route says `202` (a job was queued; poll it).
- App errors are `{file, rule, message}` where message usually repeats
  `file: rule: detail`. Framework 404/405 on unknown routes keep
  `{"detail": ...}`, not the envelope. Malformed bodies give
  `400 {file: "request", rule: "bad-request", ...}`, never bare 422.
  Unexpected failures give `500 {file: "server", rule: "internal", ...}`
  truncated to 300 chars with no traceback.
- `book_id` is a UUIDv5 hex of the source bytes; path ids must match
  `[A-Za-z0-9][A-Za-z0-9._-]*` (else `400 path-traversal`).
- `job_id` must match `^[A-Za-z0-9_-]{1,64}$` (else `400 bad-job-id`).
- Job states: `queued | running | paused | done | failed | cancelled |
  interrupted` (`interrupted` = was running at helper restart with a dead
  pid). Terminal: `done | failed | cancelled`. Kinds: `build | draft |
  voices-sample | transfer`. One global FIFO (one `running` at a time); one
  render per book (a second build/draft on a busy book is `409
  work-lock-held`); voices/transfer jobs take no book lock.
- Every job response carries the public shape: `{id, book_id, kind, state,
  created, updated, pid, exit, summary: {cached, rendered, audio_ms, wall_s},
  error: {file, rule, message} | null, chapters, pages, device, source_file,
  voices, engine, engine_version, text_hash, bundle, destination,
  dest_bundle, bundle_id, bytes_total, bytes_copied, verification}`.
- Book draft status (`drafting | done | failed | cancelled | none`) is scan
  state, not job state; `drafting` covers queued/running/paused/interrupted
  draft jobs.
- Range strings (`chapters`/`pages`) are validated by the build planner:
  `bad-range`, `chapters-pages-exclusive` (both together), `pages-need-pdf`,
  `pages-no-cover`. `device` is `auto | cpu | cuda` (default `auto`).
- Settings persist in `<workspace>/.scribe-ui-settings.json`
  (`{keep_awake: bool default true, device: "auto"}`).
- The UI v1 subset is sections 3-7 except transfer/open-folder/drives, which
  are documented here but owned by later slices.

## 3. Books

- `GET /api/books` -> `200 {books: [{id, title, author (string|null: no
  readable metadata), source_file (string|null), source_type
  (epub|pdf|null), chapters, sentences, drafted, cast_edited,
  rendering, rendering_percent: 0-100|null, draft_status, bundles, valid,
  on_tablet, transfer}]}`, sorted by title.
- `GET /api/books/{book_id}` -> full detail: identity (`author`,
  `source_file`, `source_sha256` are null until mapped; `cast_edited`
  flags a `cast.yaml` newer than the script), `chapters: [{index,
  title, sentences, est_seconds}]`, `cast` summary, `bundles: [{id, path,
  chapters, duration_ms, valid, errors}]`, `validation`, `on_tablet`,
  transfer record. `400 path-traversal` on a bad id; `404 book-not-found`.
- `POST /api/books/upload` (token required; multipart field `file`, `.epub`
  or `.pdf` only, 100 MiB cap) -> `202 {book_id, filename,
  status: "drafting", job_id}` (queues a draft job on the FIFO).
  `400 bad-extension | path-traversal | empty-file | draft-failed`,
  `409 name-taken` past 1000 copies, `413 too-large`.
- `POST /api/books/{book_id}/build` (token required; optional JSON
  `{chapters?, pages?, device?}`, present fields must be strings) ->
  `202 {job_id, book_id, state}`. `409 work-lock-held` when the book is busy;
  `404 book-not-found`.
- `POST /api/books/{book_id}/plan` (token required; same selection fields;
  pure, no lock, no writes) -> `200` preflight `{book_id (range-aware id when
  chapters/pages select a range, else the book id), title,
  source_chapters_total: int, selected_chapters: [int], cached_chapters: int,
  to_render_chapters: int, cached_sentences: int, to_render_sentences: int,
  estimated_seconds: float|null, rtf_used: float|null, page_resolution: str,
  is_range: bool, message}`. `500 plan-failed` on engine failure.

## 4. Jobs

- `GET /api/jobs` -> `200 {jobs: [...]}` newest first.
- `GET /api/jobs/{job_id}` -> the public shape. `400 bad-job-id`,
  `404 job-not-found`.
- `POST /api/jobs/{job_id}/pause` (token required; graceful stop at a
  sentence boundary, cache kept) -> current shape (`paused`, or still
  `running` if the child has not exited yet). `409 bad-state` unless
  running; voices/transfer cannot pause.
- `POST /api/jobs/{job_id}/resume` (token required; new process, cache
  reused) -> shape (`queued`/`running`). `409 bad-state` unless resumable;
  `409 work-lock-held` if another run holds the book.
- `POST /api/jobs/{job_id}/cancel` (token required; running builds are
  terminated and torn chapter files deleted; drafts record failure; transfers
  delete the partial copy) -> shape (`cancelled`). `409 bad-state` on
  terminal jobs.
- `GET /api/jobs/{job_id}/events` (SSE; token rule follows app mode):
  `200 text/event-stream`, headers `Cache-Control: no-cache`,
  `X-Accel-Buffering: no`, `Connection: keep-alive`. Immediate `: ping`
  comment, then one per 15 s idle. Each new valid-JSON line of the job's
  `events.jsonl` replays as `id: <1-based line>\ndata: <line>` (blank/torn
  lines skipped; torn tails retried every 0.5 s). Resume with `Last-Event-ID`
  header or `?lastEventId=` (unparsable/negative reads as 0). The stream ends
  after a final drain once the job is done/failed/cancelled/paused/
  interrupted. Event bodies carry `{event, chapter, sid, cached, rendered,
  audio_ms, wall_s}` plus phase markers (`draft_start`, `draft_done`,
  `draft_failed` with the error shape, `build_done`). `400 bad-job-id` /
  `404 job-not-found` come back as JSON, not SSE.

## 5. Settings

- `GET /api/settings` -> `200 {keep_awake, device}`.
- `PUT /api/settings` (token required; `{keep_awake?: bool, device?}`;
  at least one known field; unmentioned fields keep stored values; device
  validated live) -> persisted `200 {keep_awake, device}`.
  `400 bad-request` (no known field / non-bool flag),
  `400 bad-device | cuda-unavailable`.

## 6. Cast and quotes

- `GET /api/books/{book_id}/cast` -> `200 {book_id, mtime (conflict guard),
  has_comments, cast, first_person, stats: [{character, kind:
  narrator|character|generic, voice, speed, lines, minutes, low}],
  minor: [...] (surfaces with at most 3 lines), quotes_total,
  low_confidence_total, has_audio, validation}` plus `comment_warning` when
  the file has `#` comments. `404 cast-not-found` (draft first).
- `PUT /api/books/{book_id}/cast` (token required; `{cast (required dict),
  expected_mtime (required number)}`; full replace, validated verbatim) ->
  cast payload plus `{affected: {quotes}, saved_comments}`.
  `409 {rule: "conflict", current_mtime, cast}` on a stale mtime.
- `PATCH /api/books/{book_id}/cast` (token required; `{ops: [...] (required
  non-empty), expected_mtime}`; all ops validate before any write):
  `set_voice {character, voice, engine?}`, `set_speed {character, speed > 0}`,
  `set_first_person {value}`, `add_override {chapter, block, quote, speaker}
  | {match (regex), speaker}`, `remove_override {index} | {chapter, block,
  quote} | {match}` -> cast payload plus `{affected, applied}`. First error
  wins (`404` only for `cast-not-found`, else `400`).
- `GET /api/books/{book_id}/cast/report` -> `200 {book_id, mtime,
  markdown (raw cast_report.md), html (escaped), low_confidence: [{chapter,
  block, quote, text, speaker, confidence}]}`. `404 report-not-found`.
- `GET /api/books/{book_id}/quotes?confidence=all|low&page=&per_page=`
  (defaults `all`, 1, 100; per_page 1-500) -> paged reading-order
  `200 {book_id, total, page, per_page, confidence, quotes: [{chapter, block,
  quote, text, speaker, confidence, rule, resolved}]}`.
  `404 draft-missing` without a script.

## 7. Voices, validate, misc

- `GET /api/cast/voices?engine=` (default `kokoro`; must be a supported
  engine) -> `200 {voices, source: "engine"|"palette", engine,
  engine_version (package metadata, model never loaded), sample_text,
  text_hash (12-hex), details: [{name, locale, gender}], note}`.
- `GET /api/voices/{name}.wav` (never starts a job; `audio/wav`) ->
  `FileResponse` (immutable-cached versioned samples; `409
  {rule: "sample-missing", ...}` with the seeding command when absent).
- `POST /api/voices/sample` and `POST /api/voices/{name}/sample` (token
  required; `{voices?, regenerate? default false, engine?}`) -> `200
  {cached: true, ...}` when every clip is cached, else `202 {job_id, ...,
  coalesced}` (`coalesced: true` reuses an active superset job).
  `400 unknown-voice | bad-request`.
- `POST /api/books/{book_id}/validate` (token required; inline, read-only)
  -> `200 {book_id, valid, validation, bundles}` with `scribe validate`
  wording. `404 book-not-found`.
- `GET /api/health` -> `200 {ok: true}` (token rule follows app mode).
- `GET /api/version` -> `200` the ready-line versions object
  `{scribe, spec, api}` (token rule follows app mode; added Slice 16 for the
  client pin).
- `GET /api/drives` -> `200 {drives: [{path, removable}]}` (removable only;
  `[]` off Windows or on any failure, never an error).
- `POST /api/books/{book_id}/transfer` (token required; `{destination
  (required absolute path), force? default false, bundle?}`; copies to
  `<destination>/<bundle-dirname>` with per-file size+hash verify) ->
  `202 {job_id, book_id, state, bundle, destination}`. `409
  no-valid-bundle | exists` (without force); `500 plan-failed`.
- `POST /api/books/{book_id}/open-folder` (token required; optional
  `{destination?}`, else the last transfer record; Windows only) ->
  `200 {opened, book_id}`. `400 open-unsupported` off Windows.

## 8. Version policy

- The contract version is the `api` integer (today `1`). Additive changes
  (new optional fields, new routes) keep the number; any breaking change
  (removed/renamed field or route, changed status code or auth rule) bumps it
  and is logged in `docs/DECISIONS.md`.
- `docs/api.md` changes in the same commit as the code it documents. The
  bundle format is a separate contract (`spec/bundle.md`) and never changes
  silently with this one.
