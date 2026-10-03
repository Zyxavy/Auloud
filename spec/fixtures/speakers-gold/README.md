# speakers-gold: speaker-attribution gold sets (Slice 4 MV1)

Hand-labelled dialogue lines Scribe's attribution rules (MV2-MV4) are scored
against. One YAML file per book chapter; one entry per dialogue line
("quoted speech"):

```yaml
- book: crime-and-punishment  # optional string: source-book id (alias: `source`);
  chapter: 1        # required int: chapter index (1-based, draft numbering)
  block: 8          # required int: block id within the chapter (draft numbering)
  quote: 1          # required int: quote number within the block (1-based, open-quote order)
  excerpt: "..."    # required non-empty string: short identifying substring
  speaker: "..."    # required non-empty string: true speaker, CANONICAL name (best effort)
  surface: "he"     # optional non-empty string: surface form the text uses (MV4)
  uncertain: true   # optional bool, default false: label is a real guess
```

`book` (or its alias `source`) is optional and defaults to `""` so MV1-era
files without it still load; when both appear they must agree. New entries
should always set it: the anchor is `(book, chapter, block, quote)`.

## Anchor rule

Entries anchor on `(book, chapter, block, quote number)`, never on `sid`.
Sentence ids shift whenever splitting rules change; block ids and quote
order do not (Slice 4 plan, section 4, decision 2). The excerpt is
documentary (a prefix when the quote runs longer); the key is the anchor.

## Speaker labels

Use the character's true CANONICAL name when the novel makes it certain,
even if the chapter itself only says "the young man" / "he" (resolved via
speech tags and context). Predictors must output these canonical names
exactly; alias resolution arrives in MV5 (`cast.yaml`), and until then the
harness matches exactly (strip + casefold) with no alias matching. Mark
anything genuinely ambiguous `uncertain: true` rather than guessing silently.

## Scoring modes (MV4)

Strict mode (default) matches the canonical `speaker` only. Alias-aware
mode (`--alias-aware`, or `evaluate(..., alias_aware=True)`) additionally
accepts the entry's `surface` form: the surface text the book actually uses
for that line (`"he"`, `"the young man"`). Record `surface` when the text
does not name the character canonically, so MV4 surface-emitting predictors
are not scored wrong; a genuinely different canonical name is still wrong
in both modes. MV5 resolution maps surfaces to canonical names, after which
strict mode scores everything again.

## MV2 dialogue notes

MV2's detector (`scribe/text/dialogue.py`) numbers dialogue quotes per
block in open-quote order, matching this anchor. Multi-sentence quotes stay
one entry (block 11 quote 2 spans many sids but is a single line here), and
scare-quote emphasis is not dialogue at all (see below).

## Current content (MV1)

`crime-and-punishment-ch01.yaml`: 5 dialogue lines from the local
Part I Chapter I fragment (all certain, 0 uncertain).

Deliberately excluded: block 12 quotes 1-2 ("hideous", "rehearsal") are
scare quotes on narration, not spoken dialogue.

The fragment yields only these 5 lines (7 quote pairs total, 2 of them
scare quotes), far below the plan's 100-150. A proper gold set needs the
full-novel EPUB (Project Gutenberg). MV2+ rule work still uses this
directory and the `scribe/dev/eval_speakers.py` harness unchanged.
