# HP4 clean-machine gate: runs the helper packaging checks inside an offline
# Windows 11 sandbox (or spare machine) with no system Python.
# See docs/plans/Slice15.md HP4 and scribe/dev/sandbox.wsb for the mappings.
#
# Expected layout (read-only shares from the host):
#   C:\helper\embedded  embedded bundle (python\python.exe -m scribe ...)
#   C:\helper\frozen    frozen onedir (scribe-helper\scribe-helper.exe)
#   C:\helper\models    sideloaded models (kokoro-v1.0.onnx, voices-v1.0.bin, piper\)
#   C:\fixtures         spec\fixtures (read-only)
#   C:\results          writable results folder (this script writes here)
#
# Run (offline, no Python needed):
#   powershell -ExecutionPolicy Bypass -File C:\helper\run_gate.ps1 `
#     -Variant both
# Alice/C&P EPUBs are not committed; pass -AliceEpub/-CpEpub or the script
# substitutes synthetic fixtures and records the substitution.
# Pause/resume/cancel and helper-kill survival belong to HP6, not this gate.

param(
    [ValidateSet("embedded", "frozen", "both")]
    [string]$Variant = "both",
    [string]$HelperBase = "C:\helper",
    [string]$ModelsDir = "C:\helper\models",
    [string]$FixturesDir = "C:\fixtures",
    [string]$AliceEpub = "",
    [string]$CpEpub = "",
    [string]$ResultsDir = "C:\results",
    [string]$Device = "cpu"
)

$ErrorActionPreference = "Stop"
$steps = @()
function Step([string]$name, [scriptblock]$body) {
    try {
        $detail = & $body
        $script:steps += [pscustomobject]@{ name = $name; result = "PASS"; detail = "$detail" }
        Write-Host "PASS $name $detail"
    } catch {
        $script:steps += [pscustomobject]@{ name = $name; result = "FAIL"; detail = "$($_.Exception.Message)" }
        Write-Host "FAIL $name $($_.Exception.Message)"
    }
}
function Need([string]$path, [string]$what) {
    if (-not (Test-Path $path)) { throw "$what missing: $path" }
    return $path
}
function RunCli([string]$exe, [string[]]$args, [string]$cwd) {
    $p = Start-Process -FilePath $exe -ArgumentList $args -WorkingDirectory $cwd `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput "$cwd\gate-cli-out.txt" `
        -RedirectStandardError "$cwd\gate-cli-err.txt"
    $out = Get-Content "$cwd\gate-cli-out.txt" -Raw
    if ($p.ExitCode -ne 0) {
        $err = Get-Content "$cwd\gate-cli-err.txt" -Raw
        throw "exit $($p.ExitCode): $($out + $err)".Substring(0, [Math]::Min(600, ($out + $err).Length))
    }
    return $out
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$bookA = $AliceEpub; $subA = ""
if (-not $bookA) { $bookA = "$FixturesDir\scribe-golden\source\book.epub"; $subA = " (synthetic substitute: no Alice EPUB supplied)" }
$bookB = $CpEpub; $subB = ""
if (-not $bookB) { $bookB = "$FixturesDir\epub2-minimal\source\book.epub"; $subB = " (synthetic substitute: no C&P EPUB supplied)" }
$pdfBook = "$FixturesDir\pdf-golden\source\book.pdf"

$variants = @()
if ($Variant -eq "embedded" -or $Variant -eq "both") { $variants += "embedded" }
if ($Variant -eq "frozen" -or $Variant -eq "both") { $variants += "frozen" }

foreach ($v in $variants) {
    $steps = @()
    $workRoot = "$env:TEMP\gate-work-$v-$stamp"
    New-Item -ItemType Directory -Force $workRoot | Out-Null
    if ($v -eq "embedded") {
        $exe = "$HelperBase\embedded\python\python.exe"
        if (-not (Test-Path $exe)) {
            # Zip extracts with a top folder; accept that layout too.
            $exe = "$HelperBase\embedded\helper-win64\python\python.exe"
        }
        $cliPrefix = @("-m", "scribe")
    } else {
        $exe = "$HelperBase\frozen\scribe-helper\scribe-helper.exe"
        $cliPrefix = @()
    }
    $notes = @("requests=$bookA$subA", "cp=$bookB$subB")

    Step "files-present" {
        Need $exe "$v launcher"
        Need $ModelsDir "models dir"
        Need "$ModelsDir\kokoro-v1.0.onnx" "kokoro model"
        Need $bookA "book A"; Need $bookB "book B"; Need $pdfBook "pdf golden"
        "launcher + models + books found"
    }
    Step "doctor" {
        RunCli $exe ($cliPrefix + @("doctor", "--models-dir", $ModelsDir)) $workRoot | Out-Null
        "all required checks passed"
    }

    # Cold start: launch helper, wait for the ready line, check token gate.
    $ws1 = "$workRoot\ws1"; New-Item -ItemType Directory -Force $ws1 | Out-Null
    $token = "gate-$v-$stamp"
    $readyFile = "$workRoot\ready1.txt"
    $coldSecs = -1; $port = -1; $helper = $null
    Step "cold-start-ready" {
        $env:AULOUD_HELPER_TOKEN = $token
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $helper = Start-Process -FilePath $exe `
            -ArgumentList ($cliPrefix + @("helper", "--workspace", $ws1, "--port", "0")) `
            -NoNewWindow -PassThru -RedirectStandardOutput $readyFile
        $deadline = 120
        while ($sw.Elapsed.TotalSeconds -lt $deadline) {
            Start-Sleep -Milliseconds 500
            if (Test-Path $readyFile -and (Select-String -Path $readyFile -Pattern '"ready":\s*true' -Quiet)) { break }
            if ($helper.HasExited) { throw "helper exited early" }
        }
        $sw.Stop()
        if (-not (Select-String -Path $readyFile -Pattern '"ready":\s*true' -Quiet)) { throw "no ready line in ${deadline}s" }
        $m = Select-String -Path $readyFile -Pattern '"port":\s*(\d+)' | Select-Object -First 1
        $script:port = [int]$m.Matches[0].Groups[1].Value
        $script:coldSecs = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
        "ready in $($script:coldSecs)s on port $($script:port)"
    }
    $idleMb = -1
    Step "token-gate" {
        if ($port -lt 0) { throw "no helper port (cold start failed)" }
        $ok = Invoke-RestMethod "http://127.0.0.1:$port/api/health" `
            -Headers @{"X-Auloud-Token" = $token }
        if ($ok.ok -ne $true) { throw "health not ok" }
        try {
            Invoke-WebRequest "http://127.0.0.1:$port/api/health" -UseBasicParsing | Out-Null
            throw "no-token request was NOT rejected"
        } catch {
            if ("$($_.Exception.Message)" -match "NOT rejected") { throw $_ }
            $code = [int]$_.Exception.Response.StatusCode
            if ($code -lt 400 -or $code -ge 500) { throw "no-token request gave $code, want 4xx" }
        }
        $script:idleMb = [Math]::Round((Get-Process -Id $helper.Id).WorkingSet64 / 1MB, 1)
        "token ok, no-token rejected, idle $($script:idleMb) MB"
    }

    # API path: upload book A, poll the draft job, build ch.1 via API (frozen
    # re-entry proof), validate via CLI.
    $apiBookId = ""
    Step "api-upload-draft" {
        if ($port -lt 0) { throw "no helper port" }
        $up = & curl.exe -s -X POST "http://127.0.0.1:$port/api/books/upload" `
            -H "X-Auloud-Token: $token" -F "file=@$bookA" | ConvertFrom-Json
        $script:apiBookId = $up.book_id
        $jobId = $up.job_id
        for ($i = 0; $i -lt 60; $i++) {
            Start-Sleep -Seconds 5
            $job = Invoke-RestMethod "http://127.0.0.1:$port/api/jobs/$jobId" `
                -Headers @{"X-Auloud-Token" = $token }
            if ($job.state -in @("done", "failed", "cancelled")) {
                if ($job.state -ne "done") { throw "draft job $jobId ended $($job.state)" }
                break
            }
        }
        "book $($script:apiBookId), draft job done"
    }
    $bundleA = ""
    Step "api-build-validate" {
        if (-not $apiBookId) { throw "no api book (upload failed)" }
        $body = @{ chapters = "1"; device = $Device } | ConvertTo-Json
        $b = Invoke-RestMethod "http://127.0.0.1:$port/api/books/$apiBookId/build" -Method Post `
            -Headers @{"X-Auloud-Token" = $token; "Content-Type" = "application/json" } -Body $body
        for ($i = 0; $i -lt 120; $i++) {
            Start-Sleep -Seconds 5
            $job = Invoke-RestMethod "http://127.0.0.1:$port/api/jobs/$($b.job_id)" `
                -Headers @{"X-Auloud-Token" = $token }
            if ($job.state -in @("done", "failed", "cancelled")) {
                if ($job.state -ne "done") { throw "build job ended $($job.state)" }
                break
            }
        }
        $script:bundleA = "$workRoot\bundleA"
        RunCli $exe ($cliPrefix + @("build", $bookA, "--work-dir", "$workRoot\cli-work", `
            "--out-dir", $bundleA, "--models-dir", $ModelsDir, "--device", $Device, `
            "--chapters", "1", "--no-progress")) $workRoot | Out-Null
        RunCli $exe ($cliPrefix + @("validate", $bundleA)) $workRoot | Out-Null
        $mp3 = (Get-ChildItem $bundleA -Recurse -Filter *.mp3 | Measure-Object Length -Sum).Sum
        "api build done + cli build/validate PASS ($mp3 audio bytes)"
    }

    # CLI path: draft + build book B, draft the PDF golden, one voices clip.
    Step "cli-draft-build" {
        RunCli $exe ($cliPrefix + @("draft", $bookB, "--work-dir", "$workRoot\cli-work")) $workRoot | Out-Null
        $out = RunCli $exe ($cliPrefix + @("build", $bookB, "--work-dir", "$workRoot\cli-work", `
            "--out-dir", "$workRoot\bundleB", "--models-dir", $ModelsDir, "--device", $Device, `
            "--no-progress")) $workRoot
        RunCli $exe ($cliPrefix + @("validate", "$workRoot\bundleB")) $workRoot | Out-Null
        $rtf = if ($out -match "RTF\s+([\d.]+)") { $Matches[1] } else { "n/a" }
        "draft+build+validate PASS (RTF $rtf)"
    }
    Step "piper-chapter" {
        $cast = Get-ChildItem "$workRoot\cli-work" -Recurse -Filter cast.yaml | Select-Object -First 1
        if (-not $cast) { throw "no cast.yaml under cli-work" }
        $text = Get-Content $cast.FullName -Raw
        $text = $text -replace "(?ms)(^narrator:\r?\n(?:  .*\r?\n)+)", {
            $b = $_.Groups[1].Value -replace "engine: kokoro", "engine: piper" `
                -replace "voice: \S+", "voice: en_US-lessac-low"
            "narrator:`n$b"
        }
        Set-Content $cast.FullName $text -Encoding utf8 -NoNewline:$false
        RunCli $exe ($cliPrefix + @("build", $bookB, "--work-dir", "$workRoot\cli-work", `
            "--out-dir", "$workRoot\bundleP", "--models-dir", $ModelsDir, "--device", $Device, `
            "--chapters", "1", "--no-progress")) $workRoot | Out-Null
        RunCli $exe ($cliPrefix + @("validate", "$workRoot\bundleP")) $workRoot | Out-Null
        "piper narrator chapter build+validate PASS"
    }
    Step "pdf-draft" {
        RunCli $exe ($cliPrefix + @("draft", $pdfBook, "--work-dir", "$workRoot\pdf-work")) $workRoot | Out-Null
        "pdf golden draft PASS"
    }
    Step "voices-sample" {
        RunCli $exe ($cliPrefix + @("voices", "--sample", "--engine", "kokoro", "--voice", "am_onyx", `
            "--models-dir", $ModelsDir, "--out-dir", "$workRoot\voice-samples")) $workRoot | Out-Null
        "one kokoro clip PASS"
    }

    # Warm start + path cases.
    if ($helper -and -not $helper.HasExited) { Stop-Process -Id $helper.Id -Force }
    $warmSecs = -1
    Step "warm-start-ready" {
        $env:AULOUD_HELPER_TOKEN = $token
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $h2 = Start-Process -FilePath $exe `
            -ArgumentList ($cliPrefix + @("helper", "--workspace", $ws1, "--port", "0")) `
            -NoNewWindow -PassThru -RedirectStandardOutput "$workRoot\ready2.txt"
        while ($sw.Elapsed.TotalSeconds -lt 120) {
            Start-Sleep -Milliseconds 500
            if (Select-String -Path "$workRoot\ready2.txt" -Pattern '"ready":\s*true' -Quiet) { break }
            if ($h2.HasExited) { throw "helper exited early on relaunch" }
        }
        $sw.Stop()
        Stop-Process -Id $h2.Id -Force
        if (-not (Select-String -Path "$workRoot\ready2.txt" -Pattern '"ready":\s*true' -Quiet)) { throw "no ready line on relaunch" }
        $script:warmSecs = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
        "ready in $($script:warmSecs)s on relaunch"
    }
    Step "spaces-path" {
        $wsU = "$workRoot\gate espa ol $([char]0xF1)o $([char]0xE9)"; New-Item -ItemType Directory -Force $wsU | Out-Null
        $env:AULOUD_HELPER_TOKEN = $token
        $h3 = Start-Process -FilePath $exe `
            -ArgumentList ($cliPrefix + @("helper", "--workspace", $wsU, "--port", "0")) `
            -NoNewWindow -PassThru -RedirectStandardOutput "$workRoot\ready3.txt"
        Start-Sleep -Seconds 15
        $ok = Select-String -Path "$workRoot\ready3.txt" -Pattern '"ready":\s*true' -Quiet
        Stop-Process -Id $h3.Id -Force
        if (-not $ok) { throw "no ready line under spaces/non-ASCII workspace" }
        "ready ok under spaces/non-ASCII path"
    }
    Step "long-path" {
        $deep = $workRoot
        for ($i = 0; $i -lt 12; $i++) { $deep = "$deep\gate-path-segment-number-$i-padding" }
        New-Item -ItemType Directory -Force $deep | Out-Null
        if ($deep.Length -lt 200) { throw "test setup too short ($($deep.Length) chars)" }
        $env:AULOUD_HELPER_TOKEN = $token
        $h4 = Start-Process -FilePath $exe `
            -ArgumentList ($cliPrefix + @("helper", "--workspace", $deep, "--port", "0")) `
            -NoNewWindow -PassThru -RedirectStandardOutput "$workRoot\ready4.txt"
        Start-Sleep -Seconds 15
        $ok = Select-String -Path "$workRoot\ready4.txt" -Pattern '"ready":\s*true' -Quiet
        Stop-Process -Id $h4.Id -Force
        if (-not $ok) { throw "no ready line under $($deep.Length)-char workspace path" }
        "ready ok under $($deep.Length)-char path"
    }
    Step "defender-log" {
        try {
            $hits = Get-WinEvent -LogName "Microsoft-Windows-Windows Defender/Operational" -MaxEvents 50 `
                | Where-Object { $_.Id -in @(1116, 1117, 1118) -and $_.TimeCreated -gt (Get-Date).AddHours(-2) }
            "$($hits.Count) threat events in the last 2h"
        } catch {
            "defender log unreadable (needs admin?): $($_.Exception.Message)"
        }
    }

    $report = [pscustomobject]@{
        variant        = $v
        date           = $stamp
        cold_ready_s   = $coldSecs
        warm_ready_s   = $warmSecs
        idle_mb        = $idleMb
        notes          = $notes
        steps          = $steps
    }
    $report | ConvertTo-Json -Depth 5 | Set-Content "$ResultsDir\gate-$v-$stamp.json" -Encoding utf8
    $md = "# Gate run $v ($stamp)`n`n"
    $md += "- Cold ready: $coldSecs s; warm ready: $warmSecs s; idle: $idleMb MB`n"
    foreach ($n in $notes) { $md += "- $n`n" }
    foreach ($s in $steps) { $md += "- [$($s.result)] $($s.name): $($s.detail)`n" }
    Set-Content "$ResultsDir\gate-$v-$stamp.md" $md -Encoding utf8
    Write-Host "WROTE $ResultsDir\gate-$v-$stamp.json"
}
