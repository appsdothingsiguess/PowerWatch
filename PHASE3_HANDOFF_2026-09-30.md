# PowerWatch Phase 3 handoff — September 30, 2026

Implementation and automated tests pass. Automatic reboot resume, readable logs,
report export, and short screen-off Wi-Fi outage alerts have physical-device
evidence. Extended runtime acceptance is not complete.

Read PLAN.md and root/app AGENTS.md before changing behavior. PLAN.md was not
changed. No production dependency was added.

## Repository

- Branch: main.
- Remote: origin — https://github.com/appsdothingsiguess/PowerWatch.git.
- Previous commits: f025775 (CSV export), 132fb2a (boot/clear/release),
  9540657 (handoff), 6cb6f34 (heartbeat configuration).
- This follow-up includes readable logs, outage alerts, HTML reports, tests,
  a minimal README, and updated handoff documentation.
- Private signing material remains ignored. Never stage signing/ or credentials.

## User decisions

- Overnight network-interruption investigation: deferred.
- Extended/24-hour test: user will perform later.
- WAN-only testing: skipped; do not repeatedly request it.
- Reboot restart, automatic disk logging, configurable heartbeat, Clear Log,
  signed release, readable logs, filters, and outage alerts: requested.
- Monitoring was already working; this follow-up changes presentation and adds
  alerts, not the outage state machines, probe behavior, or wake-lock policy.

## Implemented

### Monitoring and persistence

- BootReceiver receives BOOT_COMPLETED and resumes the existing specialUse
  foreground service only when previously active and reboot resume is enabled.
- The setting defaults to enabled. Explicit Stop prevents automatic restart.
- On a PIN-protected phone, first unlock is required before boot resume.
- Observation gaps are logged; durations do not cross a reboot or interruption.
- Force stop remains an Android hard stop.
- Each event and heartbeat appends to internal files/events.csv and syncs to disk.
  Export is not required to save internal history.
- Heartbeat interval is configurable from 10–3600 seconds, default 300.
  Stop, edit, Save configuration, then Start to apply.
- Power changes remain immediate broadcasts. Probe interval and failure threshold
  are independent of heartbeat spacing.
- Clear Log requires stopped monitoring and confirmation. Active readers/exports
  prevent truncation. Exported copies remain intact.
- Legacy checkpoint summaries are retained in preferences. The new outage-history
  panel derives its summaries from CSV, so cleared history is no longer displayed.

### Readable logs, alerts, and reports

- Larger, spaced log entries with bold event headings, readable dates with
  recorded timezone offsets, and durations in days/hours/minutes/seconds.
- Newest 100 matching events displayed; full history stays on disk.
- Filters: All events, Outages, Heartbeats, Power, Wi-Fi/router, Internet, System.
- The selected filter survives reopening. The outage panel is independent of
  the filter and supports simultaneous ongoing outages.
- Recovery entries show observed start time, restoration time, and logged
  monotonic duration. Missing onset or observation gaps stay explicitly unknown.
- A separate Outage alerts notification channel covers charger power, router,
  and Wi-Fi internet. Android controls notification permission, sound, and DND.
- Active alerts show the start date/time and an Android chronometer. No
  one-second notification reposting; recovery replaces the alert with final timing.
- Notification taps request the Outages view. Interrupted observations are
  marked unknown, and old-process alerts are cleared on service recreation.
- HTML reports are self-contained and printable, with the full selected history
  oldest first. CSV export remains available and includes all categories.
- The report generator streams from a bounded snapshot without holding the
  monitoring writer lock during parsing or destination writes.
- Quoted CSV fields and multiline details are supported. HTML content is escaped.
  Read/export failures are explicit and leave internal history intact.
- Configuration can be collapsed to keep logs accessible.
- Network timing precision remains bounded by probe interval, timeout, and
  failure threshold; displayed milliseconds are not an accuracy guarantee.

### Release

- Signed APK: app/build/outputs/apk/release/app-release.apk.
- Local PKCS12 key: signing/powerwatch-release.p12; alias powerwatch.
- Local password and backup ZIP remain private and Git-ignored.
- The ZIP is not encrypted; copy it to secure storage on a separate disk.
- Gradle builds an unsigned release without the local signing files.
- Certificate SHA-256:
  e43d0a1f383a80839b36cc880cbfb81e75a9b6f9bd9c37b800c0a871783c6a50.
- APK Signature Scheme v2 verification passed on the rebuilt release.
- The phone retains the debug installation. Release signing cannot update it
  in place. Do not uninstall without verified log exports and user approval.
- See RELEASE.md for build and migration instructions.

## Automated verification

- Full :app:testDebugUnitTest, :app:assembleDebug, :app:assembleRelease passed
  after the final notification wording change.
- :app:lintDebug passed before that final wording/category-only refinement.
  Release vital lint passed again on the final build.
- Existing boot decision, heartbeat configuration, logger, and state-machine
  tests remain passing.
- Twelve new presentation tests cover categories, last-100 filtering, concurrent
  outages, monotonic duration despite wall-clock reversal, boot/session gaps,
  unknown onset, live-duration guards, CSV quoting/multiline fields, HTML escaping,
  full filtered reports, destination errors, and nonblocking snapshot readers.
- Snapshot readers exclude later appends, protect against clearing, and release
  protection after errors.
- git diff --check passed before documentation updates; repeat before commit.
- Updated debug APKs were installed with adb install -r; no app-data wipe was used.

## Physical-device evidence

Target: Samsung SM-A146U, Android 15/API 35, serial R92W706Z2HR.
Package: com.joey.powerwatch; targetSdk 36.
Times below preserve the phone's recorded offset -07:00.

### Reboot resume — PASS

After the user's reboot and first unlock, ADB access was restored. Evidence:

- boot_count advanced from 15 to 16.
- 11:56:25.523: monitoring_interrupted followed by service_restart with
  resumed-after-reboot detail.
- Service start intent was com.joey.powerwatch.BOOT; Android reported
  BOOT_COMPLETED as its background-start exemption.
- Foreground specialUse service and held PowerWatch:Monitoring partial wake lock.
- Healthy router and Wi-Fi internet initial states, followed by saved heartbeats
  at 12:01:25.704 and 12:06:25.716.
- Power outage 11:56:36.416–11:56:39.225: 2809 ms.
- Power outage 11:59:19.749–12:07:03.533: 463784 ms.
- The short power outage was recorded between heartbeats, demonstrating that
  heartbeat spacing does not determine power-event capture.

### Readable history and HTML export — PASS

- Phone displayed bold, spaced event headings, category choices, and recovery
  summaries. Outages filter displayed four outage events without routine rows.
- Export report used Android's document picker and saved:
  Download/PowerWatch-outages-1790797080486.html.
- Saved file was read back: four matching events, correct start/restoration
  times, router duration 18885 ms, and internet duration 18901 ms.
- Existing CSV exports remained visible in Downloads.
- Duplicate recovery timestamps were subsequently removed from the display.
  Final APK showed the compact start/restoration/duration summary.
- Browser rendering/printing of the HTML was not independently checked.

### Wi-Fi loss and new alerts — PASS

Screen-on sample:
- Router outage: 12:34:37.087–12:34:55.972, 18885 ms.
- Wi-Fi internet outage: 12:34:37.088–12:34:55.989, 18901 ms.
- Both starts and restorations persisted. Android notification records confirmed
  the outage channel and running chronometers, then final recovery durations.

Final APK, short screen-off sample:
- Screen turned off; dumpsys reported mWakefulness=Dozing and the held partial
  wake lock. This is not evidence of hours-long/deep-idle reliability.
- Wi-Fi temporarily disabled, then restored.
- Both outage starts: 12:50:45.072, elapsed_ms 3438676, boot_count 16.
- Router restoration: 12:50:56.234, elapsed_ms 3449838, duration 11162 ms.
- Internet restoration: 12:50:56.258, elapsed_ms 3449862, duration 11186 ms.
- Notifications showed start dates/times and android.showChronometer=true.
  Recovery notifications showed the same final durations and ONLY_ALERT_ONCE.
- No cellular success was used for these probes. Independently usable cellular
  connectivity was not established by this experiment.
- This isolates phone Wi-Fi loss, not a physical router-power or WAN-only failure.
- No new charger unplug/replug alert test was performed during this UI follow-up.

## Last observed device state

Final debug APK installed; monitoring started at 12:50:27.019.
After the screen-off sample: Wi-Fi restored, router and internet recovered,
foreground service still running, partial wake lock held, screen still off.
Heartbeat remained configured to 300 seconds. This is a recorded observation,
not a guarantee of the phone's state after subsequent user actions.

Earlier Phase 2 evidence remains in PHASE2_VALIDATION_2026-09-29.md.
Earlier PIN-lock/setup failures and header-only CSV observations were superseded
by successful runtime access. The cause of earlier history clearing was not
independently established; no destructive Clear Log test was run in this follow-up.

## Remaining checks

- User's extended/24-hour reliability test and battery/thermal observation.
- Physically verify Stop/disabled reboot setting prevents boot resume.
- Shorter user-selected heartbeat cadence on the phone.
- Clear Log cancellation/confirmation acceptance, after exporting history.
- Physical charger transitions with the new alert presentation.
- Actual notification-tap navigation and HTML rendering/printing.
- Manual gateway/interval/threshold, independently usable-cellular fallback,
  wall-clock/timezone change, and gateway/network-change checks.
- Secure off-disk signing backup and an agreed debug-to-release migration.

Do not reopen the WAN-only test or deferred overnight investigation without a
new user request. Do not mark full Phase 3 runtime acceptance complete solely
because builds or these short experiments passed.
