# Dot-source before any build/download command:  . .\tools\env.ps1
# Keeps every toolchain, cache and temp file inside the project folder (nothing on C:).
$env:CP_ROOT = (Resolve-Path "$PSScriptRoot\..").Path
$env:CP_TOOLS = "$env:CP_ROOT\.tools"

$env:JAVA_HOME = "$env:CP_TOOLS\jdk"
$env:ANDROID_HOME = "$env:CP_TOOLS\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = "$env:CP_TOOLS\android-user-home"
$env:GRADLE_USER_HOME = "$env:CP_TOOLS\gradle-home"
$env:UV_CACHE_DIR = "$env:CP_TOOLS\uv-cache"
$env:UV_PYTHON_INSTALL_DIR = "$env:CP_TOOLS\uv-python"
$env:UV_PROJECT_ENVIRONMENT = "$env:CP_TOOLS\ml-venv"
$env:PIP_CACHE_DIR = "$env:CP_TOOLS\pip-cache"
$env:HF_HOME = "$env:CP_TOOLS\hf-home"
$env:TORCH_HOME = "$env:CP_TOOLS\torch-home"
$env:KAGGLE_CONFIG_DIR = "$env:CP_TOOLS\kaggle"
$env:MPLCONFIGDIR = "$env:CP_TOOLS\matplotlib"
$env:TEMP = "$env:CP_TOOLS\tmp"
$env:TMP = "$env:CP_TOOLS\tmp"
foreach ($d in @("$env:CP_TOOLS\tmp", "$env:CP_TOOLS\java-user-home")) { New-Item -ItemType Directory -Force $d | Out-Null }
# JVM options cannot contain spaces ("Programas Data"), so use Windows 8.3 short paths.
$fso = New-Object -ComObject Scripting.FileSystemObject
$shortTmp = $fso.GetFolder("$env:CP_TOOLS\tmp").ShortPath
$shortHome = $fso.GetFolder("$env:CP_TOOLS\java-user-home").ShortPath
$env:GRADLE_OPTS = "-Djava.io.tmpdir=$shortTmp"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$shortTmp -Duser.home=$shortHome"

$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

$secrets = "$env:CP_TOOLS\secrets.env"
if (Test-Path $secrets) {
  Get-Content $secrets | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
  }
}

foreach ($d in @("$env:CP_TOOLS\tmp", $env:ANDROID_USER_HOME, $env:GRADLE_USER_HOME, "$env:CP_TOOLS\java-user-home")) {
  New-Item -ItemType Directory -Force $d | Out-Null
}
