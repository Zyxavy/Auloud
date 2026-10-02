# Auloud Scribe — turns ebooks into multi-voice audiobooks (PC tool).
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

"""Rule-based speaker attribution with confidence (Slice 4 MV4).

Wraps the MV3 candidate APIs (:func:`text.speakers.extract_candidates`,
gender/alias helpers) and applies the plan's priority rules in exact order,
each with a confidence. Entry point is :func:`attribute_quotes`, which walks
one chapter's :class:`text.speakers.QuoteContext` entries in reading order
and returns an :class:`Attribution` per quote. Output surfaces are the tag
text as written (``"he"``, ``"the young man"``, ``"Alice"``); canonical
names arrive in MV5 via ``cast.yaml``. The eval harness scores these in
alias-aware mode against each gold entry's ``surface`` field.

Rules (plan order):

1. **explicit** (high): a non-pronoun candidate in a narration sentence
   adjacent to the quote, same block, either side. ``person-entity``
   candidates (listeners such as ``"Bob"`` in ``"whispered to Bob"``) never
   win; they stay emitted by MV3 for character discovery.
2. **pronoun** (medium): a he/she tag resolved to the most recent history
   speaker whose gender hint agrees (a known hint that disagrees rejects the
   match; an ``unknown`` hint accepts it).
3. **alternation** (medium): the quote sits in a dialogue-only block (same
   book and chapter as the previous quote), the last two attributed
   speakers are distinct and known, and the run is established: either the
   previous quote is also in a dialogue-only block, or it just completed
   the pair via an explicit/pronoun tag. The speaker is the other one. A
   quoteless narration paragraph between bare quotes does NOT break the
   run in v1 (only quote contexts are tracked), and an action beat with a
   bare quote (``He left. "Hmm."``) falls through to fallback instead.
4. **continuation**: the MV2 ``continued`` flag is set, so the speaker (and
   confidence) carry over from the immediately previous quote. Chains link
   via adjacency (previous entry in reading order, same book and chapter);
   one MV2 ``QuoteInfo`` per block makes each link its own entry.
5. **fallback** (low): the previous dialogue speaker. With no history the
   speaker is ``"unknown"`` (rule ``"unknown"``, low).

Positional choice (MV3 review): Candidate carries no token offsets, and
adjacency needs quote-relative positions, so this module does a focused
block re-parse per block with :func:`text.dialogue.split_paragraph_dialogue`
(cached, one parse per block no matter how many quotes it holds) and reads
adjacency off sentence order. Extending Candidate with spans would still
need the quote spans, which live in dialogue's private regions, so the
re-parse reuses tested logic instead of forking it. Because only the
adjacent *narration* sentences are parsed for tags, speech-verb subjects
*inside* the quote (``'"He said he would come," she whispered'`` yields an
inner ``He`` in MV3) can never compete: the tag always wins by position.

Tie-breaks (documented, deterministic): the narration sentence after the
quote is checked before the one before it (``"B," Y said`` beats a
previous quote's trailing tag); conjoined subjects (``"Alice and Robert
said"``) take the first surface; first-person tags (``"I said"``) are
pronouns, match no he/she rule, and fall through to MV5 ``first_person``
handling. A preceding narration tag can belong to a neighbouring quote in
crowded multi-quote blocks; the following-first order only mitigates this,
so crowded blocks stay review candidates via confidence.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from text.dialogue import (
    DIALOGUE,
    NARRATION,
    ParagraphDialogue,
    split_paragraph_dialogue,
)
from text.speakers import (
    GENDER_UNKNOWN,
    SOURCE_PERSON_ENTITY,
    Candidate,
    QuoteContext,
    extract_candidates,
    is_pronoun,
    normalize_name,
    pronoun_gender,
)

#: Attribution rules version for MV6 draft staleness (stored in script.json).
#: Bump this integer ONLY when MV2-MV4 logic changes in a way that alters
#: attribution results: dialogue splitting (text/dialogue.py), candidate
#: extraction (text/speakers.py: SPEECH_VERBS, TITLE_RE, gender lists,
#: alias rules), or the priority rules/tie-breaks below. Do NOT bump for
#: comment/doc-only edits, for MV5 cast.py changes (resolution lives in
#: build, not draft), or for report formatting. A bump re-drafts every book
#: once via build's ensure_script (source hash OR version mismatch).
ATTRIBUTION_RULES_VERSION = 1

#: Confidence levels the rules produce (match the eval harness display order).
CONFIDENCE_HIGH = "high"
CONFIDENCE_MEDIUM = "medium"
CONFIDENCE_LOW = "low"

#: Rule labels, in plan priority order (``unknown`` is the no-history fallback).
RULE_EXPLICIT = "explicit"
RULE_PRONOUN = "pronoun"
RULE_ALTERNATION = "alternation"
RULE_CONTINUATION = "continuation"
RULE_FALLBACK = "fallback"
RULE_UNKNOWN = "unknown"

RULES_IN_ORDER: tuple[str, ...] = (
    RULE_EXPLICIT,
    RULE_PRONOUN,
    RULE_ALTERNATION,
    RULE_CONTINUATION,
    RULE_FALLBACK,
    RULE_UNKNOWN,
)

#: Speaker used when no history exists to fall back to.
UNKNOWN_SPEAKER = "unknown"


@dataclass(frozen=True)
class Attribution:
    """One attributed quote: surface speaker, confidence, and the rule used.

    Anchored on ``(book, chapter, block, quote)``, never on ``sid``.
    ``gender`` is the MV3 hint for the winning surface (male/female/unknown;
    pronoun-rule hits carry the pronoun gender); MV6 draft uses it for the
    cast palette vote, and MV7 build may reuse it for generic fallback.
    Defaults to unknown so older constructions keep working.
    """

    book: str
    chapter: int
    block: int
    quote: int
    speaker: str
    confidence: str
    rule: str
    gender: str = "unknown"

    @property
    def key(self) -> tuple[str, int, int, int]:
        """Stable anchor matching the gold entries."""
        return (self.book, self.chapter, self.block, self.quote)


@dataclass
class _HistoryEntry:
    """One past attribution: surface speaker, gender hint, confidence, rule."""

    speaker: str
    gender: str
    confidence: str
    rule: str


def _surrounding(context: QuoteContext) -> str:
    """Pronoun-proximity text for gender hints (mirrors ``candidates_for``)."""
    return " ".join(
        part for part in (context.prev_text, context.block_text, context.next_text) if part.strip()
    )


def _is_dialogue_only(parsed: ParagraphDialogue) -> bool:
    """True when a block's re-parse holds only dialogue sentences."""
    return bool(parsed.sentences) and all(sent.kind == DIALOGUE for sent in parsed.sentences)


def _quote_span(parsed: ParagraphDialogue, quote_no: int) -> tuple[int, int] | None:
    """Sentence index span ``(first, last)`` of one quote, or None."""
    indices = [
        pos
        for pos, sent in enumerate(parsed.sentences)
        if sent.kind == DIALOGUE and sent.quote == quote_no
    ]
    if not indices:
        return None
    return (indices[0], indices[-1])


def _tag_candidates(
    context: QuoteContext,
    parsed: ParagraphDialogue,
    span: tuple[int, int],
    *,
    nlp: Any | None,
) -> tuple[list[Candidate], list[Candidate]]:
    """Candidates from the narration sentences around a quote (after, before).

    Only immediately adjacent narration sentences in the same block count:
    index ``last + 1`` when it is narration (the following tag), then index
    ``first - 1`` when it is narration (the preceding tag). Anything else
    (dialogue sentences, block edges, other blocks) is out of scope.
    """
    first, last = span
    gender_ctx = _surrounding(context) or None
    following: list[Candidate] = []
    preceding: list[Candidate] = []
    if last + 1 < len(parsed.sentences) and parsed.sentences[last + 1].kind == NARRATION:
        following = extract_candidates(
            parsed.sentences[last + 1].text, nlp=nlp, gender_context=gender_ctx
        )
    if first - 1 >= 0 and parsed.sentences[first - 1].kind == NARRATION:
        preceding = extract_candidates(
            parsed.sentences[first - 1].text, nlp=nlp, gender_context=gender_ctx
        )
    return (following, preceding)


def _explicit_tag(following: list[Candidate], preceding: list[Candidate]) -> Candidate | None:
    """First naming candidate: non-pronoun, never a ``person-entity``.

    ``person-entity`` mentions are listeners or bystanders (``"Bob"`` in
    ``"whispered to Bob"``), not speaker claims, so they can never win a
    tag; MV3 keeps emitting them for character discovery.
    """
    for candidates in (following, preceding):
        for cand in candidates:
            if is_pronoun(cand.surface):
                continue
            if cand.source == SOURCE_PERSON_ENTITY:
                continue
            return cand
    return None


def _pronoun_tag(
    following: list[Candidate], preceding: list[Candidate]
) -> tuple[Candidate, str] | None:
    """First he/she pronoun candidate plus its gender, or None."""
    for candidates in (following, preceding):
        for cand in candidates:
            gender = pronoun_gender(cand.surface)
            if gender == GENDER_UNKNOWN:
                continue
            return (cand, gender)
    return None


def _resolve_pronoun(gender: str, history: list[_HistoryEntry]) -> _HistoryEntry | None:
    """Most recent history speaker whose gender hint agrees with ``gender``.

    A known hint that disagrees rejects the match; an ``unknown`` hint
    accepts it (agreement is vacuous when nothing is known).
    """
    for entry in reversed(history):
        if entry.gender != GENDER_UNKNOWN and entry.gender != gender:
            continue
        return entry
    return None


def attribute_quotes(
    contexts: Sequence[QuoteContext], *, nlp: Any | None = None
) -> list[Attribution]:
    """Attribute every quote in reading order (plan rules 1-5 + unknown).

    ``contexts`` must arrive in reading order (as
    :func:`text.speakers.build_quote_contexts` and the gold loader both
    yield); history (previous speakers, genders, dialogue-only runs,
    continuation chains) builds left to right. Each block's paragraph is
    re-parsed once (cached by ``(book, chapter, block)``) with the MV2
    splitter; sentence-splitting logic is reused, never forked.
    """
    attributions: list[Attribution] = []
    history: list[_HistoryEntry] = []
    parsed_by_block: dict[tuple[str, int, int], ParagraphDialogue] = {}
    prev_context: QuoteContext | None = None
    prev_parsed: ParagraphDialogue | None = None

    for context in contexts:
        block_key = (context.book, context.chapter, context.block)
        parsed = parsed_by_block.get(block_key)
        if parsed is None:
            parsed = split_paragraph_dialogue(
                context.block_text, chapter=context.chapter, block=context.block
            )
            parsed_by_block[block_key] = parsed
        span = _quote_span(parsed, context.quote)
        following: list[Candidate] = []
        preceding: list[Candidate] = []
        if span is not None:
            following, preceding = _tag_candidates(context, parsed, span, nlp=nlp)

        speaker = UNKNOWN_SPEAKER
        confidence = CONFIDENCE_LOW
        rule = RULE_UNKNOWN
        gender = GENDER_UNKNOWN

        explicit = _explicit_tag(following, preceding)
        if explicit is not None:
            speaker, confidence, rule, gender = (
                explicit.surface,
                CONFIDENCE_HIGH,
                RULE_EXPLICIT,
                explicit.gender,
            )
        else:
            pronoun = _pronoun_tag(following, preceding)
            resolved = _resolve_pronoun(pronoun[1], history) if pronoun is not None else None
            if resolved is not None:
                speaker, confidence, rule, gender = (
                    resolved.speaker,
                    CONFIDENCE_MEDIUM,
                    RULE_PRONOUN,
                    pronoun[1] if pronoun is not None else GENDER_UNKNOWN,
                )
            elif (
                len(history) >= 2
                and _is_dialogue_only(parsed)
                and prev_context is not None
                and prev_parsed is not None
                and prev_context.book == context.book
                and prev_context.chapter == context.chapter
                and (
                    _is_dialogue_only(prev_parsed)
                    or history[-1].rule in (RULE_EXPLICIT, RULE_PRONOUN)
                )
                and history[-1].speaker != UNKNOWN_SPEAKER
                and history[-2].speaker != UNKNOWN_SPEAKER
                and normalize_name(history[-1].speaker) != normalize_name(history[-2].speaker)
            ):
                other = history[-2]
                speaker, confidence, rule, gender = (
                    other.speaker,
                    CONFIDENCE_MEDIUM,
                    RULE_ALTERNATION,
                    other.gender,
                )
            elif (
                context.continued
                and history
                and prev_context is not None
                and prev_context.book == context.book
                and prev_context.chapter == context.chapter
            ):
                previous = history[-1]
                speaker, confidence, rule, gender = (
                    previous.speaker,
                    previous.confidence,
                    RULE_CONTINUATION,
                    previous.gender,
                )
            elif history:
                previous = history[-1]
                speaker, confidence, rule, gender = (
                    previous.speaker,
                    CONFIDENCE_LOW,
                    RULE_FALLBACK,
                    previous.gender,
                )

        history.append(
            _HistoryEntry(speaker=speaker, gender=gender, confidence=confidence, rule=rule)
        )
        attributions.append(
            Attribution(
                book=context.book,
                chapter=context.chapter,
                block=context.block,
                quote=context.quote,
                speaker=speaker,
                confidence=confidence,
                rule=rule,
                gender=gender,
            )
        )
        prev_context = context
        prev_parsed = parsed

    return attributions
