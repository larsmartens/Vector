# Boot Debugging Notes

These notes document the boot-loop isolation workflow that proved useful with this fork on Android 16.

## What To Check First

- `/data/system/dropbox/system_server_crash*`
- `/data/system/dropbox/system_server_watchdog*`
- `/data/system/dropbox/system_server_pre_watchdog*`
- `/data/system/dropbox/system_server_anr*`
- `/data/tombstones`

For framework-hook failures, dropbox often has the decisive evidence before tombstones do.

## Isolation Strategy

1. Restore a known-safe backend and framework baseline first.
2. Keep the Vector module enabled only after the backend path is proven stable.
3. Isolate third-party modules scoped into `system` or `system_server` before treating Vector core as the cause.
4. Re-enable one variable at a time and wait through a full boot-and-idle window.

## Practical Notes

- Durable recovery usually comes from Magisk module `disable` files, not just LSPosed toggles.
- Keep a rollback script on-device before removing any `disable` marker.
- Use the framework CLI or config DB only after the device is back in a safe baseline.
