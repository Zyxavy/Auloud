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

"""Slice 6 UI6: Cast view — patch ops, mtime conflicts, quotes, report, clips.

No model loading; no slow mark. FastAPI tests skip cleanly without the
``ui`` extra; pure op tests always run. Fixture books live in ``tmp_path``
(never the real workspace, models, bundles or logs).
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import pytest
from ebooklib import epub

LOCAL = {"host": "127.0.0.1:8137"}

SENTENCE = "The quiet alder fox crosses the mossy hill at dawn."


def _make_epub(path: Path, title: str = "UI6 Test Book") -> Path:
    """Two-chapter EPUB: attributed dialogue, then nameless bare quotes.

    Chapter 1 names Alice/Bob (explicit tags: high; continuations: medium).
    Chapter 2 has no names at all, so its bare quotes fall through to the
    ``unknown`` fallback at low confidence — the override picker's audience.
    Bodies repeat so chapters survive tiny-merge and quotes paginate.
    """
    book = epub.EpubBook()
    book.set_identifier("test-ui6-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI6 Author")
    ch1 = "".join(
        [
            '<p>Alice walked in. "Hello there," said Alice. '
            'Bob nodded. "Good morning," said Bob.</p>',
            '<p>"Go away."</p>',
        ]
        * 12
    )
    ch2 = "".join(
        [
            "<p>The room was empty. Nobody spoke a name aloud.</p>",
            '<p>"Go away."</p>',
            '<p>"Leave me alone."</p>',
            '<p>"Never come back."</p>',
        ]
        * 8
    )
    items = []
    for pos, (chapter_title, body) in enumerate(
        [("Chapter One", ch1), ("Chapter Two", ch2)], start=1
    ):
        item = epub.EpubHtml(title=chapter_title, file_name=f"ch{pos}.xhtml", lang="en")
        item.content = f"<h1>{chapter_title}</h1>{body}"
        book.add_item(item)
        items.append(item)
    book.toc = [
        epub.Link(f"ch{pos}.xhtml", chapter_title, f"ch{pos}")
        for pos, chapter_title in enumerate(["Chapter One", "Chapter Two"], 1)
    ]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = items
    epub.write_epub(str(path), book)
    return path


def _draft(workspace: Path, name: str = "book.epub") -> tuple[Path, str]:
    from draft import book_id_for_file, run_draft

    src = _make_epub(workspace / name)
    run_draft(src, work_root=workspace / ".scribe")
    book_id, _sha = book_id_for_file(src)
    return src, book_id


def _make_nameless_epub(path: Path, title: str = "UI6 Low Book") -> Path:
    """EPUB with no names or pronoun tags anywhere.

    Every bare quote falls through to the ``unknown`` fallback at low
    confidence (no explicit/pronoun/alternation evidence exists), which is
    the override picker's audience. One chapter is enough.
    """
    book = epub.EpubBook()
    book.set_identifier("test-ui6-low-book")
    book.set_title(title)
    book.set_language("en")
    book.add_author("UI6 Author")
    body = "".join(
        [
            "<p>The room was empty and cold.</p>",
            '<p>"Go away."</p>',
            "<p>Silence answered back.</p>",
            '<p>"Leave me alone."</p>',
            "<p>The wind rattled the shutters.</p>",
            '<p>"Never come back."</p>',
        ]
        * 8
    )
    item = epub.EpubHtml(title="Only", file_name="ch1.xhtml", lang="en")
    item.content = f"<h1>Only</h1>{body}"
    book.add_item(item)
    book.toc = [epub.Link("ch1.xhtml", "Only", "ch1")]
    book.add_item(epub.EpubNcx())
    book.add_item(epub.EpubNav())
    book.spine = [item]
    epub.write_epub(str(path), book)
    return path


def _draft_nameless(workspace: Path, name: str = "low.epub") -> tuple[Path, str]:
    from draft import book_id_for_file, run_draft

    src = _make_nameless_epub(workspace / name)
    run_draft(src, work_root=workspace / ".scribe")
    book_id, _sha = book_id_for_file(src)
    return src, book_id


def _api_client(workspace: Path, token: str = "ui6-token"):
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    from ui.app import create_app

    app = create_app(workspace, token=token)
    return TestClient(app), token


def _auth(token: str) -> dict[str, str]:
    return {**LOCAL, "X-Auloud-Token": token}


def _work_dir(workspace: Path, book_id: str) -> Path:
    return workspace / ".scribe" / book_id


def _cast_text(workspace: Path, book_id: str) -> str:
    return (_work_dir(workspace, book_id) / "cast.yaml").read_text(encoding="utf-8")


# --- pure patch ops -----------------------------------------------------------


def test_pure_ops_cover_every_shape() -> None:
    from text.cast import default_multivoice_cast
    from ui.cast import apply_ops, shaped_validation_errors

    base = default_multivoice_cast()
    base["characters"] = {"Alice": {"voice": "bf_isabella", "speed": 1.0}}
    base["aliases"] = {"Alice": ["Ally"]}
    before = json.loads(json.dumps(base))
    out = apply_ops(
        base,
        [
            {"op": "set_voice", "character": "Alice", "voice": "jf_alpha"},
            {"op": "set_speed", "character": "Alice", "speed": 1.25},
            {"op": "set_first_person", "value": "Alice"},
            {"op": "add_override", "chapter": 1, "block": 8, "quote": 1, "speaker": "Alice"},
            {"op": "add_override", "match": '^"Run!"', "speaker": "Ally"},
            {"op": "remove_override", "index": 0},
        ],
    )
    assert base == before  # pure: input never mutated
    assert out["characters"]["Alice"] == {"voice": "jf_alpha", "speed": 1.25}
    assert out["first_person"] == "Alice"
    assert out["overrides"] == [{"match": '^"Run!"', "speaker": "Ally"}]
    assert shaped_validation_errors(out) == []


def test_set_voice_engine_option() -> None:
    from text.cast import default_multivoice_cast
    from ui.cast import CastPatchError, apply_ops

    base = default_multivoice_cast()
    base["characters"] = {"Alice": {"voice": "bf_isabella", "speed": 1.0}}
    out = apply_ops(
        base,
        [{"op": "set_voice", "character": "Alice", "voice": "pv-one", "engine": "piper"}],
    )
    assert out["characters"]["Alice"] == {
        "voice": "pv-one",
        "speed": 1.0,
        "engine": "piper",
    }
    # Omitted engine leaves the entry untouched (no key added).
    out = apply_ops(base, [{"op": "set_voice", "character": "Alice", "voice": "jf_alpha"}])
    assert out["characters"]["Alice"] == {"voice": "jf_alpha", "speed": 1.0}
    # Bad engine is a shaped bad-op, and nothing partial applies.
    with pytest.raises(CastPatchError, match="bad-op|engine"):
        apply_ops(
            base,
            [{"op": "set_voice", "character": "Alice", "voice": "pv-one", "engine": "espeak"}],
        )


def test_pure_remove_by_key_and_match() -> None:
    from ui.cast import apply_ops
    from ui.cast import CastPatchError

    cast = {
        "overrides": [
            {"chapter": 1, "block": 1, "quote": 1, "speaker": "Alice"},
            {"match": "^Hi", "speaker": "Bob"},
        ]
    }
    out = apply_ops(cast, [{"op": "remove_override", "chapter": 1, "block": 1, "quote": 1}])
    assert out["overrides"] == [{"match": "^Hi", "speaker": "Bob"}]
    out = apply_ops(cast, [{"op": "remove_override", "match": "^Hi"}])
    assert out["overrides"] == [{"chapter": 1, "block": 1, "quote": 1, "speaker": "Alice"}]
    with pytest.raises(Exception, match="override-not-found"):
        apply_ops(cast, [{"op": "remove_override", "index": 9}])
    with pytest.raises(Exception, match="bad-op"):
        apply_ops(cast, [{"op": "frobnicate"}])
    with pytest.raises(CastPatchError):
        apply_ops(cast, [])


def test_validate_ops_atomic_rejects_before_anything_applies() -> None:
    from text.cast import default_multivoice_cast
    from ui.cast import validate_ops_atomic

    base = default_multivoice_cast()
    base["characters"] = {"Alice": {"voice": "bf_isabella", "speed": 1.0}}
    staged, errors = validate_ops_atomic(
        base,
        [
            {"op": "set_voice", "character": "Alice", "voice": "jf_alpha"},
            {"op": "add_override", "chapter": 1, "block": 1, "quote": 1, "speaker": "Nobody"},
        ],
    )
    assert staged == base  # nothing applied
    assert any("points nowhere" in e["message"] for e in errors)
    assert all(set(e) == {"file", "rule", "message"} for e in errors)


# --- API: GET cast ------------------------------------------------------------


def test_get_cast_stats_and_warnings(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    resp = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["book_id"] == book_id
    assert isinstance(doc["mtime"], float)
    assert doc["has_comments"] is False
    assert "comment_warning" not in doc
    assert doc["first_person"] == "narrator"
    names = [row["character"] for row in doc["stats"]]
    assert "narrator" in names
    assert "default_female" in names and "default_male" in names
    for row in doc["stats"]:
        assert set(row) == {
            "character",
            "kind",
            "voice",
            "speed",
            "lines",
            "minutes",
            "low",
        }
    narrator = next(r for r in doc["stats"] if r["character"] == "narrator")
    assert narrator["lines"] > 0  # narration sentences counted
    assert doc["quotes_total"] > 0
    assert doc["has_audio"] is False  # no bundle yet: lines without minutes
    assert all(row["minutes"] == 0 for row in doc["stats"])
    assert isinstance(doc["minor"], list)
    assert isinstance(doc["validation"], list)


def test_get_cast_unknown_book_and_bad_id(tmp_path: Path) -> None:
    client, token = _api_client(tmp_path)
    missing = client.get(
        "/api/books/00000000-0000-0000-0000-000000000000/cast", headers={**LOCAL}
    )
    assert missing.status_code == 404
    bad = client.get("/api/books/bad:id/cast", headers={**LOCAL})
    assert bad.status_code == 400
    assert bad.json()["rule"] == "path-traversal"


def test_get_cast_flags_hand_comments(tmp_path: Path) -> None:
    from ui.cast import COMMENT_WARNING

    _src, book_id = _draft(tmp_path)
    path = _work_dir(tmp_path, book_id) / "cast.yaml"
    path.write_text("# hand note: Alice sounds young\n" + path.read_text(encoding="utf-8"))
    client, token = _api_client(tmp_path)
    doc = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL}).json()
    assert doc["has_comments"] is True
    assert doc["comment_warning"] == COMMENT_WARNING


# --- API: PATCH ops -----------------------------------------------------------


def _get_mtime(client, book_id: str) -> float:
    resp = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    return resp.json()["mtime"]


def _patch(client, token: str, book_id: str, ops: list, mtime) -> object:
    return client.patch(
        f"/api/books/{book_id}/cast",
        json={"ops": ops, "expected_mtime": mtime},
        headers=_auth(token),
    )


def test_patch_every_op_round_trips(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    mtime = _get_mtime(client, book_id)
    names = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL}).json()
    first_character = next(
        r["character"] for r in names["stats"] if r["kind"] == "character"
    )
    resp = _patch(
        client,
        token,
        book_id,
        [
            {"op": "set_voice", "character": first_character, "voice": "jf_alpha"},
            {"op": "set_speed", "character": first_character, "speed": 1.1},
            {"op": "set_first_person", "value": first_character},
        ],
        mtime,
    )
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["cast"]["characters"][first_character] == {
        "voice": "jf_alpha",
        "speed": 1.1,
    }
    assert doc["first_person"] == first_character
    assert doc["applied"] == 3
    assert set(doc["affected"]) == {"quotes"}
    assert doc["mtime"] != mtime
    text = _cast_text(tmp_path, book_id)
    assert "jf_alpha" in text and "first_person" in text


def test_patch_override_add_remove_and_affected(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    quotes = client.get(f"/api/books/{book_id}/quotes", headers={**LOCAL}).json()
    assert quotes["total"] > 0
    first = quotes["quotes"][0]
    cast = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL}).json()
    characters = [r["character"] for r in cast["stats"] if r["kind"] == "character"]
    assert len(characters) >= 2, "dialogue fixture must draft 2+ characters"
    # Override to a character the quote does NOT already resolve to, so the
    # re-voice delta is exactly one line.
    character = next(c for c in characters if c != first.get("resolved"))
    mtime = cast["mtime"]
    added = _patch(
        client,
        token,
        book_id,
        [
            {
                "op": "add_override",
                "chapter": first["chapter"],
                "block": first["block"],
                "quote": first["quote"],
                "speaker": character,
            }
        ],
        mtime,
    )
    assert added.status_code == 200, added.text
    assert added.json()["affected"] == {"quotes": 1}
    assert len(added.json()["cast"]["overrides"]) == 1
    removed = _patch(
        client,
        token,
        book_id,
        [
            {
                "op": "remove_override",
                "chapter": first["chapter"],
                "block": first["block"],
                "quote": first["quote"],
            }
        ],
        added.json()["mtime"],
    )
    assert removed.status_code == 200, removed.text
    assert removed.json()["cast"]["overrides"] == []


def test_patch_validation_wording_matches_cli(tmp_path: Path) -> None:
    """Rejoined API triples equal text.cast.validate_cast strings exactly."""
    from text.cast import read_cast, validate_cast

    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    mtime = _get_mtime(client, book_id)

    bad_voice = _patch(
        client, token, book_id, [{"op": "set_voice", "character": "Alice", "voice": ""}], mtime
    )
    # Alice may not exist in this draft; either the op-level unknown-character
    # error or the CLI voice error applies — both rejoin verbatim below.
    assert bad_voice.status_code == 400
    body = bad_voice.json()
    rejoined = f"{body['file']}: {body['rule']}: {body['message']}"
    assert body["file"] == "cast.yaml"

    bad_speed = _patch(
        client,
        token,
        book_id,
        [{"op": "set_speed", "character": "Alice", "speed": -2}],
        mtime,
    )
    assert bad_speed.status_code == 400
    assert "above 0" in bad_speed.json()["message"]

    # CLI-verbatim: an override to an unknown speaker fails with the exact
    # validate_cast string (file + rule + message rejoin to the CLI line).
    cast = read_cast(_work_dir(tmp_path, book_id) / "cast.yaml")
    probe = dict(cast)
    probe["overrides"] = list(probe.get("overrides", [])) + [
        {"chapter": 1, "block": 1, "quote": 1, "speaker": "Nobody At All"}
    ]
    cli_lines = [e for e in validate_cast(probe) if "points nowhere" in e]
    assert cli_lines, "expected a CLI points-nowhere line for the probe"
    resp = _patch(
        client,
        token,
        book_id,
        [
            {
                "op": "add_override",
                "chapter": 1,
                "block": 1,
                "quote": 1,
                "speaker": "Nobody At All",
            }
        ],
        mtime,
    )
    assert resp.status_code == 400, resp.text
    body = resp.json()
    assert f"{body['file']}: {body['rule']}: {body['message']}" in cli_lines
    # Nothing was written (atomic).
    assert _cast_text(tmp_path, book_id).count("Nobody At All") == 0
    assert rejoined.startswith("cast.yaml: ")


def test_patch_atomic_and_unknown_op(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    before = _cast_text(tmp_path, book_id)
    mtime = _get_mtime(client, book_id)
    mixed = _patch(
        client,
        token,
        book_id,
        [
            {"op": "set_speed", "character": "narrator", "speed": 1.2},
            {"op": "frobnicate"},
        ],
        mtime,
    )
    assert mixed.status_code == 400
    assert mixed.json()["rule"] == "bad-op"
    assert _cast_text(tmp_path, book_id) == before  # atomic: nothing applied
    assert _patch(client, token, book_id, [], mtime).status_code == 400


def test_patch_needs_mtime_and_token(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    no_guard = client.patch(
        f"/api/books/{book_id}/cast",
        json={"ops": [{"op": "set_speed", "character": "narrator", "speed": 1.1}]},
        headers=_auth(token),
    )
    assert no_guard.status_code == 400
    assert no_guard.json()["rule"] == "bad-op"
    denied = client.patch(
        f"/api/books/{book_id}/cast",
        json={
            "ops": [{"op": "set_speed", "character": "narrator", "speed": 1.1}],
            "expected_mtime": 0,
        },
        headers=LOCAL,
    )
    assert denied.status_code == 403


def test_patch_never_clobbers_hand_edits_with_comments(tmp_path: Path) -> None:
    """Hand values survive a UI patch; the comment loss is flagged, not silent."""
    _src, book_id = _draft(tmp_path)
    path = _work_dir(tmp_path, book_id) / "cast.yaml"
    raw = path.read_text(encoding="utf-8")
    path.write_text(
        "# hand note: keep Alice slow\n" + raw.replace("speed: 1.0", "speed: 0.9", 1),
        encoding="utf-8",
    )
    client, token = _api_client(tmp_path)
    doc = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL}).json()
    assert doc["has_comments"] is True
    character = next(r["character"] for r in doc["stats"] if r["kind"] == "character")
    ops = [{"op": "set_voice", "character": character, "voice": "jf_alpha"}]
    resp = _patch(client, token, book_id, ops, doc["mtime"])
    assert resp.status_code == 200, resp.text
    assert resp.json()["saved_comments"] is True  # loud: comments were dropped
    after = _cast_text(tmp_path, book_id)
    assert "speed: 0.9" in after  # the hand value survived the merge
    assert after.startswith("#") is False  # PyYAML drops comments (documented)


def test_conflict_on_hand_edit_mid_session(tmp_path: Path) -> None:
    import yaml

    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    stale = _get_mtime(client, book_id)
    # A hand edit lands mid-session (mtime moves, content differs).
    path = _work_dir(tmp_path, book_id) / "cast.yaml"
    hand = yaml.safe_load(path.read_text(encoding="utf-8"))
    hand["characters"]["Hand Hero"] = {"voice": "am_eric", "speed": 1.0}
    path.write_text(yaml.safe_dump(hand, sort_keys=True), encoding="utf-8")
    time.sleep(0.02)
    resp = _patch(
        client, token, book_id, [{"op": "set_speed", "character": "narrator", "speed": 1.3}], stale
    )
    assert resp.status_code == 409, resp.text
    body = resp.json()
    assert body["rule"] == "conflict"
    assert "Hand Hero" in json.dumps(body["cast"])  # differing state included
    assert body["current_mtime"] != stale
    assert "1.3" not in _cast_text(tmp_path, book_id)  # nothing overwritten
    # Saving again on the fresh mtime works.
    retry = _patch(
        client,
        token,
        book_id,
        [{"op": "set_speed", "character": "narrator", "speed": 1.3}],
        body["current_mtime"],
    )
    assert retry.status_code == 200, retry.text
    assert "Hand Hero" in _cast_text(tmp_path, book_id)  # hand entry kept


# --- API: PUT full replace -----------------------------------------------------


def test_put_full_replace_with_guard(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, token = _api_client(tmp_path)
    doc = client.get(f"/api/books/{book_id}/cast", headers={**LOCAL}).json()
    new_cast = json.loads(json.dumps(doc["cast"]))
    new_cast["narrator"]["speed"] = 1.15
    resp = client.put(
        f"/api/books/{book_id}/cast",
        json={"cast": new_cast, "expected_mtime": doc["mtime"]},
        headers=_auth(token),
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["cast"]["narrator"]["speed"] == 1.15
    # Stale guard conflicts; invalid replacement is CLI-verbatim.
    stale = client.put(
        f"/api/books/{book_id}/cast",
        json={"cast": new_cast, "expected_mtime": doc["mtime"]},
        headers=_auth(token),
    )
    assert stale.status_code == 409
    broken = dict(new_cast)
    broken["narrator"] = {"engine": "kokoro", "voice": "", "speed": 1.0}
    bad = client.put(
        f"/api/books/{book_id}/cast",
        json={"cast": broken, "expected_mtime": resp.json()["mtime"]},
        headers=_auth(token),
    )
    assert bad.status_code == 400
    assert "narrator.voice" in bad.json()["rule"]
    assert client.put(
        f"/api/books/{book_id}/cast", json={"cast": new_cast}, headers=_auth(token)
    ).status_code == 400


# --- API: quotes picker --------------------------------------------------------


def test_quotes_pagination_and_low_filter(tmp_path: Path) -> None:
    _src, book_id = _draft(tmp_path)
    client, _token = _api_client(tmp_path)
    first = client.get(f"/api/books/{book_id}/quotes", headers={**LOCAL}).json()
    assert first["total"] > 2, "dialogue fixture must yield several quotes"
    assert first["page"] == 1 and first["per_page"] == 100
    for entry in first["quotes"]:
        assert set(entry) >= {
            "chapter",
            "block",
            "quote",
            "text",
            "speaker",
            "confidence",
            "resolved",
        }
        assert entry["text"].strip()
    paged = client.get(
        f"/api/books/{book_id}/quotes?per_page=2&page=2", headers={**LOCAL}
    ).json()
    assert paged["total"] == first["total"]
    assert len(paged["quotes"]) == 2
    assert paged["quotes"] != first["quotes"][:2]
    low = client.get(f"/api/books/{book_id}/quotes?confidence=low", headers={**LOCAL}).json()
    # The named fixture attributes everything high/medium (explicit tags plus
    # continuation); the filter itself must still be sound: a subset, all low.
    assert low["total"] <= first["total"]
    assert all(q["confidence"] == "low" for q in low["quotes"])
    assert client.get(
        f"/api/books/{book_id}/quotes?confidence=nope", headers={**LOCAL}
    ).status_code == 400
    assert client.get(
        f"/api/books/{book_id}/quotes?per_page=9999", headers={**LOCAL}
    ).status_code == 400
    assert client.get(
        "/api/books/00000000-0000-0000-0000-000000000000/quotes", headers={**LOCAL}
    ).status_code == 404


def test_quotes_low_filter_finds_fallback_lines(tmp_path: Path) -> None:
    """Nameless books attribute low: the picker's real audience."""
    _src, book_id = _draft_nameless(tmp_path)
    client, _token = _api_client(tmp_path)
    everything = client.get(f"/api/books/{book_id}/quotes", headers={**LOCAL}).json()
    assert everything["total"] >= 3
    low = client.get(f"/api/books/{book_id}/quotes?confidence=low", headers={**LOCAL}).json()
    assert low["total"] == everything["total"] >= 3
    assert all(q["confidence"] == "low" for q in low["quotes"])
    assert all(q["resolved"] == "default_female" for q in low["quotes"])
    # Paging still honors the total on a fully-low book.
    half = everything["total"] // 2
    paged = client.get(
        f"/api/books/{book_id}/quotes?confidence=low&per_page={half}&page=2",
        headers={**LOCAL},
    ).json()
    assert paged["total"] == everything["total"]
    assert len(paged["quotes"]) == everything["total"] - half


# --- API: report ----------------------------------------------------------------


def test_report_escapes_malicious_input(tmp_path: Path) -> None:
    _src, book_id = _draft_nameless(tmp_path)  # low lines populate the pane
    report = _work_dir(tmp_path, book_id) / "cast_report.md"
    report.write_text(
        "# Cast report\n\n<script>alert(1)</script>\n\n"
        "<img src=x onerror=alert(2)>\n\n"
        "[Alice](javascript:alert(3)) | `code` | quote (1, 2, 3)\n",
        encoding="utf-8",
    )
    client, _token = _api_client(tmp_path)
    resp = client.get(f"/api/books/{book_id}/cast/report", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert "<script>" not in doc["html"]
    assert "&lt;script&gt;" in doc["html"]
    assert "&lt;img" in doc["html"]
    assert "<img" not in doc["html"]
    assert doc["markdown"].startswith("# Cast report")  # raw text preserved
    assert isinstance(doc["low_confidence"], list)
    assert len(doc["low_confidence"]) >= 3
    assert all(q["confidence"] == "low" for q in doc["low_confidence"])
    assert client.get(
        "/api/books/00000000-0000-0000-0000-000000000000/cast/report", headers={**LOCAL}
    ).status_code == 404


# --- API: voices + audition clips -----------------------------------------------


def test_voices_list_is_palette_without_models(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from ui.cast import PALETTE_VOICES

    # Isolate CWD: the fallback is CWD models/, which exists when pytest
    # runs from scribe/ (and then the engine list is the honest answer).
    monkeypatch.chdir(tmp_path)
    client, _token = _api_client(tmp_path)
    resp = client.get("/api/cast/voices", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["voices"] == list(PALETTE_VOICES)
    assert doc["source"] == "palette"
    for voice in ("am_onyx", "bf_isabella", "bm_lewis", "af_bella", "am_adam"):
        assert voice in doc["voices"]


def _write_voice_archive(models_dir: Path, names: list[str]) -> None:
    import numpy as np

    from tts.voices import VOICES_ARCHIVE_FILENAME

    models_dir.mkdir(parents=True, exist_ok=True)
    staged = models_dir / "staged.npz"
    np.savez(str(staged), **{name: np.zeros(4, dtype=np.float32) for name in names})
    staged.rename(models_dir / VOICES_ARCHIVE_FILENAME)


def test_voices_list_is_engine_with_models(tmp_path: Path) -> None:
    _write_voice_archive(
        tmp_path / "models", ["zm_yunxi", "af_heart", "am_adam", "ef_dora"]
    )
    client, _token = _api_client(tmp_path)
    resp = client.get("/api/cast/voices", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["source"] == "engine"
    assert doc["engine"] == "kokoro"
    assert doc["voices"] == ["af_heart", "am_adam", "ef_dora", "zm_yunxi"]
    by_name = {entry["name"]: entry for entry in doc["details"]}
    assert by_name["ef_dora"]["locale"] == "Spanish"
    assert by_name["zm_yunxi"]["gender"] == "male"


def _write_piper_pair(models_dir: Path, stem: str) -> None:
    piper_dir = models_dir / "piper"
    piper_dir.mkdir(parents=True, exist_ok=True)
    (piper_dir / f"{stem}.onnx").write_bytes(b"fake-onnx")
    (piper_dir / f"{stem}.onnx.json").write_text("{}", encoding="utf-8")


def test_voices_list_piper_engine_with_pairs(tmp_path: Path) -> None:
    _write_piper_pair(tmp_path / "models", "en_US-test-low")
    client, _token = _api_client(tmp_path)
    resp = client.get("/api/cast/voices?engine=piper", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["source"] == "engine"
    assert doc["engine"] == "piper"
    assert doc["voices"] == ["en_US-test-low"]
    assert doc["engine_version"].startswith("piper-tts ")
    assert doc["details"] == [
        {"name": "en_US-test-low", "locale": "en-US", "gender": "unknown"}
    ]


def test_voices_list_piper_without_pairs_is_empty_not_palette(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    # Piper has no palette fallback: no pairs means an honest empty list,
    # never the Kokoro shortlist under a piper label.
    monkeypatch.chdir(tmp_path)
    client, _token = _api_client(tmp_path)
    resp = client.get("/api/cast/voices?engine=piper", headers={**LOCAL})
    assert resp.status_code == 200, resp.text
    doc = resp.json()
    assert doc["voices"] == [] and doc["source"] == "engine"
    assert doc["engine"] == "piper"


def test_voices_list_unknown_engine_is_400(tmp_path: Path) -> None:
    client, _token = _api_client(tmp_path)
    resp = client.get("/api/cast/voices?engine=espeak", headers={**LOCAL})
    assert resp.status_code == 400, resp.text
    assert resp.json()["rule"] == "bad-request"
    assert "espeak" in resp.json()["message"]


def test_describe_voice_piper_ids() -> None:
    from ui.cast import describe_voice

    assert describe_voice("en_US-test-low") == {
        "name": "en_US-test-low",
        "locale": "en-US",
        "gender": "unknown",
    }
    # Kokoro heuristic untouched.
    assert describe_voice("af_bella")["locale"] == "American"


def test_voices_unknown_name_rejected_not_palette_gated(tmp_path: Path) -> None:
    _write_voice_archive(tmp_path / "models", ["zm_yunxi", "af_heart"])
    client, token = _api_client(tmp_path)
    headers = {**LOCAL, "X-Auloud-Token": token}
    bad = client.post(
        "/api/voices/no_such_voice_xyz/sample", json={}, headers=headers
    )
    assert bad.status_code == 400, bad.text
    assert bad.json()["rule"] == "unknown-voice"
    assert "not a known kokoro voice" in bad.json()["message"]


def test_voice_sample_served_missing_and_bad(tmp_path: Path) -> None:
    from ui.cast import samples_dir

    client, _token = _api_client(tmp_path)
    missing = client.get("/api/voices/af_heart.wav", headers={**LOCAL})
    assert missing.status_code == 409
    assert missing.json()["rule"] == "sample-missing"
    assert "voices --sample" in missing.json()["message"]
    cached = samples_dir(tmp_path)
    cached.mkdir(parents=True)
    blob = b"RIFF" + bytes(100)
    (cached / "01-af_heart.wav").write_bytes(blob)  # --sample naming shape
    hit = client.get("/api/voices/af_heart.wav", headers={**LOCAL})
    assert hit.status_code == 200, hit.text
    assert hit.headers["content-type"] == "audio/wav"
    assert hit.content == blob
    bad = client.get("/api/voices/..%2F..%2Fx.wav", headers={**LOCAL})
    assert bad.status_code in (400, 404)


# --- frontend ---------------------------------------------------------------------


def test_frontend_cast_view_offline_tokens_and_mounts() -> None:
    from ui import security

    index = Path(security.__file__).resolve().parent / "static" / "index.html"
    text = index.read_text(encoding="utf-8")
    assert "{{AULOUD_TOKEN}}" in text
    assert "http" not in text, "no CDN or external requests (offline)"
    for needle in (
        'id="cast-step"',
        'id="cast-table"',
        'id="cast-save"',
        'id="cast-conflict"',
        'id="cast-report"',
        'id="cast-report-drop"',
        "Cast report — show",
        'id="cast-low"',
        "Low-confidence lines (",
        "cast-minor",
        'id="cast-first-person"',
        'id="cast-overrides"',
        'id="player-row"',
        "setupCastStep",
        "saveCast",
        "auditionVoice",
        "No unsaved changes.",
        "Unsaved changes",
        "Conflict: the file changed on disk",
        "Minor voices",
        "Low-confidence lines",
        "Use in override",
        "First person",
        "voice-samples",
        "whole covering chapters",
    ):
        assert needle in text, needle
