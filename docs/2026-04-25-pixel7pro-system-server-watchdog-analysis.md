# Pixel 7 Pro System Server Watchdog Analysis - 2026-04-25

## Context

Device: Pixel 7 Pro (`cheetah`) on Android 16 (`CP1A.260405.005`).

Problem: after OTA and Magisk/LSPosed/module updates, the device entered repeated
`system_server` ANR/watchdog cycles with intermittent loss of connectivity.

Primary evidence was collected locally from the attached device with adb/root. Raw
logs were intentionally kept out of the repository because they contain personal
package state, account-adjacent app names, device identifiers, and large artifacts.

Local evidence roots:

- `/home/larsm/android-evidence-20260425-crash`
- `/home/larsm/android-evidence-20260425-deep-log`
- `/home/larsm/android-evidence-20260425-post-fix`
- `/home/larsm/android-evidence-20260425-verify`
- `/home/larsm/android-evidence-20260425-final`

## Findings

The strongest crash-loop evidence came from `/data/system/dropbox` entries, not
tombstones. Repeated entries around `2026-04-25 03:31-04:25` included:

- `system_server_anr`
- `system_server_watchdog`
- `system_server_pre_watchdog`

Those entries repeatedly showed native frames inside:

- `/data/adb/modules/zygisk_vector/zygisk/arm64-v8a.so`

and Java-side frames around LSPosed/Vector callback dispatch, including:

- `Vector_.callApplicationOnCreate`
- `Vector_.call`
- `HookBridge.callbackSnapshot`

The failure mode matched a lock-order hazard in the native hook bridge: the
snapshot path could interact with Java-reflected backup `Method` objects in a way
that kept `system_server` threads blocked long enough to hit watchdog thresholds.

## Fix

Patch committed on branch `fix/vector-callback-deadlock`:

- Commit: `bb1d5831 fix(vector): avoid Method monitor deadlock in hook snapshots`
- CI artifact used during validation:
  `/home/larsm/android-evidence-20260425-crash/vector-ci-artifacts/Vector-vector-3039-3045-callback-deadlock-fix-Release.zip`

The fix avoids the problematic reflected `Method` monitor path in native callback
snapshot handling.

## Validation

After installing the patched Vector artifact and re-enabling the previously
disabled module set, the device completed boot and remained stable during the
initial idle validation window:

- `sys.boot_completed=1`
- `init.svc.bootanim=stopped`
- `sys.system_server.start_count=1`
- no new `system_server_anr`, `system_server_watchdog`, or
  `system_server_pre_watchdog` entries appeared after the post-fix reboot window

Follow-up device-wide fixes were also applied outside this repository:

- moved AdGuard certificate mount work out of Magisk `post-fs-data`
- narrowed LSPosed `system` scopes for unrelated high-risk modules
- made PixelXpert service policy/scope writes idempotent
- repaired Termux package state after Java cleanup
- regenerated YouTube ART/cache state

Those changes are documented in the local evidence directories listed above.
