# Extract the directory path and change directory 
MODDIR="${0%/*}"
cd "$MODDIR" || exit 1

# Start the daemon directly in the background within a private mount namespace.
# On this fork, avoid boot-time system_server restart retries from the wrapper.
unshare --propagation slave -m "$MODDIR/daemon" --system-server-max-retry=0 "$@" &
