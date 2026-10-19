#!/system/bin/sh
# scripts/runtime-bundles/chroot-exec.sh
# NextCraft Forge (com.nextcraft.forge) — Pure Root Chroot Supervisor Script
# Dipanggil via:
#   su -mm -c "/data/data/com.nextcraft.forge/files/runtime/bin/chroot-exec.sh <rootfs> <workspace_host> <guest_workspace> <bridge_host> <app_uid> <cmd...>"
set -e

if [ "$#" -lt 6 ]; then
    echo "Usage: $0 <rootfs> <workspace_host> <guest_workspace> <bridge_host> <app_uid> <command...>" >&2
    exit 64
fi

ROOTFS="$1"
WORKSPACE_HOST="$2"
GUEST_WORKSPACE="$3"
BRIDGE_HOST="$4"
APP_UID="$5"
shift 5

# Validasi keamanan path guest workspace (wajib /root, /workspace, atau /workspace/<slug> tanpa '..' atau nested '/')
case "$GUEST_WORKSPACE" in
    *..*|/workspace/.|/workspace/*/*|*[!a-zA-Z0-9._/-]*)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
    /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
    *)
        echo "Invalid guest workspace path: $GUEST_WORKSPACE" >&2
        exit 65
        ;;
esac

case "$APP_UID" in
    ''|*[!0-9]*)
        echo "Invalid app UID: $APP_UID" >&2
        exit 65
        ;;
esac

is_mounted() {
    awk -v target="$1" '$2 == target { found=1; exit } END { exit !found }' /proc/mounts
}

# Exit Trap: Kembalikan kepemilikan file workspace & bridge ke UID aplikasi Android (u0_aXXX)
# Mencegah bug EACCES pada WorkspaceCheckpoints.kt setelah proses Chroot (UID 0) selesai,
# sekaligus mencegah chown rekursif jika WORKSPACE_HOST berada di dalam ROOTFS (misal /root)!
cleanup_ownership() {
    case "$WORKSPACE_HOST" in
        "$ROOTFS"|"$ROOTFS"/*)
            ;;
        *)
            chown -R "$APP_UID:$APP_UID" "$WORKSPACE_HOST" 2>/dev/null || true
            chmod -R u+rwX,g+rwX "$WORKSPACE_HOST" 2>/dev/null || true
            ;;
    esac
    chown -R "$APP_UID:$APP_UID" "$BRIDGE_HOST" 2>/dev/null || true
    chmod -R u+rwX,g+rwX "$BRIDGE_HOST" 2>/dev/null || true
}
# INT/TERM harus mengakhiri skrip (exit memicu EXIT trap sekali); trap handler biasa akan
# melanjutkan eksekusi (mis. terus me-mount) dan menjalankan cleanup dua kali.
trap cleanup_ownership EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Fungsi Idempotent Mount untuk direktori sistem statis
mount_once() {
    local fstype="$1"
    local src="$2"
    local dst="$3"
    local opts="$4"
    mkdir -p "$dst"
    if ! is_mounted "$dst"; then
        if [ "$fstype" = "bind" ]; then
            mount --bind "$src" "$dst"
            if [ "$opts" = "ro" ]; then
                mount -o remount,ro,bind "$dst" 2>/dev/null || true
            fi
        else
            mount -t "$fstype" -o "$opts" "$src" "$dst"
        fi
    fi
}

# Android's /apex is a tmpfs with every APEX module mounted below it, and /system/bin/linker64 (the interpreter
# of Bionic tools such as aapt2) points into /apex/com.android.runtime. A plain bind leaves those modules out.
# A recursive bind is not used: its copies are peers of the shared Android mounts until they are made slaves,
# and unmounting a copy that is still a peer also unmounts the real module. Instead the directory is bound on
# its own, made a slave while it has no submounts yet, and only then are the modules bound one by one.
has_submounts() {
    awk -v prefix="$1/" 'index($2, prefix) == 1 { found=1; exit } END { exit !found }' /proc/mounts
}

make_slave() {
    mount --make-rslave "$1" 2>/dev/null || mount -o rslave "$1" 2>/dev/null ||
        mount --make-slave "$1" 2>/dev/null || mount -o slave "$1" 2>/dev/null
}

mount_bionic() {
    local src="$1"
    local dst="$2"
    mkdir -p "$dst"
    # Earlier versions bound these directories without their submounts; replace such a mount.
    if is_mounted "$dst" && has_submounts "$src" && ! has_submounts "$dst"; then
        umount "$dst" 2>/dev/null || umount -l "$dst" 2>/dev/null || true
    fi
    is_mounted "$dst" && return 0
    mount -o bind "$src" "$dst" || return 1
    if ! make_slave "$dst"; then
        echo "Warning: mount propagation cannot be changed; $dst is bound without its submounts" >&2
        mount -o remount,bind,ro "$dst" 2>/dev/null || true
        return 0
    fi
    # Parents first, so each module lands on a mount point that is already a slave copy.
    awk -v prefix="$src/" 'index($2, prefix) == 1 { print $2 }' /proc/mounts | sort | while read -r sub; do
        target="$dst${sub#"$src"}"
        [ -d "$sub" ] && [ -d "$target" ] || continue
        is_mounted "$target" && continue
        if mount -o bind "$sub" "$target" 2>/dev/null && ! make_slave "$target"; then
            umount "$target" 2>/dev/null || true
        fi
    done
    mount -o remount,bind,ro "$dst" 2>/dev/null || true
}

# Fungsi Dynamic Bind Mount: mendeteksi pergantian proyek (misal pindah dari proj-A ke proj-B)
# Menggunakan state file per-destination agar sesi paralel (mis. probe hello vs workspace) tidak saling menimpa state
mount_dynamic_bind() {
    local src="$1"
    local dst="$2"
    local state_dir="$ROOTFS/tmp/.nextcraft-mounts"
    local state_key
    state_key=$(printf '%s' "$dst" | tr '/' '_')
    local state_file="$state_dir/$state_key"
    mkdir -p "$src" "$dst" "$state_dir"
    local current_src=""
    if [ -f "$state_file" ]; then
        current_src=$(cat "$state_file" 2>/dev/null || true)
    fi
    if is_mounted "$dst"; then
        if [ "$current_src" != "$src" ]; then
            umount "$dst" 2>/dev/null || umount -lf "$dst" 2>/dev/null || true
        fi
    fi
    if ! is_mounted "$dst"; then
        mount --bind "$src" "$dst"
        printf '%s' "$src" > "$state_file"
    fi
}

# 1. Pastikan partisi /data mengizinkan exec & suid di dalam mount namespace ini
mount -o remount,exec,suid /data 2>/dev/null || true

# 2. Mount virtual filesystems kernel Linux
# Bind-mount /dev dan /dev/pts dari host agar slave PTY yang dibuka oleh forge_spawn.c
# di sisi host memiliki inode yang 100% identik di dalam Chroot (/dev/pts/<N>).
mount_once "bind"  "/dev"     "$ROOTFS/dev"     "rw"
mount_once "bind"  "/dev/pts" "$ROOTFS/dev/pts" "rw"
mount_once "proc"  "proc"     "$ROOTFS/proc"    "rw,nosuid,nodev,noexec,relatime"
mount_once "sysfs" "sysfs"    "$ROOTFS/sys"     "rw,nosuid,nodev,noexec,relatime"
mount_once "tmpfs" "tmpfs"    "$ROOTFS/tmp"     "rw,nosuid,nodev,mode=1777"
mount_once "tmpfs" "tmpfs"    "$ROOTFS/dev/shm" "rw,nosuid,nodev,mode=1777"

# 3. Read-Only Bind Mount partisi Bionic Android (Agar binary Bionic aapt2 jalan di dalam Ubuntu 24.04)
for sysdir in /system /apex /vendor /product; do
    if [ -d "$sysdir" ]; then
        mount_bionic "$sysdir" "$ROOTFS$sysdir"
    fi
done

# 4. Dynamic Bind Mount Workspace Proyek & Folder IPC Bridge
# Mendukung /nextcraft-bridge, /pocketdev-bridge, dan /pocket-bridge untuk kompatibilitas penuh dengan hook agen
if [ "$WORKSPACE_HOST" != "$ROOTFS$GUEST_WORKSPACE" ]; then
    mount_dynamic_bind "$WORKSPACE_HOST" "$ROOTFS$GUEST_WORKSPACE"
fi
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/nextcraft-bridge"
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/pocketdev-bridge"
mount_dynamic_bind "$BRIDGE_HOST" "$ROOTFS/pocket-bridge"

# 5. Pastikan DNS resolver tersedia
if [ ! -s "$ROOTFS/etc/resolv.conf" ]; then
    printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "$ROOTFS/etc/resolv.conf"
fi

# 6. Bersihkan variabel environment spesifik Android Bionic tanpa menghapus variabel AI/Gateway
# (ANTHROPIC_BASE_URL, ANTHROPIC_API_KEY, OPENAI_API_KEY, CLAUDE_*, DEEPSEEK_*, SSH_CONNECTION tetap utuh!)
unset LD_LIBRARY_PATH LD_PRELOAD
unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_DATA ANDROID_I18N_ROOT
unset ANDROID_ROOT ANDROID_RUNTIME_ROOT ANDROID_STORAGE ANDROID_TZDATA_ROOT
unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH

export HOME="/root"
export USER="root"
export LOGNAME="root"
export LANG="${LANG:-C.UTF-8}"
export TERM="${TERM:-xterm-256color}"
export GLIBC_TUNABLES="glibc.pthread.rseq=0"

BASE_PATH="/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
if [ -d "$ROOTFS/root/android-sdk" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-/root/android-sdk}"
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/root/android-sdk}"
    # Keep in sync with AndroidToolchain.kt (RuntimeInstaller sets the same environment for in-app launches).
    export GRADLE_HOME="${GRADLE_HOME:-/opt/gradle/gradle-9.8.1}"
    export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
    export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="/root/android-sdk/build-tools/36.0.0/aapt2"
    export PATH="/opt/gradle/gradle-9.8.1/bin:/root/android-sdk/build-tools/36.0.0:/root/android-sdk/cmdline-tools/latest/bin:$BASE_PATH"
else
    export PATH="$BASE_PATH"
fi

# 7. Masuk ke Chroot Ubuntu 24.04 LTS ARM64, pindah ke direktori $GUEST_WORKSPACE, dan eksekusi perintah
# Run the guest command as a child and forward INT/TERM to it. With a plain foreground command the shell only runs
# its trap after the command exits, so a cancelled npm install or Gradle build kept running until it finished, and
# a later SIGKILL of this script orphaned it and skipped the ownership fix. Stdin (the
# Claude prompt) is passed through fd 3: a non-interactive shell gives background commands /dev/null.
child_status=0
exec 3<&0
forward_signal() {
    kill "-$1" "$child" 2>/dev/null || true
}
chroot "$ROOTFS" /bin/sh -c 'cd "$1" && shift && exec "$@"' -- "$GUEST_WORKSPACE" "$@" <&3 3<&- &
child=$!
trap 'forward_signal INT' INT
trap 'forward_signal TERM' TERM
# wait returns early when a trapped signal arrives; keep waiting until the child has really exited.
while :; do
    # `|| child_status=$?` keeps set -e from aborting when a trapped signal interrupts wait (status 128+N).
    child_status=0
    wait "$child" || child_status=$?
    kill -0 "$child" 2>/dev/null || break
done
exit "$child_status"
