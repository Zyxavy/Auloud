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

"""Chapter/page range parsing (Slice 6 UI1): ``--chapters 3-5,7``.

``build`` accepts a chapter selection (1-based list/ranges); ``draft``
ALWAYS processes the whole book (attribution needs full context). PDF
``--pages 40-90`` resolves via sentence ``page`` provenance (min/max per
chapter from ``script.json``) to covering chapters (whole chapters render).
``--chapters`` and ``--pages`` together is an error naming the rule
(``chapters-pages-exclusive``).

Syntax (both flags): comma-separated ``N`` or ``A-B`` (``A <= B``,
whitespace tolerated, duplicates collapse, output sorted). Anything else
(``0``, negatives, ``5-3``, non-integers, empties) raises :class:`ValueError`
with a ``{file, rule, message}``-shaped message the CLI prints cleanly.
"""

from __future__ import annotations


def parse_range_spec(spec: str, *, total: int, kind: str) -> list[int]:
    """Parse ``"3-5,7"`` into sorted 1-based indices (validated vs ``total``).

    :param spec: raw flag value (e.g. ``"3-5,7"``).
    :param total: upper bound (chapter count for ``--chapters``, max source
        page for ``--pages``). Must be >= 1 or :class:`ValueError`.
    :param kind: ``"chapters"`` or ``"pages"`` (names the rule in errors).
    :returns: sorted unique indices, each 1..``total``.
    :raises ValueError: bad syntax or out-of-range, shaped as
        ``{flag}: {rule}: {message}`` (e.g. ``chapters: bad-range: ...``).
    """
    if total < 1:
        raise ValueError(f"{kind}: bad-range: book has no {kind} to select (total 0)")
    if not isinstance(spec, str) or not spec.strip():
        raise ValueError(f"{kind}: bad-range: empty {kind} selection (e.g. --{kind} 3-5,7)")
    picked: set[int] = set()
    for raw_part in spec.split(","):
        part = raw_part.strip()
        if not part:
            raise ValueError(
                f"{kind}: bad-range: empty entry in {spec!r} (use N or A-B, e.g. 3-5,7)"
            )
        if "-" in part:
            bounds = part.split("-")
            if len(bounds) != 2:
                raise ValueError(
                    f"{kind}: bad-range: {part!r} is not N or A-B (e.g. 3-5,7)"
                )
            try:
                first = int(bounds[0].strip())
                last = int(bounds[1].strip())
            except ValueError as exc:
                raise ValueError(
                    f"{kind}: bad-range: {part!r} is not N or A-B (e.g. 3-5,7)"
                ) from exc
            if first < 1 or last < 1:
                raise ValueError(
                    f"{kind}: bad-range: {part!r} needs 1-based values "
                    f"(got {first}-{last})"
                )
            if last < first:
                raise ValueError(
                    f"{kind}: bad-range: {part!r} runs backwards "
                    "(use A-B with A <= B)"
                )
            for value in range(first, last + 1):
                picked.add(value)
        else:
            try:
                value = int(part)
            except ValueError as exc:
                raise ValueError(
                    f"{kind}: bad-range: {part!r} is not N or A-B (e.g. 3-5,7)"
                ) from exc
            if value < 1:
                raise ValueError(
                    f"{kind}: bad-range: chapter {value} needs 1-based values"
                    if kind == "chapters"
                    else f"{kind}: bad-range: page {value} needs 1-based values"
                )
            picked.add(value)
    out = sorted(picked)
    bad = [v for v in out if v < 1 or v > total]
    if bad:
        raise ValueError(
            f"{kind}: bad-range: {kind} {bad[0]} out of range "
            f"(book has {total} {kind}, need 1..{total})"
        )
    return out


def chapters_for_pages(
    chapter_page_ranges: dict[int, tuple[int, int]],
    pages: list[int],
) -> list[int]:
    """Covering chapters for ``pages`` (whole chapters render, D7).

    :param chapter_page_ranges: ``{source_chapter: (min_page, max_page)}``
        from ``script.json`` sentence ``page`` provenance.
    :param pages: requested 1-based source pages (already parsed).
    :returns: sorted source chapters whose ``[min_page, max_page]``
        overlaps any requested page.
    """
    wanted = set(pages)
    covering: list[int] = []
    for chapter in sorted(chapter_page_ranges):
        first, last = chapter_page_ranges[chapter]
        if any(first <= page <= last for page in wanted):
            covering.append(chapter)
    return covering
