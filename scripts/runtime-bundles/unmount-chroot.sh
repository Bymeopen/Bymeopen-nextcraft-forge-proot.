#!/system/bin/sh
# scripts/runtime-bundles/unmount-chroot.sh
# NextCraft Forge (com.nextcraft.forge) — Safe Chroot Unmount Guard
# WAJIB dijalankan via `su -mm` dan `su` sebelum RootfsRepair menghapus atau me-rename direktori rootfs!
set -e

if [ -z "$1" ]; then
    echo "Usage: $0 <rootfs_canonical_path>" >&2
    exit 64
fi

ROOTFS_REAL=$(realpath "$1" 2>/dev/null || printf '%s' "$1")
ROOTFS_PATH="${ROOTFS_REAL%/}"

# Lepaskan seluruh mount di bawah $ROOTFS_PATH dari urutan terdalam ke terluar (sort -r)
for pass in 1 2 3; do
    MOUNTS=$(awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts | sort -r)
    if [ -z "$MOUNTS" ]; then
        break
    fi
    for mp in $MOUNTS; do
        if [ "$pass" -lt 3 ]; then
            umount "$mp" 2>/dev/null || true
        else
            umount -lf "$mp" 2>/dev/null || umount -f "$mp" 2>/dev/null || true
        fi
    done
done

# Verifikasi keras: tolak (exit 1) jika masih ada satu pun mount kernel yang tersisa di bawah $ROOTFS_PATH
if awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { found=1; exit } END { exit !found }' /proc/mounts; then
    echo "CRITICAL: Active kernel mounts still remain under $ROOTFS_PATH:" >&2
    awk -v prefix="${ROOTFS_PATH}/" -v exact="${ROOTFS_PATH}" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts >&2
    exit 1
fi

rm -rf "$ROOTFS_PATH/tmp/.nextcraft-mounts" 2>/dev/null || true
exit 0
