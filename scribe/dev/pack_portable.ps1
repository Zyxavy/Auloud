# IN1 (Slice 22): assemble the portable Windows artifact and zip it.
#
# Two variants (D-187), differing only in helper\embedded\site-packages:
# -Variant gpu (the default) ships the CUDA 12 stack, about 2.9 GB total;
# -Variant cpu ships the CPU onnxruntime instead, near 300 MB.
#
# Layout inside <OutRoot>\Auloud-Scribe-<Version>-<Variant>\ (one top folder,
# so extraction cannot scatter files):
#
#   scribe-ui.exe                          the app
#   helper\embedded\python\python.exe      the discovery path (helper.rs)
#   helper\embedded\ffmpeg\*.exe           ffmpeg and ffprobe as files
#   helper\embedded\helper-manifest.json   helper versions and hashes
#   licenses\                              NOTICE, third-party list, texts,
#                                          source offer and the SBOM (LG1)
#
# Models stay out of the zip: the wizard downloads or sideloads them into
# the workspace.
#
# The stage is rebuilt from scratch every run, so no stale files can ship.
# Downloads (CPython standalone, ffmpeg) are reused from -CacheDir.
#
#   powershell -ExecutionPolicy Bypass -File scribe\dev\pack_portable.ps1
#   powershell -ExecutionPolicy Bypass -File scribe\dev\pack_portable.ps1 -CacheDir D:\packcache

param(
    [string]$Version = "3.0.0",
    [ValidateSet("gpu", "cpu")][string]$Variant = "gpu",
    [string]$OutRoot = "",
    [string]$CacheDir = "",
    [switch]$SkipUi
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutRoot) { $OutRoot = Join-Path $repo "logs\portable" }
if (-not $CacheDir) { $CacheDir = Join-Path $OutRoot "cache" }
$stage = Join-Path $OutRoot "Auloud-Scribe-$Version-$Variant"
$zip = "$stage.zip"

function Require([string]$path, [string]$what) {
    if (-not (Test-Path $path)) { throw "$what missing: $path" }
}

# 1. The app.
$exe = Join-Path $repo "scribe-rs\target\release\scribe-ui.exe"
if (-not $SkipUi) {
    Write-Host "building scribe-ui (release)"
    & cargo build --release -p scribe-ui --manifest-path (Join-Path $repo "scribe-rs\Cargo.toml")
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
}
Require $exe "the built app"

# 2. The helper bundle (HP2 builder), into a clean stage.
$embedded = Join-Path $stage "helper\embedded"
Write-Host "building the embedded helper bundle ($Variant variant)"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
Push-Location (Join-Path $repo "scribe")
try {
    & uv run python dev/package_helper.py --out $embedded --cache-dir $CacheDir --variant $Variant
    if ($LASTEXITCODE -ne 0) { throw "package_helper.py failed" }
} finally {
    Pop-Location
}

# 3. Self-check the layout: these are the paths resolve_command probes.
Require (Join-Path $embedded "python\python.exe") "the helper interpreter"
Require (Join-Path $embedded "helper-manifest.json") "the helper manifest"
Require (Join-Path $embedded "ffmpeg\ffmpeg.exe") "ffmpeg"
Copy-Item -Force $exe (Join-Path $stage "scribe-ui.exe")

# 4. Licenses (LG1): NOTICE, third-party list, texts, source offer, SBOM.
$ffmpegZip = Get-ChildItem (Join-Path $CacheDir "ffmpeg-*.zip") -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $ffmpegZip) { throw "no ffmpeg archive in $CacheDir (its license files ship)" }
Write-Host "generating NOTICE, license texts and the SBOM"
Push-Location (Join-Path $repo "scribe")
try {
    & uv run python dev/gen_licenses.py --artifact $stage --app-version $Version --ffmpeg-archive $ffmpegZip.FullName
    if ($LASTEXITCODE -ne 0) { throw "gen_licenses.py failed" }
} finally {
    Pop-Location
}

# 5. Zip it.
Write-Host "zipping $stage"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

# 6. Self-check the zip: the entries the app needs after extraction.
$wanted = @(
    "Auloud-Scribe-$Version-$Variant/scribe-ui.exe",
    "Auloud-Scribe-$Version-$Variant/helper/embedded/python/python.exe",
    "Auloud-Scribe-$Version-$Variant/licenses/NOTICE.txt",
    "Auloud-Scribe-$Version-$Variant/licenses/texts/AGPL-3.0-or-later.txt",
    "Auloud-Scribe-$Version-$Variant/licenses/sbom.cdx.json"
)
$archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
    $names = @($archive.Entries | ForEach-Object { $_.FullName -replace '\\', '/' })
} finally {
    $archive.Dispose()
}
foreach ($entry in $wanted) {
    if ($names -notcontains $entry) { throw "zip is missing $entry" }
}

"{0} ({1:N0} MB, {2} entries)" -f $zip, ((Get-Item $zip).Length / 1MB), $names.Count
