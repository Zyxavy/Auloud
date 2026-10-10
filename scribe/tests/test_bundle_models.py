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

"""SW2: shape tests for ``bundle/models.py`` (spec sections 3-5)."""

from __future__ import annotations

import pytest

from bundle.models import (
    AudioSpec,
    Block,
    BundleError,
    ChapterFile,
    ChapterEntry,
    Manifest,
    PageEntry,
    Sentence,
    SourceInfo,
    Voice,
)


def _full_manifest_dict() -> dict:
    return {
        "spec_version": "1.0",
        "id": "8f0c6c1e-3a8f-4c6e-9d54-0b6a3f1a2b77",
        "title": "Example Novel",
        "author": "A. Author",
        "language": "en",
        "type": "epub",
        "source": {"file": "source/book.epub", "sha256": "ab" * 32},
        "cover": "cover.jpg",
        "audio": {
            "format": "mp3",
            "channels": 1,
            "sample_rate": 24000,
            "bitrate_kbps": 64,
            "cbr": True,
        },
        "voices": {
            "narrator": {"engine": "kokoro", "voice": "af_sarah", "speed": 1.0, "pitch": 1.0}
        },
        "chapters": [
            {
                "index": 1,
                "title": "Chapter One",
                "audio": "audio/ch001.mp3",
                "text": "text/ch001.json",
                "duration_ms": 1832400,
            }
        ],
        "created_at": "2026-09-29T10:00:00Z",
        "generator": "scribe 0.1.0",
        "future_field": {"nested": True},
    }


def test_manifest_full_round_trip() -> None:
    manifest = Manifest.from_dict(_full_manifest_dict())
    assert manifest.spec_version == "1.0"
    assert manifest.voices["narrator"].voice == "af_sarah"
    assert manifest.chapters[0].duration_ms == 1832400
    assert manifest.source is not None and manifest.source.file == "source/book.epub"
    again = Manifest.from_dict(manifest.to_dict())
    assert again == manifest


def test_manifest_minimal_only_required() -> None:
    manifest = Manifest.from_dict(
        {
            "spec_version": "1.0",
            "id": "x",
            "title": "T",
            "type": "epub",
            "audio": {},
            "chapters": [],
        }
    )
    assert manifest.author is None
    assert manifest.voices == {}
    assert manifest.audio.channels == 1  # Player-compatible defaults
    assert manifest.audio.sample_rate == 24000


def test_manifest_unknown_keys_ignored() -> None:
    data = _full_manifest_dict()
    data["chapters"][0]["unknown_chapter_flag"] = True
    data["audio"]["v2_field"] = "x"
    manifest = Manifest.from_dict(data)
    assert manifest.title == "Example Novel"


def test_manifest_missing_required_raises_naming_field() -> None:
    # Spec v2.0 (IN1): `audio` is conditional, so the shape layer accepts
    # it missing (the validator requires it for 1.x and rendered 2.0).
    for key in ("spec_version", "id", "title", "type", "chapters"):
        data = _full_manifest_dict()
        del data[key]
        with pytest.raises(BundleError, match=key):
            Manifest.from_dict(data)


def test_manifest_audio_and_render_state_optional_shape() -> None:
    """Missing `audio`/`render_state` parse as None (validators decide)."""
    data = _full_manifest_dict()
    del data["audio"]
    manifest = Manifest.from_dict(data)
    assert manifest.audio is None
    assert manifest.render_state is None
    assert Manifest.from_dict(manifest.to_dict()) == manifest
    data2 = _full_manifest_dict()
    data2["render_state"] = "none"
    assert Manifest.from_dict(data2).render_state == "none"


def test_manifest_wrong_type_raises() -> None:
    data = _full_manifest_dict()
    data["chapters"] = {"index": 1}
    with pytest.raises(BundleError, match="chapters"):
        Manifest.from_dict(data)
    with pytest.raises(BundleError):
        Manifest.from_dict([1, 2, 3])


def test_audio_spec_defaults_and_voice_defaults() -> None:
    audio = AudioSpec.from_dict({})
    assert (audio.format, audio.channels, audio.bitrate_kbps, audio.cbr) == (
        "mp3",
        1,
        64,
        True,
    )
    voice = Voice.from_dict({})
    assert (voice.engine, voice.speed, voice.pitch) == ("", 1.0, 1.0)
    source = SourceInfo.from_dict({})
    assert source.file is None and source.sha256 is None


def _sentence_dict(sid: int = 1) -> dict:
    return {
        "sid": sid,
        "speaker": "narrator",
        "start_ms": 0,
        "end_ms": 4200,
        "text": "The rain had not stopped.",
        "spans": [{"start": 0, "end": 3, "style": "italic"}],
    }


def test_sentence_span_round_trip() -> None:
    sentence = Sentence.from_dict(_sentence_dict())
    assert sentence.spans[0].style == "italic"
    assert Sentence.from_dict(sentence.to_dict()) == sentence


def test_sentence_rejects_non_integer_ms() -> None:
    bad = _sentence_dict()
    bad["start_ms"] = 12.5
    with pytest.raises(BundleError, match="start_ms"):
        Sentence.from_dict(bad)
    bad = _sentence_dict()
    bad["sid"] = True
    with pytest.raises(BundleError, match="sid"):
        Sentence.from_dict(bad)


def test_chapter_epub_form_and_order() -> None:
    chapter = ChapterFile.from_dict(
        {
            "spec_version": "1.0",
            "chapter": 1,
            "title": "One",
            "duration_ms": 9000,
            "blocks": [
                {"id": 1, "type": "heading", "level": 1, "text": "One"},
                {
                    "id": 2,
                    "type": "para",
                    "sentences": [_sentence_dict(1), _sentence_dict(2)],
                },
                {"id": 3, "type": "break"},
            ],
        }
    )
    assert [s.sid for s in chapter.sentences_in_order()] == [1, 2]
    assert ChapterFile.from_dict(chapter.to_dict()) == chapter


def test_chapter_unknown_block_type_raises() -> None:
    with pytest.raises(BundleError, match="sidebar"):
        Block.from_dict({"id": 1, "type": "sidebar"})


def test_chapter_pdf_pages_form() -> None:
    chapter = ChapterFile.from_dict(
        {
            "spec_version": "1.0",
            "chapter": 1,
            "title": "Pages 1-2",
            "duration_ms": 100000,
            "pages": [{"page": 1, "start_ms": 0}, {"page": 2, "start_ms": 93000}],
        }
    )
    assert chapter.pages is not None and len(chapter.pages) == 2
    assert isinstance(chapter.pages[0], PageEntry)
    assert chapter.sentences_in_order() == []


def test_chapter_needs_blocks_or_pages_v11_allows_both() -> None:
    """v1.1: blocks+pages together is the PDF text path (both allowed)."""
    base = {"spec_version": "1.1", "chapter": 1, "title": "T", "duration_ms": 1000}
    with pytest.raises(BundleError, match="blocks"):
        ChapterFile.from_dict(dict(base))
    both = ChapterFile.from_dict(dict(base, blocks=[], pages=[]))
    assert both.blocks == [] and both.pages == []
    assert ChapterFile.from_dict(both.to_dict()) == both


def test_sentence_page_absent_not_null_epub() -> None:
    """EPUB sentences omit `page` (absent, never null); PDF carries 1-based."""
    epub_sent = Sentence.from_dict(
        {"sid": 1, "speaker": "narrator", "start_ms": 0, "end_ms": 100, "text": "Hi."}
    )
    assert epub_sent.page is None
    assert "page" not in epub_sent.to_dict()
    pdf_sent = Sentence.from_dict(
        {
            "sid": 1,
            "speaker": "narrator",
            "start_ms": 0,
            "end_ms": 100,
            "text": "Hi.",
            "page": 2,
        }
    )
    assert pdf_sent.page == 2
    assert pdf_sent.to_dict()["page"] == 2
    assert Sentence.from_dict(pdf_sent.to_dict()) == pdf_sent


def test_chapter_entry_requires_text_title_index() -> None:
    """`text` (plus index/title) is always required; audio/duration are
    conditional in 2.0 (absent parses as None, the validator decides)."""
    with pytest.raises(BundleError, match="text"):
        ChapterEntry.from_dict({"index": 1, "title": "T", "audio": "a", "duration_ms": 5})
    unrendered = ChapterEntry.from_dict({"index": 1, "title": "T", "text": "t"})
    assert unrendered.audio is None and unrendered.duration_ms is None
    assert "audio" not in unrendered.to_dict()
    assert "duration_ms" not in unrendered.to_dict()
    assert ChapterEntry.from_dict(unrendered.to_dict()) == unrendered


def test_sentence_timings_optional_shape() -> None:
    """Unrendered sentences omit start/end (None); rendered keep them."""
    bare = Sentence.from_dict({"sid": 1, "speaker": "narrator", "text": "Hi."})
    assert bare.start_ms is None and bare.end_ms is None
    assert "start_ms" not in bare.to_dict()
    assert Sentence.from_dict(bare.to_dict()) == bare
    timed = Sentence.from_dict(
        {"sid": 1, "speaker": "narrator", "start_ms": 0, "end_ms": 100, "text": "Hi."}
    )
    assert (timed.start_ms, timed.end_ms) == (0, 100)


def test_chapter_duration_optional_shape() -> None:
    """Unrendered chapters omit duration_ms (None); rendered keep it."""
    base = {"spec_version": "2.0", "chapter": 1, "title": "T", "blocks": []}
    assert ChapterFile.from_dict(dict(base)).duration_ms is None
    assert "duration_ms" not in ChapterFile.from_dict(dict(base)).to_dict()
