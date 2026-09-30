# PowerWatch Phase 2 implementation and validation — September 29, 2026

Status: Phase 2 is built, installed, and running. Gateway discovery, screen-off phone Wi-Fi loss/recovery, and the user-performed router interruption passed; full physical acceptance remains pending.
PLAN.md was not changed. No production dependencies were added.

## Physical target and Phase 1 evidence

- Samsung SM-A146U; Android 15 / API 35; serial R92W706Z2HR.
- Package com.joey.powerwatch; targetSdk 36.
- ADB initially showed no devices, then the intended authorized phone became reachable.
- Existing Phase 1 installation was confirmed without reinstalling it first.
- Existing foreground MonitoringService had run for approximately six hours.
- Persisted five-minute heartbeats extended from 15:01 through 20:51, with approximately 300-second elapsed-clock spacing.
- This confirms logging continuity, but the screen state during that entire historical interval is not independently known.
- Historical CSV contained several power outage/recovery pairs; these were observed records, not timed acceptance experiments by this agent.
- Stop was tested through the Activity. Service inventory became empty, a monitoring_stopped row was saved, and dumpsys power recorded release of PowerWatch:Monitoring.
- Start was tested through the Activity. A new monitoring_started and initial connected-power row were saved.
- Activity was backgrounded with Home, then screen was put to sleep.
- dumpsys confirmed mWakefulness=Dozing and the partial monitoring wake lock.
- User physically unplugged/reconnected the phone and reported completion.
- The persisted test contained exactly one new loss/recovery pair:

```csv
2026-09-29T20:59:29.663-07:00,outage_start,power,down,,External charger power lost,24175051,15
2026-09-29T20:59:37.111-07:00,outage_end,power,up,7448,External charger power restored,24182499,15
```

- Duration calculation: 24182499 - 24175051 = 7448 ms.
- User was asked for approximately five seconds; an independently measured physical duration was not supplied.
- A five-minute heartbeat was also saved at 20:59:09.863 while the Activity was backgrounded.
- Basic screen-off power milestone passed; sub-250-ms event latency and several-hour screen-off acceptance are not established.
- Explicit removal from Recents remains unverified.

## Phase 2 implementation

- NetworkMonitor listens for Wi-Fi NetworkCallbacks in the service and discovers the default-route gateway from LinkProperties.
- Wi-Fi eligibility waits for capabilities and rejects cellular, mixed Wi-Fi/cellular, and VPN paths.
- RouterProbe uses Android InetAddress.isReachable on the explicit Wi-Fi NetworkInterface, TTL 2, timeout argument 700 ms.
- Interface absence or lack of a matching source-address family produces a failure instead of a default-interface fallback.
- InternetProbe performs a TCP handshake to the configured numeric IPv4 address on port 443, with the socket explicitly bound to the selected Wi-Fi Network and a 700-ms connect timeout.
- ProbeEngine has two worker threads, runs router/internet concurrently, and allows only one round in flight.
- Probe generations reject results from an old/replaced/disappeared Wi-Fi path.
- Default interval is 1000 ms; rounds that take longer delay the next round rather than accumulating probes.
- No active Wi-Fi produces failed router and internet samples even if another transport is available.
- OutageStateMachine uses UNKNOWN, UP, SUSPECTED_DOWN, and DOWN.
- Two failures confirm an outage beginning at the first failed sample; one success restores it.
- Startup while already offline records an initial down state and does not invent the actual outage start or duration.
- Monotonic clocks measure durations; wall time is for event display.
- CSV records transitions and five-minute heartbeats, not successful one-second probes.
- Checkpoints include router/internet state and observed outage starts. Restarts log observation gaps and reset duration measurement.
- Activity and notification show power, router, internet, and Wi-Fi/gateway state.
- Minimal settings support interval, automatic/manual router gateway, internet IPv4 target, and failure threshold.
- Settings can be saved while stopped; monitoring start logs the applied configuration.
- INTERNET and ACCESS_NETWORK_STATE were added for probes and Wi-Fi callbacks. No sensitive runtime permission was added.
- No Wi-Fi lock, boot receiver, export/clear controls, or UI polish was added.

## Build and installation evidence

Commands ran successfully:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
Remove-Item Env:ANDROID_PREFS_ROOT -ErrorAction SilentlyContinue
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
adb -s R92W706Z2HR install -r app\build\outputs\apk\debug\app-debug.apk
```

The environment adjustment was limited to the shell command.
The deprecated ANDROID_PREFS_ROOT conflicted with ANDROID_USER_HOME.
No persistent machine setting was changed.
App data and existing CSV were preserved.
The build/runtime evidence above was gathered before Git initialization.
Git was subsequently initialized on main at the user's request for an initial
commit containing the implementation and this validation record.

Tests cover power/CSV behavior plus network debounce, first-failure timing,
monotonic recovery, independent outages, duplicate suppression, startup/gaps,
Wi-Fi-only eligibility, stale-result rejection, and configuration validation.

## Phase 2 runtime evidence

- Started through the updated Activity on the physical target at 21:19:02.
- Foreground specialUse service confirmed by dumpsys.
- APK-update observation gap explicitly recorded as monitoring_interrupted.
- Auto-discovered wlan0 gateway 192.168.0.1.
- Persisted initial_state router up and internet up.
- Phone Wi-Fi was temporarily disabled through ADB for a controlled loss test.
- Activity was backgrounded and screen put to sleep before the test.
- During the outage, dumpsys confirmed mWakefulness=Dozing and the held partial wake lock.
- Wi-Fi was restored in a finally block.
- Both targets saved exactly one outage_start and one outage_end.
- No charger-power transition occurred during the test.
- Service remained foreground after recovery.

```csv
2026-09-29T21:19:03.975-07:00,initial_state,router,up,,Wi-Fi interface ICMP/TCP echo,25349363,15
2026-09-29T21:19:03.99-07:00,initial_state,internet,up,,Wi-Fi TCP port 443,25349378,15
2026-09-29T21:20:05.029-07:00,outage_start,router,down,,No active Wi-Fi network,25410416,15
2026-09-29T21:20:05.029-07:00,outage_start,internet,down,,No active Wi-Fi network; cellular excluded,25410416,15
2026-09-29T21:20:15.166-07:00,outage_end,router,up,10138,Wi-Fi interface ICMP/TCP echo,25420554,15
2026-09-29T21:20:15.178-07:00,outage_end,internet,up,10150,Wi-Fi TCP port 443,25420566,15
```

PASS: discovered gateway, active healthy probes, screen-off phone Wi-Fi loss,
separate persisted target transitions, recovery, and no duplicate event spam.
Working cellular fallback has not been established. Cellular network requests
in dumpsys are not proof of an active usable cellular network.
### User-performed router interruption

The user reported completing the requested approximately 15-second router power
interruption, with phone power retained. ADB was temporarily unavailable when
verification was first attempted; evidence was read after USB reconnection.

```csv
2026-09-29T21:30:10.07-07:00,network_changed,wifi,down,,No active Wi-Fi; cellular excluded,26015458,15
2026-09-29T21:30:10.186-07:00,outage_start,router,down,,No active Wi-Fi network,26015574,15
2026-09-29T21:30:10.186-07:00,outage_start,internet,down,,No active Wi-Fi network; cellular excluded,26015574,15
2026-09-29T21:30:25.085-07:00,network_changed,wifi,connected,,Wi-Fi interface: wlan0; gateway: 192.168.0.1,26030473,15
2026-09-29T21:30:25.263-07:00,outage_end,router,up,15077,Wi-Fi interface ICMP/TCP echo,26030651,15
2026-09-29T21:30:25.278-07:00,outage_end,internet,up,15092,Wi-Fi TCP port 443,26030666,15
```

PASS: one loss/recovery pair per network target, gateway rediscovery, and power
remained connected during the reported router test. Recorded durations were
15.077 seconds for router and 15.092 seconds for internet. No independent physical
stopwatch duration was provided.

A later phone USB interruption was recorded separately at 21:49:31.848 through
21:49:34.186, lasting 2338 ms. It was not simultaneous with the router test.

### Heartbeat continuity

Six Phase 2 heartbeats were saved at 21:24, 21:29, 21:34, 21:39, 21:44, and 21:49.
Elapsed-clock values: 25648329, 25948342, 26248352, 26548364, 26848375, 27148386.
Successive gaps: 300013, 300010, 300012, 300011, and 300011 ms.
Each heartbeat reported connected power and healthy router/internet.
Service inventory at approximately 21:50 confirmed the same foreground process
with about 31 minutes of service uptime and its held partial wake lock.
No restart or additional unexplained network outage appeared in this interval.
Screen-off was established at test setup; the entire later interval's screen
state was not independently sampled, and USB reconnection woke the phone.
At the subsequent check the screen was returned to Dozing; USB power was connected,
battery level was 64%, and the reported battery temperature was 28.0 °C.
This snapshot does not establish long-term battery/thermal behavior.

WAN-only loss was skipped at the user's explicit request. It has not been
validated and is not marked PASS. Usable-cellular fallback, Recents removal,
and extended idle acceptance remain pending.

## Remaining physical validation

1. Verify Stop/Start and wake-lock release on the updated build.
2. WAN-only loss: SKIPPED by user; behavior remains unverified.
3. Test Wi-Fi outage with working cellular internet if this phone has a usable cellular data connection.
4. Verify Activity removal from Recents and extended idle periods beyond the observed 31-minute run.
5. Validate manual gateway override and configurable interval/threshold on the phone.
6. Check 24-hour heartbeat continuity, false outages, thermal/battery behavior before trusting the app.

Current device state: updated monitoring service running, phone Wi-Fi restored,
Activity backgrounded; screen returned to sleep and mWakefulness=Dozing confirmed after USB reconnection.
Do not assume monitoring resumes automatically after an APK update.

Limitations: manual targets accept unicast IPv4 literals only; automatic gateway
discovery can select IPv4 or IPv6. Internet reachability is specifically the
configured endpoint's TCP port 443. The initial ICMP/TCP-echo method works on this
phone/router in baseline and recovery tests; longer-term false-failure testing
remains necessary. HostProbe allows a replacement if testing requires one.

## September 30 continuation — CSV export and acceptance checks

Root and app AGENTS.md and PLAN.md were read before continuing.
The worktree was clean at entry. PLAN.md remains unchanged.
No production dependency or permission was added.

### Implementation and automated verification

- Added Export CSV using Android's CreateDocument/Save As picker.
- File-provider writes run on the Activity's existing background executor.
- EventLogger captures a complete-row byte boundary under its writer lock, then
  streams only those bytes without retaining the lock during destination writes.
  Later monitoring appends remain in the internal log for the next export.
- Missing/empty logs export the CSV header.
- Cancellation does not change the internal log. Destination I/O and permission
  errors are shown explicitly; a failed destination may contain a partial copy.
- Added tests for exporting more than the 50 displayed rows, concurrent appends,
  snapshot boundaries, missing/empty logs, and destination failure preservation.
- Baseline unit tests and assembleDebug passed.
- Focused EventLoggerTest passed after implementation.
- Complete :app:testDebugUnitTest and :app:assembleDebug passed afterward.
- git diff --check passed. IDE inspections reported style warnings, no errors.
- Installed the debug APK with adb install -r, retaining existing app data.

### Overnight observations before updating

Device: Samsung SM-A146U, Android 15/API 35, targetSdk 36.
The prior foreground service and partial wake lock had approximately 12 hours
28 minutes of uninterrupted uptime at inspection.

149 heartbeats were present from September 29 21:24:02.941 to September 30
09:44:04.864 (phone-local offset -07:00). The 148 monotonic gaps ranged from
300009 to 300024 ms, averaging approximately 300013 ms. No service restart
appeared in that interval. This establishes heartbeat continuity; the entire
overnight screen state was not independently observed.

Three short simultaneous router/internet outages were logged on September 30:

| First failure, phone-local time | Router duration | Internet duration |
|---|---:|---:|
| 04:10:45.718 | 1776 ms | 1795 ms |
| 06:42:18.048 | 2307 ms | 2317 ms |
| 08:45:48.643 | 2312 ms | 2316 ms |

Router reasons were gateway timeout/unreachable; internet reasons were TCP
SocketTimeoutException. These are unverified interruptions, potentially false
positives. The event records alone cannot distinguish actual LAN loss from
phone Wi-Fi suspension or transient probe failure. No Wi-Fi lock or threshold
change was introduced without further evidence.

At inspection battery level was 68%, temperature 29.0 °C, and USB power was
connected. This snapshot does not establish 24-hour thermal/battery acceptance.

### Updated physical checks

PASS — Stop/Start:
- Startup after APK replacement explicitly logged monitoring_interrupted.
- Started at 09:48:39.727; router and internet reached initial UP states.
- Stopped through the Activity at 09:49:15.899.
- Service inventory became empty, no held PowerWatch partial wake lock remained,
  and the monitoring_stopped row was persisted.
- Restarted through the Activity at 09:49:42.261.
- Foreground service, partial wake lock, and healthy gateway probes returned.

PASS — full CSV export during monitoring:
- Saved through the system picker to phone Downloads:
  PowerWatch-1790787006583.csv.
- Activity reported CSV exported and monitoring remained foreground.
- Exported and internal files had matching SHA-256 at comparison:
  cd2affc95a83cd3b998fba80658a21bc5485f3015260c2702f5783997126e1d6.
- No internal log records were deleted.

PASS — Recents removal with screen-off probing:
- Swiped only PowerWatch's Activity card out of Recents.
- Confirmed its card/task disappeared; service remained foreground.
- Sent KEYCODE_SLEEP and confirmed mWakefulness=Dozing with partial wake lock.
- Temporarily disabled phone Wi-Fi for eight seconds, restoring it in finally.
- Both targets persisted exactly one new loss/recovery pair; no power transition.
- Auto gateway rediscovery returned wlan0 / 192.168.0.1.

```csv
2026-09-30T09:53:46.716-07:00,outage_start,router,down,,No active Wi-Fi network,70632104,15
2026-09-30T09:53:46.716-07:00,outage_start,internet,down,,No active Wi-Fi network; cellular excluded,70632104,15
2026-09-30T09:53:56.297-07:00,outage_end,router,up,9581,Wi-Fi interface ICMP/TCP echo,70641685,15
2026-09-30T09:53:56.297-07:00,outage_end,internet,up,9581,Wi-Fi TCP port 443,70641685,15
```

Both monotonic durations are 70641685 - 70632104 = 9581 ms.
This tests phone Wi-Fi removal, not a physical WAN interruption.
Working cellular fallback is still not established: the active-agent inventory
showed validated Wi-Fi, without an active usable cellular agent; historical
connectivity entries were not treated as evidence of current cellular access.

### Remaining work and current state

- Investigate whether overnight network interruptions were real or false positives.
- Complete extended screen-off and 24-hour reliability/thermal acceptance.
- Validate manual gateway override and configurable interval/threshold physically.
- Verify Wi-Fi failure with independently working cellular data when available.
- WAN-only testing remains skipped at the user's prior request.
- Boot restart/settings and reboot validation remain unimplemented/pending.
- Clear-log controls and signed release APK remain future plan work.
- Timed controlled outages and wall-clock/gateway-change acceptance remain pending.

At the end of the controlled Wi-Fi test, Wi-Fi was restored, both probes were UP,
the Activity remained removed from Recents, and the screen was Dozing.
Monitoring remained foreground with its partial wake lock held.
A subsequent heartbeat at 09:54:42.274 (elapsed 70687662) reported connected
power and healthy router/internet while the screen was still Dozing.
