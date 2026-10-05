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

"""Slice 6 UI9: design system and polish (tokens, contrast, components, offline).

Visual-only slice: no API or behavior change. These tests read the served
``ui/static/index.html`` (no browser, no model) and assert:

- the ``:root`` tokens match the spec (paper/ink/oxblood) with dark-mode
  inversion flipping the same variables (media query, not a second palette);
- every color/spacing/radius/font resolves to ``:root`` (no one-off hex,
  no extra hues: sage is success-only, oxblood is primary-action/position
  only, the amber ``--warn`` and duplicate ``--success`` are gone);
- WCAG contrast of body/muted/accent/success text is >= 4.5:1 in both
  light and dark values;
- exactly the five components exist (table/stepper/tray/player-row/chips)
  with a documented utility allowlist; ``.card``/``.modal``/``.dialog``
  style sixth components fail the test;
- the page stays offline (no remote requests, system font stacks only);
- accessibility basics (skip link, focus rings, labels, live regions);
- copy conventions (``58:32`` clocks, ``24.8x RTF``, ``file: rule: message``
  errors, named empty states, verb-first buttons).
"""

from __future__ import annotations

import re
from pathlib import Path

from ui import security

# --- helpers --------------------------------------------------------------


def _index_text() -> str:
    index = Path(security.__file__).resolve().parent / "static" / "index.html"
    return index.read_text(encoding="utf-8")


def _style_block(text: str) -> str:
    return text.split("<style>")[1].split("</style>")[0]


def _root_vars(block: str) -> dict[str, str]:
    return dict(re.findall(r"--([\w-]+)\s*:\s*([^;]+);", block))


def _light_vars(text: str) -> dict[str, str]:
    return _root_vars(_style_block(text).split("@media")[0])


def _dark_vars(text: str) -> dict[str, str]:
    style = _style_block(text)
    assert "@media (prefers-color-scheme: dark)" in style, "dark inversion missing"
    dark_part = style.split("@media (prefers-color-scheme: dark)")[1]
    return _root_vars(dark_part)


def _srgb_to_lin(c: int) -> float:
    v = c / 255.0
    return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4


def _luminance(hexcol: str) -> float:
    h = hexcol.strip().lstrip("#")
    if len(h) == 3:
        h = "".join(ch * 2 for ch in h)
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * _srgb_to_lin(r) + 0.7152 * _srgb_to_lin(g) + 0.0722 * _srgb_to_lin(b)


def _ratio(fg: str, bg: str) -> float:
    a, b = _luminance(fg), _luminance(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)


# --- tokens -----------------------------------------------------------------


def test_design_tokens_match_spec() -> None:
    text = _index_text()
    light = _light_vars(text)
    assert light["paper"].strip() == "#FAF7F0", light.get("paper")
    assert light["ink"].strip() == "#1A1714", light.get("ink")
    assert light["oxblood"].strip() == "#7B2D26", light.get("oxblood")
    assert light["sage"].strip() == "#5F7166", light.get("sage")
    assert light["muted"].strip() == "#6B625C", light.get("muted")
    assert light["hairline"].strip() == "#E7E0D4", light.get("hairline")
    assert light["raised"].strip() == "#FFFFFF", light.get("raised")
    assert light["on-accent"].strip() == "#FFFFFF", light.get("on-accent")
    assert light["radius"].strip() == "4px", light.get("radius")
    assert light["space"].strip() == "8px", light.get("space")
    assert light["line"].strip() == "1px", light.get("line")
    assert "--success" not in light, "duplicate success token must go (sage is success)"
    assert "--warn" not in light, "amber warn is an extra hue (use ink)"
    assert "font-sans" in light and "font-serif" in light


def test_dark_mode_flips_same_variables() -> None:
    text = _index_text()
    light = _light_vars(text)
    dark = _dark_vars(text)
    for key in ("paper", "raised", "ink", "muted", "oxblood", "sage", "hairline", "on-accent"):
        assert key in dark, f"dark must flip --{key}"
    assert set(dark) <= set(light), "dark must not invent a second palette"
    assert dark["paper"].strip() == "#1A1714"
    assert dark["ink"].strip() == "#FAF7F0"
    assert dark["muted"].strip() == "#B8AEA6"
    assert dark["oxblood"].strip() == "#E8AFA6"
    assert dark["sage"].strip() == "#9DB8A6"
    assert dark["hairline"].strip() == "#3A342E"
    assert dark["on-accent"].strip() == "#1A1714"


def test_no_one_off_colors_spacing_radius_fonts() -> None:
    text = _index_text()
    style = _style_block(text)
    light = _light_vars(text)
    dark = _dark_vars(text)
    allowed_hex = {
        v.strip().upper()
        for source in (light, dark)
        for v in source.values()
        if v.strip().startswith("#")
    }
    for found in set(re.findall(r"#[0-9A-Fa-f]{3,8}", text)):
        assert found.upper() in allowed_hex, f"one-off hex {found} must resolve to :root"
    assert "#EFF2ED" not in text and "#fff" not in text.lower().replace("#ffffff", "")
    assert "999px" not in style, "pill radii must use var(--radius)"
    assert "rgba(28,25,23" not in style, "old shadow rgb must go"
    assert style.count("box-shadow") == 1, "one elevation level only (conflict banner)"
    assert "#cast-conflict" in style, "the one shadow belongs to the conflict banner"
    for match in re.findall(r"font-family\s*:\s*([^;]+);", style):
        assert "var(--font-" in match, f"font-family must use a token: {match}"
    for match in re.findall(r"border-radius\s*:\s*([^;]+);", style):
        assert "var(--radius)" in match, f"radius must use var(--radius): {match}"


def test_contrast_light_and_dark() -> None:
    text = _index_text()
    light = _light_vars(text)
    dark = _dark_vars(text)
    cases = [
        ("light body", light["ink"], light["paper"]),
        ("light muted", light["muted"], light["paper"]),
        ("light accent", light["oxblood"], light["paper"]),
        ("light success", light["sage"], light["paper"]),
        ("light button", light["on-accent"], light["oxblood"]),
        ("dark body", dark["ink"], dark["paper"]),
        ("dark muted", dark["muted"], dark["paper"]),
        ("dark accent", dark["oxblood"], dark["paper"]),
        ("dark success", dark["sage"], dark["paper"]),
        ("dark button", dark["on-accent"], dark["oxblood"]),
    ]
    for name, fg, bg in cases:
        ratio = _ratio(fg.strip(), bg.strip())
        assert ratio >= 4.5, f"{name} {fg}/{bg} is {ratio:.2f}, need >= 4.5:1"


# --- five components ----------------------------------------------------------

#: The UI9 inventory. Five components only; everything else below is a
#: documented utility (typography, layout, state), never a sixth component.
#: ``.toast`` is the tray's transient notice (same store, same poll), not a
#: modal; ``.empty``/``pre.errors`` are empty-state/error-list typography;
#: ``.row-actions``/``.muted``/``.num`` are layout/type helpers; ``.skip``
#: is the accessibility skip link; ``.ghost`` is the secondary button
#: variant; ``.on``/``.good``/``.busy``/``.done``/``.active``/``.over`` are
#: state modifiers; ``.step-title``/``.step-body``/``.tray-row``/``.tray-prog``
#: are sub-elements of their component; ``.brand`` scopes the serif wordmark.
COMPONENTS = ("auloud-table", "stepper", "tray", "player-row", "chips", "chip")
UTILITIES = (
    "empty", "errors", "row-actions", "muted", "num", "skip", "ghost",
    "on", "good", "busy", "done", "active", "over",
    "step-title", "step-body", "tray-row", "tray-prog", "toast", "brand",
)


def test_five_components_present_and_no_sixth() -> None:
    text = _index_text()
    style = _style_block(text)
    for needle in (
        "auloud-table", "stepper", 'id="tray"',
        'id="player-row"', 'class="chip',
    ):
        assert needle in text, needle
    assert ".chip {" in style, "chip CSS rule missing"
    for banned in ('class="card"', ".card {", ".card,", 'class="modal"', ".modal",
                    'role="dialog"', "<dialog", ".dialog {", ".popover", 'class="popover"'):
        assert banned not in text, f"sixth component {banned} must go"
    css_classes = set(re.findall(r"\.([A-Za-z][\w-]*)", style))
    allowed = set(COMPONENTS) | set(UTILITIES)
    extra = css_classes - allowed
    assert not extra, f"new classes outside the inventory: {sorted(extra)}"


# --- offline ------------------------------------------------------------------


def test_offline_no_external_requests() -> None:
    text = _index_text()
    assert "http" not in text, "no remote URLs at all (offline)"
    lowered = text.lower()
    for needle in ("http://", "https://", "src=", "srcset", "@import", "@font-face"):
        assert needle not in lowered, f"offline violator: {needle}"
    for needle in ("url(http", "url(//", "url(data:"):
        assert needle not in lowered, f"offline violator: {needle}"
    # URL.createObjectURL(blob) builds local blob URLs for audition clips
    # (offline); any other url( would be a remote fetch.
    bare_urls = re.findall(r"(?<!object)url\(", lowered)
    assert not bare_urls, f"offline url( outside createObjectURL: {bare_urls}"
    assert 'href="#main"' in text or "href='#main'" in text
    assert "goog" not in lowered and "cdn" not in lowered
    style = _style_block(text)
    assert "Georgia" in style and "system-ui" in style, "system stacks only"


# --- accessibility --------------------------------------------------------------


def test_accessibility_basics() -> None:
    text = _index_text()
    assert 'href="#main"' in text, "skip link missing"
    assert 'id="main"' in text, "<main> missing"
    assert "outline:none" not in text.replace(" ", ""), "never kill focus without replacement"
    assert ":focus-visible" in text, "visible focus rings missing"
    for sel in ("select:focus-visible", "audio:focus-visible"):
        assert sel in text, f"{sel} missing"
    for label in ('aria-label="Pick an EPUB or PDF"', 'for="transfer-path"',
                    'id="render-chapters-sum"', 'for="render-device"',
                   'aria-label="Voice for', 'aria-label="Speed for',
                   'for="cast-first-person"', 'aria-label="Override chapter"'):
        assert label in text, f"programmatic label missing: {label}"
    assert "<button" in text and "new Promise" in text  # real buttons, async actions
    assert "<select" in text, "dropdowns must stay native selects"
    assert 'id="tray" role="status" aria-live="polite"' in text
    assert 'id="validate-results" aria-live="assertive"' in text
    assert 'id="transfer-error" role="alert"' in text
    assert 'id="render-error" role="alert"' in text
    assert 'id="cast-errors" hidden role="alert"' in text
    assert 'id="player-row" hidden aria-live="polite"' in text


# --- copy -----------------------------------------------------------------------


def test_copy_conventions() -> None:
    text = _index_text()
    assert "padStart(2" in text, "clocks pad to 58:32 shape"
    assert 'toFixed(1) + "x RTF"' in text, "RTF renders as 24.8x RTF"
    for needle in ('data.file + ": " + data.rule + ": " + data.message',
                     'job.error.file + ": " + job.error.rule',
                     "data.draft_error.file",
                     "data.draft_error.rule"):
        assert needle in text, needle
    for empty in ("No books yet", "Pick a book", "No voices listed",
                  "No jobs yet", "Draft has not finished yet", "Nothing valid yet"):
        assert empty in text, f"empty state missing: {empty}"
    assert ">OK<" not in text and ">Submit<" not in text, "verb-first buttons only"
    for label in ("Upload and draft", "Start render", "Regenerate all",
                  "Validate now", "Copy to tablet folder", "Save cast",
                  "Clear selection", "Reload cast", "Remove override"):
        assert label in text, f"verb-first label missing: {label}"
