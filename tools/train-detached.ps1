# Runs the full detector training independently of any terminal or Claude session.
# Launch detached (survives closing the terminal/app):
#   Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
#     CommandLine = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "<repo>\tools\train-detached.ps1"' }
# While training runs it asks Windows not to idle-sleep (SetThreadExecutionState); the request ends with the process.
param([int]$Epochs = 40, [int]$Batch = 8, [int]$Workers = 2)
$ErrorActionPreference = "Stop"

. "$PSScriptRoot\env.ps1"
$env:CP_MULTISCALE = "0"
$env:CP_NO_AUG_EPOCHS = "5"
$log = "$env:CP_TOOLS\train-main.log"

$uv = (Get-Command uv -ErrorAction SilentlyContinue).Source
if (-not $uv) { "ERROR: uv not found on PATH" | Set-Content $log; exit 1 }

Add-Type -Namespace CuentaPepas -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);'
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED
[CuentaPepas.Power]::SetThreadExecutionState([uint32]"0x80000001") | Out-Null

Push-Location "$env:CP_ROOT\ml"
try {
    "started $(Get-Date -Format o) epochs=$Epochs batch=$Batch workers=$Workers" | Set-Content $log
    & $uv run --no-sync cuentapepas train --batch $Batch --epochs $Epochs --workers $Workers *>> $log
    "exit=$LASTEXITCODE" | Add-Content $log
}
finally {
    Pop-Location
    [CuentaPepas.Power]::SetThreadExecutionState([uint32]"0x80000000") | Out-Null
}
