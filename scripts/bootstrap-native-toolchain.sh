#!/usr/bin/env bash
# Bootstrap the native toolchain needed to assemble a real APK.
#
# The project builds proot / prootloader / libpocketspawn.so from source via
# CMake, so a runnable APK needs:
#   1. the two native submodules (third_party/proot, third_party/libandroid-shmem)
#   2. Android NDK 26.1.10909125  (== r26d; AGP is told this exact revision)
#   3. CMake 3.22.1              (the version the externalNativeBuild block asks for)
#   4. a native `ninja` for CMake
#
# IMPORTANT — architecture:
#   The Android NDK ships host tools for x86_64 ONLY. Its clang is an x86-64 ELF
#   and cannot execute on an aarch64 host ("/bin/sh: .../clang: not found").
#   The steps below make the *sources and build files* available and let CMake
#   configure, but the final compile of the native layer can only finish on an
#   x86_64 machine. On an ARM device use this to prepare sources, then verify
#   with `gradle -PmhNativeBuild=false`.
#
# Every download uses `curl -C -` with aggressive retry, so a flaky or slow
# connection resumes instead of restarting. Safe to run repeatedly; each step is
# skipped when already satisfied.
set -uo pipefail

CACHE="${TOOLCHAIN_CACHE:-/tmp/toolchain-cache}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/root/android-sdk}}"
NDK_VERSION="26.1.10909125"      # == r26d
CMAKE_VERSION="3.22.1"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r26d-linux.zip"
NDK_SIZE=668556491

PROOT_URL="https://github.com/termux/proot.git"
PROOT_REV="61681c6481197e3c0cec6726075053adb740f235"
SHMEM_URL="https://github.com/termux/libandroid-shmem.git"
SHMEM_REV="7f0bd7e25dbdd146265aff7c6a890029e374622d"

log() { printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*"; }
die() { log "ERROR: $*"; exit 1; }

# fetch <url> <dest> <expected-size> — resumable, retries a few times.
fetch() {
  local url="$1" dest="$2" want="$3" got
  for a in 1 2 3 4 5; do
    curl -L -C - --retry 5 --retry-delay 5 --connect-timeout 60 \
         --speed-time 300 --speed-limit 1024 -o "$dest" "$url"
    got=$(stat -c %s "$dest" 2>/dev/null || echo 0)
    [ "$got" -ge "$want" ] && return 0
    log "  attempt ${a}: ${got}/${want} bytes, retrying..."
  done
  return 1
}

mkdir -p "$CACHE"

# ---- 1. native submodules -------------------------------------------------
# Populate by cloning the pinned commit directly; a fresh clone of the parent
# repo has no .git/modules, so `git submodule update --init` cannot work here.
populate_submodule() {
  local url="$1" rev="$2" dest="$3" name="$4" probe="$5"
  if [ -e "$dest/$probe" ]; then
    log "submodule ${name}: already populated"
    return 0
  fi
  log "submodule ${name}: cloning ${rev:0:7}"
  rm -rf "${CACHE}/${name}"
  git clone --quiet "$url" "${CACHE}/${name}" || die "clone ${name} failed"
  git -C "${CACHE}/${name}" checkout --quiet "$rev" || die "checkout ${name} failed"
  mkdir -p "$dest"
  cp -a "${CACHE}/${name}/." "$dest/"
  rm -rf "$dest/.git"
  [ -e "$dest/$probe" ] || die "submodule ${name} incomplete after copy"
  log "submodule ${name}: populated at ${rev:0:7}"
}

step_submodules() {
  populate_submodule "$PROOT_URL" "$PROOT_REV" third_party/proot proot "src"
  populate_submodule "$SHMEM_URL" "$SHMEM_REV" third_party/libandroid-shmem shmem "shmem.c"
}

# ---- 2. NDK ---------------------------------------------------------------
step_ndk() {
  local dest="$SDK/ndk/$NDK_VERSION"
  if [ -f "$dest/source.properties" ]; then
    log "NDK ${NDK_VERSION}: already installed"
    return 0
  fi
  local zip="$CACHE/ndk.zip"
  log "NDK ${NDK_VERSION}: downloading r26d (~638 MB, resumable) ..."
  fetch "$NDK_URL" "$zip" "$NDK_SIZE" || die "NDK download incomplete"
  log "NDK: extracting (a few minutes) ..."
  rm -rf "$CACHE/ndkx" && mkdir -p "$CACHE/ndkx"
  unzip -q "$zip" -d "$CACHE/ndkx" || die "NDK unzip failed"
  rm -rf "$dest"; mkdir -p "$SDK/ndk"
  mv "$CACHE/ndkx/android-ndk-r26d" "$dest" || die "unexpected NDK archive layout"
  rm -f "$zip"
  [ -f "$dest/source.properties" ] || die "NDK install incomplete"
  log "NDK: installed $(tr '\n' ' ' < "$dest/source.properties")"
}

# ---- 3/4. CMake + ninja ---------------------------------------------------
# Google's cmake-3.22.1-linux.zip is an x86_64 build. On aarch64 we instead
# install native binaries from PyPI wheels, which do ship aarch64.
step_cmake() {
  local dest="$SDK/cmake/$CMAKE_VERSION"
  if [ -x "$dest/bin/cmake" ] && "$dest/bin/cmake" --version >/dev/null 2>&1; then
    log "CMake: already installed ($("$dest/bin/cmake" --version | head -1))"
    return 0
  fi
  log "CMake: installing native build via pip wheel"
  pip3 install --quiet --target "$CACHE/cmake-pkg" cmake || die "pip cmake failed"
  pip3 install --quiet --target "$CACHE/ninja-pkg" ninja || die "pip ninja failed"
  mkdir -p "$dest/bin"
  cp -a "$CACHE/cmake-pkg/cmake/data/." "$dest/"
  cp -a "$CACHE/ninja-pkg/bin/ninja" "$dest/bin/ninja"
  chmod +x "$dest/bin/"* 2>/dev/null || true
  # The pip wheel's data/bin/cmake is the real ELF; the top-level bin/cmake is a
  # Python wrapper. Prefer the ELF.
  if ! head -c 4 "$dest/bin/cmake" 2>/dev/null | grep -q ELF; then
    cp -a "$CACHE/cmake-pkg/cmake/data/bin/cmake" "$dest/bin/cmake"
    chmod +x "$dest/bin/cmake"
  fi
  printf 'Pkg.Revision = %s\nPkg.Path = cmake;%s\n' "$CMAKE_VERSION" "$CMAKE_VERSION" \
    > "$dest/source.properties"
  "$dest/bin/cmake" --version >/dev/null 2>&1 || die "cmake not runnable after install"
  log "CMake: $("$dest/bin/cmake" --version | head -1); ninja $("$dest/bin/ninja" --version)"
}

log "SDK   : $SDK"
log "cache : $CACHE"
df -h / 2>/dev/null | tail -1

case "${1:-all}" in
  submodules) step_submodules ;;
  ndk)        step_ndk ;;
  cmake)      step_cmake ;;
  all)        step_submodules; step_ndk; step_cmake ;;
  *)          die "usage: $0 [all|submodules|ndk|cmake]" ;;
esac

log "final disk:"
df -h / 2>/dev/null | tail -1
log "next: gradle :app:assembleOnlineDebug   (native compile needs an x86_64 host)"
