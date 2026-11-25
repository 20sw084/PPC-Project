<#
.SYNOPSIS
  Unattended run for 25 Nov: recalibration, error-level extension (0.75, 1.0) and the spin robustness check.

.DESCRIPTION
  Steps, in order (about 2.5 h in total; every step is resumable, so the whole script can simply be re-run):
    0. pre-flight: on AC power, tools present, compile, main grid file present and free of corrupted runs,
       back up the main grid file
    1. block sleep for the duration of the script, switch to the High performance plan (restored at the end)
    2. recalibration (~3 min)           -> results\calibration-raw.csv, results\calibration-<date>.txt
    3. extension: LPT at error 0.75, 1.0 (~1 h, 720 runs)  -> appended to results\grid-timed.csv
    4. spin check, 10 reps, N in 2,4,8 (~1.3 h, 900 runs) -> results\grid-spin.csv, at the NEW spin rate
    5. after steps 3 and 4: outlier check; corrupted runs (suspend/stall) are removed and redone automatically
    6. analysis: results\analysis (timed, incl. extension) and results\analysis-spin

  Run from any folder:
    powershell -ExecutionPolicy Bypass -File D:\globalcare_automation\ppc-sharding\scripts\run_extension_and_spin.ps1

  -DryRun          print what would run, execute nothing long (checks the script itself)
  -SkipCalibration reuse -ItersPerMs instead of recalibrating (default if omitted: calibrate)
  -ItersPerMs      spin rate to use together with -SkipCalibration
#>
param(
    [switch]$DryRun,
    [switch]$SkipCalibration,
    [double]$ItersPerMs = 0
)

$ErrorActionPreference = 'Stop'
$root    = Split-Path -Parent $PSScriptRoot
$harness = Join-Path $root 'harness'
$classes = Join-Path $harness 'target\classes'
$results = Join-Path $root 'results'
$analysis = Join-Path $root 'analysis'
$gridTimed = Join-Path $results 'grid-timed.csv'
$gridSpin  = Join-Path $results 'grid-spin.csv'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$started = Get-Date
# Windows 'execution state' flags. Written as unsigned decimals: in Windows PowerShell 5.1 the literal 0x80000001
# is a NEGATIVE Int32 and cannot be converted to the UInt32 the API expects.
$ES_CONTINUOUS_SYSTEM_REQUIRED = [uint32]2147483649   # 0x80000001 = ES_CONTINUOUS | ES_SYSTEM_REQUIRED (stay awake)
$ES_CONTINUOUS                 = [uint32]2147483648   # 0x80000000 = ES_CONTINUOUS (allow sleep again)
$sleepBlocked = $false

function Say($text) { Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $text) -ForegroundColor Cyan }

function Run-Native([string]$what, [scriptblock]$command) {
    if ($DryRun) { Say "DRY RUN, would run: $what"; return }
    Say $what
    & $command
    if ($LASTEXITCODE -ne 0) { throw "step failed (exit code $LASTEXITCODE): $what" }
}

function Check-AndRedo([string]$csv, [scriptblock]$redoCommand, [string]$label) {
    # remove corrupted runs (machine suspended or stalled mid-run) and let the driver redo exactly those runs
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        & python (Join-Path $analysis 'check_outliers.py') $csv
        if ($LASTEXITCODE -eq 0) { return }
        if ($DryRun) { Say "DRY RUN: would remove and redo corrupted $label runs"; return }
        Say "corrupted $label runs found: removing and redoing them (attempt $attempt of 3)"
        & python (Join-Path $analysis 'check_outliers.py') $csv --remove | Out-Null
        & $redoCommand
        if ($LASTEXITCODE -ne 0) { throw "redo of corrupted $label runs failed" }
    }
    & python (Join-Path $analysis 'check_outliers.py') $csv
    if ($LASTEXITCODE -ne 0) { throw "$label still has corrupted runs after 3 attempts; the PC is probably going to sleep" }
}

Start-Transcript -Path (Join-Path $results "overnight-$stamp.log") | Out-Null
$oldPlan = $null
try {
    # ---- 0. pre-flight ----------------------------------------------------------------------------------
    Say '0. pre-flight checks'
    foreach ($tool in 'java', 'mvn', 'python') {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool is not on PATH" }
    }
    Add-Type -AssemblyName System.Windows.Forms
    if (-not $DryRun -and [System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus -ne 'Online') {
        throw 'the laptop is not plugged in: connect the charger and start again'
    }
    Push-Location $harness
    try { & mvn -q compile; if ($LASTEXITCODE -ne 0) { throw 'mvn compile failed' } } finally { Pop-Location }
    if (-not (Test-Path $gridTimed)) { throw "$gridTimed is missing" }
    $dataRows = (Get-Content $gridTimed | Measure-Object -Line).Lines - 1
    if ($dataRows -lt 3600) { throw "$gridTimed has only $dataRows rows (expected at least 3600)" }
    & python (Join-Path $analysis 'check_outliers.py') $gridTimed
    if ($LASTEXITCODE -ne 0) { throw 'the main grid has corrupted runs; fix them first' }
    $backup = Join-Path $results 'raw-backup\grid-timed.before-extension.csv'
    if (-not (Test-Path $backup)) {
        New-Item -ItemType Directory -Force (Split-Path $backup) | Out-Null
        Copy-Item $gridTimed $backup
        Say "backed up the main grid to $backup"
    }

    # ---- 1. keep the machine awake, High performance plan -----------------------------------------------
    if (-not $DryRun) {
        Add-Type -Namespace Win32 -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
        [Win32.Power]::SetThreadExecutionState($ES_CONTINUOUS_SYSTEM_REQUIRED) | Out-Null
        $sleepBlocked = $true
        $planLine = (& powercfg /getactivescheme) -join ' '
        if ($planLine -match '([0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12})') { $oldPlan = $Matches[1] }
        try { & powercfg /setactive SCHEME_MIN } catch { Write-Warning 'could not switch to High performance' }
        Say "sleep blocked, High performance plan active (previous plan: $oldPlan)"
    }

    # ---- 2. recalibration -------------------------------------------------------------------------------
    if ($SkipCalibration) {
        if ($ItersPerMs -le 0) { throw '-SkipCalibration needs -ItersPerMs' }
        $rate = [long]$ItersPerMs
    } elseif ($DryRun) {
        Say 'DRY RUN, would run Calibration (about 3 min)'
        $rate = 690624
    } else {
        Say '2. recalibration (about 3 min): do not touch the PC'
        & java -cp $classes ppc.sharding.Calibration (Join-Path $results 'calibration-raw.csv') |
            Tee-Object -FilePath (Join-Path $results "calibration-$stamp.txt") |
            Tee-Object -Variable calOut | Out-Host
        if ($LASTEXITCODE -ne 0) { throw 'Calibration failed' }
        $m = [regex]::Match(($calOut -join "`n"), '--iters-per-ms=(\d+)')
        if (-not $m.Success) { throw 'could not read --iters-per-ms from the calibration output' }
        $rate = [long]$m.Groups[1].Value
    }
    Say "spin rate for the spin check: $rate iterations/ms"

    # ---- 3. extension -----------------------------------------------------------------------------------
    $extension = { & java -cp $classes ppc.sharding.ExperimentDriver --errors=0.75,1.0 --out=$gridTimed }
    Run-Native '3. extension: LPT at error 0.75 and 1.0 (about 1 h)' $extension
    Check-AndRedo $gridTimed $extension 'extension'

    # ---- 4. spin check ----------------------------------------------------------------------------------
    $spin = { & java -cp $classes ppc.sharding.ExperimentDriver --variant=spin "--iters-per-ms=$rate" --out=$gridSpin }
    Run-Native '4. spin check: 10 reps, N in 2,4,8 (about 1.3 h)' $spin
    if (Test-Path $gridSpin) { Check-AndRedo $gridSpin $spin 'spin' }

    # ---- 6. analysis ------------------------------------------------------------------------------------
    if (-not $DryRun) {
        Say '6. analysis'
        & python (Join-Path $analysis 'stats.py') --grid $gridTimed --out (Join-Path $results 'analysis')
        if ($LASTEXITCODE -ne 0) { throw 'stats.py failed on the timed grid' }
        & python (Join-Path $analysis 'stats.py') --grid $gridSpin --out (Join-Path $results 'analysis-spin') --boot 3000
        if ($LASTEXITCODE -ne 0) { throw 'stats.py failed on the spin grid' }
    }
    $elapsed = (Get-Date) - $started
    Say ("ALL DONE in {0:N0} min. Now: restore your sleep settings if you changed them, then tell Claude 'overnight run done'." -f $elapsed.TotalMinutes)
}
catch {
    Write-Host "STOPPED: $_" -ForegroundColor Red
    Write-Host 'Fix the cause and run the same command again: every step resumes where it stopped.' -ForegroundColor Red
}
finally {
    if ($sleepBlocked) { [Win32.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null }   # allow sleep again
    if ($oldPlan) { & powercfg /setactive $oldPlan }
    Stop-Transcript | Out-Null
}
