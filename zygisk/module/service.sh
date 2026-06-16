# Extract the directory path and change directory 
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# Start the daemon directly in the background within a private mount namespace.
# Android toybox/busybox unshare does not support util-linux --propagation.
unshare -m sh -c '
  mount --make-rslave / 2>/dev/null || mount --make-private / 2>/dev/null || true
  exec "$@"
' sh "$MODDIR/daemon" --system-server-max-retry=0 "$@" &
