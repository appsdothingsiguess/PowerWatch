# PowerWatch local release

The signed APK is at:
`app/build/outputs/apk/release/app-release.apk`.

The release key was created September 30, 2026:
- PKCS12 file: `signing/powerwatch-release.p12`
- Alias: `powerwatch`
- Password file: `signing/password.txt`
- Local backup containing key and password: `signing/powerwatch-signing-backup.zip`
- Signing certificate SHA-256:
  `e43d0a1f383a80839b36cc880cbfb81e75a9b6f9bd9c37b800c0a871783c6a50`

The signing directory is excluded from Git and its Windows ACL is restricted to
the current user. Keep the ZIP private: it contains the password and is not
encrypted. Copy it to a secure backup on another disk; the current local ZIP
alone does not protect against loss of this computer.

Build with the same signing files for all future updates:

```powershell
.\gradlew.bat :app:assembleRelease --no-configuration-cache
```

Without the private signing files, release builds are unsigned. Verify an APK
with Android SDK build-tools apksigner before distributing it:

```text
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

The September 30 APK passed apksigner verification with APK signature scheme v2
and the certificate above. Debug and release builds and the unit suite passed.

## Installation and data

The installed development app uses Android's debug key. The release key cannot
update that installation in place. Do not uninstall the debug app until its
logs have been exported and verified: uninstalling removes internal app data.
The signed release APK has been built but has not replaced the debug installation.

Logs save automatically to the phone's internal app storage as `files/events.csv`.
Each event and configurable heartbeat is appended and synced to disk.
Heartbeat interval is set while stopped, in seconds (10–3600; default 300).
Power changes are recorded immediately, independently of the heartbeat interval. Export CSV
creates a separate user-selected copy. No broad storage permission is needed.
Clear Log requires stopped monitoring and explicit confirmation, and preserves
exported copies. It clears the CSV history; the last-outage status summary is
retained.

## Restart after reboot

Restart after reboot defaults to enabled and is configurable while stopped.
Monitoring resumes only if it was running before reboot. Explicit Stop prevents
automatic boot restart. On a PIN-protected phone, Android delivers BOOT_COMPLETED
after the first unlock, so monitoring does not begin before that unlock.
The observation gap is logged, and outage durations do not span the reboot.
Force stop remains an Android hard stop.

The receiver uses the existing specialUse foreground service and adds only
RECEIVE_BOOT_COMPLETED. Android's background foreground-service start documentation
lists boot completion as an exemption; specialUse has no while-in-use prerequisite:
[Android documentation](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## September 30 validation status

- Boot resume decision tests passed for enabled/disabled and running/stopped cases.
- CSV clear/header/new-event and clear-versus-export tests passed.
- Complete debug unit suite, assembleDebug, assembleRelease, and release vital lint passed.
- New debug APK installed with app data preserved.
- Physical boot restart and Clear Log UI validation remain pending because the
  connected phone is PIN-locked. No real reboot or destructive log clear was
  performed during this implementation.
- After the APK update, no running foreground monitoring process was confirmed.
  Unlock the phone and press Start monitoring before relying on monitoring.
- Overnight interruption investigation is deferred at the user's request.
- The user will perform extended/24-hour reliability testing later.
- WAN-only testing remains skipped at the user's request.
- PLAN.md was not changed.
