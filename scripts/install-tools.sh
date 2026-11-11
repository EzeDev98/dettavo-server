#!/usr/bin/env bash
# Installs Dettavo's native tools: FFmpeg, whisper.cpp (whisper-cli) and the transcription model.
# Safe to run again: anything already in place is skipped. Java 21 and Node 22 are checked, never installed.
set -euo pipefail

WORKSPACE_DIR="${APP_WORKSPACE_DIR:-$HOME/Dettavo}"
MODEL_PATH="${WHISPER_MODEL_PATH:-$WORKSPACE_DIR/models/ggml-small.en.bin}"
MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin"
MODEL_MIN_BYTES=480000000
WHISPER_REPOSITORY="https://github.com/ggml-org/whisper.cpp.git"
WHISPER_REF="${WHISPER_CPP_REF:-v1.9.5}"
WHISPER_SOURCE_DIR="$WORKSPACE_DIR/tools/whisper.cpp"
LINK_DIR="${TOOLS_LINK_DIR:-/usr/local/bin}"
FFMPEG_MIN_MAJOR=7
REQUIRED_ENCODERS=(h264_videotoolbox libx264 libmp3lame)

ok()   { printf '  ok    %s\n' "$1"; }
step() { printf '  ...   %s\n' "$1"; }
warn() { printf '  warn  %s\n' "$1"; }
fail() { printf '  fail  %s\n' "$1" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
file_bytes() { stat -f%z "$1" 2>/dev/null || echo 0; }

create_workspace() {
  mkdir -p "$WORKSPACE_DIR/projects" "$WORKSPACE_DIR/models" "$WORKSPACE_DIR/logs"
  ok "Workspace $WORKSPACE_DIR"
}

check_runtimes() {
  if have java && java -version 2>&1 | head -1 | grep -qE '"21\.'; then
    ok "Java 21"
  else
    warn "Java 21 is not the java on PATH; dettavo-server needs it"
  fi
  if have node && node -v | grep -qE '^v22\.'; then
    ok "Node $(node -v)"
  else
    warn "Node 22 is not the node on PATH; dettavo-client needs it"
  fi
}

check_ffmpeg() {
  if ! have ffmpeg; then
    have brew || fail "FFmpeg is missing and Homebrew is not installed. Install FFmpeg 7 or newer, then run this again."
    [[ "$(uname -m)" == "x86_64" ]] && warn "Homebrew compiles FFmpeg from source on Intel Macs; this can take a long time"
    step "Installing FFmpeg with Homebrew"
    brew install ffmpeg
    brew pin ffmpeg
  fi

  local version major encoders encoder
  version="$(ffmpeg -version | head -1 | sed -E 's/^ffmpeg version n?([^ ]+).*/\1/')"
  major="${version%%.*}"
  [[ "$major" =~ ^[0-9]+$ ]] && (( major >= FFMPEG_MIN_MAJOR )) || fail "FFmpeg $version is older than $FFMPEG_MIN_MAJOR.0"

  encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
  for encoder in "${REQUIRED_ENCODERS[@]}"; do
    grep -q " $encoder " <<<"$encoders" || fail "FFmpeg $version has no $encoder encoder"
  done
  ok "FFmpeg $version with ${REQUIRED_ENCODERS[*]}"
}

ensure_cmake() {
  have cmake && return
  have python3 || fail "CMake is missing and python3 is not available to install it"
  step "Installing CMake with pip"
  python3 -m pip install --user --quiet cmake
  export PATH="$(python3 -m site --user-base)/bin:$PATH"
  have cmake || fail "CMake installed but is not on PATH; add $(python3 -m site --user-base)/bin to PATH"
}

install_whisper() {
  if have whisper-cli; then
    ok "whisper-cli at $(command -v whisper-cli)"
    return
  fi

  have git || fail "git is missing; run xcode-select --install, then run this again"
  [[ -w "$LINK_DIR" ]] || fail "$LINK_DIR is not writable; run again with TOOLS_LINK_DIR set to a folder on your PATH"
  ensure_cmake

  if [[ ! -d "$WHISPER_SOURCE_DIR/.git" ]]; then
    step "Downloading whisper.cpp $WHISPER_REF"
    mkdir -p "$(dirname "$WHISPER_SOURCE_DIR")"
    git clone --quiet --depth 1 --branch "$WHISPER_REF" "$WHISPER_REPOSITORY" "$WHISPER_SOURCE_DIR"
  fi

  local metal=OFF
  [[ "$(uname -m)" == "arm64" ]] && metal=ON

  step "Building whisper-cli (a few minutes)"
  cmake -S "$WHISPER_SOURCE_DIR" -B "$WHISPER_SOURCE_DIR/build" -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DGGML_METAL="$metal" -DWHISPER_BUILD_TESTS=OFF >/dev/null
  cmake --build "$WHISPER_SOURCE_DIR/build" -j "$(sysctl -n hw.ncpu)" --config Release --target whisper-cli >/dev/null

  ln -sf "$WHISPER_SOURCE_DIR/build/bin/whisper-cli" "$LINK_DIR/whisper-cli"
  have whisper-cli || fail "whisper-cli was built but $LINK_DIR is not on PATH"
  ok "whisper-cli built and linked into $LINK_DIR"
}

download_model() {
  mkdir -p "$(dirname "$MODEL_PATH")"
  if (( $(file_bytes "$MODEL_PATH") >= MODEL_MIN_BYTES )); then
    ok "Model $MODEL_PATH"
    return
  fi

  rm -f "$MODEL_PATH"
  local partial="$MODEL_PATH.partial"
  step "Downloading $(basename "$MODEL_PATH") (about 465 MB)"
  curl -fL --progress-bar -C - -o "$partial" "$MODEL_URL"
  (( $(file_bytes "$partial") >= MODEL_MIN_BYTES )) || fail "The model download is incomplete; run this again to resume it"
  mv "$partial" "$MODEL_PATH"
  ok "Model downloaded to $MODEL_PATH"
}

echo "Dettavo tools"
create_workspace
check_runtimes
check_ffmpeg
install_whisper
download_model
echo "Done."
