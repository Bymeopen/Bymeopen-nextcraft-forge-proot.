#!/usr/bin/env bash
set -euo pipefail

# Build `proot` for the NextCraft Forge PRoot engine with the Android NDK, as app/src/main/jniLibs/arm64-v8a/libproot.so
# (+ libproot_loader.so). This mirrors how Termux builds the proot that proot-distro uses on non-root phones:
#
#   - source: the Termux fork (termux/proot), which catches the SIGSYS that Android's app seccomp filter raises for
#     syscalls outside bionic's allowlist, emulates the common ones and returns ENOSYS for the rest;
#   - toolchain: NDK clang against bionic (API 24), so proot itself is an ordinary Android executable
#     (PIE, dynamically linked to /system/bin/linker64 + libc.so) instead of a static glibc program;
#   - 16 KB page alignment (Android 15 devices with 16 KB pages), talloc linked statically.
#
# Android forbids executing files from the app data directory, so both binaries ship inside the APK as native
# libraries and run from nativeLibraryDir (jniLibs.useLegacyPackaging = true).
#
# Sources (verified against the pinned checksums / commit):
#   proot   termux/proot tag v5.1.107.98 = commit e2d843ee6dab076ea0ed1ffd5d0ea12232185499
#   talloc  2.5.0  https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz
#
#   ANDROID_NDK_HOME=/path/to/ndk/27.2.12479018 ./build-proot-ndk.sh
#
# Needs: NDK r27 (clang, llvm-ar, llvm-strip), git, curl, python3 (talloc's waf), gawk (loader-info.awk uses strtonum),
# readelf (binutils). Environment: NEXTCRAFT_OUT_DIR (default dist/runtime-bundles), NEXTCRAFT_WORK_DIR, NEXTCRAFT_KEEP_WORK.
NDK="${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an Android NDK r27 directory}"
API=24
PROOT_REPO="${NEXTCRAFT_PROOT_REPO:-https://github.com/termux/proot}"
PROOT_TAG="v5.1.107.98"
PROOT_COMMIT="${NEXTCRAFT_PROOT_COMMIT:-e2d843ee6dab076ea0ed1ffd5d0ea12232185499}"
TALLOC_VERSION="2.5.0"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
WORK_PARENT="${NEXTCRAFT_WORK_DIR:-}"
KEEP_WORK="${NEXTCRAFT_KEEP_WORK:-0}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="${NEXTCRAFT_OUT_DIR:-$ROOT_DIR/dist/runtime-bundles}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$TC/aarch64-linux-android${API}-clang" ]] || { echo "NDK clang not found under $TC" >&2; exit 1; }
for tool in git curl python3 gawk readelf sha256sum; do
  command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 1; }
done
export PATH="$TC:$PATH"
export CC="aarch64-linux-android${API}-clang" AR=llvm-ar RANLIB=llvm-ranlib STRIP=llvm-strip

work="$(mktemp -d ${WORK_PARENT:+-p "$WORK_PARENT"})"
cleanup() {
  if [[ "$KEEP_WORK" != "1" && -n "$work" && "$work" != "/" ]]; then
    rm -rf --one-file-system -- "$work"
  else
    echo "Work directory kept: $work" >&2
  fi
}
trap cleanup EXIT
prefix="$work/prefix"
mkdir -p "$work/src" "$prefix"

echo "Fetching sources"
curl -fsSL -o "$work/src/talloc.tar.gz" "$TALLOC_URL"
echo "$TALLOC_SHA256  $work/src/talloc.tar.gz" | sha256sum -c --status
tar -xzf "$work/src/talloc.tar.gz" -C "$work/src"
git clone -q --depth 1 --branch "$PROOT_TAG" "$PROOT_REPO" "$work/src/proot"
actual_commit="$(git -C "$work/src/proot" rev-parse HEAD)"
[[ "$actual_commit" == "$PROOT_COMMIT" ]] || { echo "proot $PROOT_TAG is $actual_commit, expected $PROOT_COMMIT" >&2; exit 1; }
echo "proot $PROOT_TAG = $actual_commit"

echo "Building talloc (static, bionic)"
# waf cannot run its configure probes under cross-compilation; these answers are the ones termux-packages uses.
cat > "$work/src/talloc-$TALLOC_VERSION/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS
(
  cd "$work/src/talloc-$TALLOC_VERSION"
  ./configure --prefix="$prefix" --disable-rpath --disable-python --cross-compile --cross-answers=cross-answers.txt >/dev/null
  make -j"$(nproc)" >/dev/null
  make install >/dev/null
  rm -f "$prefix"/lib/libtalloc.so*
  # bionic < API 34 has no memset_explicit; talloc's replace objects provide rep_memset_explicit.
  llvm-ar rcs "$prefix/lib/libtalloc.a" bin/default/talloc*.o bin/default/lib/replace/replace*.o
)

echo "Building proot (bionic, PIE, 16 KB pages)"
# ashmem_memfd.c uses strcmp/memset without including <string.h>; NDK r27 clang rejects implicit declarations.
sed -i '0,/^#include </s//#include <string.h>\n#include </' "$work/src/proot/src/extension/ashmem_memfd/ashmem_memfd.c"
(
  cd "$work/src/proot/src"
  # CFLAGS/LDFLAGS go through the environment: the GNUmakefile appends `pkg-config --libs talloc` with `+=`, which a
  # command-line LDFLAGS would override. HAS_LOADER_32BIT= skips the 32-bit (-m32) loader the app never needs.
  PKG_CONFIG_PATH="$prefix/lib/pkgconfig" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$prefix/include -DARG_MAX=131072 -DVERSION=\"${PROOT_TAG#v}\"" \
  CFLAGS="-O2 -fPIE -ffile-prefix-map=$work=." \
  LDFLAGS="-L$prefix/lib -fPIE -pie -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
  LOADER_LDFLAGS="-Wl,-z,max-page-size=16384" \
  make -j"$(nproc)" CC="$CC" AR=llvm-ar STRIP=llvm-strip OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump HAS_LOADER_32BIT= >/dev/null
  llvm-strip --strip-all proot
)

mkdir -p "$OUT_DIR"
cp "$work/src/proot/src/proot" "$OUT_DIR/libproot.so"
# proot runs every guest execve through this small ELF loader; by default it is extracted into PROOT_TMP_DIR, which
# Android refuses to exec from app data, so proot-exec.sh points PROOT_LOADER at this copy shipped beside proot.
cp "$work/src/proot/src/loader/loader" "$OUT_DIR/libproot_loader.so"
chmod 755 "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
for f in libproot.so libproot_loader.so; do
  desc="$(file -b "$OUT_DIR/$f")"
  [[ "$desc" == *"ARM aarch64"* ]] || { echo "Unexpected binary $f: $desc" >&2; exit 1; }
  readelf -lW "$OUT_DIR/$f" | awk '/LOAD/ && $NF != "0x4000" { bad = 1 } END { exit bad }' ||
    { echo "$f is not 16 KB page aligned" >&2; exit 1; }
done
sha256sum "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
wc -c "$OUT_DIR/libproot.so" "$OUT_DIR/libproot_loader.so"
echo "Copy both to app/src/main/jniLibs/arm64-v8a/ and record the SHA-256 values in docs/NON_ROOT.md"
