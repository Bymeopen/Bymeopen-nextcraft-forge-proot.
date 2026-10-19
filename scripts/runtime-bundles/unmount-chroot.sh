#!/system/bin/sh
ROOTFS="$1"
if [ -z "$ROOTFS" ]; then
    echo "Usage: unmount-chroot.sh <rootfs_path>" >&2
    exit 2
fi
ROOTFS_REAL=$(realpath "$ROOTFS" 2>/dev/null || printf '%s' "$ROOTFS")
ROOTFS_CLEAN="${ROOTFS_REAL%/}"
# Make every mount under the rootfs private before unmounting: the Bionic directories are recursive copies of
# shared Android mounts, and unmounting a copy that is still a peer would also unmount the original.
for mp in $(awk -v prefix="$ROOTFS_CLEAN/" -v exact="$ROOTFS_CLEAN" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts | sort); do
    mount --make-private "$mp" 2>/dev/null || mount -o private "$mp" 2>/dev/null || true
done

for pass in 1 2 3; do
    MOUNTS=$(awk -v prefix="$ROOTFS_CLEAN/" -v exact="$ROOTFS_CLEAN" 'index($2, prefix) == 1 || $2 == exact { print $2 }' /proc/mounts | sort -r)
    if [ -z "$MOUNTS" ]; then
        break
    fi
    for mnt in $MOUNTS; do
        if [ "$pass" -lt 3 ]; then
            umount "$mnt" 2>/dev/null || true
        else
            umount -l "$mnt" 2>/dev/null || umount -f "$mnt" 2>/dev/null || true
        fi
    done
done
if awk -v prefix="$ROOTFS_CLEAN/" -v exact="$ROOTFS_CLEAN" 'index($2, prefix) == 1 || $2 == exact { found=1; exit } END { exit !found }' /proc/mounts; then
    exit 1
fi
rm -rf "$ROOTFS_CLEAN/tmp/.nextcraft-mounts" 2>/dev/null || true
exit 0
