<#
.SYNOPSIS
  Runs Gradle with short, agent-friendly output.

.DESCRIPTION
  Runs gradlew.bat from the repo root with the given tasks plus
  --console=plain --warning-mode=summary. All output goes to
  %TEMP%\gradle-last.log; only a short summary is printed:
    success: "BUILD OK" plus up to 5 "w:" lines
    failure: up to 40 matching lines ('^e: |FAILED|What went wrong|Exception|expected:|but was:')
             with 3 lines of context (at most 120 printed lines), plus the log path
  The script exits with Gradle's exit code.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/gradle-brief.ps1 assembleDebug testDebugUnitTest
#>
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks
)

$ErrorActionPreference = 'Stop'
if (-not $Tasks -or $Tasks.Count -eq 0) { $Tasks = @('assembleDebug', 'testDebugUnitTest') }

$root = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $root 'gradlew.bat'
$log = Join-Path $env:TEMP 'gradle-last.log'
$maxMatches = 40
$maxPrinted = 120
$context = 3

# JAVA_HOME and ANDROID_HOME are set at user level; a fresh agent shell may not have them.
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME')) {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($value) { [Environment]::SetEnvironmentVariable($name, $value, 'Process') }
    }
}

# cmd /s /c strips exactly the outer quotes, so paths with spaces stay quoted.
$taskArgs = ($Tasks | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$inner = '"' + $gradlew + '" ' + $taskArgs + ' --console=plain --warning-mode=summary > "' + $log + '" 2>&1'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
$psi.Arguments = '/d /s /c "' + $inner + '"'
$psi.WorkingDirectory = $root
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$proc.WaitForExit()
$code = $proc.ExitCode

$lines = @()
if (Test-Path -LiteralPath $log) { $lines = @(Get-Content -LiteralPath $log) }

if ($code -eq 0) {
    Write-Output ('BUILD OK: ' + ($Tasks -join ' '))
    $warnings = @($lines | Where-Object { $_ -match '^w: ' })
    $warnings | Select-Object -First 5 | ForEach-Object { Write-Output $_ }
    if ($warnings.Count -gt 5) { Write-Output ('... ' + ($warnings.Count - 5) + ' more w: lines in ' + $log) }
} else {
    Write-Output ('BUILD FAILED (exit ' + $code + '): ' + ($Tasks -join ' '))
    $pattern = '^e: |FAILED|What went wrong|Exception|expected:|but was:'
    $hits = @()
    for ($i = 0; $i -lt $lines.Count -and $hits.Count -lt $maxMatches; $i++) {
        if ($lines[$i] -match $pattern) { $hits += $i }
    }
    $keep = New-Object 'System.Collections.Generic.SortedSet[int]'
    foreach ($h in $hits) {
        $from = [Math]::Max(0, $h - $context)
        $to = [Math]::Min($lines.Count - 1, $h + $context)
        for ($j = $from; $j -le $to; $j++) { [void]$keep.Add($j) }
    }
    $printed = 0
    $previous = -2
    foreach ($j in $keep) {
        if ($printed -ge $maxPrinted) { Write-Output '... (truncated)'; break }
        if ($previous -ge 0 -and $j -ne $previous + 1) { Write-Output '--' }
        Write-Output $lines[$j]
        $printed++
        $previous = $j
    }
    if ($hits.Count -eq 0) {
        $lines | Select-Object -Last 15 | ForEach-Object { Write-Output $_ }
    }
}
Write-Output ('Full log: ' + $log)
exit $code
