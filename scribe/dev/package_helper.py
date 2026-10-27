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

"""HP2: build the embedded helper bundle (standalone Python plus Scribe).

Run with ``uv`` from ``scribe/`` (uses the host uv for downloads and the
``uv.lock`` hashes; the bundle itself never needs uv)::

    uv run python dev/package_helper.py --out ../logs/packaging-gate/helper-win64

Layout of ``out``: ``python/`` (standalone distribution), ``site-packages``
inside it with locked dependencies plus the built Scribe wheel, ``ffmpeg/``
with ``ffmpeg.exe``/``ffprobe.exe``, ``helper-manifest.json`` (versions and
file hashes). Models stay outside (sideloaded); MSVC runtime DLLs are added
at HP4 only if the clean machine proves them missing.

Two variants (D-187): ``--variant gpu`` (default) ships the CUDA 12 stack from
D-185, ``--variant cpu`` ships the CPU ``onnxruntime`` instead and drops the
2.7 GB of CUDA wheels. The manifest records which one this is.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import urllib.request
import zipfile
from pathlib import Path

# --- Pins (HP0; bump with a DECISIONS.md entry, never silently) ---
PYTHON_VERSION = "3.14.8"
PBS_RELEASE = "20261009"
PBS_ASSET = f"cpython-{PYTHON_VERSION}+{PBS_RELEASE}-x86_64-pc-windows-msvc-install_only.tar.gz"
PBS_URL = (
    "https://github.com/astral-sh/python-build-standalone/releases/download/"
    f"{PBS_RELEASE}/{PBS_ASSET}"
)
PBS_SHA256 = "6c03535bfa5888dd73b0c0ebd91b76a2fdfa06eedaa411b0c4c9e664db6b4bb4"
FFMPEG_VERSION = "9.0.2"
FFMPEG_ASSET = "ffmpeg-9.0.2-essentials_build.zip"
FFMPEG_URL = f"https://www.gyan.dev/ffmpeg/builds/packages/{FFMPEG_ASSET}"
FFMPEG_SHA256 = "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba"
# Gyan builds are GPLv3 (essentials includes libmp3lame and friends); the
# source-offer decision lands in Slice 22, recorded here so it is not lost.
FFMPEG_LICENSE = "GPLv3"
# onnxruntime-gpu comes from ONNX Runtime's CUDA 12 feed (D-185), because PyPI
# publishes the same version number as a CUDA 13 build that will not load on
# this laptop's driver (D-176). `uv export` records that wheel's hash but not
# its index, so the hash-checked install below needs the feed explicitly.
ORT_CUDA_FEED = (
    "https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/"
    "onnxruntime-cuda-12/pypi/simple/"
)
#: The CUDA stack in the lock (D-185). ``--variant cpu`` drops these: 2.7 GB
#: of wheels a CPU-only machine can never load, and the bundle is meant to be
#: the small one.
GPU_PACKAGES = frozenset(
    {
        "onnxruntime-gpu",
        "nvidia-cublas-cu12",
        "nvidia-cudnn-cu12",
        "nvidia-cuda-runtime-cu12",
        "nvidia-cuda-nvrtc-cu12",
    }
)

SCRIBE_DIR = Path(__file__).resolve().parent.parent


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(url: str, expected_sha256: str, dest: Path) -> Path:
    """Download ``url`` to ``dest`` (skip when the hash already matches)."""
    if dest.is_file() and sha256_of(dest) == expected_sha256:
        print(f"cached {dest.name}")
        return dest
    print(f"download {url}")
    urllib.request.urlretrieve(url, dest)
    actual = sha256_of(dest)
    if actual != expected_sha256:
        raise RuntimeError(f"hash mismatch for {dest.name}: got {actual}")
    return dest


def extract_python(archive: Path, out: Path) -> Path:
    """Unpack the standalone distribution to ``out/python`` (idempotent)."""
    target = out / "python"
    marker = out / ".helper-python-ok"
    if marker.is_file():
        found = sorted(target.rglob("python.exe"))
        if found:
            print("python cached")
            return found[0]
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    with tarfile.open(archive, "r:gz") as tar:
        tar.extractall(target, filter="data")
    found = sorted(target.rglob("python.exe"))
    if not found:
        raise RuntimeError(f"no python.exe in {archive.name}")
    exe = found[0]
    if exe.parent != target:
        # Flatten a single top-level directory from the archive.
        top = exe.parent
        while top.parent != target:
            top = top.parent
        for child in list(top.iterdir()):
            shutil.move(str(child), target)
        shutil.rmtree(top, ignore_errors=True)
        exe = target / "python.exe"
    marker.write_text(f"{PBS_ASSET}\n", encoding="utf-8")
    return exe


def requirement_name(line: str) -> str:
    """Project name a requirement line starts with (``pkg==1`` or ``pkg ; marker``)."""
    cuts = [line.find(char) for char in "=<>!~[; \t"]
    cut = min((index for index in cuts if index != -1), default=len(line))
    return line[:cut].strip().lower()


def drop_requirements(path: Path, names: frozenset[str]) -> None:
    """Remove whole requirement blocks for ``names`` (hashes sit on continuations)."""
    kept: list[str] = []
    dropping = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line[:1].strip():  # a block starts on a line with no leading whitespace
            dropping = requirement_name(line) in names
        if not dropping:
            kept.append(line)
    path.write_text("\n".join(kept) + "\n", encoding="utf-8", newline="\n")


def locked_version(name: str) -> str:
    """Distribution version ``uv.lock`` picked for ``name``."""
    lock = tomllib.loads((SCRIBE_DIR / "uv.lock").read_text(encoding="utf-8"))
    for package in lock["package"]:
        if package["name"] == name:
            return str(package["version"])
    raise RuntimeError(f"{name} is not in uv.lock")


def export_requirements(dest: Path, *, variant: str) -> Path:
    """Frozen hashed requirements from ``uv.lock`` (runtime plus ui extra).

    ``--variant cpu`` drops the CUDA stack, which Windows installs by default
    (D-185): the bundle then gets the CPU ``onnxruntime`` instead.
    """
    dest.write_text(
        "# Generated by dev/package_helper.py; do not edit (see uv.lock).\n",
        encoding="utf-8",
    )
    completed = subprocess.run(
        [
            "uv",
            "export",
            "--frozen",
            "--no-dev",
            "--extra",
            "ui",
            "--no-emit-package",
            "auloud-scribe",
            "--format",
            "requirements.txt",
            "--output-file",
            str(dest),
        ],
        cwd=SCRIBE_DIR,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"uv export failed: {completed.stderr.strip()[-500:]}")
    if variant == "cpu":
        # The CUDA stack, and the lock's own CPU onnxruntime entry with it: that
        # one is pinned for non-Windows only, and a marker-false entry for the
        # same package makes uv ignore the unmarked, hashed requirement that
        # replaces it. Observed: the install reports "Checked 1 package" and the
        # bundle ships with no TTS runtime at all (D-187).
        drop_requirements(dest, GPU_PACKAGES | {"onnxruntime"})
    return dest


def cpu_onnxruntime_requirement(work: Path) -> Path:
    """Hash-pinned CPU ``onnxruntime`` requirement for ``--variant cpu``.

    The lock pins that wheel for the platforms the GPU stack does not cover,
    so a Windows wheel is not in it; the digest comes from PyPI's JSON API for
    the same version, which keeps the hash-checked install honest.
    """
    version = locked_version("onnxruntime")
    major, minor = PYTHON_VERSION.split(".")[:2]
    wheel_suffix = f"cp{major}{minor}-cp{major}{minor}-win_amd64.whl"
    with urllib.request.urlopen(f"https://pypi.org/pypi/onnxruntime/{version}/json") as response:
        payload = json.load(response)
    wheel = next((f for f in payload["urls"] if f["filename"].endswith(wheel_suffix)), None)
    if wheel is None:
        raise RuntimeError(f"PyPI has no {wheel_suffix} for onnxruntime {version}")
    dest = work / "onnxruntime-cpu.txt"
    dest.write_text(
        f"onnxruntime=={version} --hash=sha256:{wheel['digests']['sha256']}\n",
        encoding="utf-8",
        newline="\n",
    )
    return dest


def build_wheel(dest_dir: Path) -> Path:
    """Build the Scribe wheel (static assets force-included)."""
    completed = subprocess.run(
        ["uv", "build", "--wheel", "--out-dir", str(dest_dir)],
        cwd=SCRIBE_DIR,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"uv build failed: {completed.stderr.strip()[-500:]}")
    wheels = sorted(dest_dir.glob("auloud_scribe-*.whl"))
    if not wheels:
        raise RuntimeError("uv build produced no wheel")
    return wheels[-1]


def install_packages(
    python_exe: Path,
    requirements: Path,
    wheel: Path,
    *,
    variant: str,
    cpu_requirement: Path | None,
) -> None:
    """Install locked deps (hash-checked) plus the locally built wheel.

    The wheel carries no registry hash (it is built here from pinned
    source); its installed files land in ``helper-manifest.json``, and the
    reproducibility check covers content drift.
    """
    base = [
        # ``--no-config``: this install must not inherit the project's uv
        # settings. Run from ``scribe/``, the ``[tool.uv]`` override that keeps
        # the CPU onnxruntime off Windows rewrites the CPU variant's own
        # requirement to a false marker, and uv then installs nothing and says
        # so only in a "Checked 1 package" line (D-187).
        "uv",
        "--no-config",
        "pip",
        "install",
        "--python",
        str(python_exe),
        # The standalone interpreter is deliberately not a venv.
        "--break-system-packages",
    ]
    command = [
        # ``--no-deps``: the export is the project's resolved closure, so there
        # is nothing left to resolve. Resolving anyway lets metadata for a name
        # the lock already pinned become an unpinned requirement, which hash
        # mode rejects, or pulls in packages the lock never pinned (D-187).
        "--no-deps",
        "--require-hashes",
        "--requirement",
        str(requirements),
    ]
    if variant == "cpu":
        command += ["--requirement", str(cpu_requirement)]
    else:
        # Same version number, different build: PyPI's onnxruntime-gpu is CUDA
        # 13 and will not load on this driver (D-176), so the CUDA 12 feed must
        # be reachable. ``unsafe-best-match`` is what keeps that feed to a source
        # for this package: ``--no-config`` means uv does not see the project's
        # explicit-index routing, and under the default strategy the feed would
        # answer for every name it happens to carry (it carries ``flatbuffers``,
        # at a version the lock does not pin). Hashes decide what installs, so
        # no other index can substitute anything (D-187).
        command += [
            "--extra-index-url",
            ORT_CUDA_FEED,
            "--index-strategy",
            "unsafe-best-match",
        ]
    completed = subprocess.run(
        base + command,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"uv pip install failed: {completed.stderr.strip()[-2000:]}")
    completed = subprocess.run(
        # No dependency resolution here: the hash-checked install above put the
        # locked set in place, and resolving this wheel's metadata would pull
        # the CUDA stack (from PyPI, which is CUDA 13, D-176) behind the CPU
        # variant's back. The wheel adds the ``scribe`` package and nothing else.
        base + ["--no-deps", "--requirement", str(requirements), str(wheel)],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"uv pip install failed: {completed.stderr.strip()[-2000:]}")


def assert_variant_install(site_packages: Path, variant: str) -> None:
    """Stop when the installed set contradicts the variant (D-187).

    A stage that already held the other variant keeps its packages (nothing
    removes them), so the CPU bundle could otherwise ship the CUDA stack.
    """
    names = [path.name for path in site_packages.glob("*.dist-info")]

    def has(prefix: str) -> bool:
        return any(name.startswith(prefix) for name in names)

    if variant == "cpu":
        required = [("onnxruntime-", "onnxruntime")]
        forbidden = [("onnxruntime_gpu-", "onnxruntime-gpu"), ("nvidia_", "the NVIDIA wheels")]
    else:
        required = [("onnxruntime_gpu-", "onnxruntime-gpu")]
        forbidden = []
    missing = [what for prefix, what in required if not has(prefix)]
    extra = [what for prefix, what in forbidden if has(prefix)]
    if missing or extra:
        raise RuntimeError(
            f"{variant} helper is wrong: missing {missing or 'nothing'}, "
            f"unexpected {extra or 'nothing'}; build into a clean --out"
        )


def assert_helper_runs(python_exe: Path, site_packages: Path) -> None:
    """Stop when the staged interpreter cannot import the code it ships.

    The bundle's job is ``python -m scribe`` (scribe-ui/src/helper.rs), and
    ``scribe/__main__.py`` puts the namespace package on ``sys.path`` so the
    flat imports inside it resolve; a check that only reads dist-info names
    would not notice a wheel that landed somewhere else.
    """
    script = (
        "import sys; sys.path.insert(0, sys.argv[1]); "
        "import cli, build, device, tts.kokoro; print('imports ok')"
    )
    completed = subprocess.run(
        [str(python_exe), "-c", script, str(site_packages / "scribe")],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0 or "imports ok" not in completed.stdout:
        raise RuntimeError(
            f"the helper cannot import its own code: {completed.stderr.strip()[-2000:]}"
        )


def site_packages_of(python_exe: Path) -> Path:
    """Locate the standalone interpreter's site-packages.

    Not ``site.getsitepackages()[0]``: on the Windows standalone build that
    is the prefix itself, and only index 1 is the directory packages land in.
    """
    completed = subprocess.run(
        [str(python_exe), "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"],
        capture_output=True,
        text=True,
        check=True,
    )
    return Path(completed.stdout.strip())


def prepare_bytecode(out: Path, site_packages: Path) -> None:
    """Deterministic bytecode for the whole bundle (stdlib plus site-packages).

    The tarball ships ``__pycache__`` files whose code objects embed the
    builder's absolute paths, so two builds never agree. Drop every cache
    and test dir, then recompile with hash-based invalidation (proven
    byte-identical across processes) and paths relative to ``out`` so
    ``co_filename`` carries no machine-specific prefix.
    """
    root = out / "python"
    for base in (root, site_packages):
        for tests in [
            path for path in base.rglob("*") if path.is_dir() and path.name in ("test", "tests")
        ]:
            shutil.rmtree(tests, ignore_errors=True)
    for cache in out.rglob("__pycache__"):
        shutil.rmtree(cache, ignore_errors=True)
    # Console launchers embed the absolute interpreter path of the build
    # machine, so they differ per build and break when the bundle moves;
    # the helper always runs as ``python.exe -m scribe``, never via them.
    for launcher in (root / "Scripts").glob("*.exe"):
        launcher.unlink()
    import os

    targets = ["python/Lib", os.path.relpath(site_packages, out)]
    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            "compileall",
            "-q",
            "-f",
            "--invalidation-mode",
            "unchecked-hash",
            *targets,
        ],
        cwd=out,
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"compileall failed: {completed.stderr.strip()[-500:]}")


def extract_ffmpeg(archive: Path, out: Path) -> Path:
    """Unpack ffmpeg.exe/ffprobe.exe to ``out/ffmpeg`` (idempotent)."""
    target = out / "ffmpeg"
    if (target / "ffmpeg.exe").is_file() and (target / "ffprobe.exe").is_file():
        print("ffmpeg cached")
        return target
    shutil.rmtree(target, ignore_errors=True)
    with zipfile.ZipFile(archive) as jar:
        names = jar.namelist()
        for tool in ("ffmpeg.exe", "ffprobe.exe"):
            match = next((n for n in names if n.replace("/", "\\").endswith(f"bin\\{tool}")), None)
            if match is None:
                raise RuntimeError(f"{tool} not in {archive.name}")
            data = jar.read(match)
            dest = target / tool
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
    return target


def write_manifest(out: Path, extra: dict, *, variant: str) -> Path:
    """Hash every bundle file into ``helper-manifest.json`` (itself excluded)."""
    entries = []
    for path in sorted(out.rglob("*")):
        if path.is_file() and path.name != "helper-manifest.json":
            entries.append(
                {
                    "path": path.relative_to(out).as_posix(),
                    "sha256": sha256_of(path),
                    "bytes": path.stat().st_size,
                }
            )
    manifest = {
        "generator": "scribe dev/package_helper.py",
        # Which artifact this is: the CUDA stack or the CPU-only build (D-187).
        "variant": variant,
        "python": {"version": PYTHON_VERSION, "asset": PBS_ASSET, "sha256": PBS_SHA256},
        "ffmpeg": {
            "version": FFMPEG_VERSION,
            "asset": FFMPEG_ASSET,
            "sha256": FFMPEG_SHA256,
            "license": FFMPEG_LICENSE,
        },
        "scribe": extra,
        "files": entries,
    }
    dest = out / "helper-manifest.json"
    dest.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n"
    )
    return dest


def scribe_version() -> str:
    completed = subprocess.run(
        [
            "uv",
            "run",
            "--no-sync",
            "--frozen",
            "python",
            "-c",
            "from cli import __version__; print(__version__)",
        ],
        cwd=SCRIBE_DIR,
        capture_output=True,
        text=True,
        check=True,
    )
    return completed.stdout.strip()


def build_once(out: Path, cache: Path, work: Path, variant: str = "gpu") -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    python_archive = fetch(PBS_URL, PBS_SHA256, cache / PBS_ASSET)
    ffmpeg_archive = fetch(FFMPEG_URL, FFMPEG_SHA256, cache / FFMPEG_ASSET)
    python_exe = extract_python(python_archive, out)
    requirements = export_requirements(work / "requirements.txt", variant=variant)
    cpu_requirement = cpu_onnxruntime_requirement(work) if variant == "cpu" else None
    wheel = build_wheel(work)
    install_packages(
        python_exe, requirements, wheel, variant=variant, cpu_requirement=cpu_requirement
    )
    site_packages = site_packages_of(python_exe)
    assert_variant_install(site_packages, variant)
    assert_helper_runs(python_exe, site_packages)
    prepare_bytecode(out, site_packages)
    extract_ffmpeg(ffmpeg_archive, out)
    version = scribe_version()
    write_manifest(out, {"version": version, "wheel": wheel.name}, variant=variant)
    print(f"helper bundle ready: {out} (scribe {version}, {variant} variant)")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the embedded helper bundle.")
    parser.add_argument("--out", type=Path, required=True, help="Bundle output dir.")
    parser.add_argument(
        "--cache-dir", type=Path, default=None, help="Download cache (default <out>/../cache)."
    )
    parser.add_argument("--zip", action="store_true", help="Also produce <out>.zip.")
    parser.add_argument(
        "--variant",
        choices=("gpu", "cpu"),
        default="gpu",
        help="gpu: the CUDA 12 stack (D-185); cpu: the CPU-only bundle (D-187).",
    )
    parser.add_argument(
        "--verify-reproducible",
        action="store_true",
        help="Build twice into temp dirs and compare manifests (slow).",
    )
    args = parser.parse_args(argv)
    out = args.out.resolve()
    cache = (args.cache_dir.resolve() if args.cache_dir else out.parent / "cache").resolve()
    with tempfile.TemporaryDirectory(prefix="helper-work-") as tmp:
        build_once(out, cache, Path(tmp), args.variant)
    if args.zip:
        archive = shutil.make_archive(str(out), "zip", out.parent, out.name)
        print(f"wrote {archive}")
    if args.verify_reproducible:
        first = out.parent / "repro-a"
        second = out.parent / "repro-b"
        shutil.rmtree(first, ignore_errors=True)
        shutil.rmtree(second, ignore_errors=True)
        with tempfile.TemporaryDirectory(prefix="helper-work-") as tmp:
            build_once(first, cache, Path(tmp), args.variant)
        with tempfile.TemporaryDirectory(prefix="helper-work-") as tmp:
            build_once(second, cache, Path(tmp), args.variant)
        first_manifest = json.loads((first / "helper-manifest.json").read_text(encoding="utf-8"))
        second_manifest = json.loads((second / "helper-manifest.json").read_text(encoding="utf-8"))

        # Installer metadata (RECORD, direct_url, uv cache) embeds
        # per-install paths and is regenerated on every install, so it is
        # excluded from the comparison (it ships; it just cannot be
        # byte-stable). Everything else must match.
        def comparable(entries):
            excluded = ("RECORD", "direct_url.json", "uv_cache.json")
            return [entry for entry in entries if Path(entry["path"]).name not in excluded]

        first_map = {e["path"]: e["sha256"] for e in comparable(first_manifest["files"])}
        second_map = {e["path"]: e["sha256"] for e in comparable(second_manifest["files"])}
        if first_map != second_map:
            all_paths = sorted(set(first_map) | set(second_map))
            differing = [path for path in all_paths if first_map.get(path) != second_map.get(path)]
            raise RuntimeError(f"builds differ in {len(differing)} files: {differing[:10]}")
        print("reproducible: identical file hashes across two builds")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
