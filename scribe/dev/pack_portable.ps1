# IN1 (Slice 22): assemble the portable Windows artifact and zip it.
#
# Layout inside <OutRoot>\Auloud-Scribe-<Version>\ (one top folder, so
# extraction cannot scatter files):
#
#   scribe-ui.exe                          the app
#   helper\embedded\python\python.exe      the discovery path (helper.rs)
#   helper\embedded\ffmpeg\*.exe           ffmpeg and ffprobe as files
#   helper\embedded\helper-manifest.json   helper versions and hashes
#
# Models stay out of the zip: the wizard downloads or sideloads them into
# the workspace. NOTICE, SBOM, in-app licenses and the source offer join
# this same folder in LG1; until then the zip is for the project only.
#
# The stage is rebuilt from scratch every run, so no stale files can ship.
# Downloads (CPython standalone, ffmpeg) are reused from -CacheDir.
#
#   powershell -ExecutionPolicy Bypass -File scribe\dev\pack_portable.ps1
#   powershell -ExecutionPolicy Bypass -File scribe\dev\pack_portable.ps1 -CacheDir D:\packcache

param(
    [string]$Version = "3.0.0",
    [string]$OutRoot = "",
    [string]$CacheDir = "",
    [switch]$SkipUi
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutRoot) { $OutRoot = Join-Path $repo "logs\portable" }
if (-not $CacheDir) { $CacheDir = Join-Path $OutRoot "cache" }
$stage = Join-Path $OutRoot "Auloud-Scribe-$Version"
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
Write-Host "building the embedded helper bundle"
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
Push-Location (Join-Path $repo "scribe")
try {
    & uv run python dev/package_helper.py --out $embedded --cache-dir $CacheDir
    if ($LASTEXITCODE -ne 0) { throw "package_helper.py failed" }
} finally {
    Pop-Location
}

# 3. Self-check the layout: these are the paths resolve_command probes.
Require (Join-Path $embedded "python\python.exe") "the helper interpreter"
Require (Join-Path $embedded "helper-manifest.json") "the helper manifest"
Require (Join-Path $embedded "ffmpeg\ffmpeg.exe") "ffmpeg"
Copy-Item -Force $exe (Join-Path $stage "scribe-ui.exe")

# 4. Zip it.
Write-Host "zipping $stage"
Remove-Item -Force $zip -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)

# 5. Self-check the zip: the two entries the app needs after extraction.
$wanted = @(
    "Auloud-Scribe-$Version/scribe-ui.exe",
    "Auloud-Scribe-$Version/helper/embedded/python/python.exe"
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
