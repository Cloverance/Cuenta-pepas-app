# Source this file before any build/download command:  source tools/env.sh
# Keeps every toolchain, cache and temp file inside the project folder (nothing on C:).
CP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export CP_ROOT
export CP_TOOLS="$CP_ROOT/.tools"

export JAVA_HOME="$CP_TOOLS/jdk"
export ANDROID_HOME="$CP_TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$CP_TOOLS/android-user-home"
export GRADLE_USER_HOME="$CP_TOOLS/gradle-home"
export UV_CACHE_DIR="$CP_TOOLS/uv-cache"
export UV_PYTHON_INSTALL_DIR="$CP_TOOLS/uv-python"
export UV_PROJECT_ENVIRONMENT="$CP_TOOLS/ml-venv"
export PIP_CACHE_DIR="$CP_TOOLS/pip-cache"
export HF_HOME="$CP_TOOLS/hf-home"
export TORCH_HOME="$CP_TOOLS/torch-home"
export KAGGLE_CONFIG_DIR="$CP_TOOLS/kaggle"
export MPLCONFIGDIR="$CP_TOOLS/matplotlib"
export TEMP="$CP_TOOLS/tmp"
export TMP="$CP_TOOLS/tmp"
export TMPDIR="$CP_TOOLS/tmp"
mkdir -p "$CP_TOOLS/tmp" "$CP_TOOLS/java-user-home"
# JVM options cannot contain spaces ("Programas Data"), so use Windows 8.3 short paths.
_cp_short() { cygpath -d "$1" 2>/dev/null || echo "$1"; }
export GRADLE_OPTS="-Djava.io.tmpdir=$(_cp_short "$CP_TOOLS/tmp")"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$(_cp_short "$CP_TOOLS/tmp") -Duser.home=$(_cp_short "$CP_TOOLS/java-user-home")"

export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Optional secrets (API keys) live in a gitignored file the user fills in themselves.
if [ -f "$CP_TOOLS/secrets.env" ]; then
  set -a; . "$CP_TOOLS/secrets.env"; set +a
fi

mkdir -p "$CP_TOOLS/tmp" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME" "$CP_TOOLS/java-user-home"
