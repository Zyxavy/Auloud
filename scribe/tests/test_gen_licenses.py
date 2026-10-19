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

"""``dev/gen_licenses.py``: the packaging-time license generator (LG1).

The generator reads the locked sets, so these tests cover the decisions and
the wording it owns: SPDX branch picking, naming a shipped text, the per
distribution METADATA resolution, and the files the artifact receives. A
licence the allow-lists do not carry must stop the build (a missing entry is
the owner's decision, not a silent ship).
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEV = ROOT / "dev"

_spec = importlib.util.spec_from_file_location("gen_licenses", DEV / "gen_licenses.py")
gen_licenses = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = gen_licenses
_spec.loader.exec_module(gen_licenses)

MIT_TEXT = (
    "MIT License\n\nCopyright (c) 2026 Test\n\n"
    "Permission is hereby granted, free of charge, to any person obtaining a copy "
    "of this software and associated documentation files (the \"Software\"), to deal "
    "in the Software without restriction.\n"
)

#: Every atom the shipped sets currently use; each one must keep its text.
ATOMS_IN_USE = [
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
    "MIT",
    "MPL-2.0",
    "OFL-1.1",
    "PSF-2.0",
    "Ubuntu-font-1.0",
    "Unicode-3.0",
    "Zlib",
]


def _dist_info(
    site_packages: Path,
    name: str,
    version: str,
    metadata: str,
    files: dict[str, str] | None = None,
) -> Path:
    info = site_packages / f"{name.replace('-', '_')}-{version}.dist-info"
    info.mkdir(parents=True)
    (info / "METADATA").write_text(
        f"Metadata-Version: 2.4\nName: {name}\nVersion: {version}\n{metadata}\n",
        encoding="utf-8",
    )
    for relative, text in (files or {}).items():
        target = info / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return info


def test_choices_read_parentheses_the_way_spdx_does() -> None:
    # The AND applies to the whole group, so both ways to comply keep it.
    assert gen_licenses.choices("(MIT OR Apache-2.0) AND Unicode-3.0") == [
        ["MIT", "Unicode-3.0"],
        ["Apache-2.0", "Unicode-3.0"],
    ]
    assert gen_licenses.choices("MIT/Apache-2.0") == [["MIT"], ["Apache-2.0"]]


def test_choose_atoms_takes_the_first_allowed_branch() -> None:
    allowed = {"MIT", "Apache-2.0"}
    assert gen_licenses.choose_atoms("MIT OR Apache-2.0", allowed) == ["MIT"]
    assert gen_licenses.choose_atoms("Apache-2.0 OR MIT", allowed) == ["Apache-2.0"]
    assert gen_licenses.choose_atoms("MIT/Apache-2.0", allowed) == ["MIT"]
    # OR means the redistributor picks, so an unlisted alternative is fine.
    assert gen_licenses.choose_atoms("MIT OR Beerware", allowed) == ["MIT"]


def test_choose_atoms_rejects_a_license_the_list_does_not_carry() -> None:
    assert gen_licenses.choose_atoms("Beerware", {"MIT"}) is None
    assert gen_licenses.choose_atoms("MIT AND Beerware", {"MIT"}) is None


def test_sniff_names_the_shipped_texts() -> None:
    texts = ROOT / "dev" / "license-texts"
    # AGPL-3.0 and AGPL-3.0-or-later share one text; only the grant differs.
    expected = {
        "0BSD": "0BSD",
        "ISC": "ISC",
        "MIT": "MIT",
        "Apache-2.0": "Apache-2.0",
        "AGPL-3.0": "AGPL-3.0-or-later",
        "GPL-3.0-or-later": "GPL-3.0-or-later",
        "OFL-1.1": "OFL-1.1",
        "Ubuntu-font-1.0": "Ubuntu-font-1.0",
        "Zlib": "Zlib",
    }
    for atom, sniffed in expected.items():
        text = (texts / f"{atom}.txt").read_text(encoding="utf-8")
        assert gen_licenses.sniff_atom(text) == sniffed


def test_every_license_in_use_keeps_its_text() -> None:
    for atom in ATOMS_IN_USE:
        text = (ROOT / "dev" / "license-texts" / f"{atom}.txt").read_text(encoding="utf-8")
        assert len(text) > 200, atom


def test_python_components_read_an_expression_and_its_license_file(tmp_path: Path) -> None:
    _dist_info(
        tmp_path,
        "demo",
        "1.0",
        "License-Expression: MIT\nLicense-File: LICENSE",
        {"licenses/LICENSE": MIT_TEXT},
    )

    components = gen_licenses.python_components(tmp_path)

    assert len(components) == 1
    assert (components[0].name, components[0].version) == ("demo", "1.0")
    assert components[0].atoms == ["MIT"]
    assert components[0].files[0][1] == Path("licenses/LICENSE")


def test_python_components_refuse_a_license_outside_the_list(tmp_path: Path) -> None:
    _dist_info(tmp_path, "demo", "1.0", "License-Expression: Beerware")

    try:
        gen_licenses.python_components(tmp_path)
    except SystemExit as error:
        assert "Beerware" in str(error)
    else:
        raise AssertionError("an unlisted license must stop the build")


def test_python_components_name_a_text_when_nothing_is_declared(tmp_path: Path) -> None:
    _dist_info(tmp_path, "quiet", "2.0", "", {"LICENSE": MIT_TEXT})

    components = gen_licenses.python_components(tmp_path)

    assert components[0].atoms == ["MIT"]
    assert components[0].note == "identified from the shipped text"


def test_python_components_refuse_a_distribution_with_no_license_at_all(tmp_path: Path) -> None:
    _dist_info(tmp_path, "mute", "3.0", "")

    try:
        gen_licenses.python_components(tmp_path)
    except SystemExit as error:
        assert "mute" in str(error)
    else:
        raise AssertionError("a distribution with no license must stop the build")


def test_native_components_add_the_bundled_native_rows(tmp_path: Path) -> None:
    _dist_info(tmp_path, "soundfile", "0.14.0", "License: BSD-3-Clause")
    copying = tmp_path / "_soundfile_data" / "COPYING"
    copying.parent.mkdir()
    copying.write_text("GNU LESSER GENERAL PUBLIC LICENSE Version 2.1\n", encoding="utf-8")

    wheels = gen_licenses.python_components(tmp_path)
    natives = gen_licenses.native_components(tmp_path, wheels)

    assert [(row.name, row.declared, row.atoms) for row in natives] == [
        ("libsndfile", "LGPL-2.1-or-later", ["LGPL-2.1-or-later"])
    ]
    assert natives[0].files[0][1] == Path("COPYING")
    assert "bundled with soundfile 0.14.0" in natives[0].note


def test_native_components_name_a_row_with_no_spdx_identifier(tmp_path: Path) -> None:
    _dist_info(tmp_path, "numpy", "2.5.3", "License: BSD-3-Clause")

    wheels = gen_licenses.python_components(tmp_path)
    rows = {row.name: row for row in gen_licenses.native_components(tmp_path, wheels)}

    runtime = rows["Microsoft Visual C++ runtime"]
    assert runtime.atoms == []
    assert runtime.declared == "Microsoft Visual C++ runtime (no SPDX identifier)"
    assert rows["OpenBLAS"].atoms == ["BSD-3-Clause"]


def test_writers_emit_the_notice_the_copies_and_the_sbom(tmp_path: Path) -> None:
    canonical = tmp_path / "canonical"
    canonical.mkdir()
    for atom in ("MIT", "PSF-2.0"):
        (canonical / f"{atom}.txt").write_text(MIT_TEXT, encoding="utf-8")
    own = tmp_path / "own-LICENSE"
    own.write_text(MIT_TEXT, encoding="utf-8")

    components = [
        gen_licenses.Component("cargo", "demo", "1.0", "MIT", ["MIT"], [(own, Path("LICENSE"))]),
        gen_licenses.Component("pypi", "quiet", "2.0", "MIT", ["MIT"]),
        gen_licenses.Component("program", "python", "3.14.8", "PSF-2.0", ["PSF-2.0"]),
        gen_licenses.Component(
            "native",
            "Microsoft Visual C++ runtime",
            "",
            "Microsoft Visual C++ runtime (no SPDX identifier)",
            [],
            note="redistributed runtime",
        ),
    ]
    out = tmp_path / "out"
    out.mkdir()

    gen_licenses.write_texts(components, canonical, out)
    gen_licenses.copy_component_files(components, out)
    gen_licenses.write_third_party(components, out, 1, 1)
    gen_licenses.write_notice(
        components, out, "3.0.0", {"cargo": 1, "pypi": 1, "python": "3.14.8", "ffmpeg": "9.0.2"}
    )
    gen_licenses.write_sbom(components, out, "3.0.0")
    gen_licenses.write_manifest_hash(out)

    assert (out / "texts" / "MIT.txt").read_text(encoding="utf-8") == MIT_TEXT
    assert (out / "third-party" / "cargo" / "demo-1.0" / "LICENSE").is_file()
    assert not (out / "third-party" / "pypi" / "quiet-2.0").exists()

    notice = (out / "NOTICE.txt").read_text(encoding="utf-8")
    assert "third-party/program/python-3.14.8/" in notice
    assert "1 Rust crates and 1 Python" in notice

    third_party = (out / "THIRD_PARTY_LICENSES.txt").read_text(encoding="utf-8")
    assert "demo 1.0" in third_party
    assert "quiet 2.0" in third_party
    assert "Native code bundled by a distribution" in third_party

    sbom = json.loads((out / "sbom.cdx.json").read_text(encoding="utf-8"))
    assert sbom["bomFormat"] == "CycloneDX"
    assert sbom["specVersion"] == "1.5"
    assert sbom["metadata"]["component"]["name"] == "Auloud Scribe"
    assert [entry["purl"] for entry in sbom["components"]] == [
        "pkg:cargo/demo@1.0",
        "pkg:pypi/quiet@2.0",
        "pkg:generic/python@3.14.8",
        "pkg:generic/microsoft-visual-c-runtime",
    ]
    runtime = sbom["components"][-1]
    # No SPDX id and no version: the entry carries the name alone.
    assert "version" not in runtime
    assert runtime["licenses"] == [
        {"license": {"name": "Microsoft Visual C++ runtime (no SPDX identifier)"}}
    ]
    assert (out / "MANIFEST.sha256").read_text(encoding="utf-8").endswith("  licenses/\n")
