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

"""Speaker candidate extraction (Slice 4 MV3).

Output candidates only: who *might* be speaking a quote, never who is.
MV4 owns the priority rules and high/medium/low confidence; MV5 resolves
aliases to canonical names and maps them through ``cast.yaml``. Nothing here
assigns confidence, picks a winner, or anchors on ``sid`` (keys are always
``(book, chapter, block, quote)`` per D-033).

Pipeline (spaCy ``en_core_web_sm`` via :mod:`text.nlp`, never hand-rolled):

1. Parse the paragraph. For every verb whose lemma is in
   :data:`SPEECH_VERBS`, take its ``nsubj``/``nsubjpass`` subject(s),
   expanded through ``conj`` (``"Mr. Darcy and Miss Bennet said"`` yields
   two), and use each subject's noun chunk as the candidate surface.
2. Named characters come from ``PERSON`` entities plus the
   :data:`TITLE_RE` titled-name pattern (``Mr.``/``Mrs.``/``Miss``/``Ms.``/
   ``Dr.``). NER alone is not trusted: the small model drops titles
   (``"Darcy"`` for ``"Mr. Darcy"``), misses some titled names entirely
   (``"Miss Bennet"``), and flips common-noun speakers between ``PERSON``
   and ``ORG``/nothing depending on context (``"the Cat"``), so verb
   subjects are always collected regardless of entities.
3. :func:`normalize_name` strips determiners/titles/case so ``"the Queen"``
   and ``"Queen"``, ``"Mr. Darcy"`` and ``"Darcy"`` share a key.
4. :func:`are_aliases`/:func:`cluster_aliases` group variants by simple
   deterministic rules (case-insensitive, determiner-stripped, title+name,
   last-name). Surfaces stay exactly as the text supports them; canonical
   resolution happens at MV5.
5. :func:`gender_hint_for` emits a hint (``male``/``female``/``unknown``)
   from pronoun surfaces, a small explicit first-name list (obviously
   incomplete, documented below), and pronouns near the mention. Never a
   hard gendered decision.

MV4 plug-in shape::

    from text.speakers import build_quote_contexts, candidates_for

    for ctx in build_quote_contexts(chapter_dialogue, book="crime-and-punishment"):
        cands = candidates_for(ctx)  # list[Candidate], no confidence attached
        ...  # MV4 priority rules pick one and grade it high/medium/low

Known limitations (accepted for MV3, MV4 disambiguates):

- The small model sometimes labels a quoted interjection as ``nsubj``
  (``'"Hm," said the young man'`` tags both ``Hm`` and ``man``). When any
  subject follows the verb, pre-verbal subjects are dropped, which fixes
  the observed cases; a bare ``INTJ`` subject is dropped regardless.
- Speech verbs *inside* the quoted speech itself (``'"He said he would
  come," she whispered'``) also yield subjects; MV4 adjacency rules
  prefer the tag. ``block_text`` is parsed whole on purpose (plan: spaCy
  per paragraph).
- ``thought`` is a speech verb here (gold block 11 quote 2 is an interior
  monologue attributed to Raskolnikov); the thought-vs-speech decision is
  out of scope and stays attributed, never filtered.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # annotations only; never imported at runtime (wheel has no dev/)
    from dev.eval_speakers import QuoteContext
    from text.dialogue import ChapterDialogue

#: Speech-verb lemmas (matched against ``token.lemma_``). Covers the plan's
#: required set (say, ask, reply, cry, whisper, shout, mutter, think,
#: answer, exclaim, call, add, continue, begin) plus obvious others. Explicit
#: and incomplete by design: rare verbs (ejaculated, vociferated, ...) fall
#: back to MV4 continuation/fallback rules instead of growing this list
#: unboundedly.
SPEECH_VERBS: frozenset[str] = frozenset(
    {
        "say",
        "ask",
        "reply",
        "cry",
        "whisper",
        "shout",
        "mutter",
        "think",
        "answer",
        "exclaim",
        "call",
        "add",
        "continue",
        "begin",
        "respond",
        "remark",
        "murmur",
        "mumble",
        "yell",
        "scream",
        "declare",
        "state",
        "observe",
        "note",
        "comment",
        "repeat",
        "retort",
        "announce",
        "explain",
        "interject",
        "growl",
        "hiss",
    }
)

#: Candidate provenance. ``tag-subject`` is any speech-verb subject that is a
#: pronoun or a bare proper name; ``descriptive`` is a verb subject with a
#: determiner or a common-noun phrase (``"the Hatter"``, ``"the young man"``);
#: ``titled`` matches :data:`TITLE_RE`; ``person-entity`` is a leftover
#: ``PERSON`` mention that is not any verb's subject.
SOURCE_TAG_SUBJECT = "tag-subject"
SOURCE_PERSON_ENTITY = "person-entity"
SOURCE_TITLED = "titled"
SOURCE_DESCRIPTIVE = "descriptive"

SOURCES: tuple[str, ...] = (
    SOURCE_TAG_SUBJECT,
    SOURCE_PERSON_ENTITY,
    SOURCE_TITLED,
    SOURCE_DESCRIPTIVE,
)

#: Gender hints only (MV5 generics need the hint, never a decision).
GENDER_MALE = "male"
GENDER_FEMALE = "female"
GENDER_UNKNOWN = "unknown"

_MALE_PRONOUNS = frozenset({"he", "him", "his"})
_FEMALE_PRONOUNS = frozenset({"she", "her", "hers"})

#: Small built-in first-name lists for gender hints. Deliberately tiny and
#: obviously incomplete (common English names only; surnames like Darcy or
#: Raskolnikov are NOT here and resolve via nearby pronouns instead).
#: Keep small and explicit; do not grow without a test.
MALE_NAMES: frozenset[str] = frozenset(
    {
        "john",
        "james",
        "robert",
        "michael",
        "william",
        "david",
        "george",
        "charles",
        "henry",
        "thomas",
        "arthur",
        "frederick",
    }
)

FEMALE_NAMES: frozenset[str] = frozenset(
    {
        "mary",
        "elizabeth",
        "alice",
        "anna",
        "jane",
        "emma",
        "sarah",
        "margaret",
        "catherine",
        "charlotte",
        "amelia",
        "lydia",
    }
)

_TITLE_WORDS = ("Miss", "Mrs", "Mr", "Ms", "Dr")

#: Titled names: Mr./Mrs./Miss/Ms./Dr. plus one or two capitalized words.
#: NER drops the title (``"Darcy"``) or misses the name (``"Miss Bennet"``),
#: so this pattern recovers the full surface.
TITLE_RE: re.Pattern[str] = re.compile(
    r"\b(?:Miss|Mrs|Mr|Ms|Dr)\.?\s+[A-Z][\w'-]*(?:\s+[A-Z][\w'-]*)?"
)

_DET_PREFIX_RE = re.compile(r"^(?:the|a|an)\s+", re.IGNORECASE)
_TITLE_PREFIX_RE = re.compile(r"^(?:mr|mrs|miss|ms|dr)\.?\s+", re.IGNORECASE)
_WS_RE = re.compile(r"\s+")
_WORD_RE = re.compile(r"[a-z]+")


@dataclass(frozen=True)
class Candidate:
    """One speaker candidate: surface text plus MV4-ready metadata.

    ``surface`` is exactly as the paragraph supports it (``"Mr. Darcy"``,
    not ``"Darcy"``); ``normalized`` is the :func:`normalize_name` key MV4
    groups by. ``source`` is one of :data:`SOURCES`; ``gender`` is one of
    ``male``/``female``/``unknown``. Deliberately no confidence field:
    MV4 owns high/medium/low.
    """

    surface: str
    normalized: str
    source: str
    gender: str


def normalize_name(name: str) -> str:
    """Normalize a speaker string so variants share one key.

    Strips surrounding quotes/whitespace, collapses inner whitespace,
    casefolds, drops a leading determiner (``the``/``a``/``an``) and a
    leading title (``Mr.``/``Mrs.``/``Miss``/``Ms.``/``Dr.``), and trims a
    trailing possessive (``'s``). Hence ``"the Queen"``/``"Queen"`` and
    ``"Mr. Darcy"``/``"Darcy"`` each match.
    """
    text = name.strip().strip("\"'“”‘’")
    text = _WS_RE.sub(" ", text).strip(" \t.,;:!?()[]")
    low = text.casefold()
    low = _DET_PREFIX_RE.sub("", low)
    low = _TITLE_PREFIX_RE.sub("", low)
    low = re.sub(r"['\u2019]s$", "", low)
    return _WS_RE.sub(" ", low).strip()


def are_aliases(first: str, second: str) -> bool:
    """True when two speaker strings name the same character (rules below).

    Deterministic pairwise rules, each unit-tested: case-insensitive
    equality, determiner-stripped equality, title+name equality (all via
    :func:`normalize_name`), and last-name match (multi-word name whose
    final token equals a single-word name, e.g. ``"Elizabeth Bennet"`` /
    ``"Bennet"``). Empty strings never alias.
    """
    first_norm = normalize_name(first)
    second_norm = normalize_name(second)
    if not first_norm or not second_norm:
        return False
    if first_norm == second_norm:
        return True
    first_parts = first_norm.split(" ")
    second_parts = second_norm.split(" ")
    if len(first_parts) >= 2 and len(second_parts) == 1:
        return first_parts[-1] == second_parts[0]
    if len(second_parts) >= 2 and len(first_parts) == 1:
        return second_parts[-1] == first_parts[0]
    return False


def cluster_aliases(names: list[str]) -> dict[str, list[str]]:
    """Group speaker strings into alias clusters (deterministic).

    Union-find over :func:`are_aliases`, processing pairs in sorted order
    so output is stable. Returns ``{normalized-key: sorted(surfaces)}``
    where the key is the smallest normalized member. Duplicate inputs are
    merged silently.
    """
    unique = sorted(set(names))
    parent: dict[str, str] = {name: name for name in unique}

    def find(item: str) -> str:
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    for pos, first in enumerate(unique):
        for second in unique[pos + 1 :]:
            if are_aliases(first, second):
                root_first, root_second = find(first), find(second)
                if root_first != root_second:
                    winner = min(root_first, root_second)
                    parent[root_first] = winner
                    parent[root_second] = winner
    groups: dict[str, list[str]] = {}
    for name in unique:
        groups.setdefault(find(name), []).append(name)
    return {min(normalize_name(n) for n in members): members for members in groups.values()}


def gender_hint_for(surface: str, context: str = "") -> str:
    """Gender hint for a candidate surface (never a hard decision).

    Order: pronoun surfaces first (``he``/``she``/...), then the small
    :data:`MALE_NAMES`/:data:`FEMALE_NAMES` first-name lists (titles
    skipped, so ``"Miss Bennet"`` checks ``bennet``), then pronouns near
    the mention: when ``context`` (the paragraph, or prev+block+next)
    holds only male or only female third-person pronouns, that side wins.
    Mixed or pronoun-free contexts give ``unknown``.
    """
    norm = normalize_name(surface)
    if norm in _MALE_PRONOUNS:
        return GENDER_MALE
    if norm in _FEMALE_PRONOUNS:
        return GENDER_FEMALE
    for token in norm.split(" "):
        if token in ("mr", "mrs", "miss", "ms", "dr"):
            continue
        if token in FEMALE_NAMES:
            return GENDER_FEMALE
        if token in MALE_NAMES:
            return GENDER_MALE
    words = _WORD_RE.findall(context.casefold())
    male = any(word in _MALE_PRONOUNS for word in words)
    female = any(word in _FEMALE_PRONOUNS for word in words)
    if male and not female:
        return GENDER_MALE
    if female and not male:
        return GENDER_FEMALE
    return GENDER_UNKNOWN


def _clean_surface(text: str) -> str:
    """Trim a noun-chunk surface without harming titles (periods kept)."""
    return _WS_RE.sub(" ", text.strip().strip("\"'“”‘’")).strip()


_SUBJECT_DEPS = frozenset({"nsubj", "nsubjpass"})


def _verb_subjects(verb: Any) -> list[Any]:
    """``nsubj`` children of ``verb``, post-verbal preferred, conj expanded.

    The small model sometimes parses a quoted interjection as the subject
    (``'"Hm," said the young man'`` yields both ``Hm`` and ``man``); the
    true tag subject follows the verb in those misparses, so pre-verbal
    subjects are dropped whenever any subject follows the verb. Bare
    ``INTJ`` subjects are dropped regardless. ``conj`` chains (``"Bob and
    Alice said"``) yield every conjunct in token order.
    """
    subjects = [child for child in verb.children if child.dep_ in _SUBJECT_DEPS]
    post_verbal = [tok for tok in subjects if tok.i > verb.i]
    if post_verbal:
        subjects = post_verbal
    subjects = [tok for tok in subjects if tok.pos_ != "INTJ"]
    ordered: list[Any] = []
    seen: set[int] = set()
    queue = list(subjects)
    while queue:
        token = queue.pop(0)
        if token.i in seen:
            continue
        seen.add(token.i)
        ordered.append(token)
        for child in token.children:
            if child.dep_ == "conj" and child.i not in seen:
                queue.append(child)
    return sorted(ordered, key=lambda tok: tok.i)


def _classify_subject(surface: str, token: Any, doc: Any) -> str:
    """Source label for a speech-verb subject chunk."""
    stripped = surface.strip()
    if TITLE_RE.match(stripped):
        return SOURCE_TITLED
    if token.pos_ == "PRON":
        return SOURCE_TAG_SUBJECT
    if _DET_PREFIX_RE.match(stripped):
        return SOURCE_DESCRIPTIVE
    for ent in doc.ents:
        if ent.label_ == "PERSON" and ent.start <= token.i < ent.end:
            return SOURCE_TAG_SUBJECT
    if token.pos_ == "PROPN":
        return SOURCE_TAG_SUBJECT
    return SOURCE_DESCRIPTIVE


def extract_candidates(
    text: str, *, nlp: Any | None = None, gender_context: str | None = None
) -> list[Candidate]:
    """Extract speaker candidates from one paragraph of tag text.

    Parses ``text`` with the blessed cached model (:func:`text.nlp.load_model`
    unless ``nlp`` is given), collects speech-verb subjects (noun-chunk
    surfaces), then titled names, then leftover ``PERSON`` mentions, each
    with its normalized key, source, and gender hint. Dedupes by normalized
    key keeping first occurrence (subjects, then titled, then entities), in
    token order. Paragraphs without speech verbs, names, or titles yield
    ``[]``. ``gender_context`` (default: ``text`` itself) feeds pronoun
    proximity in :func:`gender_hint_for`.
    """
    if not text or not text.strip():
        return []
    if nlp is None:  # blessed cached accessor; never hand-roll loading
        from text.nlp import load_model

        nlp = load_model()
    doc = nlp(text)
    gender_ctx = text if gender_context is None else gender_context
    chunks = list(doc.noun_chunks)
    chunk_by_root: dict[int, str] = {}
    for chunk in chunks:
        chunk_by_root.setdefault(chunk.root.i, chunk.text)

    def chunk_surface(token: Any) -> str:
        if token.i in chunk_by_root:
            return chunk_by_root[token.i]
        for chunk in chunks:
            if chunk.start <= token.i < chunk.end:
                return chunk.text
        return token.text

    candidates: list[Candidate] = []
    seen: set[str] = set()

    def add(surface: str, source: str) -> None:
        cleaned = _clean_surface(surface)
        if not cleaned:
            return
        key = normalize_name(cleaned)
        if not key or key in seen:
            return
        seen.add(key)
        candidates.append(
            Candidate(
                surface=cleaned,
                normalized=key,
                source=source,
                gender=gender_hint_for(cleaned, gender_ctx),
            )
        )

    for token in doc:
        if token.pos_ != "VERB" or token.lemma_.lower() not in SPEECH_VERBS:
            continue
        for subject in _verb_subjects(token):
            add(chunk_surface(subject), _classify_subject(chunk_surface(subject), subject, doc))
    for match in TITLE_RE.finditer(text):
        add(match.group(0), SOURCE_TITLED)
    for ent in doc.ents:
        if ent.label_ == "PERSON":
            add(ent.text, SOURCE_PERSON_ENTITY)
    return candidates


def candidates_for(context: QuoteContext, *, nlp: Any | None = None) -> list[Candidate]:
    """Candidates for one :class:`QuoteContext` (MV4 predictor-slot input).

    Duck-typed: only ``block_text``/``prev_text``/``next_text`` are read, so
    MV4 can wrap this without rewriting it::

        def predict(ctx):  # MV4: priority rules + high/medium/low
            cands = candidates_for(ctx)
            ...

    Parses ``block_text`` (the full paragraph holding the quote, tag
    sentences included); an empty ``block_text`` yields ``[]`` rather than
    guessing from the excerpt. Pronoun proximity spans prev+block+next.
    No confidence is attached here; MV4 owns that.
    """
    block_text = getattr(context, "block_text", "") or ""
    if not block_text.strip():
        return []
    prev_text = getattr(context, "prev_text", "") or ""
    next_text = getattr(context, "next_text", "") or ""
    gender_ctx = " ".join(part for part in (prev_text, block_text, next_text) if part.strip())
    return extract_candidates(block_text, nlp=nlp, gender_context=gender_ctx or None)


def _block_paragraphs(dialogue: ChapterDialogue) -> tuple[dict[int, str], list[int]]:
    """Map block id to its rebuilt paragraph text, in tagged order."""
    paras: dict[int, str] = {}
    order: list[int] = []
    for tagged in dialogue.tagged:
        if tagged.block not in paras:
            paras[tagged.block] = ""
            order.append(tagged.block)
        paras[tagged.block] += tagged.text
    return paras, order


def candidates_by_quote(
    dialogue: ChapterDialogue, book: str = "", *, nlp: Any | None = None
) -> dict[tuple[str, int, int, int], list[Candidate]]:
    """Candidates per quote key ``(book, chapter, block, quote)``.

    Each block's paragraph (rebuilt by joining its tagged sentences, which
    round-trips exactly) is parsed once; every quote in that block shares
    the block's list (copied per key). Never anchored on ``sid``.
    """
    paras, _order = _block_paragraphs(dialogue)
    by_block: dict[int, list[Candidate]] = {}
    result: dict[tuple[str, int, int, int], list[Candidate]] = {}
    for quote in dialogue.quotes:
        if quote.block not in by_block:
            by_block[quote.block] = extract_candidates(paras.get(quote.block, ""), nlp=nlp)
        result[(book, quote.chapter, quote.block, quote.quote)] = list(by_block[quote.block])
    return result


def build_quote_contexts(dialogue: ChapterDialogue, book: str = "") -> list[QuoteContext]:
    """Build :class:`QuoteContext` entries from real dialogue output.

    Bridge for MV4: one context per :class:`QuoteInfo` with ``block_text``
    (the full paragraph holding the quote, tag sentences included),
    ``prev_text``/``next_text`` (neighbouring block paragraphs), and
    ``full_quote`` (the whole quote region, not just the gold excerpt), so
    the predictor receives tag sentences instead of bare excerpts. Keys
    join the gold on ``(book, chapter, block, quote)``; ``excerpt`` carries
    the full quote text (documentary, like the gold excerpts).
    """
    from dev.eval_speakers import QuoteContext

    paras, tagged_order = _block_paragraphs(dialogue)
    chapter_blocks = getattr(dialogue.chapter, "blocks", None) or []
    order = [block.id for block in chapter_blocks] if chapter_blocks else list(tagged_order)
    for block_id in paras:
        if block_id not in order:
            order.append(block_id)
    contexts: list[QuoteContext] = []
    for quote in dialogue.quotes:
        pos = order.index(quote.block) if quote.block in order else -1
        prev_text = paras.get(order[pos - 1], "") if pos > 0 else ""
        next_text = paras.get(order[pos + 1], "") if 0 <= pos < len(order) - 1 else ""
        contexts.append(
            QuoteContext(
                chapter=quote.chapter,
                block=quote.block,
                quote=quote.quote,
                excerpt=quote.text,
                book=book,
                block_text=paras.get(quote.block, ""),
                prev_text=prev_text,
                next_text=next_text,
                full_quote=quote.text,
            )
        )
    return contexts
