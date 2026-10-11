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

"""RS1: export ``parity/expected/<fixture>.json`` from the Python validator.

Each error becomes ``{file, rule, message}`` (plus ``prefix`` for the few
messages that embed parser or OS detail text). Rule ids come from the RULES
table below, matched as regex against the message with an exactly-one-match
guard: an unmatched or double-matched message fails loudly instead of
silently passing. Run ``export_expected.py --render-docs`` to regenerate the
rule table section of ``docs/rust-port.md`` (a test pins the committed copy).

Probing: fixture MP3s are 0-byte placeholders, so the default stub derives
plausible values from the manifest (codec from the audio extension,
channels/rate/bitrate from ``manifest.audio``, duration from the chapter
entry, CBR ok). ``parity/probes/<fixture>.json`` overrides per-file probe
values for the audio-mismatch fixtures; the Rust contract tests (RS4) read
the same files.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from bundle.models import Manifest  # noqa: E402
from bundle.validate import AudioProbe, validate_bundle  # noqa: E402

REPO = Path(__file__).resolve().parent.parent.parent
FIXTURES = REPO / "spec" / "fixtures"
EXPECTED = REPO / "parity" / "expected"
PROBES = REPO / "parity" / "probes"


@dataclass(frozen=True)
class Rule:
    """One comparable validation rule.

    ``file`` scopes the match (manifest, chapter, audio, source, any).
    ``fragment`` is a regex matching a constant part unique to the template;
    RULES is ordered specific-before-general and the matcher asserts exactly
    one hit. ``prefix`` (with ``{file}`` substituted) is the compared head
    for messages that embed parser or OS detail; None means exact compare.
    ``fixture`` names one covering fixture (several rules share a fixture).
    """

    id: str
    file: str
    fragment: str
    prefix: str | None = None
    fixture: str = ""


RULES: list[Rule] = [
    # --- IO and syntax (detail text varies: prefix compare) ---
    Rule("IO-READ", "any", r"cannot read file", "{file}: cannot read file", "rs4-only"),
    Rule("IO-UTF8", "any", r"not valid UTF-8", "{file}: not valid UTF-8", "bad-chapter"),
    Rule("IO-NFC-FILE", "any", r"text is not NFC-normalized \(normalize", None, "bad-chapter"),
    Rule(
        "JSON-SYNTAX-MANIFEST",
        "manifest",
        r"invalid JSON",
        "manifest.json: invalid JSON",
        "bad-json",
    ),
    Rule("JSON-SYNTAX-CHAPTER", "chapter", r"invalid JSON", "{file}: invalid JSON", "bad-chapter"),
    Rule("PROBE-ERROR", "audio", r"ffprobe", "{file}: ffprobe", "bad-audio"),
    Rule("PROBE-CRASHED", "audio", r"audio probe failed", None, "bad-audio"),
    # --- manifest gate and declarations ---
    Rule(
        "VERS-GATE-MANIFEST",
        "manifest",
        r"must be one of \['1\.0', '1\.1', '1\.2', '2\.0'\]",
        None,
        "bad-manifest",
    ),
    Rule(
        "MANIFEST-BLANK-FIELD",
        "manifest",
        r"^manifest\.json: missing required field '\w+' \(blank\)$",
        None,
        "bad-manifest",
    ),
    Rule(
        "RS-IN-1X",
        "manifest",
        r"must be absent in 1\.x bundles \(2\.0 only\)",
        None,
        "bad-1x-fields",
    ),
    Rule(
        "RS-VALUE",
        "manifest",
        r"must be one of \['none', 'partial', 'complete'\]",
        None,
        "bad-2.0-state",
    ),
    Rule("NO-CHAPTERS", "manifest", r"no chapters listed", None, "bad-empty"),
    Rule("LOAD-MISSING-MANIFEST", "manifest", r"file missing in", None, "rs4-only"),
    Rule("AUDIO-FORMAT-VALUE", "manifest", r"audio\.format must be one of", None, "bad-manifest"),
    Rule("AUDIO-CHANNELS", "manifest", r"audio\.channels must be 1 \(mono\)", None, "bad-manifest"),
    Rule("AUDIO-RATE", "manifest", r"audio\.sample_rate must be one of", None, "bad-manifest"),
    Rule("AUDIO-BITRATE", "manifest", r"audio\.bitrate_kbps must be 64", None, "bad-manifest"),
    Rule("AUDIO-CBR", "manifest", r"audio\.cbr must be true", None, "bad-manifest"),
    Rule(
        "AUDIO-MISSING-1X",
        "manifest",
        r"missing required field 'audio' \(absent\)",
        None,
        "bad-noaudio",
    ),
    Rule(
        "AUDIO-NONE-FORBIDDEN",
        "manifest",
        r"audio must be absent when render_state is 'none'",
        None,
        "bad-2.0-noneaudio",
    ),
    Rule("AUDIO-MISSING-2X", "manifest", r"needs it for rendered chapters", None, "bad-2.0-state"),
    # --- chapter entries ---
    Rule("ENTRY-IDX-NONPOS", "manifest", r"non-positive index", None, "bad-manifest"),
    Rule("ENTRY-IDX-ORDER", "manifest", r"not strictly increasing", None, "bad-manifest"),
    Rule(
        "ENTRY-IDX-CONTIGUOUS",
        "manifest",
        r"indices must be consecutive 1\.\.N",
        None,
        "bad-manifest",
    ),
    Rule("ENTRY-SRC-INVALID", "manifest", r"has invalid source_index", None, "bad-manifest"),
    Rule(
        "ENTRY-TITLE-BLANK",
        "manifest",
        r"chapter \d+ missing required field 'title' \(blank\)",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-TEXT-BLANK",
        "manifest",
        r"chapter \d+ missing required field 'text' \(blank\)",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-AUDIO-BLANK-1X",
        "manifest",
        r"chapter \d+ missing required field 'audio' \(blank\)",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-AUDIO-EXT-1X",
        "manifest",
        r"must end in \.mp3 \(m4a audio needs spec 2\.0\)",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-DURATION-ABSENT-1X",
        "manifest",
        r"chapter \d+ missing required field 'duration_ms' \(absent\)",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-DURATION-NONPOS", "manifest", r"has non-positive duration_ms", None, "bad-manifest"
    ),
    Rule(
        "ENTRY-AUDIO-EXT-2X",
        "manifest",
        r"must end in \.mp3 or \.m4a \(per-chapter format",
        None,
        "bad-manifest",
    ),
    Rule(
        "ENTRY-AUDIO-WITHOUT-DURATION",
        "manifest",
        r"has audio without duration_ms",
        None,
        "bad-2.0-partial",
    ),
    Rule(
        "ENTRY-DURATION-WITHOUT-AUDIO",
        "manifest",
        r"has duration_ms without audio",
        None,
        "bad-2.0-partial",
    ),
    Rule("FORMAT-AGREEMENT", "manifest", r"matches no rendered chapter", None, "bad-2.0-partial"),
    # --- 2.0 device fields ---
    Rule("GAIN-1X", "manifest", r"gain_db is 2\.0-only", None, "bad-1x-fields"),
    Rule("GAIN-EMPTY", "manifest", r"gain_db present but empty", None, "bad-2.0-complete"),
    Rule("GAIN-UNKNOWN-ROLE", "manifest", r"gain_db has unknown role", None, "bad-2.0-partial"),
    Rule("ENCODER-1X", "manifest", r"encoder_offset_ms is 2\.0-only", None, "bad-1x-fields"),
    Rule("FP-1X", "manifest", r"render_fingerprint is 2\.0-only", None, "bad-1x-fields"),
    Rule(
        "FP-NO-DURATION",
        "manifest",
        r"carries render_fingerprint without duration_ms",
        None,
        "bad-2.0-partial",
    ),
    Rule(
        "FP-ENGINE-BLANK",
        "manifest",
        r"\.engine must be a non-empty string",
        None,
        "bad-2.0-partial",
    ),
    Rule(
        "FP-VOICES-MISSING",
        "manifest",
        r"voices missing or blank voice for role",
        None,
        "bad-2.0-partial",
    ),
    Rule("FP-VOICES-UNKNOWN", "manifest", r"voices has unknown role", None, "bad-2.0-partial"),
    Rule(
        "FP-SPEEDS-MISSING",
        "manifest",
        r"speeds missing or invalid speed for role",
        None,
        "bad-2.0-partial",
    ),
    Rule("FP-SPEEDS-UNKNOWN", "manifest", r"speeds has unknown role", None, "bad-2.0-partial"),
    Rule(
        "FP-VERSIONS-EMPTY",
        "manifest",
        r"engine_versions present but empty",
        None,
        "bad-2.0-partial",
    ),
    Rule(
        "FP-VERSIONS-BLANK",
        "manifest",
        r"engine_versions has a blank engine or version",
        None,
        "bad-2.0-partial",
    ),
    # --- render-state consistency ---
    Rule("RS-NONE-MISMATCH", "manifest", r"render_state 'none' but", None, "bad-2.0-none"),
    Rule(
        "RS-COMPLETE-MISMATCH", "manifest", r"render_state 'complete' but", None, "bad-2.0-complete"
    ),
    Rule(
        "RS-PARTIAL-MISMATCH",
        "manifest",
        r"render_state 'partial' needs at least one rendered",
        None,
        "bad-2.0-partialall",
    ),
    # --- source_index ---
    Rule(
        "SRC-IDX-MISSING",
        "manifest",
        r"missing source_index \(range bundles",
        None,
        "shape-srcrange",
    ),
    Rule("SRC-IDX-DUP", "manifest", r"duplicate source_index values", None, "shape-srcrange"),
    Rule("SRC-IDX-ORDER", "manifest", r"source_index \d+ out of order", None, "shape-srcrange"),
    # --- voices ---
    Rule(
        "VOICE-KEY-BLANK",
        "manifest",
        r"speaker key must be a non-empty string",
        None,
        "bad-manifest",
    ),
    Rule(
        "VOICE-ENGINE-BLANK",
        "manifest",
        r"\.engine: must be a non-empty string",
        None,
        "bad-manifest",
    ),
    Rule("VOICE-NAME-UNKNOWN", "manifest", r"\.voice: unknown voice", None, "bad-manifest"),
    Rule("VOICE-SPEED-BAD", "manifest", r"\.speed: must be a number above 0", None, "bad-manifest"),
    Rule("VOICE-PITCH-BAD", "manifest", r"\.pitch: must be a number above 0", None, "bad-manifest"),
    Rule(
        "VOICES-RESERVED-MISSING",
        "manifest",
        r"voices missing reserved speaker",
        None,
        "bad-2.0-voices",
    ),
    # --- referenced files and source ---
    Rule("AUDIO-FILE-MISSING", "manifest", r"audio file missing", None, "missing-mp3"),
    Rule("TEXT-FILE-MISSING", "manifest", r"text file missing", None, "bad-manifest"),
    Rule("COVER-MISSING", "manifest", r"cover file missing", None, "bad-manifest"),
    Rule(
        "SRC-FILE-MISSING",
        "source",
        r"file missing \(listed in manifest source\)",
        None,
        "shape-srcfile",
    ),
    Rule(
        "SRC-SHA-NOT-HEX",
        "manifest",
        r"source sha256 is not a 64-char hex string",
        None,
        "bad-source",
    ),
    Rule("SRC-SHA-MISMATCH", "source", r"sha256 mismatch", None, "bad-manifest"),
    # --- chapter content ---
    Rule("CHAPTER-IDX-MISMATCH", "chapter", r"manifest lists index", None, "bad-chapter"),
    Rule(
        "VERS-GATE-CHAPTER",
        "chapter",
        r"must be one of \['1\.0', '1\.1', '1\.2', '2\.0'\]",
        None,
        "shape-vers",
    ),
    Rule(
        "CHAPTER-SRC-INVALID",
        "chapter",
        r"invalid source_index \d+ \(need 1-based",
        None,
        "shape-src",
    ),
    Rule(
        "CHAPTER-SRC-ABSENT-MISMATCH",
        "chapter",
        r"source_index mismatch",
        None,
        "bad-2.0-partialall",
    ),
    Rule(
        "CHAPTER-SRC-VALUE-MISMATCH",
        "chapter",
        r"does not match manifest entry source_index",
        None,
        "bad-2.0-partialall",
    ),
    Rule(
        "CHAPTER-VERSION-MISMATCH-2X",
        "chapter",
        r"2\.0 chapters belong to 2\.0 books",
        None,
        "bad-2.0-partialall",
    ),
    Rule(
        "CHAPTER-VERSION-MISMATCH-1X",
        "chapter",
        r"1\.x books hold 1\.x chapters",
        None,
        "bad-1x-fields",
    ),
    Rule(
        "CHAPTER-DURATION-ABSENT-1X",
        "chapter",
        r"missing required field 'duration_ms' \(absent\)",
        None,
        "bad-chapter",
    ),
    Rule("CHAPTER-DURATION-NONPOS", "chapter", r": non-positive duration_ms", None, "shape-dur"),
    Rule(
        "CHAPTER-DURATION-DRIFT",
        "chapter",
        r"differs from manifest duration_ms",
        None,
        "bad-chapter",
    ),
    Rule(
        "CHAPTER-FILE-OMITS-DURATION",
        "chapter",
        r"chapter file omits duration_ms but the manifest entry has",
        None,
        "bad-chapter",
    ),
    Rule(
        "CHAPTER-FILE-UNEXPECTED-DURATION",
        "chapter",
        r"chapter file has duration_ms \d+ but the manifest entry omits it",
        None,
        "shape-dur",
    ),
    Rule(
        "PAGES-WITHOUT-BLOCKS-2X",
        "chapter",
        r"pages without blocks is a 1\.x-only shape",
        None,
        "bad-2.0-partialall",
    ),
    Rule("PAGES-NEED-TIMINGS", "chapter", r"pages marks need timings", None, "bad-2.0-partial"),
    Rule("HEADING-LEVEL", "chapter", r"heading block \d+ has level", None, "bad-chapter"),
    Rule("NFC-TITLE", "chapter", r"title is not NFC-normalized", None, "bad-chapter"),
    Rule("NFC-BLOCK", "chapter", r"block \d+ text is not NFC-normalized", None, "bad-chapter"),
    Rule(
        "NFC-SENTENCE", "chapter", r"sentence \d+ text is not NFC-normalized", None, "bad-chapter"
    ),
    # --- sentences ---
    Rule("SPEAKER-UNKNOWN", "chapter", r"has unknown speaker", None, "bad-chapter"),
    Rule("SPEAKER-2X-ONLY", "chapter", r"2\.0 books allow only", None, "bad-2.0-partial"),
    Rule("SID-ORDER", "chapter", r"sentence sid out of order", None, "bad-chapter"),
    Rule(
        "TIMINGS-IN-UNRENDERED",
        "chapter",
        r"carries timings but the chapter has no duration_ms",
        None,
        "bad-2.0-partial",
    ),
    Rule(
        "TIMINGS-OMITTED",
        "chapter",
        r"omits timings but the chapter has duration_ms",
        None,
        "bad-chapter",
    ),
    Rule("START-NEGATIVE", "chapter", r"has negative start_ms", None, "bad-chapter"),
    Rule("START-GE-END", "chapter", r">=", None, "bad-chapter"),
    Rule("END-EXCEEDS", "chapter", r"exceeds duration_ms", None, "bad-chapter"),
    Rule("FIRST-START", "chapter", r"first sentence start_ms is", None, "bad-chapter"),
    Rule("OVERLAP", "chapter", r"overlaps sentence", None, "bad-chapter"),
    Rule("PAGE-INVALID", "chapter", r"has invalid page", None, "bad-chapter"),
    # --- spans ---
    Rule("SPAN-STYLE", "chapter", r"has unknown style", None, "bad-chapter"),
    Rule("SPAN-NEGATIVE", "chapter", r"has negative offset", None, "bad-chapter"),
    Rule("SPAN-ORDER", "chapter", r"span \d+ has start \d+ > end", None, "bad-chapter"),
    Rule("SPAN-RANGE", "chapter", r"exceeds text length", None, "bad-chapter"),
    # --- pages (pure-pages and blocks+pages share identical texts: one id each) ---
    Rule("PAGES-PAGE-NONPOS", "chapter", r"has non-positive page", None, "bad-chapter"),
    Rule("PAGES-OUTSIDE", "chapter", r"outside duration_ms", None, "bad-chapter"),
    Rule("PAGES-FIRST-START", "chapter", r"first page start_ms is", None, "bad-chapter"),
    Rule("PAGES-START-ORDER", "chapter", r"start_ms \d+ out of order", None, "bad-chapter"),
    Rule("PAGES-PAGE-ORDER", "chapter", r"page \d+ out of order", None, "bad-chapter"),
    Rule("PAGESBLK-EMPTY", "chapter", r"pages present but empty", None, "bad-chapter"),
    Rule("PAGESBLK-DUP", "chapter", r"duplicates page", None, "bad-chapter"),
    Rule("PAGESBLK-NO-SENTENCES", "chapter", r"has no sentences", None, "bad-chapter"),
    Rule(
        "PAGESBLK-MISMATCH-FIRST",
        "chapter",
        r"does not match first sentence on page",
        None,
        "bad-chapter",
    ),
    Rule("PAGESBLK-MISSING-MARK", "chapter", r"missing from pages marks", None, "bad-chapter"),
    # --- audio probe ---
    Rule("AUDIO-CODEC-MP3", "audio", r"expected 'mp3'", None, "bad-audio"),
    Rule("AUDIO-CODEC-AAC", "audio", r"expected 'aac' for \.m4a chapters", None, "bad-audio"),
    Rule("AUDIO-CONTAINER", "audio", r"is not mp4/m4a", None, "bad-audio"),
    Rule(
        "AUDIO-CHANNELS-MISMATCH",
        "audio",
        r"channels \d+ do not match manifest audio",
        None,
        "bad-audio",
    ),
    Rule(
        "AUDIO-RATE-MISMATCH",
        "audio",
        r"sample_rate \d+ does not match manifest audio",
        None,
        "bad-audio",
    ),
    Rule("AUDIO-BITRATE-UNKNOWN", "audio", r"could not determine audio bitrate", None, "bad-audio"),
    Rule("AUDIO-BITRATE-AAC", "audio", r"constrained average required", None, "bad-audio"),
    Rule("AUDIO-BITRATE-MP3", "audio", r"CBR required", None, "bad-audio"),
    Rule("AUDIO-CBR", "audio", r"MP3 frames vary in size", None, "bad-audio"),
    Rule(
        "AUDIO-DURATION-UNKNOWN-AAC",
        "audio",
        r"could not determine audio duration",
        None,
        "bad-audio",
    ),
    Rule(
        "AUDIO-DURATION-UNKNOWN-MP3",
        "audio",
        r"could not determine MP3 duration",
        None,
        "bad-audio",
    ),
    Rule("AUDIO-DURATION-AAC", "audio", r"audio duration \d+ ms differs", None, "bad-audio"),
    Rule("AUDIO-DURATION-MP3", "audio", r"MP3 duration \d+ ms differs", None, "bad-audio"),
    # --- shape (BundleError carried through as "manifest.json: ..." / "<label>: ...") ---
    Rule(
        "SHAPE-MANIFEST-OBJECT",
        "manifest",
        r"manifest must be a JSON object",
        None,
        "shape-manifest",
    ),
    Rule(
        "SHAPE-AUDIO-OBJECT",
        "manifest",
        r"invalid field 'audio' \(need object\)",
        None,
        "unit-only",
    ),
    Rule(
        "SHAPE-AUDIO-REQUIRED",
        "manifest",
        r"missing or invalid required field 'audio' \(need object\)",
        None,
        "shape-audio-req",
    ),
    Rule(
        "SHAPE-VOICES-OBJECT",
        "manifest",
        r"invalid field 'voices' \(need object\)",
        None,
        "shape-voices",
    ),
    Rule(
        "SHAPE-VOICE-ENTRY", "manifest", r"invalid voice entry \(need object\)", None, "shape-voice"
    ),
    Rule(
        "SHAPE-SOURCE-OBJECT",
        "manifest",
        r"invalid field 'source' \(need object\)",
        None,
        "shape-source",
    ),
    Rule(
        "SHAPE-FP-OBJECT",
        "manifest",
        r"invalid field 'render_fingerprint' \(need object\)",
        None,
        "shape-fp",
    ),
    Rule(
        "SHAPE-FP-VOICES-OBJECT",
        "manifest",
        r"render_fingerprint\.voices' \(need object\)",
        None,
        "shape-fp-voices",
    ),
    Rule(
        "SHAPE-FP-VOICES-MAP",
        "manifest",
        r"render_fingerprint\.voices' \(need string map\)",
        None,
        "shape-fp-voicesmap",
    ),
    Rule(
        "SHAPE-FP-SPEEDS-OBJECT",
        "manifest",
        r"render_fingerprint\.speeds' \(need object\)",
        None,
        "shape-fp-speeds",
    ),
    Rule(
        "SHAPE-FP-SPEEDS-MAP",
        "manifest",
        r"render_fingerprint\.speeds' \(need number map\)",
        None,
        "shape-fp-speedsmap",
    ),
    Rule(
        "SHAPE-FP-VERSIONS-OBJECT",
        "manifest",
        r"engine_versions' \(need object\)",
        None,
        "shape-fp-versions",
    ),
    Rule(
        "SHAPE-FP-VERSIONS-MAP",
        "manifest",
        r"engine_versions' \(need string map\)",
        None,
        "shape-fp-versionsmap",
    ),
    Rule(
        "SHAPE-CHAPTER-ENTRY",
        "manifest",
        r"invalid chapter entry \(need object\)",
        None,
        "shape-chapter",
    ),
    Rule(
        "SHAPE-GAIN-OBJECT",
        "manifest",
        r"invalid field 'gain_db' \(need object\)",
        None,
        "shape-gain",
    ),
    Rule(
        "SHAPE-GAIN-MAP",
        "manifest",
        r"invalid field 'gain_db' \(need string to number map\)",
        None,
        "shape-gainmap",
    ),
    Rule(
        "SHAPE-REQ-STR",
        "manifest",
        r"missing or invalid required field .* \(need string\)",
        None,
        "shape-reqstr",
    ),
    Rule(
        "SHAPE-REQ-INT",
        "manifest",
        r"missing or invalid required field .* \(need integer\)",
        None,
        "shape-reqint",
    ),
    Rule(
        "SHAPE-REQ-LIST",
        "manifest",
        r"missing or invalid required field .* \(need list\)",
        None,
        "shape-reqlist",
    ),
    Rule("SHAPE-OPT-STR", "manifest", r"invalid field .* \(need string\)", None, "shape-optstr"),
    Rule("SHAPE-OPT-INT", "manifest", r"invalid field .* \(need integer\)", None, "shape-optint"),
    Rule(
        "SHAPE-OPT-FLOAT", "manifest", r"invalid field .* \(need number\)", None, "shape-optfloat"
    ),
    Rule("SHAPE-OPT-BOOL", "manifest", r"invalid field .* \(need boolean\)", None, "shape-optbool"),
    Rule("SHAPE-SPAN-ENTRY", "chapter", r"invalid span entry \(need object\)", None, "bad-chapter"),
    Rule(
        "SHAPE-SENTENCE-ENTRY",
        "chapter",
        r"invalid sentence entry \(need object\)",
        None,
        "bad-chapter",
    ),
    Rule(
        "SHAPE-SPANS-LIST", "chapter", r"invalid field 'spans' \(need list\)", None, "bad-chapter"
    ),
    Rule(
        "SHAPE-BLOCK-ENTRY", "chapter", r"invalid block entry \(need object\)", None, "bad-chapter"
    ),
    Rule("SHAPE-BLOCK-TYPE", "chapter", r"invalid block type", None, "bad-chapter"),
    Rule(
        "SHAPE-SENTENCES-LIST",
        "chapter",
        r"invalid field 'sentences' \(need list\)",
        None,
        "bad-chapter",
    ),
    Rule("SHAPE-PAGE-ENTRY", "chapter", r"invalid page entry \(need object\)", None, "bad-chapter"),
    Rule(
        "SHAPE-CHAPTER-OBJECT",
        "chapter",
        r"chapter file must be a JSON object",
        None,
        "bad-chapter",
    ),
    Rule(
        "SHAPE-CHAPTER-NEEDS-BLOCKS", "chapter", r"chapter file needs 'blocks'", None, "bad-chapter"
    ),
    Rule(
        "SHAPE-BLOCKS-LIST", "chapter", r"invalid field 'blocks' \(need list\)", None, "bad-chapter"
    ),
    Rule(
        "SHAPE-PAGES-LIST", "chapter", r"invalid field 'pages' \(need list\)", None, "bad-chapter"
    ),
]

# Rules with no portable-JSON fixture (documented exceptions, covered by unit tests):
# - GAIN-NONFINITE ("gain_db.{role} must be a finite number"): needs NaN/Inf,
#   which standard JSON cannot carry (Python parses NaN, serde_json refuses,
#   so no fixture can validate identically). Covered by scribe/tests plus a
#   Rust struct-level unit test in RS2.
# - Non-string dict keys (gain_db/voices/fingerprint maps): JSON object keys
#   are always strings, so the key-type branches are unreachable from files.
# - IO-READ ("cannot read file") and LOAD-MISSING-MANIFEST: OS-dependent;
#   covered by RS4 loader tests, not fixtures.
# - VOICE-NAME-UNKNOWN membership half (known_voices allow-list): shares its
#   template with the shape half, so the rule is covered by bad-manifest;
#   the membership path itself is covered by scribe/tests/test_mv8.py.
# - SHAPE-AUDIO-OBJECT ("invalid field 'audio' (need object)"): defensive
#   branch inside AudioSpec.from_dict, unreachable because the caller only
#   passes dicts; covered by a direct unit call, not a fixture.


def file_of(message: str, bundle_files: set[str]) -> str:
    """Split the leading file label off a validator message.

    Every label is a short relative path (or ``manifest.json``); only the
    message *detail* may embed absolute paths, never the label.
    """
    if message.startswith("manifest.json: "):
        return "manifest.json"
    match = re.match(r"^((?:text|audio|source|cover)/[^:]+): ", message)
    if match:
        return match.group(1)
    raise ValueError(f"cannot split file label: {message!r}")


def rule_for(message: str, file: str) -> Rule:
    """The single RULES entry matching (file, message); fails loudly."""
    hits = [
        rule
        for rule in RULES
        if re.search(rule.fragment, message)
        and (rule.file == "any" or rule.file == file_kind(file))
    ]
    if len(hits) != 1:
        raise ValueError(
            f"rule match != 1 ({len(hits)}) for {message!r}: {[rule.id for rule in hits]}"
        )
    return hits[0]


def file_kind(file: str) -> str:
    if file == "manifest.json":
        return "manifest"
    if file.startswith("text/"):
        return "chapter"
    if file.startswith("audio/"):
        return "audio"
    return "source"


def normalize(message: str, bundle_dir: Path) -> str:
    """Machine-independent form: bundle root becomes <bundle>, / separators."""
    return message.replace(str(bundle_dir), "<bundle>").replace("\\", "/")


def stub_probe(bundle_dir: Path, overrides: dict):
    """Plausible probe derived from the manifest; per-file overrides win.

    The manifest parse is lazy: bundles whose manifest cannot parse never
    reach probing (validation returns early), so the stub must not crash
    on them either.
    """
    by_audio: dict[str, AudioProbe] = {}
    try:
        manifest = Manifest.from_dict(
            json.loads((bundle_dir / "manifest.json").read_text(encoding="utf-8"))
        )
    except Exception:
        manifest = None
    if manifest is not None:
        for entry in manifest.chapters:
            if entry.audio and entry.duration_ms is not None:
                is_aac = Path(entry.audio).suffix == ".m4a"
                audio = manifest.audio
                by_audio[entry.audio] = AudioProbe(
                    codec="aac" if is_aac else "mp3",
                    channels=audio.channels if audio else 1,
                    sample_rate=audio.sample_rate if audio else 24000,
                    bit_rate_bps=(audio.bitrate_kbps if audio else 64) * 1000,
                    bit_rate_from_stream=True,
                    duration_ms=entry.duration_ms,
                    cbr_frames=True,
                    container="mov,mp4,m4a,3gp,3g2,mj2" if is_aac else "mp3",
                )

    def probe(path: Path) -> AudioProbe:
        rel = path.relative_to(bundle_dir).as_posix()
        if rel in overrides:
            spec = overrides[rel]
            if "error" in spec:
                from bundle.validate import AudioProbeError

                raise AudioProbeError(spec["error"])
            if "crash" in spec:
                raise RuntimeError(spec["crash"])
            base = by_audio.get(rel)
            assert base is not None, f"probe override for unrendered {rel}"
            return AudioProbe(
                codec=spec.get("codec", base.codec),
                channels=spec.get("channels", base.channels),
                sample_rate=spec.get("sample_rate", base.sample_rate),
                bit_rate_bps=spec.get("bit_rate_bps", base.bit_rate_bps),
                bit_rate_from_stream=spec.get("bit_rate_from_stream", base.bit_rate_from_stream),
                duration_ms=spec.get("duration_ms", base.duration_ms),
                cbr_frames=spec.get("cbr_frames", base.cbr_frames),
                container=spec.get("container", base.container),
            )
        if rel in by_audio:
            return by_audio[rel]
        from bundle.validate import AudioProbeError

        raise AudioProbeError("ffprobe failed: stub has no audio (test wiring)")

    return probe


def bundle_files(bundle_dir: Path) -> set[str]:
    """Every relative file path in the bundle (for file-label splitting)."""
    return {
        path.relative_to(bundle_dir).as_posix()
        for path in sorted(bundle_dir.rglob("*"))
        if path.is_file()
    }


def export_fixture(name: str) -> dict:
    bundle_dir = FIXTURES / name
    overrides_path = PROBES / f"{name}.json"
    overrides = (
        json.loads(overrides_path.read_text(encoding="utf-8")) if overrides_path.is_file() else {}
    )
    result = validate_bundle(bundle_dir, probe=stub_probe(bundle_dir, overrides))
    files = bundle_files(bundle_dir)
    errors = []
    for message in result.errors:
        norm = normalize(message, bundle_dir)
        file = file_of(norm, {f.replace("\\", "/") for f in files})
        rule = rule_for(norm, file)
        entry: dict[str, object] = {
            "file": file,
            "rule": rule.id,
            "message": norm,
        }
        if rule.prefix is not None:
            entry["prefix"] = rule.prefix.replace("{file}", file)
        errors.append(entry)
    errors.sort(key=lambda entry: (entry["file"], entry["rule"], entry["message"]))
    return {"valid": not errors, "errors": errors}


def render_docs() -> str:
    """The rule-table section of docs/rust-port.md, generated from RULES."""
    lines = [
        "## Rule table (generated by `scribe/dev/export_expected.py --render-docs`)",
        "",
        "Match order is specific-before-general; the exporter asserts exactly",
        "one rule per message. `prefix` set means only the head is compared",
        "(parser or OS detail text varies).",
        "",
        "| Rule | File | Compared | Covering fixture |",
        "| --- | --- | --- | --- |",
    ]
    for rule in RULES:
        compared = f"prefix `{rule.prefix}`" if rule.prefix else "exact"
        lines.append(f"| {rule.id} | {rule.file} | {compared} | `{rule.fixture}` |")
    lines += [
        "",
        "No portable-JSON fixture (covered by unit tests, see the module docstring):",
        "`GAIN-NONFINITE`, non-string dict keys, `IO-READ`,",
        "`LOAD-MISSING-MANIFEST`, `SHAPE-AUDIO-OBJECT`.",
        "",
    ]
    return "\n".join(lines)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Export parity expected files.")
    parser.add_argument("--render-docs", action="store_true")
    parser.add_argument("--fixture", default=None)
    args = parser.parse_args(argv)
    if args.render_docs:
        print(render_docs())
        return 0
    names = (
        [args.fixture]
        if args.fixture
        else sorted(path.name for path in FIXTURES.iterdir() if (path / "manifest.json").is_file())
    )
    EXPECTED.mkdir(parents=True, exist_ok=True)
    for name in names:
        data = export_fixture(name)
        out = EXPECTED / f"{name}.json"
        # newline="\n": expected files must be byte-identical on Windows
        # and Linux checkouts.
        out.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8", newline="\n")
        status = "valid" if data["valid"] else f"{len(data['errors'])} errors"
        print(f"{name}: {status}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
