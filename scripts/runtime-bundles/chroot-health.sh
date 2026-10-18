#!/usr/bin/env bash
# Health check for the NextCraft Forge Ubuntu chroot. Run it from the in-app chroot terminal:
#   bash chroot-health.sh 2>&1 | tee chroot-health.log
# Prints one OK/ERR line per component. ERR for a stack that is not installed is expected.
pass=0
fail=0

# check NAME COMMAND: OK when COMMAND exits 0. Each command must fail as a whole (use &&, not ;).
check() {
  local name="$1"
  shift
  local out
  if out=$(timeout 60 bash -c "$*" 2>&1); then
    printf 'OK   %-22s %s\n' "$name" "$(printf '%s' "$out" | head -1 | cut -c1-70)"
    pass=$((pass + 1))
  else
    printf 'ERR  %-22s %s\n' "$name" "$(printf '%s' "$out" | tail -1 | cut -c1-90)"
    fail=$((fail + 1))
  fi
}

echo "== Sistem"
check "rootfs"            'cat /.pocket-rootfs-version'
check "glibc"             'ldd --version | head -1'
check "disk /"            'df -h / | awk "NR==2{print \$4\" free\"; exit (\$5+0>95)}"'
check "/tmp writable"     'f=$(mktemp) && rm -f "$f" && echo ok'

echo "== Mount"
for m in /dev /dev/pts /proc /sys /tmp /system /apex /vendor /product; do
  check "mount $m"        "grep -q ' $m ' /proc/mounts && echo mounted"
done
# Bionic tools need the APEX modules mounted under /apex (see docs/RUNTIME_KNOWN_ISSUES.md #1).
check "apex submounts"    'n=$(grep -c " /apex/" /proc/mounts); echo "$n submount"; [ "$n" -gt 0 ]'
check "linker64"          't=$(readlink -f /system/bin/linker64) && test -e "$t" && echo "$t"'

echo "== Jaringan"
check "DNS"               'getent hosts archive.ubuntu.com'
check "HTTPS"             'curl -fsS -o /dev/null -m 15 -w "%{http_code}" https://registry.npmjs.org/'
check "apt index"         'apt-get -o Acquire::Retries=0 update -qq >/dev/null && echo ok'

echo "== Core"
for c in bash git curl jq rg zstd; do check "$c" "$c --version"; done
check "node"              'node --version'
check "npm"               'npm --version'

echo "== Agen"
check "claude"            'claude --version'
check "dsh"               'dsh --version'
check "agy"               '/root/.local/bin/agy --version'
check "gh"                '/root/.local/bin/gh --version'

echo "== Python"
check "python3"           'python3 --version'
check "pip3"              'pip3 --version'
check "venv"              'd=$(mktemp -d) && python3 -m venv "$d/v" && "$d/v/bin/python" -c "print(\"venv ok\")"; rc=$?; rm -rf "$d"; exit $rc'
check "stdlib ssl/sqlite" 'python3 -c "import ssl,sqlite3,ctypes; print(ssl.OPENSSL_VERSION)"'

echo "== Android"
check "java 17"           'java -version 2>&1 | head -1 | grep -E "\"17\."'
check "javac"             'javac -version'
check "gradle"            'gradle --version --offline | grep ^Gradle'
check "android.jar"       'ls /root/android-sdk/platforms/android-36/android.jar'
check "aapt2"             '/root/android-sdk/build-tools/35.0.0/aapt2 version'
# zipalign without arguments prints usage and exits non-zero; a missing linker gives "cannot execute" instead.
check "zipalign"          'out=$(/root/android-sdk/build-tools/35.0.0/zipalign 2>&1); echo "$out" | head -1; echo "$out" | grep -qi "usage"'
check "d8"                '/root/android-sdk/build-tools/35.0.0/d8 --version'
check "gradle init"       'grep -q "android.aapt2FromMavenOverride" /root/.gradle/init.d/pocketdev-android.gradle && echo ok'

echo "== C/C++"
for c in gcc g++ make cmake gdb; do check "$c" "$c --version"; done
check "compile C"         'd=$(mktemp -d) && printf "int main(){return 0;}" > "$d/a.c" && gcc "$d/a.c" -o "$d/a" && "$d/a" && echo ok; rc=$?; rm -rf "$d"; exit $rc'

echo "== PHP"
check "php"               'php --version'
check "composer"          'composer --version 2>/dev/null | grep -i "composer version"'

echo
echo "Selesai: $pass OK, $fail ERR"
