# Auloud Scribe turns ebooks into multi-voice audiobooks (PC tool).
# Copyright (C) 2026 Zyxavy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.
#
# You should have received a copy of the GNU Affero General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Rebuild real MV4 contexts from a draft ``script.json`` (dev-only, Slice 5 CP1).

Problem: ``script.json`` sentences carry per-quote attribution but the MV2
:class:`text.dialogue.ChapterDialogue` object (needed to rebuild full
``QuoteContext`` paragraph texts) is not persisted. The eval harness
``--predictor mv4`` scores over :meth:`QuoteContext.from_gold` (EMPTY
texts), so it validates the wiring format, never real accuracy.

This module rebuilds full contexts from a draft ``script.json`` so dev
tooling (the ``mv4-real`` eval mode, the gold pre-label sampler) can run
:func:`text.attribution.attribute_quotes` over real paragraph text.

Round-trip rules (exact unless noted):

- ``block_text`` is the concatenation of that block's sentence ``text``
  fields in file order. The MV2 splitter partitions each paragraph
  byte-exactly (whitespace-only gaps attach to the previous sentence, or
  prefix the next when leading), so the join reproduces the original
  paragraph EXACTLY -- the same join :func:`text.speakers.build_quote_contexts`
  performs over ``TaggedSentence.text`` (draft persists those strings
  unchanged in ``script.json``).
- ``excerpt``/``full_quote`` are the concatenation of the sentences sharing
  the same ``(chapter, block, quote)`` key, in file order. For a
  multi-sentence quote this equals the MV2 ``QuoteInfo.text`` region;
  single-sentence quotes match exactly. Small caveat: inter-quote
  whitespace attaches to the previous sentence in MV2, so a quote followed
  by a gap may carry one trailing space more than ``QuoteInfo.text``.
  Documentary only: MV4 never reads ``excerpt``/``full_quote``.
- ``prev_text``/``next_text`` are the neighbouring block paragraphs in
  chapter order (same concatenation), mirroring
  :func:`text.speakers.build_quote_contexts` (all block types count for
  ordering; blocks with no sentences contribute ``""``).
- ``continued`` is best-effort (see below).

MV4 reads exactly these fields: ``block_text`` (block re-parse plus tag
candidates), ``prev_text``/``next_text`` (pronoun-proximity gender
context), ``continued`` (rule 4), and the ``(book, chapter, block,
quote)`` anchor. ``excerpt``/``full_quote`` are filled for
documentation and gold-excerpt comparison; no rule reads them.

``continued`` reconstruction and its limits: ``script.json`` persists no
MV2 ``continued`` flag, and ``split_pair`` ids are paragraph-local by
construction (they link the two halves of one quote-split sentence and
never cross a block boundary), so ``split_pair`` carries NO cross-block
signal and is only inspected to confirm it cannot help. The chain
evidence used instead: a quote is marked continued when it is its block's
first quote AND the previous block in chapter order ends with a dialogue
sentence AND this block starts with a dialogue sentence (the
multi-paragraph pattern: a paragraph ending mid-quote, the next starting
with another opener consumed as the continuation marker). Known limits:
adjacent dialogue-only blocks in an alternation run (``"Yes."`` / ``"No."``)
also match and over-mark; a continued chain whose previous block ends on a
narration tag is missed; quoteless continuation paragraphs hold no quote
to mark. Hand-check every ``continued`` pre-label in the gold pass.

Dev-only: the library (``text/*``, ``draft.py``, ``build.py``) never
imports this module (the wheel ships no ``dev/``). Keys are always
``(book, chapter, block, quote)``; ``sid`` is never read.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Dev script: make the scribe/ tree importable so ``text.*`` resolves when
# run as ``uv run python dev/...`` (mirrors the eval_speakers.py bootstrap).
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from text.speakers import QuoteContext  # noqa: E402


def _sentences_of(block: object) -> list[dict]:
    """Sentence dicts of one script chapter block (never raises on shape)."""
    if not isinstance(block, dict):
        return []
    sentences = block.get("sentences")
    if not isinstance(sentences, list):
        return []
    return [s for s in sentences if isinstance(s, dict)]


def _quote_key(sentence: dict, *, chapter: int, block_id: int) -> tuple[int, int, int] | None:
    """``(chapter, block, quote)`` for a dialogue sentence, else None.

    The ``block`` leg comes from the enclosing block (never from ``sid``);
    the chapter leg prefers the sentence's own ``quote.chapter`` stamp and
    falls back to the enclosing chapter file's number.
    """
    if sentence.get("kind") != "dialogue":
        return None
    quote = sentence.get("quote")
    if not isinstance(quote, dict):
        return None
    number = quote.get("quote")
    if not isinstance(number, int) or isinstance(number, bool):
        return None
    raw_chapter = quote.get("chapter")
    if isinstance(raw_chapter, int) and not isinstance(raw_chapter, bool):
        chapter = raw_chapter
    raw_block = quote.get("block")
    if isinstance(raw_block, int) and not isinstance(raw_block, bool):
        block_id = raw_block
    return (chapter, block_id, number)


def contexts_from_script(script_path: str | Path, book: str = "") -> list[QuoteContext]:
    """Rebuild :class:`QuoteContext` entries from a draft ``script.json``.

    :param script_path: draft ``script.json`` (MV6 ``include_draft=True``
        sentences: ``kind``/``confidence``/``quote`` per sentence).
    :param book: book label stamped on every context (gold anchoring is
        ``(book, chapter, block, quote)``; pass the gold ``book`` id so
        real contexts join the gold entries).
    :returns: one context per ``(chapter, block, quote)`` dialogue key, in
        reading order (chapter file order, then block order, then quote
        number). Chapters without blocks (PDF page-sync form) contribute
        nothing.
    :raises ValueError: the file is missing, unparsable, or holds no
        ``chapters`` list.
    """
    path = Path(script_path)
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise ValueError(f"{path}: cannot read script ({exc})") from exc
    except json.JSONDecodeError as exc:
        raise ValueError(f"{path}: invalid JSON ({exc})") from exc
    if not isinstance(raw, dict) or not isinstance(raw.get("chapters"), list):
        raise ValueError(f"{path}: not a draft script.json (missing 'chapters' list)")

    contexts: list[QuoteContext] = []
    for chapter_file in raw["chapters"]:
        if not isinstance(chapter_file, dict):
            continue
        file_number = chapter_file.get("chapter")
        if not isinstance(file_number, int) or isinstance(file_number, bool):
            continue
        blocks = chapter_file.get("blocks")
        if not isinstance(blocks, list):
            continue  # pages-form chapter: no paragraphs to rebuild
        paras: dict[int, str] = {}
        order: list[int] = []
        quote_texts: dict[tuple[int, int, int], list[str]] = {}
        starts_dialogue: dict[int, bool] = {}
        ends_dialogue: dict[int, bool] = {}
        first_quote: dict[int, int] = {}
        for block in blocks:
            if not isinstance(block, dict):
                continue
            block_id = block.get("id")
            if not isinstance(block_id, int) or isinstance(block_id, bool):
                continue
            sentences = _sentences_of(block)
            order.append(block_id)
            paras[block_id] = "".join(s.get("text", "") for s in sentences)
            if sentences:
                starts_dialogue[block_id] = sentences[0].get("kind") == "dialogue"
                ends_dialogue[block_id] = sentences[-1].get("kind") == "dialogue"
            for sentence in sentences:
                key = _quote_key(sentence, chapter=file_number, block_id=block_id)
                if key is None:
                    continue
                quote_texts.setdefault(key, []).append(sentence.get("text", ""))
                if block_id not in first_quote or key[2] < first_quote[block_id]:
                    first_quote[block_id] = key[2]
        for key in sorted(quote_texts):
            _, block_id, number = key
            pos = order.index(block_id)
            prev_text = paras.get(order[pos - 1], "") if pos > 0 else ""
            next_text = paras.get(order[pos + 1], "") if pos < len(order) - 1 else ""
            full = "".join(quote_texts[key])
            continued = (
                first_quote.get(block_id) == number
                and pos > 0
                and ends_dialogue.get(order[pos - 1], False)
                and starts_dialogue.get(block_id, False)
            )
            contexts.append(
                QuoteContext(
                    chapter=key[0],
                    block=block_id,
                    quote=number,
                    excerpt=full,
                    book=book,
                    block_text=paras.get(block_id, ""),
                    prev_text=prev_text,
                    next_text=next_text,
                    full_quote=full,
                    continued=continued,
                )
            )
    return contexts
