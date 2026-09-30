---
name: powerwatch-device-validation
description: Validate PowerWatch monitoring reliability on a physical Android device. Use for ADB/Logcat debugging, charger power-event testing, router or WAN outage simulation, screen-off/idle tests, foreground-service survival, reboot behavior, Wi-Fi-versus-cellular verification, and acceptance testing of outage timing.
metadata:
  author: PowerWatch project
  version: "1.0"
---

# PowerWatch physical-device validation

Use this skill only when the task involves runtime validation or diagnosing behavior on an attached Android phone.

Read the root `AGENTS.md` and `plan.md` first.

## Principles

- The physical target phone is authoritative for runtime reliability.
- Compilation is necessary but not sufficient.
- Do not modify architecture merely to make a test easier.
- Prefer a controlled experiment that isolates one failure mode.
- Record exact observed behavior before proposing a fix.
- Never intentionally factory-reset, wipe, root, unlock the bootloader, or alter unrelated phone settings.
- Never disable Android security protections globally.
- Ask before making a persistent device-wide setting change not already required by the project.

## Establish device state

Start with:

```powershell
adb devices
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
```

Confirm there is exactly one intended target when running commands that change device state.

Capture the package name from the project rather than guessing it.

## Build/install baseline

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

If installation fails, diagnose the actual error before changing the app.

Do not delete app data as a routine fix because doing so may destroy the outage log needed for diagnosis.

## Observe runtime

Use Logcat with the package/process filtered where possible.

Useful observations include:

- service creation/start/restart,
- notification/foreground-service errors,
- power connected/disconnected callbacks,
- active Wi-Fi network identity,
- discovered gateway,
- probe results and timeouts,
- outage state transitions,
- heartbeat emission,
- wake-lock acquisition/release,
- file-write errors.

Avoid adding verbose permanent logging solely for interactive debugging. Temporary debug logs should be narrowly scoped and removed or downgraded afterward.

## Test one scenario at a time

### A. Charger power transition

Goal: prove external-power events are captured independently of router/internet state.

1. Start monitoring.
2. Confirm the phone reports external power connected.
3. Turn the screen off.
4. Unplug the phone charger without disturbing the router.
5. Wait a few seconds.
6. Reconnect the charger.
7. Verify one power-down and one power-up transition.
8. Verify duration is sensible and based on monotonic elapsed time.
9. Verify no duplicate transition spam occurred.

### B. Router-only loss

Goal: distinguish local-network failure from charger-power failure.

1. Keep the phone charger powered.
2. Keep monitoring active.
3. Remove power from the router or otherwise isolate the monitored router.
4. Confirm power remains UP.
5. Confirm router becomes DOWN.
6. Confirm monitored Wi-Fi internet becomes DOWN.
7. Restore the router and verify recovery ordering/timing.

### C. WAN/internet-only loss

Goal: distinguish ISP/WAN failure from local-router failure.

1. Keep router LAN/Wi-Fi operating.
2. Interrupt WAN connectivity without powering off the router when practical.
3. Verify router remains UP.
4. Verify internet becomes DOWN.
5. Restore WAN and verify internet recovers.

### D. Cellular fallback protection

Goal: prove mobile data cannot hide failure of the monitored Wi-Fi path.

1. Enable cellular data.
2. Connect to monitored Wi-Fi.
3. Verify both router and Wi-Fi internet are UP.
4. Interrupt monitored Wi-Fi/router connectivity.
5. Confirm Android may have general connectivity through cellular.
6. Confirm PowerWatch still records the monitored Wi-Fi internet path as DOWN.

A cellular success is not a successful PowerWatch Wi-Fi internet probe.

### E. Screen-off longevity

Goal: prove the service does useful work after extended idle time.

1. Start monitoring.
2. Turn screen off.
3. Leave device untouched for the requested period.
4. Afterward verify heartbeat continuity.
5. Perform a controlled outage while screen remains off.
6. Verify it is captured.
7. Inspect battery/thermal behavior for obvious problems.

Do not add a Wi-Fi lock preemptively. Add one only if testing demonstrates Wi-Fi suspension that breaks the required behavior.

### F. Activity lifecycle

Verify monitoring behavior when:

- Activity is backgrounded,
- Activity is closed,
- app is swiped from Recents.

Treat Android **Force stop** as an expected hard stop; do not attempt to defeat it.

### G. Reboot

If boot restart is implemented:

1. Start monitoring and persist the enabled state.
2. Reboot the device normally.
3. Wait for Android boot completion.
4. Verify the service restarts only in a way permitted by the current target/API behavior.
5. Verify the notification and heartbeat return.
6. Record any OEM-specific battery/autostart restriction rather than guessing.

## Timing validation

For controlled outages, compare:

- real-world approximate outage duration,
- logged start wall time,
- logged end wall time,
- monotonic duration.

Network detection precision is bounded by the probe interval, timeout, and failure threshold. Do not claim millisecond-level network outage accuracy when the design samples approximately once per second.

Power-event timing may be substantially more precise because it is event-driven.

## Acceptance evidence

For each scenario, report:

- device model and Android/API version,
- app build/commit if available,
- test preconditions,
- action performed,
- expected result,
- observed result,
- relevant log excerpt,
- PASS/FAIL,
- any limitation or ambiguity.

Do not mark a scenario PASS if the evidence only proves the UI updated; confirm the persisted event log where applicable.

## Failure diagnosis order

When a test fails, investigate in this order:

1. Was the service actually running?
2. Did Android deliver the expected platform callback/event?
3. Was the correct Wi-Fi `Network` object being monitored?
4. Was the gateway discovered correctly?
5. Did the probe execute and respect its timeout?
6. Did the state machine transition correctly?
7. Did the logger persist the transition?
8. Did the UI merely fail to reflect correct backend state?

Avoid architectural rewrites until the failing layer is identified.
