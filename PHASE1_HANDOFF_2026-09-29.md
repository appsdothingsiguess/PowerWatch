# PowerWatch Phase 1 handoff — September 29, 2026

Work stopped at the user's request. Phase 1 implementation is built and installed, but physical acceptance testing is incomplete.

## Source of truth and environment

- Read PLAN.md (renamed by the user from build_plan.md), the supplied root AGENTS.md instructions, app/AGENTS.md, and the powerwatch-device-validation skill.
- Physical target: Samsung SM-A146U, Android 15 / API 35.
- ADB serial: R92W706Z2HR. Last successful inventory showed one authorized physical device.
- Package: com.joey.powerwatch.
- Git status reported this directory is not a Git repository. No commits were made.
- PLAN.md was not edited.

## Implemented

- Aligned compileSdk and targetSdk to 36, as specified in PLAN.md.
- Retained minSdk 28, compatible with the verified API 35 phone.
- MonitoringService: foreground specialUse service, truthful persistent power notification, notification Stop action, START_STICKY, and serialized background disk writes.
- PowerMonitor: ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED receiver and initial sticky battery EXTRA_PLUGGED state. No power polling.
- PowerStateMachine: immediate deduplicated transitions; event receipt captures wall time and elapsedRealtime; recovery duration uses monotonic time.
- EventLogger: append-only app-private files/events.csv, CSV escaping, one header, flushed writes, and elapsed time / boot count columns.
- Five-minute service heartbeats and a partial wake lock while monitoring; release on orderly destruction. No screen or Wi-Fi lock.
- SharedPreferences checkpoints retain observed power/outage context. Observation gaps are logged explicitly; durations do not cross an unobserved restart gap.
- Logger/checkpoint I/O failures are surfaced and stop monitoring.
- Minimal XML Activity: start/stop, current power/service status, last outage, and recent 50 CSV rows.
- Manifest: FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, WAKE_LOCK; non-exported service and stopWithTask=false.
- Backup disabled so monitoring checkpoints are not intentionally restored onto another device.
- No new dependencies, networking, boot receiver, export feature, or UI polish.

## Files changed or added

- app/build.gradle.kts
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/joey/powerwatch/MainActivity.kt
- app/src/main/java/com/joey/powerwatch/MonitoringService.kt
- app/src/main/java/com/joey/powerwatch/PowerMonitor.kt
- app/src/main/java/com/joey/powerwatch/PowerStateMachine.kt
- app/src/main/java/com/joey/powerwatch/EventLogger.kt
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/drawable/ic_power_monitor.xml
- app/src/test/java/com/joey/powerwatch/PowerStateMachineTest.kt
- app/src/test/java/com/joey/powerwatch/EventLoggerTest.kt

## Verification completed

- Original scaffold assembled and its existing unit test passed.
- Original APK installed and launched on the target phone.
- Final implementation: :app:testDebugUnitTest and :app:assembleDebug succeeded.
- Eight unit tests passed (seven new tests plus the original example test).
- Tests cover immediate loss, duplicate suppression, retained start time, recovery, monotonic duration despite wall-clock changes, independent subsequent outages, unknown duration after a monitoring gap, CSV persistence/header/escaping, and display limiting without deleting records.
- Phase 1 APK installed with adb install -r; app data was not cleared.
- Activity launched successfully and monitoring was started through its controls.
- Android notification permission was granted through the runtime prompt.
- dumpsys confirmed MonitoringService isForeground=true, targetSdkVersion=36, specialUse type 0x40000000, and an ongoing foreground notification.
- Persisted CSV, read from the actual phone, contained:

```csv
timestamp,event,target,state,duration_ms,detail,elapsed_ms,boot_count
2026-09-29T14:56:36.125-07:00,monitoring_started,service,up,,Power only; heartbeat every 5 minutes,2401513,15
2026-09-29T14:56:36.128-07:00,initial_state,power,up,,External charger power connected,2401516,15
```

## Exact stopping point

- Attempts to remove PowerWatch from Recents did not establish removal; the resulting UI showed its Activity again. Do not mark Recents survival as passed.
- Last confirmed service state was running and foreground, with external charger power connected.
- The final tool sequence intended to press Home, turn the screen off, and inspect screen/wake-lock state. The user interrupted this sequence; some input may have executed, but screen-off and wake-lock state were not confirmed.
- No controlled physical unplug/replug test has been completed.
- No heartbeat row, stop/start test, restart behavior, or extended idle reliability has been verified.
- The monitoring service was not deliberately stopped before handoff. It may still be running and holding its partial wake lock. Check current state on resumption; use the app's Stop monitoring control if monitoring is no longer wanted.
- No device-wide battery optimization or other unrelated settings were changed.

## Remaining Phase 1 acceptance work

1. Read PLAN.md and the device-validation skill, inspect current files/device state, and preserve the user's edits.
2. Confirm app startup/notification and explicitly test Stop monitoring, including wake-lock release and persisted monitoring_stopped.
3. Start monitoring; confirm initial external power state.
4. With the screen off, physically unplug the charger for several seconds and reconnect it. USB ADB may disconnect during the outage; inspect persisted CSV after reconnection.
5. Confirm exactly one outage_start and one outage_end, sensible elapsedRealtime duration, no duplicate spam, and truthful status/notification.
6. Explicitly remove the Activity from Recents and repeat the charger test while the service runs.
7. Verify five-minute heartbeat persistence with screen off, followed by extended idle testing. A foreground service/partial wake lock does not by itself prove OEM/Doze reliability.
8. Check interruption handling conservatively without clearing logs. Force stop is an expected hard stop; reboot auto-start is outside Phase 1.
9. Record device actions, persisted log excerpts, results, and limitations before declaring Phase 1 complete.

Use Android Studio studio_mcp_ tools for workspace access, editing, builds, and shell/device inspection. Do not start network work until the power milestone is physically verified.
