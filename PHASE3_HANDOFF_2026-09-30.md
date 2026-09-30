# PowerWatch Phase 3 handoff — September 30, 2026

Status: implementation, automated tests, and signed-release build are complete.
Physical reboot restart and Clear Log UI acceptance are pending phone unlock.
Do not mark Phase 3 runtime acceptance complete yet.

Read PLAN.md and root/app AGENTS.md before continuing. PLAN.md was not changed.
No production dependency was added.

## Commits and repository

- `f025775`: CSV export and physical-device validation evidence.
- `132fb2a`: boot restart, confirmed log clearing, and release signing.
- This handoff is committed separately after those implementations.
- Branch: main.
- No Git remote is configured. Push requires the user's repository URL.
- Never commit the private signing directory.

## User decisions

- Overnight network-interruption investigation: deferred at user's request.
- Extended/24-hour test: user will perform later.
- WAN-only testing: skipped at user's request; do not repeatedly request it.
- Restart after reboot: required.
- Confirmed Clear Log and signed release APK: requested.
- Logs must save automatically to disk.
- Commit completed work and maintain a Phase 3 handoff.

## Implemented

### Reboot restart

- BootReceiver receives BOOT_COMPLETED.
- Added RECEIVE_BOOT_COMPLETED for that receiver.
- Uses the existing specialUse foreground MonitoringService.
- Resume-after-reboot setting defaults to enabled; configurable while stopped.
- Receiver starts only if monitoring was previously active and reboot restart
  is enabled. Explicit Stop prevents boot restart.
- The service rechecks the persisted active flag.
- Restart records service_restart with resumed-after-reboot detail.
- Existing interruption checkpoint records the unobserved gap and resets timing;
  durations do not cross a reboot.
- A PIN-protected phone must be unlocked once after reboot before BOOT_COMPLETED
  and credential-protected logs/preferences are available.
- Boot-start permission/background restrictions are recorded as an explicit
  persisted error for display on the next Activity launch.
- Force stop remains an expected Android hard stop.

Android documentation was checked for targetSdk 36 foreground-service behavior:
[Background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

### Automatic disk logging and Clear Log

- Automatic persistence already existed: each event/heartbeat is appended to
  internal files/events.csv and FileDescriptor.sync is called.
- No export or Save button is required for internal persistence.
- Heartbeats occur every five minutes; successful probes are not logged per second.
- UI wording now states that logs save automatically.
- Export CSV creates a separate user-selected snapshot.
- Clear Log requires stopped monitoring, no shutdown/config-save/clear operation,
  and explicit confirmation. Existing exported copies remain intact.
- An active export prevents logger truncation, including overlapping Activities.
- A stopping status flag prevents new Activity operations during service cleanup.
- Clear retains the CSV header and permits subsequent events.
- Last-outage status summaries remain after CSV clearing.
- No actual historical device log was cleared during this implementation.

### Signed release

- Built app/build/outputs/apk/release/app-release.apk.
- Generated a 3072-bit RSA release key with 10,000-day certificate validity.
- PKCS12 key: signing/powerwatch-release.p12; alias powerwatch.
- Credentials: signing/password.txt.
- Local backup: signing/powerwatch-signing-backup.zip.
- Entire signing directory is Git-ignored and Windows access is restricted to
  the current user.
- ZIP contains both key and password and is not encrypted. The user still needs
  to copy it to secure storage on another disk.
- Gradle loads local signing files when present; without them release builds
  are unsigned.
- Release certificate SHA-256:
  e43d0a1f383a80839b36cc880cbfb81e75a9b6f9bd9c37b800c0a871783c6a50.
- apksigner verification passed with APK Signature Scheme v2.
- Debug installation was retained. The release key cannot update the debug-key
  installation in place. Do not uninstall it without verified CSV exports and
  user authorization for the data-removing migration.

See RELEASE.md for build/signing/installation instructions.

## Verification performed

- After reboot feature: :app:testDebugUnitTest and :app:assembleDebug passed.
- After Clear Log feature: same complete unit suite and debug build passed.
- Final :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease passed.
- Release vital lint passed as part of assembleRelease.
- apksigner verify --verbose --print-certs passed.
- git diff --check passed.
- Boot decision test covers all combinations of prior monitoring state and
  reboot setting.
- Logger tests cover complete snapshot/concurrent appends, errors, clear/header/
  subsequent events, and clear rejection during export.
- Updated debug APK installed with adb install -r, preserving app data.

Earlier CSV export, Stop/Start, Recents removal, screen-off Wi-Fi outage/recovery,
and overnight heartbeat evidence are in PHASE2_VALIDATION_2026-09-29.md.
Those tests preceded the boot/Clear Log implementation and do not establish
physical acceptance of the new reboot feature.

## Device state at handoff

Target: Samsung SM-A146U, Android 15/API 35, serial R92W706Z2HR.
Package: com.joey.powerwatch; targetSdk 36.

ADB is connected. The device was PIN-locked during attempted Activity setup.
The most recent check reported Awake but did not confirm a running app process,
foreground monitoring service, or held PowerWatch wake lock.

The service inventory contains an inactive ServiceRecord from a rejected shell
start attempt, with no application process. A ServiceRecord alone is not proof
that monitoring runs. Shell start was denied because the service is not exported;
do not export the service to work around this.

Latest persisted heartbeat observed:
- September 30 11:09:42.492, phone-local offset -07:00.
- elapsed_ms 75187880, boot_count 15.
- Connected power, healthy router and internet.

No new boot-restart event was observed. No real reboot was performed in Phase 3.
Unlock locally and press Start monitoring before relying on the app.
Do not ask the user to send the phone PIN.

## Next actions

1. Unlock phone locally; inspect Activity, Start monitoring, and confirm new
   monitoring_started/healthy states and partial wake lock in persisted evidence.
2. Perform a real normal reboot with monitoring enabled. After first unlock,
   confirm automatic foreground restart without tapping Start, truthful
   notification, service_restart/reboot gap records, new boot count, healthy
   network probes, and a later heartbeat.
3. Verify stopping monitoring prevents restart after a subsequent reboot.
   Test disabled reboot setting when practical, restoring enabled afterward.
4. Test Clear Log confirmation and cancellation physically. Export and verify
   current history before any destructive successful-clear test, and preserve
   existing exports. Restart monitoring afterward.
5. User performs extended/24-hour acceptance later.
6. Back up signing material to a secure separate disk.
7. Plan debug-to-release migration with the user, preserving current logs.
8. Commit updated runtime evidence and refresh this handoff after checks.
9. Configure remote and push once the repository URL is supplied.

Manual gateway/interval/threshold, independently usable-cellular fallback,
timed outage accuracy, and wall-clock/gateway-change checks remain unverified
as described in the Phase 2 record. No WAN test or overnight interruption
investigation is required by the user's latest decisions.
