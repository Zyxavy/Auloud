# ingest-parity: Scribe-side parity expectations (Slice 9 IN2)

Parity data for the on-device EPUB ingestion port (IN4-IN6). Generated from
Scribe's real pipeline (extraction plus dialogue splitting, no TTS), so the
Kotlin port compares against true Scribe behavior instead of hand-written
guesses. Scribe's own suite pins these files fresh on every run
(`scribe/tests/test_export_ingest.py`); a code change without re-export
fails loudly.

## Files

- `<fixture>.json`: one per covered golden EPUB (chapters, blocks, runs).
- `sources.json`: every discovered fixture source plus its status
  (`covered`, `skipped`, `out-of-scope`) with sha256 and reason.
- `../dialogue-cases.json`: dialogue unit vectors (separate file, shared
  with IN6).

## Parity file schema

```json
{
  "source": {"fixture": "scribe-golden", "file": "source/book.epub", "sha256": "<hex>"},
  "chapters": [
    {"index": 1, "title": "Chapter One", "words": 242,
     "blocks": [
       {"id": 1, "type": "heading", "text": "Chapter One"},
       {"id": 2, "type": "para", "text": "<full paragraph>",
        "runs": [{"kind": "narration", "text": "<run>"}, ...]}
     ]}
  ]
}
```

- `words`: whitespace-separated tokens over the final heading/para/quote
  block texts (same rule as Scribe's tiny-merge counter).
- `blocks`: `id` restarts at 1 per chapter; `type` is one of
  `heading`, `para`, `quote`, `break`. `text` is the stored block text.
- `runs`: only on `para` and `quote` blocks. Adjacent same-kind sentences
  merged by plain concatenation (spacing is stored, so concatenation is
  exact); joining a block's run texts reproduces its `text` exactly.
  `kind` is `narration` or `dialogue`.
- Headings carry no `runs` (they are single narration sentences by
  construction); breaks carry neither text nor runs.

## Coverage (generated 2026-10-06, Slice 9 IN2)

| Fixture source | Status | Detail |
|---|---|---|
| `scribe-golden/source/book.epub` | covered | `scribe-golden.json` (2 chapters, narration only) |
| `partial-golden/source/book.epub` | covered | `partial-golden.json` |
| `multivoice-golden/source/book.epub` | covered | `multivoice-golden.json` |
| `unrendered-golden/source/book.epub` | skipped | placeholder bytes, not a parseable EPUB (IN1 fixture) |
| `pdf-golden/source/book.pdf` | out-of-scope | Slice 9 ingestion is EPUB-only; PDF stays on Scribe |

## Regenerating

Run from `scribe/` with `uv run --no-sync scribe export-ingest-fixtures`
(library logic in `scribe/export_ingest.py`, thin CLI wrapper in
`scribe/cli.py`). Output is deterministic: sorted keys, pipeline order,
content hashes, no timestamps. Running it twice gives byte-identical
files; verify with `git status` (clean after a re-run) or the
double-run test in `test_export_ingest.py`.

## Adding books (for later packages)

Drop a real EPUB at `spec/fixtures/<name>/source/book.epub` and re-run
the command: discovery is automatic (sorted `*/source/book.epub`), no code
change needed. The planned C&P and Alice goldens (Slice 9 IN10 acceptance)
arrive this way; only `multivoice-golden` exercises `dialogue` runs today,
so real book chapters will harden that coverage.
