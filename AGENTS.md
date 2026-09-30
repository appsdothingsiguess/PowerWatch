# PowerWatch — Agent Instructions

## Mission

Build **PowerWatch**, a small native Android application that continuously detects and records:

1. loss/restoration of external charger power,
2. loss/restoration of the local Wi-Fi router/gateway,
3. loss/restoration of internet reachability through the Wi-Fi network.

The primary success criterion is **reliable outage detection while the phone is locked, the Activity is closed, and the device has been idle for hours**.

Before implementing or changing behavior, read `plan.md`. Treat `plan.md` as the product and architecture source of truth unless the user explicitly changes a requirement.

Do not rewrite `plan.md` unless asked.

---

## Non-negotiable project rules

1. Keep the application minimal.
2. Do not introduce Room, Firebase, cloud services, dependency-injection frameworks, repository layers, Clean Architecture, or other unnecessary abstractions.
3. Build and test after each major feature.
4. Never assume that Wi-Fi being connected means the router is reachable.
5. Never allow cellular data to satisfy a Wi-Fi outage probe.
6. Power transitions must be event-driven, not detected by periodic polling.
7. Network probes should run at a configurable interval of approximately one second unless `plan.md` specifies otherwise.
8. Persist state transitions and periodic heartbeats; do not log every successful probe.
9. Use a monotonic clock for outage-duration calculations. Wall-clock timestamps are for human-readable event time only.
10. Monitoring must continue with the Activity closed and the screen off.
11. The physical target Android phone is the authoritative runtime test device.
12. Prefer Android platform APIs over external dependencies.
13. Do not spend time on UI polish until monitoring reliability is demonstrated.
14. Every completed phase must leave the project buildable.
15. Keep changes small, independently testable, and easy to revert.

---

## Technology constraints

Unless `plan.md` or the user explicitly changes these decisions:

- Language: Kotlin.
- UI: Android Views/XML, not Jetpack Compose.
- Core runtime: one Activity plus a foreground monitoring service.
- Persistence: simple append-only local log files/CSV unless a concrete requirement justifies something else.
- Networking: Android platform networking APIs and explicit probes.
- Build system: Gradle Wrapper checked into the project.
- No backend.
- No account system.
- No analytics.
- No advertisements.
- No telemetry.
- No NDK/native code unless proven necessary.

Do not add a dependency merely because it is convenient. Explain and justify any new production dependency before adding it.

---

## Architecture boundaries

Keep responsibilities obvious and small.

Expected conceptual components:

- `MainActivity`: status/configuration/export controls only.
- `MonitoringService`: owns long-running monitoring lifecycle.
- `PowerMonitor`: external-power state and power-transition events.
- `NetworkMonitor`: Android network state, Wi-Fi network identity, gateway discovery.
- `ProbeEngine`: active router and internet checks.
- `OutageStateMachine`: debounce/state-transition logic and outage timing.
- `EventLogger`: append-only event and heartbeat persistence.

Names may differ if the existing project already uses sensible names. Do not create layers solely to match this list.

The Activity must not be responsible for continuous monitoring.

---

## Monitoring invariants

### External power

- Detect charger/external-power changes using Android power/battery events as described in `plan.md`.
- Record the transition immediately.
- Do not infer a household power outage solely from router/internet loss.
- Describe the recorded signal accurately: the phone's external charger power was lost/restored.

### Wi-Fi router

- Do not hard-code a gateway address as the only supported configuration.
- Auto-discover the active Wi-Fi gateway when possible.
- Permit a manual gateway override if required by the plan.
- A valid Android Wi-Fi connection is not proof of gateway reachability.

### Internet

- Internet health must be tested through the Wi-Fi network being monitored.
- Do not silently fall back to cellular.
- If the Wi-Fi network disappears, the monitored Wi-Fi internet state should reflect that loss even if the phone has mobile data.

### Probes

- Keep probe implementation replaceable if a specific device/router does not respond reliably to the initial method.
- Use explicit timeouts.
- Avoid overlapping an unbounded number of probes.
- Run router and internet probes concurrently when practical.
- A single dropped packet should not necessarily create a logged outage; follow the failure threshold/state-machine behavior in `plan.md`.

---

## Timekeeping

Use separate concepts for event display time and duration measurement.

- Human-readable timestamp: wall clock.
- Duration measurement: monotonic elapsed time.

Never compute outage duration solely from wall-clock timestamps.

Persist enough information to reconstruct a useful outage record after process/service interruption.

---

## Logging rules

Prefer event-based records such as:

- monitoring started/stopped,
- power lost/restored,
- router lost/restored,
- internet lost/restored,
- periodic heartbeat,
- configuration change relevant to monitoring,
- unexpected service restart or recoverable monitoring error.

Do not emit one persistent log row per successful one-second probe.

Log records should be deterministic, machine-readable, and exportable.

Do not log secrets, Android IDs, account data, unrelated device information, or precise location.

---

## Foreground-service and background behavior

The foreground service is the product-critical component.

When changing service lifecycle behavior:

1. Check the current Android API requirements for the project's `targetSdk`.
2. Keep the persistent notification truthful about monitoring state.
3. Ensure all required foreground-service permissions/types are declared.
4. Handle service restart conservatively.
5. Do not claim Android can survive a user **Force stop**; treat force-stop as an expected hard stop.
6. Verify behavior with the screen off on the actual phone.

Use a partial wake lock only as required for the intended monitoring interval and release it when monitoring stops.

Do not keep the screen awake.

Do not add a Wi-Fi lock unless device testing demonstrates that it is necessary.

---

## Permissions policy

Request only permissions needed for the documented behavior.

Do not add access to:

- location,
- microphone,
- camera,
- contacts,
- accessibility,
- broad storage,
- Bluetooth,

unless a new user requirement genuinely requires it and the user approves the change.

If Android requires a permission for a platform behavior, document why it is needed.

---

## Build workflow

At the beginning of a coding task:

1. Read `plan.md`.
2. Inspect the existing project before proposing structural changes.
3. Check `git status` if Git is available.
4. Identify the smallest change that satisfies the current task.
5. Build before changing code when practical to establish a baseline.

After a meaningful implementation change:

```powershell
.\gradlew.bat assembleDebug
```

Run relevant tests if present.

If a physical Android device is connected:

```powershell
adb devices
```

Install/update the debug APK when appropriate:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Use Logcat/ADB/device interaction to diagnose actual runtime behavior rather than guessing.

Do not declare a runtime feature complete merely because compilation succeeds.

---

## Physical-device validation

For features involving service lifetime, networking, power detection, reboot behavior, Doze/idle behavior, or screen-off behavior, emulator-only validation is insufficient.

Validate the relevant scenario on the physical target phone when available.

Important scenarios include:

- unplug/replug charger,
- router loses power while phone charger stays powered,
- WAN/internet loss while router remains reachable,
- cellular enabled while monitored Wi-Fi fails,
- screen off for an extended period,
- Activity removed from Recents,
- device reboot,
- wall clock/timezone change,
- gateway/network change.

Use the optional `powerwatch-device-validation` Android Studio skill when performing these checks.

---

## Testing philosophy

Prioritize deterministic tests around the outage state machine and duration calculations.

Unit-test at minimum:

- first failure becomes suspected-down,
- configured failure threshold produces down state,
- outage start time is retained from the first qualifying failure,
- success restores down state,
- duration uses monotonic time,
- duplicate state events are not logged repeatedly,
- cellular availability cannot mark monitored Wi-Fi internet as healthy.

Do not introduce a mocking framework unless the existing test setup requires one or it materially simplifies a hard-to-test boundary.

---

## UI rules

UI is secondary to monitoring correctness.

The main screen should make these states immediately understandable:

- monitoring running/stopped,
- external power connected/disconnected,
- router online/offline,
- internet online/offline,
- last outage time/duration,
- current monitoring configuration.

Avoid animations, complex navigation, onboarding flows, decorative dashboards, charts, or design-system work until the monitoring loop is proven reliable.

Accessibility basics still apply: readable labels, sufficient touch targets, and status not communicated by color alone.

---

## Error handling

Prefer explicit degraded states over silent failure.

Examples:

- gateway unavailable,
- no active Wi-Fi network,
- probe timeout,
- logger write failure,
- service restart,
- missing permission.

Do not crash because a network disappears during a probe.

Do not catch broad exceptions and ignore them.

For recoverable failures, log a concise reason and continue where safe.

---

## Source-of-truth order

When requirements conflict, resolve them in this order:

1. the user's latest explicit instruction,
2. `plan.md`,
3. this `AGENTS.md`,
4. existing implementation conventions.

If a requested change contradicts a reliability invariant, call out the conflict before implementing it.

---

## Definition of done for a feature

A feature is not complete until:

- the project builds,
- relevant tests pass,
- the change follows `plan.md`,
- no unnecessary dependency/architecture was added,
- behavior is verified on the physical phone when the feature depends on real Android runtime behavior,
- any important limitation is documented.

For monitoring features, correctness under screen-off/idle conditions matters more than UI appearance.
