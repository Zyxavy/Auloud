# Auloud Scribe - turns ebooks into multi-voice audiobooks (PC tool).
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

"""LG1: generate the license files that ship beside the app.

Writes into ``<artifact>/licenses``:

* ``NOTICE.txt``: our own terms, the bundled programs and the counts.
* ``THIRD_PARTY_LICENSES.txt``: one row per component.
* ``SOURCE_OFFER.txt``: the GPL-3.0 section 6b written offer.
* ``sbom.cdx.json``: CycloneDX 1.5, both ecosystems.
* ``texts/<SPDX>.txt``: the canonical text of every license in use.
* ``third-party/<ecosystem>/<name>-<version>/``: the component's own files.
* ``MANIFEST.sha256``: one sha256sum line per generated file.

Sources of truth: ``cargo tree`` plus ``cargo metadata`` for the crates that
link into ``scribe-ui.exe``, the installed ``dist-info`` of the embedded
helper for the Python wheels, and the helper manifest plus the pinned ffmpeg
archive for the two programs the bundle carries. The allowed license atoms
come from ``scribe-rs/deny.toml`` (cargo-deny stays the enforcer) and
``PYTHON_ALLOWED`` here; nothing is written until every component resolves to
an allowed license and every atom has a text, so a new dependency stops the
build instead of shipping unattributed.

Run from ``scribe/``::

    uv run python dev/gen_licenses.py --artifact ../logs/portable/Auloud-Scribe-3.0.0
    uv run python dev/gen_licenses.py --harvest-texts dev/license-texts
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.parser import Parser
from pathlib import Path

SCRIBE_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = SCRIBE_DIR.parent

APP_NAME = "Auloud Scribe"
COPYRIGHT = "Copyright (C) 2026 Zyxavy"
CONTACT_NAME = "Zyxavy"
CONTACT_EMAIL = "kentandrewparejas@gmail.com"
CONTACT_URL = "https://github.com/Zyxavy/Auloud"

#: Python distributions allowed in the helper (atoms). The Rust side reads
#: ``deny.toml`` instead, so cargo-deny and this generator cannot disagree.
PYTHON_ALLOWED = {
    "0BSD",
    "AGPL-3.0",
    "AGPL-3.0-or-later",
    "Apache-2.0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "BSL-1.0",
    "CC0-1.0",
    "GPL-3.0-or-later",
    "ISC",
    "LGPL-2.1-or-later",
    # The CUDA 12 wheels NVIDIA publishes declare this reference themselves and
    # ship their own texts, which the artifact copies into third-party/ (D-188).
    "LicenseRef-NVIDIA-Proprietary",
    "MIT",
    "MPL-2.0",
    "PSF-2.0",
    "Unicode-3.0",
    "Unlicense",
    "Zlib",
}

#: Components whose installed metadata declares no license at all. Each one
#: needs a decision here plus a row in ``docs/08-Licenses.md``.
LICENSE_OVERRIDES = {
    "espeakng-loader": (
        "MIT",
        "the loader wrapper is MIT (PyPI project metadata); the espeak-ng.dll "
        "and espeak-ng-data it carries are GPL-3.0-or-later (docs/08-Licenses.md "
        "section 4)",
    ),
    "mdurl": ("MIT", "declared by the OSI classifier in METADATA"),
    "pymupdf": (
        "AGPL-3.0",
        "dual licensed (AGPL-3.0 or an Artifex commercial license); Scribe "
        "relies on AGPL-3.0, see docs/08-Licenses.md section 3",
    ),
}

#: Native code a distribution bundles, which its own METADATA does not name.
#: Each entry adds one component for the native part; a ``None`` license means
#: no SPDX identifier exists, so the row carries the name alone (the Microsoft
#: redistributable runtime). A new bundled native library needs a row here plus
#: a note in ``docs/08-Licenses.md`` section 3.
BUNDLED_NATIVE: dict[str, list[tuple[str, str | None, str, str]]] = {
    "soundfile": [
        (
            "libsndfile",
            "LGPL-2.1-or-later",
            "the DLL and its COPYING under _soundfile_data",
            "_soundfile_data/COPYING",
        ),
    ],
    "numpy": [
        (
            "OpenBLAS",
            "BSD-3-Clause",
            "libscipy_openblas64_*.dll in numpy.libs",
            "",
        ),
        (
            "Microsoft Visual C++ runtime",
            None,
            "msvcp140-*.dll in numpy.libs, redistributed as a runtime",
            "",
        ),
    ],
}

LINK_FILES = re.compile(r"(?i)^(licen[cs]e|copying|notice|copyright)")


@dataclass
class Component:
    """One shipped component: our code, a crate, a wheel or a program."""

    ecosystem: str
    name: str
    version: str
    declared: str
    atoms: list[str]
    files: list[tuple[Path, str]] = field(default_factory=list)
    note: str = ""

    @property
    def slug(self) -> str:
        return f"{self.name}-{self.version}" if self.version else self.name


# --- pure helpers -------------------------------------------------------


def choices(expression: str) -> list[list[str]]:
    """An SPDX-ish expression as the OR branches of AND atoms.

    Parentheses are kept, so ``(MIT OR Apache-2.0) AND Unicode-3.0`` is the
    two ways to comply: MIT with Unicode-3.0, or Apache-2.0 with Unicode-3.0.
    The legacy ``MIT/Apache-2.0`` form means the same as OR.
    """
    tokens = TOKEN.findall(expression.replace("/", " OR "))
    if not tokens:
        return []
    node, _ = _parse_or(tokens, 0)
    return _branch_atoms(node)


#: One SPDX-ish token: a paren, the two operators, or an atom.
TOKEN = re.compile(r"\(|\)|\bAND\b|\bOR\b|[^()\s]+")


def _parse_or(tokens: list[str], index: int) -> tuple[tuple, int]:
    node, index = _parse_and(tokens, index)
    while index < len(tokens) and tokens[index] == "OR":
        right, index = _parse_and(tokens, index + 1)
        node = ("or", node, right)
    return node, index


def _parse_and(tokens: list[str], index: int) -> tuple[tuple, int]:
    node, index = _parse_term(tokens, index)
    while index < len(tokens) and tokens[index] == "AND":
        right, index = _parse_term(tokens, index + 1)
        node = ("and", node, right)
    return node, index


def _parse_term(tokens: list[str], index: int) -> tuple[tuple, int]:
    if tokens[index] == "(":
        node, index = _parse_or(tokens, index + 1)
        return node, index + 1  # past the ")"
    return ("atom", tokens[index]), index + 1


def _branch_atoms(node: tuple) -> list[list[str]]:
    """Every full OR branch of a parsed node (AND distributes over OR)."""
    kind = node[0]
    if kind == "atom":
        return [[node[1]]]
    if kind == "or":
        return _branch_atoms(node[1]) + _branch_atoms(node[2])
    return [
        left + right
        for left in _branch_atoms(node[1])
        for right in _branch_atoms(node[2])
    ]


def choose_atoms(expression: str, allowed: set[str]) -> list[str] | None:
    """Return the atoms of the first fully allowed OR branch, else None."""
    for atoms in choices(expression):
        if all(atom in allowed for atom in atoms):
            return atoms
    return None


#: Markers that identify a license text shipped without an SPDX name.
#: Whitespace is normalised before matching, so a reflowed copy still matches.
SNIFFERS = [
    ("Apache-2.0", "Apache License Version 2.0"),
    ("AGPL-3.0-or-later", "GNU AFFERO GENERAL PUBLIC LICENSE Version 3"),
    ("GPL-3.0-or-later", "GNU GENERAL PUBLIC LICENSE Version 3"),
    ("LGPL-2.1-or-later", "GNU LESSER GENERAL PUBLIC LICENSE Version 2.1"),
    ("MPL-2.0", "Mozilla Public License Version 2.0"),
    ("BSL-1.0", "Boost Software License - Version 1.0"),
    ("OFL-1.1", "SIL OPEN FONT LICENSE Version 1.1"),
    ("Ubuntu-font-1.0", "UBUNTU FONT LICENCE Version 1.0"),
    ("CC0-1.0", "CC0 1.0 Universal"),
    ("Unlicense", "This is free and unencumbered software released into the public domain"),
    ("Zlib", "This software is provided 'as-is', without any express or implied warranty"),
    ("Zlib", 'This software is provided "as-is", without any express or implied warranty'),
    # 0BSD shares the ISC sentence, so it comes first and is told apart by
    # its own disclaimer.
    (
        "0BSD",
        "Permission to use, copy, modify, and/or distribute this software for "
        "any purpose with or without fee is hereby granted. THE SOFTWARE IS PROVIDED",
    ),
    (
        "ISC",
        "Permission to use, copy, modify, and distribute this software for any "
        "purpose with or without fee is hereby granted",
    ),
    (
        "ISC",
        "Permission to use, copy, modify, and/or distribute this software for any "
        "purpose with or without fee is hereby granted",
    ),
    # The third clause is what separates 3-clause from 2-clause.
    ("BSD-3-Clause", "3. Neither the name of"),
    ("BSD-2-Clause", "Redistribution and use in source and binary forms"),
    (
        "MIT",
        "Permission is hereby granted, free of charge, to any person obtaining "
        "a copy of this software and associated documentation files",
    ),
]


def sniff_atom(text: str) -> str | None:
    """Name the license a text file holds, by its wording."""
    flat = re.sub(r"\s+", " ", text)
    for atom, marker in SNIFFERS:
        if marker in flat:
            return atom
    return None


def classifier_atom(classifiers: list[str]) -> str | None:
    """Map a ``License ::`` trove classifier to an atom."""
    for item in classifiers:
        if not item.startswith("License ::"):
            continue
        if item.endswith("MIT License"):
            return "MIT"
        if item.endswith("Apache Software License"):
            return "Apache-2.0"
        if item.endswith("BSD License"):
            return "BSD-3-Clause"
        if "GNU Affero General Public License v3" in item or "AGPLv3" in item:
            return "AGPL-3.0-or-later"
        if "GNU General Public License v3" in item or "GPLv3" in item:
            return "GPL-3.0-or-later"
        if "Python Software Foundation License" in item:
            return "PSF-2.0"
    return None


def short_license_name(value: str) -> str | None:
    """Map a short ``License:`` field value to an atom."""
    name = value.strip()
    if len(name) > 64 or "\n" in name:
        return None
    table = {
        "mit": "MIT",
        "mit license": "MIT",
        "the mit license": "MIT",
        "apache 2.0": "Apache-2.0",
        "apache-2.0": "Apache-2.0",
        "apache 2": "Apache-2.0",
        "apache license 2.0": "Apache-2.0",
        "apache software license": "Apache-2.0",
        "bsd": "BSD-3-Clause",
        "bsd-3-clause": "BSD-3-Clause",
        "bsd-3-clause license": "BSD-3-Clause",
        "3-clause bsd license": "BSD-3-Clause",
        "isc": "ISC",
        "isc license": "ISC",
        "mpl-2.0": "MPL-2.0",
        "zlib": "Zlib",
        "unlicense": "Unlicense",
        "gplv3": "GPL-3.0-or-later",
        "gpl-3.0-or-later": "GPL-3.0-or-later",
        "agpl-3.0-or-later": "AGPL-3.0-or-later",
        "psf-2.0": "PSF-2.0",
        "the unlicense (unlicense)": "Unlicense",
    }
    return table.get(name.lower())


def purl(component: Component) -> str:
    name = re.sub(r"[^a-z0-9.]+", "-", component.name.lower())
    at = f"@{component.version}" if component.version else ""
    if component.ecosystem == "pypi":
        return f"pkg:pypi/{name}{at}"
    if component.ecosystem == "cargo":
        return f"pkg:cargo/{name}{at}"
    return f"pkg:generic/{name}{at}"


def license_files(directory: Path) -> list[Path]:
    """License-ish files at the top level of ``directory``."""
    if not directory.is_dir():
        return []
    return sorted(
        path
        for path in directory.iterdir()
        if path.is_file() and LINK_FILES.match(path.name)
    )


# --- collectors ---------------------------------------------------------


def run(command: list[str], cwd: Path) -> str:
    completed = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    if completed.returncode != 0:
        raise SystemExit(f"{' '.join(command)} failed: {completed.stderr.strip()[-400:]}")
    return completed.stdout


def cargo_allowed() -> set[str]:
    """The cargo-deny allow-list, so the two never diverge."""
    config = tomllib.loads((REPO_DIR / "scribe-rs" / "deny.toml").read_text(encoding="utf-8"))
    return set(config["licenses"]["allow"])


def rust_components() -> list[Component]:
    """The crates that link into ``scribe-ui.exe`` (normal and build edges)."""
    root = REPO_DIR / "scribe-rs"
    tree = run(
        [
            "cargo",
            "tree",
            "-p",
            "scribe-ui",
            "--edges",
            "normal,build",
            "--prefix",
            "none",
            "--format",
            "{p}|{l}",
        ],
        root,
    )
    metadata = json.loads(run(["cargo", "metadata", "--format-version", "1"], root))
    dirs = {
        (package["name"], package["version"]): Path(package["manifest_path"]).parent
        for package in metadata["packages"]
    }
    members = {package["name"] for package in metadata["packages"] if package["source"] is None}

    allowed = cargo_allowed()
    components: dict[tuple[str, str], Component] = {}
    for line in tree.splitlines():
        line = re.sub(r" \(\*\)$", "", line.rstrip())
        package, _, expression = line.rpartition("|")
        match = re.match(r"^(?P<name>\S+) v(?P<version>\S+)", package)
        if not match or not expression:
            continue
        name, version = match.group("name"), match.group("version")
        if name in members or (name, version) in components:
            # cargo tree prints a crate once per path that reaches it
            continue
        atoms = choose_atoms(expression, allowed)
        if atoms is None:
            raise SystemExit(
                f"cargo: {name} {version} declares {expression!r}, "
                "which deny.toml does not allow"
            )
        source = dirs.get((name, version))
        files = (
            [(path, Path(path.name)) for path in license_files(source)]
            if source is not None
            else []
        )
        note = ""
        if len(choices(expression)) > 1:
            note = f"we rely on {' AND '.join(atoms)}"
        components[(name, version)] = Component(
            "cargo", name, version, expression, atoms, files, note
        )
    return list(components.values())


def python_components(site_packages: Path) -> list[Component]:
    """Every installed distribution in the helper's site-packages."""
    components = []
    for info in sorted(site_packages.glob("*.dist-info")):
        text = (info / "METADATA").read_text(encoding="utf-8", errors="replace")
        message = Parser().parsestr(text)
        name = message.get("Name", "") or info.name.rsplit("-", 1)[0]
        version = message.get("Version", "") or info.name.rsplit("-", 1)[1]
        key = name.replace("_", "-").lower()
        expression = message.get("License-Expression") or ""
        classifiers = message.get_all("Classifier") or []
        note = ""
        if not expression:
            field = (message.get("License") or "").strip()
            # A short field is a name or a small expression ("MPL-2.0 AND
            # MIT"); a long one is the license text pasted in.
            if field and len(field) <= 64 and "\n" not in field:
                expression = (
                    short_license_name(field)
                    or (field if choose_atoms(field, PYTHON_ALLOWED) else "")
                    or (sniff_atom(field) or "")
                )
                if expression:
                    note = "from the License field"
        if not expression:
            expression = classifier_atom(classifiers) or ""
            if expression:
                note = "from the OSI classifier"
        atoms = choose_atoms(expression, PYTHON_ALLOWED) if expression else None
        if atoms is None and key in LICENSE_OVERRIDES:
            atom, note = LICENSE_OVERRIDES[key]
            atoms = [atom]
            expression = expression or atom
        files = []
        for entry in message.get_all("License-File") or []:
            relative = Path(entry.replace("\\", "/"))
            if relative.is_absolute() or ".." in relative.parts:
                raise SystemExit(f"pypi: {info.name} lists an unsafe License-File path: {entry}")
            for candidate in (
                info / "licenses" / relative,
                site_packages / relative,
                info / relative,
            ):
                if candidate.is_file():
                    files.append((candidate, Path("licenses") / relative))
                    break
        if not files:
            for path in license_files(info):
                files.append((path, Path(path.name)))
            if not files and info.is_dir():
                for path in license_files(info.parent / re.sub(r"[-.].*$", "", name)):
                    files.append((path, Path(path.name)))
        if not files and not expression:
            raise SystemExit(f"pypi: {info.name} declares no license and ships no text")
        if atoms is None:
            sniffed = None
            for path, _ in files:
                sniffed = sniff_atom(path.read_text(encoding="utf-8", errors="replace"))
                if sniffed:
                    break
            if sniffed is None or sniffed not in PYTHON_ALLOWED:
                raise SystemExit(
                    f"pypi: {info.name} {version} declares {expression!r} "
                    f"(text identifies {sniffed!r}), which PYTHON_ALLOWED does not allow"
                )
            expression = sniffed
            atoms = [sniffed]
            note = "identified from the shipped text"
        if len(choices(expression)) > 1:
            note = "; ".join([note, f"we rely on {' AND '.join(atoms)}"]).lstrip("; ")
        components.append(Component("pypi", name, version, expression, atoms, files, note))
    return components


def native_components(site_packages: Path, wheels: list[Component]) -> list[Component]:
    """Native code a distribution bundles, added per distribution."""
    components = []
    for wheel in wheels:
        key = re.sub(r"[-_.]+", "-", wheel.name).lower()
        for native, license_id, where, relative in BUNDLED_NATIVE.get(key, []):
            native_files = []
            if relative:
                path = site_packages / relative
                if not path.is_file():
                    raise SystemExit(f"pypi: {wheel.name} bundles {relative}, which is missing")
                native_files = [(path, Path(path.name))]
            components.append(
                Component(
                    "native",
                    native,
                    "",
                    license_id or f"{native} (no SPDX identifier)",
                    [license_id] if license_id else [],
                    native_files,
                    f"{where}; bundled with {wheel.name} {wheel.version}",
                )
            )
    return components


def program_components(embedded: Path, ffmpeg_archive: Path, work: Path) -> list[Component]:
    """The two programs the bundle carries: CPython and ffmpeg."""
    manifest = json.loads((embedded / "helper-manifest.json").read_text(encoding="utf-8"))
    python_version = manifest["python"]["version"]
    ffmpeg_version = manifest["ffmpeg"]["version"]

    python_license = embedded / "python" / "LICENSE.txt"
    if not python_license.is_file():
        raise SystemExit(f"no CPython license text at {python_license}")

    with zipfile.ZipFile(ffmpeg_archive) as archive:
        entries = [
            name
            for name in archive.namelist()
            if LINK_FILES.match(Path(name).name) and name.count("/") <= 2
        ]
        if not entries:
            raise SystemExit(f"no license text in {ffmpeg_archive.name}")
        targets = []
        for name in sorted(entries):
            target = work / Path(name).name
            target.write_bytes(archive.read(name))
            targets.append(target)

    # The row's claim comes from the archive's own text, so pointing the
    # packer at a different build (an LGPL one, say) stops instead of
    # shipping a wrong sentence.
    sniffed = {sniff_atom(text_of(path)) for path in targets}
    if sniffed != {"GPL-3.0-or-later"}:
        found = sorted(atom or "unrecognised" for atom in sniffed)
        raise SystemExit(
            f"ffmpeg: {ffmpeg_archive.name} licenses read as {found}; the program "
            "row claims GPL-3.0-or-later, so another build needs a decision"
        )

    return [
        Component(
            "program",
            "python",
            python_version,
            "PSF-2.0",
            ["PSF-2.0"],
            [(python_license, Path("LICENSE.txt"))],
            "the embedded interpreter (python-build-standalone)",
        ),
        Component(
            "program",
            "ffmpeg",
            ffmpeg_version,
            "GPL-3.0-or-later",
            ["GPL-3.0-or-later"],
            [(path, Path(path.name)) for path in targets],
            "run as a separate process, never linked; ffprobe ships in the same pack",
        ),
    ]


# --- writers ------------------------------------------------------------


def text_of(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace").replace("\r\n", "\n")


def write_texts(components: list[Component], canonical: Path, out: Path) -> dict[str, Path]:
    """Copy one canonical text per atom, taken from a component when it has one."""
    wanted = sorted({atom for component in components for atom in component.atoms})
    written: dict[str, Path] = {}
    for atom in wanted:
        target = out / "texts" / f"{atom}.txt"
        target.parent.mkdir(parents=True, exist_ok=True)
        source = canonical / f"{atom}.txt"
        if source.is_file():
            shutil.copyfile(source, target)
        else:
            harvested = None
            for component in components:
                if atom not in component.atoms:
                    continue
                for path, _ in component.files:
                    if sniff_atom(text_of(path)) == atom:
                        harvested = path
                        break
                if harvested:
                    break
            if harvested is None:
                raise SystemExit(f"no text for {atom}: add it to {canonical}")
            shutil.copyfile(harvested, target)
        written[atom] = target
    return written


def copy_component_files(components: list[Component], out: Path) -> None:
    for component in components:
        if not component.files:
            continue
        target = out / "third-party" / component.ecosystem / component.slug
        for source, name in component.files:
            destination = target / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)


def component_table(components: list[Component]) -> str:
    lines = []
    for component in components:
        where = (
            f"third-party/{component.ecosystem}/{component.slug}/"
            if component.files
            else "texts/"
        )
        note = f"  ({component.note})" if component.note else ""
        head = f"{component.name} {component.version}".strip()
        lines.append(f"{head:<42} {component.declared}{note}")
        lines.append(f"    {where}")
    return "\n".join(lines)


def write_third_party(
    components: list[Component], out: Path, tree_size: int, wheel_count: int
) -> None:
    groups = {
        "cargo": "Rust crates (cargo tree -p scribe-ui --edges normal,build)",
        "pypi": "Python distributions (helper site-packages)",
        "native": "Native code bundled by a distribution",
        "program": "Programs carried by the bundle",
    }
    parts = [
        f"{APP_NAME}: third-party components",
        "=" * (len(APP_NAME) + len(": third-party components")),
        "",
        "Generated by scribe/dev/gen_licenses.py from the locked sets; do not edit.",
        f"Rust crates: {tree_size}. Python distributions: {wheel_count}. Programs: 2.",
        "",
        "Each row is a component, the license we rely on, and where its own text is.",
        "A row pointing at texts/ means the component ships no license file of its own.",
        "",
    ]
    for ecosystem, title in groups.items():
        chosen = [component for component in components if component.ecosystem == ecosystem]
        if not chosen:
            continue
        parts += [title, "-" * len(title), "", component_table(chosen), ""]
    (out / "THIRD_PARTY_LICENSES.txt").write_text("\n".join(parts), encoding="utf-8")


def write_notice(
    components: list[Component], out: Path, app_version: str, counts: dict[str, int]
) -> None:
    rows = [
        f"  {component.name} {component.version}".ljust(22)
        + component.declared.ljust(18)
        + f"third-party/program/{component.slug}/"
        for component in components
        if component.ecosystem == "program"
    ]
    if any(component.name == "espeakng-loader" for component in components):
        rows.append(
            "  espeak-ng".ljust(22)
            + "GPL-3.0-or-later".ljust(18)
            + "inside the espeakng_loader distribution"
        )
    text = f"""{APP_NAME} {app_version}

{COPYRIGHT}

The Scribe source is licensed under the GNU Affero General Public License,
version 3 or later. The full text is texts/AGPL-3.0-or-later.txt.

This build carries a Python helper and the programs below, so it is a
combined work that includes copyleft parts: ffmpeg and the espeak-ng data
under GPL-3.0, libsndfile under LGPL-2.1.

{chr(10).join(rows)}

Every other component is listed with its version, license and text in
THIRD_PARTY_LICENSES.txt: {counts['cargo']} Rust crates and {counts['pypi']} Python
distributions. Each license text used by this build is in texts/; each
component that ships its own license file also has that file under
third-party/.

Written offer of Corresponding Source: SOURCE_OFFER.txt.
Machine-readable bill of materials: sbom.cdx.json.

Not bundled: the Kokoro voice model (Apache-2.0) and the Piper voice packs
(each with its own license). The app downloads or sideloads those at the
operator's request, so their terms are shown before the download.
"""
    (out / "NOTICE.txt").write_text(text, encoding="utf-8")


def write_source_offer(out: Path, app_version: str, manifest: dict) -> None:
    text = f"""{APP_NAME} {app_version}: written offer of Corresponding Source
(GPL-3.0 section 6b, AGPL-3.0 section 13).

This offer covers the exact build this file shipped in. It is valid for at
least three years from the date you received that build.

To get the Corresponding Source, write to:

    Name:  {CONTACT_NAME}
    Email: {CONTACT_EMAIL}
    URL:   {CONTACT_URL}

State the app version (shown in the window title) and you will receive the
complete tagged source for that build: the Rust UI, the Python Scribe
helper, the pinned lock files (Cargo.lock and the pinned Python
dependencies), and the build instructions below, on a durable medium or by
network download at no charge beyond reasonable media cost.

To rebuild this build yourself from that source:

1. Install Rust at the pinned toolchain (scribe-rs/rust-toolchain.toml) and
   Python 3.11 or newer with uv.
2. Check out the tagged source named in the release notes.
3. Build the app: cd scribe-rs && cargo build --release -p scribe-ui
4. Build the helper: cd scribe && uv run python dev/package_helper.py --out <dir>
   It downloads the pinned CPython {manifest['python']['version']}
   ({manifest['python']['asset']}) and ffmpeg {manifest['ffmpeg']['version']}
   ({manifest['ffmpeg']['asset']}), both checked against the sha256 in the
   helper manifest, and installs the hash-pinned wheels from uv.lock.
5. Assemble and zip the folder: powershell -ExecutionPolicy Bypass
   -File scribe/dev/pack_portable.ps1 (it also re-runs this generator).

The texts for the copyleft parts (AGPL-3.0 for Scribe, GPL-3.0 for ffmpeg
and espeak-ng, LGPL-2.1 for libsndfile) ship in this build under
licenses/texts/.
"""
    (out / "SOURCE_OFFER.txt").write_text(text, encoding="utf-8")


def write_sbom(components: list[Component], out: Path, app_version: str) -> None:
    def entry(component: Component) -> dict:
        # CycloneDX carries an SPDX id when one exists, else the plain name
        # (the Microsoft redistributable runtime has no SPDX identifier).
        license_entry = (
            {"license": {"id": component.atoms[0]}}
            if component.atoms
            else {"license": {"name": component.declared}}
        )
        entry = {
            "type": "library",
            "bom-ref": purl(component),
            "name": component.name,
            "purl": purl(component),
            "properties": [{"name": "auloud:license-declared", "value": component.declared}],
            "licenses": [license_entry],
        }
        if component.version:
            entry["version"] = component.version
        return entry

    document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "component": {
                "type": "application",
                "bom-ref": f"pkg:generic/auloud-scribe@{app_version}",
                "name": APP_NAME,
                "version": app_version,
                "licenses": [{"license": {"id": "AGPL-3.0-or-later"}}],
            },
            "tools": [{"vendor": "Auloud", "name": "scribe/dev/gen_licenses.py"}],
        },
        "components": [entry(component) for component in components],
    }
    (out / "sbom.cdx.json").write_text(
        json.dumps(document, indent=2, sort_keys=False) + "\n", encoding="utf-8"
    )


def write_manifest_hash(out: Path) -> None:
    """sha256sum lines for the generated files, so a shipped folder can be checked."""
    lines = []
    for path in sorted(out.rglob("*")):
        if path.is_file() and path.name != "MANIFEST.sha256":
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            lines.append(f"{digest}  {path.relative_to(out).as_posix()}")
    (out / "MANIFEST.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")


# --- entry point --------------------------------------------------------


def harvest_texts(components: list[Component], target: Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for atom in sorted({atom for component in components for atom in component.atoms}):
        destination = target / f"{atom}.txt"
        if destination.is_file():
            continue
        for component in components:
            if atom not in component.atoms:
                continue
            for path, _ in component.files:
                if sniff_atom(text_of(path)) == atom:
                    shutil.copyfile(path, destination)
                    break
            if destination.is_file():
                break
        print(f"{'wrote' if destination.is_file() else 'MISSING'} {destination.name}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Generate the artifact's license files.")
    parser.add_argument("--artifact", type=Path, default=None, help="Staged app folder.")
    parser.add_argument(
        "--site-packages",
        type=Path,
        default=None,
        help="Helper site-packages (default <artifact>/helper/embedded/python/Lib/site-packages).",
    )
    parser.add_argument(
        "--ffmpeg-archive",
        type=Path,
        default=None,
        help="The pinned ffmpeg zip the helper was built from.",
    )
    parser.add_argument("--canonical", type=Path, default=SCRIBE_DIR / "dev" / "license-texts")
    parser.add_argument(
        "--harvest-texts",
        type=Path,
        default=None,
        help="Refresh the canonical texts, then stop.",
    )
    parser.add_argument("--app-version", default="3.0.0")
    args = parser.parse_args(argv)

    embedded = args.artifact / "helper" / "embedded" if args.artifact else None
    site_packages = args.site_packages
    if site_packages is None and embedded is not None:
        site_packages = embedded / "python" / "Lib" / "site-packages"
    if site_packages is None and args.harvest_texts is None:
        parser.error("--site-packages or --artifact is required")
    if args.harvest_texts is None:
        needed = ((site_packages, "--site-packages"), (args.ffmpeg_archive, "--ffmpeg-archive"))
        for value, what in needed:
            if value is None or not Path(value).exists():
                parser.error(f"{what} is required and must exist: {value}")

    crates = rust_components()
    wheels = python_components(Path(site_packages))
    natives = native_components(Path(site_packages), wheels)
    if args.harvest_texts is not None:
        harvest_texts(crates + wheels + natives, args.harvest_texts)
        return 0

    with tempfile.TemporaryDirectory() as tmp:
        programs = program_components(Path(embedded), Path(args.ffmpeg_archive), Path(tmp))
        components = crates + wheels + natives + programs
        out = Path(args.artifact) / "licenses"
        if out.exists():
            shutil.rmtree(out)
        out.mkdir(parents=True)
        manifest = json.loads((Path(embedded) / "helper-manifest.json").read_text(encoding="utf-8"))
        write_texts(components, Path(args.canonical), out)
        copy_component_files(components, out)
        counts = {
            "cargo": len(crates),
            "pypi": len(wheels),
            "python": programs[0].version,
            "ffmpeg": programs[1].version,
        }
        write_third_party(components, out, len(crates), len(wheels))
        write_notice(components, out, args.app_version, counts)
        write_source_offer(out, args.app_version, manifest)
        write_sbom(components, out, args.app_version)
        write_manifest_hash(out)

    texts = len(list((out / "texts").glob("*.txt")))
    files = sum(1 for path in (out / "third-party").rglob("*") if path.is_file())
    print(
        f"licenses written: {len(components)} components, {texts} texts, "
        f"{files} component license files -> {out}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
