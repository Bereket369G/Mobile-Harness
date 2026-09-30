#!/usr/bin/env bash
# =============================================================================
# bootstrap-and-build-apk.sh  --  one command, end-to-end APK build
# =============================================================================
#
# Purpose
# -------
# Mobile Harness builds its native engine (proot, prootloader, libpocketspawn)
# from source with CMake + the Android NDK. This script provisions every build
# input and assembles a real, runnable APK with a single invocation:
#
#     bash scripts/bootstrap-and-build-apk.sh
#
# It is designed to run unattended (CI, Codespaces, a fresh cloud dev box). Every
# step is idempotent, every download is resumable (`curl -C -`), and each stage
# is skipped when already satisfied -- so re-running after a failure continues
# instead of starting over.
#
# -----------------------------------------------------------------------------
# ARCHITECTURE: WHY THIS MUST RUN ON x86_64
# -----------------------------------------------------------------------------
# Google ships the Android NDK host tools for x86_64 ONLY. The NDK's clang is an
# x86-64 ELF (`e_machine=62`) and cannot execute on an aarch64 host -- it fails
# with "/bin/sh: .../clang: not found". There is no aarch64 build of the NDK.
#
#   x86_64 / amd64 (Linux, macOS, GitHub Codespaces, most CI runners)
#       -> CAN build the native layer and therefore a runnable APK.  <== TARGET
#
#   aarch64 / arm64 (phones, ARM laptops)
#       -> CANNOT compile the native layer. This script detects that and points
#          you at a Kotlin-only verification build instead of failing obscurely.
#
# The Kotlin sources are architecture-independent and DO compile on aarch64, so
# on ARM we fall back to `-PmhNativeBuild=false` to at least validate the code.
#
# -----------------------------------------------------------------------------
# WHAT IT INSTALLS
# -----------------------------------------------------------------------------
#   JDK 17            (Temurin)             -- AGP 8.13 requires 17
#   Android cmdline-tools (latest)          -- sdkmanager
#   platform-tools                           -- adb (optional)
#   platforms;android-36                    -- compileSdk 36
#   build-tools;35.0.0                      -- aapt2 / d8 / zipalign
#   ndk;26.1.10909125        (== r26d)       -- the exact revision AGP is told
#   cmake;3.22.1                             -- the version externalNativeBuild asks for
#   native submodules at pinned commits       -- third_party/proot, libandroid-shmem
#
# -----------------------------------------------------------------------------
# USAGE
# -----------------------------------------------------------------------------
#   bash scripts/bootstrap-and-build-apk.sh              # provision + build debug
#   bash scripts/bootstrap-and-build-apk.sh --tasks :app:assembleOnlineDebug
#   bash scripts/bootstrap-and-build-apk.sh --offline    # assume toolchain present
#   bash scripts/bootstrap-and-build-apk.sh --dry-run    # report, change nothing
#
# App identity (install alongside an existing PocketDev instead of replacing it):
#   bash scripts/bootstrap-and-build-apk.sh \
#     --app-id io.github.bereket369g.pocketdev --app-label "PocketDev OC"
#
# Environment overrides:
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK location (default ~/.android-sdk)
#   TOOLCHAIN_CACHE                   download cache (default /tmp/toolchain-cache)
#   JAVA_HOME                         pre-existing JDK 17 to use as-is
#
set -uo pipefail

# ---- pinned build inputs (keep in sync with app/build.gradle.kts) ------------
COMPILE_SDK="36"
BUILD_TOOLS="35.0.0"
NDK_VERSION="26.1.10909125"          # == r26d
CMAKE_VERSION="3.22.1"
GRADLE_MIN="8.14"                    # wrapper pins 8.14; AGP 8.13 needs >= 8.13

NDK_URL="https://dl.google.com/android/repository/android-ndk-r26d-linux.zip"
NDK_SIZE=668556491
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

PROOT_URL="https://github.com/termux/proot.git"
PROOT_REV="61681c6481197e3c0cec6726075053adb740f235"
SHMEM_URL="https://github.com/termux/libandroid-shmem.git"
SHMEM_REV="7f0bd7e25dbdd146265aff7c6a890029e374622d"

# ---- options ----------------------------------------------------------------
TASKS=":app:assembleOnlineDebug"
OFFLINE=0
DRY_RUN=0
APP_ID=""
APP_LABEL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tasks)      TASKS="${2:?--tasks needs a value}"; shift 2 ;;
    --offline)    OFFLINE=1; shift ;;
    --dry-run)    DRY_RUN=1; shift ;;
    --app-id)     APP_ID="${2:?--app-id needs a value}"; shift 2 ;;
    --app-label)  APP_LABEL="${2:?--app-label needs a value}"; shift 2 ;;
    --help|-h)    sed -n '2,60p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

CACHE="${TOOLCHAIN_CACHE:-/tmp/toolchain-cache}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---- logging ----------------------------------------------------------------
if [ -t 1 ]; then
  C_INFO=$'\033[36m'; C_OK=$'\033[32m'; C_WARN=$'\033[33m'; C_ERR=$'\033[31m'; C_0=$'\033[0m'
else
  C_INFO=""; C_OK=""; C_WARN=""; C_ERR=""; C_0=""
fi
log()  { printf '%s[ %s ]%s %s\n' "$C_INFO" "$(date '+%H:%M:%S')" "$C_0" "$*"; }
ok()   { printf '%s[ ok  ]%s %s\n' "$C_OK"   "$C_0" "$*"; }
warn() { printf '%s[warn ]%s %s\n' "$C_WARN" "$C_0" "$*" >&2; }
die()  { printf '%s[FAIL ]%s %s\n' "$C_ERR"  "$C_0" "$*" >&2; exit 1; }
step() { printf '\n%s=== %s ===%s\n' "$C_INFO" "$*" "$C_0"; }
run()  { if [ "$DRY_RUN" = 1 ]; then echo "  (dry-run) $*"; else "$@"; fi; }

# =============================================================================
# 0. environment report + architecture gate
# =============================================================================
detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64)  echo "x86_64" ;;
    aarch64|arm64) echo "aarch64" ;;
    *)             echo "$(uname -m)" ;;
  esac
}
ARCH="$(detect_arch)"

step "environment"
log "repo        : $REPO_ROOT"
log "architecture: $ARCH"
log "os          : $(uname -s) $(uname -r)"
log "sdk path    : $SDK"
log "cache       : $CACHE"
log "build tasks : $TASKS"
if command -v df >/dev/null 2>&1; then
  df -h "$REPO_ROOT" | tail -1 | awk '{print "disk free   : "$4" on "$6}'
fi

# Hard architecture gate for the native layer.
CAN_BUILD_NATIVE=0
case "$ARCH" in
  x86_64) CAN_BUILD_NATIVE=1 ;;
  *)
    warn "architecture is $ARCH, not x86_64"
    warn "the Android NDK ships x86_64 host tools only; the native engine"
    warn "(proot / prootloader / libpocketspawn) CANNOT be compiled here."
    warn "a runnable APK therefore needs an x86_64 host (CI, Codespaces, laptop)."
    ;;
esac

# =============================================================================
# helpers
# =============================================================================
have() { command -v "$1" >/dev/null 2>&1; }

need_curl() {
  have curl || die "curl is required but not installed. On Debian/Ubuntu: apt-get install -y curl"
}

# fetch <url> <dest> <expected-size-or-0>  -- resumable, retries.
fetch() {
  local url="$1" dest="$2" want="${3:-0}" got a
  mkdir -p "$(dirname "$dest")"
  for a in 1 2 3 4 5; do
    curl -L -C - --retry 5 --retry-delay 5 --connect-timeout 60 \
         --speed-time 300 --speed-limit 1024 -o "$dest" "$url" 2>/dev/null
    got=$(stat -c %s "$dest" 2>/dev/null || echo 0)
    if [ "$want" -eq 0 ] || [ "$got" -ge "$want" ]; then
      return 0
    fi
    warn "download attempt ${a}: ${got}/${want} bytes -- retrying"
  done
  return 1
}

# =============================================================================
# 1. JDK 17
# =============================================================================
java_major() {
  have java || { echo 0; return; }
  java -version 2>&1 | awk -F'"' '/version/{print ($2 ~ /^1\./) ? int($3) : int($2); exit}'
}

install_jdk() {
  step "JDK 17"
  local maj; maj="$(java_major)"
  if [ "${JAVA_HOME:-}" != "" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    ok "JAVA_HOME supplied: $($JAVA_HOME/bin/java -version 2>&1 | head -1)"
    return 0
  fi
  if [ "$maj" -ge 17 ] 2>/dev/null; then
    ok "JDK $maj already present: $(java -version 2>&1 | head -1)"
    return 0
  fi
  [ "$maj" != "0" ] && warn "found JDK $maj; AGP 8.13 wants 17+"
  need_curl
  local ver="17.0.13+11" dest="$CACHE/jdk17.tar.gz" url
  url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-${ver/+/%2B}/OpenJDK17U-jdk_x64_linux_hotspot_${ver/+/%2B}.tar.gz"
  # fall back to a known-good tag if the exact patch moved
  if ! curl -sIL "$url" -o /dev/null -w '%{http_code}' | grep -q 200; then
    url="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz"
  fi
  log "downloading Temurin JDK 17 ..."
  [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would fetch $url"; return 0; }
  fetch "$url" "$dest" 0 || { warn "JDK download failed; trying apt"; apt-get update -qq && apt-get install -y openjdk-17-jdk-headless; export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"; return 0; }
  rm -rf "$CACHE/jdk17" && mkdir -p "$CACHE/jdk17"
  tar -xzf "$dest" -C "$CACHE/jdk17" --strip-components=1 || die "JDK extract failed"
  export JAVA_HOME="$CACHE/jdk17"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "JDK 17 installed at $JAVA_HOME"
}

# =============================================================================
# 2. Android SDK
# =============================================================================
have_sdkmanager() {
  [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ] && return 0
  [ -x "$SDK/cmdline-tools/bin/sdkmanager" ] && return 0
  return 1
}

install_cmdline_tools() {
  step "Android cmdline-tools"
  if have_sdkmanager; then
    ok "cmdline-tools present"
    return 0
  fi
  need_curl
  local zip="$CACHE/cmdline-tools.zip"
  log "downloading cmdline-tools (~150 MB) ..."
  [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would fetch $CMDLINE_TOOLS_URL"; return 0; }
  fetch "$CMDLINE_TOOLS_URL" "$zip" 0 || die "cmdline-tools download failed"
  rm -rf "$SDK/cmdline-tools"
  mkdir -p "$SDK/cmdline-tools/tmp"
  unzip -q "$zip" -d "$SDK/cmdline-tools/tmp" || die "cmdline-tools unzip failed"
  # archive contains a top-level "cmdline-tools/" directory
  mv "$SDK/cmdline-tools/tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$SDK/cmdline-tools/tmp"
  ok "cmdline-tools installed"
}

sdk_accept_licenses() {
  yes 2>/dev/null | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 \
    || true
}

install_sdk_packages() {
  step "SDK packages"
  have_sdkmanager || install_cmdline_tools
  local sm="$SDK/cmdline-tools/latest/bin/sdkmanager"
  [ -x "$sm" ] || sm="$SDK/cmdline-tools/bin/sdkmanager"
  [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would install platform/build-tools"; return 0; }
  sdk_accept_licenses
  log "installing platform-tools, platforms;android-${COMPILE_SDK}, build-tools;${BUILD_TOOLS}"
  "$sm" --sdk_root="$SDK" "platform-tools" "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS}" 2>&1 | tail -5
  ok "SDK packages installed"
}

install_ndk() {
  step "NDK ${NDK_VERSION} (r26d)"
  local dest="$SDK/ndk/$NDK_VERSION"
  if [ -f "$dest/source.properties" ]; then
    ok "NDK present: $(tr '\n' ' ' < "$dest/source.properties")"
    return 0
  fi
  need_curl
  [ "$CAN_BUILD_NATIVE" = 1 ] || warn "NDK is x86_64-only; installing anyway for completeness (it will not run on $ARCH)"
  local zip="$CACHE/ndk.zip"
  log "downloading NDK (~638 MB, resumable) ..."
  [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would fetch $NDK_URL"; return 0; }
  fetch "$NDK_URL" "$zip" "$NDK_SIZE" || die "NDK download incomplete"
  log "extracting NDK ..."
  rm -rf "$CACHE/ndkx" && mkdir -p "$CACHE/ndkx"
  unzip -q "$zip" -d "$CACHE/ndkx" || die "NDK unzip failed"
  rm -rf "$dest"; mkdir -p "$SDK/ndk"
  mv "$CACHE/ndkx/android-ndk-r26d" "$dest" || die "unexpected NDK archive layout"
  rm -f "$zip"
  [ -f "$dest/source.properties" ] || die "NDK install incomplete"
  ok "NDK installed: $(tr '\n' ' ' < "$dest/source.properties")"
}

install_cmake() {
  step "CMake ${CMAKE_VERSION}"
  local dest="$SDK/cmake/$CMAKE_VERSION"
  if [ -x "$dest/bin/cmake" ] && "$dest/bin/cmake" --version >/dev/null 2>&1; then
    ok "CMake present: $("$dest/bin/cmake" --version | head -1)"
    return 0
  fi
  [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would install cmake ${CMAKE_VERSION}"; return 0; }
  # Prefer the SDK's own cmake; it ships a compatible ninja alongside.
  if have_sdkmanager; then
    local sm="$SDK/cmdline-tools/latest/bin/sdkmanager"
    [ -x "$sm" ] || sm="$SDK/cmdline-tools/bin/sdkmanager"
    sdk_accept_licenses
    "$sm" --sdk_root="$SDK" "cmake;${CMAKE_VERSION}" 2>&1 | tail -3
  fi
  if [ -x "$dest/bin/cmake" ] && "$dest/bin/cmake" --version >/dev/null 2>&1; then
    ok "CMake present: $("$dest/bin/cmake" --version | head -1)"
    return 0
  fi
  # Fallback for hosts without the SDK package (e.g. aarch64): native pip wheels.
  if have pip3; then
    log "falling back to native pip wheels for cmake+ninja"
    pip3 install --quiet --target "$CACHE/cmake-pkg" cmake  || die "pip cmake failed"
    pip3 install --quiet --target "$CACHE/ninja-pkg" ninja   || die "pip ninja failed"
    mkdir -p "$dest/bin"
    cp -a "$CACHE/cmake-pkg/cmake/data/." "$dest/"
    cp -a "$CACHE/ninja-pkg/bin/ninja" "$dest/bin/ninja"
    chmod +x "$dest/bin/"* 2>/dev/null || true
    printf 'Pkg.Revision = %s\nPkg.Path = cmake;%s\n' "$CMAKE_VERSION" "$CMAKE_VERSION" > "$dest/source.properties"
  fi
  [ -x "$dest/bin/cmake" ] || warn "CMake ${CMAKE_VERSION} not available; native build may fail"
}

# =============================================================================
# 3. native submodules (pinned commits)
# =============================================================================
install_submodules() {
  step "native submodules"
  have git || die "git is required but not installed"
  populate() {
    local url="$1" rev="$2" dest="$3" name="$4" probe="$5"
    if [ -e "$dest/$probe" ]; then
      ok "submodule $name present"
      return 0
    fi
    log "cloning $name @ ${rev:0:7} ..."
    [ "$DRY_RUN" = 1 ] && { echo "  (dry-run) would clone $url"; return 0; }
    rm -rf "${CACHE}/${name}"
    git clone --quiet "$url" "${CACHE}/${name}" || die "clone $name failed"
    git -C "${CACHE}/${name}" checkout --quiet "$rev" || die "checkout $name failed"
    mkdir -p "$dest"; cp -a "${CACHE}/${name}/." "$dest/"; rm -rf "$dest/.git"
    [ -e "$dest/$probe" ] || die "submodule $name incomplete"
    ok "submodule $name populated"
  }
  populate "$PROOT_URL" "$PROOT_REV" third_party/proot              proot  "src"
  populate "$SHMEM_URL" "$SHMEM_REV" third_party/libandroid-shmem   shmem  "shmem.c"
}

# =============================================================================
# 4. build
# =============================================================================
write_local_properties() {
  step "local.properties"
  if [ "$DRY_RUN" = 1 ]; then echo "  (dry-run) would write sdk.dir=$SDK"; return 0; fi
  # local.properties is machine-local and gitignored; write it for the SDK path.
  printf 'sdk.dir=%s\n' "$SDK" > local.properties
  ok "sdk.dir=$SDK"
}

build() {
  step "gradle build"
  local gw="./gradlew"
  [ -x "$gw" ] || { chmod +x "$gw" 2>/dev/null || true; }
  local args=""
  if [ "$CAN_BUILD_NATIVE" = 1 ]; then
    log "native build ENABLED (host is $ARCH)"
  else
    log "native build DISABLED (-PmhNativeBuild=false; host is $ARCH)"
    args="-PmhNativeBuild=false"
  fi
  # App identity. A different applicationId makes Android install this build
  # *alongside* an existing PocketDev install (separate data dir + icon) instead
  # of demanding an uninstall. Unset keeps the upstream package id.
  [ -n "$APP_ID" ]    && args="$args -PmhApplicationId=$APP_ID"
  [ -n "$APP_LABEL" ] && args="$args -PmhAppLabel=$APP_LABEL"
  if [ -n "$APP_ID" ]; then
    log "application id : $APP_ID"
    log "app label      : ${APP_LABEL:-<default>}"
  fi
  if [ "$DRY_RUN" = 1 ]; then
    echo "  (dry-run) would run: $gw $args $TASKS"
    return 0
  fi
  log "running: $gw $args $TASKS"
  # JAVA_HOME must be exported for the Gradle wrapper.
  if [ -z "${JAVA_HOME:-}" ] && have javac; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
  fi
  "$gw" $args $TASKS || die "gradle build FAILED -- see output above"
}

report() {
  step "artifacts"
  local found=0
  for apk in $(find app/build/outputs/apk -name '*.apk' 2>/dev/null | sort); do
    ok "$apk  ($(du -h "$apk" | cut -f1))"
    found=1
  done
  [ "$found" = 1 ] || warn "no APK produced -- check the build output"
  if [ "$CAN_BUILD_NATIVE" = 0 ]; then
    warn "NOTE: built without the native engine (host is $ARCH)."
    warn "      Re-run this exact script on an x86_64 machine for a runnable APK."
  fi
}

# =============================================================================
# main
# =============================================================================
if [ "$OFFLINE" = 0 ]; then
  install_jdk
  install_sdk_packages
  install_ndk
  install_cmake
fi
install_submodules
write_local_properties
build
report

ok "done."
