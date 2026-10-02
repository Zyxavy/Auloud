# speakers-gold: speaker-attribution gold sets (Slice 4 MV1)

Hand-labelled dialogue lines Scribe's attribution rules (MV2-MV4) are scored
against. One YAML file per book chapter; one entry per dialogue line
("quoted speech"):

```yaml
- chapter: 1        # required int: chapter index (1-based, draft numbering)
  block: 8          # required int: block id within the chapter (draft numbering)
  quote: 1          # required int: quote number within the block (1-based, open-quote order)
  excerpt: "..."    # required non-empty string: short identifying substring
  speaker: "..."    # required non-empty string: true speaker (best effort)
  uncertain: true   # optional bool, default false: label is a real guess
```

## Anchor rule

Entries anchor on `(chapter, block, quote number)`, never on `sid`.
Sentence ids shift whenever splitting rules change; block ids and quote
order do not (Slice 4 plan, section 4, decision 2). The excerpt is
documentary (a prefix when the quote runs longer); the key is the anchor.

## Speaker labels

Use the character's true name when the novel makes it certain, even if the
chapter itself only says "the young man" / "he" (resolved via speech tags
and context). Mark anything genuinely ambiguous `uncertain: true` rather
than guessing silently.

## Current content (MV1)

`crime-and-punishment-ch01.yaml`: 5 dialogue lines from the local
Part I Chapter I fragment (all certain, 0 uncertain).

Deliberately excluded: block 12 quotes 1-2 ("hideous", "rehearsal") are
scare quotes on narration, not spoken dialogue.

The fragment yields only these 5 lines (7 quote pairs total, 2 of them
scare quotes), far below the plan's 100-150. A proper gold set needs the
full-novel EPUB (Project Gutenberg). MV2+ rule work still uses this
directory and the `scribe/dev/eval_speakers.py` harness unchanged.
