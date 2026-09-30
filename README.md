# PowerWatch

Android app that records outages of the phone's external charger power,
Wi-Fi router, and internet connection through Wi-Fi.

Monitoring runs in a foreground service with the screen off. Events and
configurable heartbeats save automatically on the phone.

- Outage alerts with start time and duration.
- Filtered event history.
- Raw CSV and printable HTML report exports.
- Optional restart after reboot, following the first unlock.

Start monitoring while connected to your charger and Wi-Fi. Stop monitoring
before changing settings. Cellular data does not satisfy Wi-Fi probes.

## Build

Open in Android Studio, or run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Android 9 or newer. See [RELEASE.md](RELEASE.md) for release signing and
[the Phase 3 handoff](PHASE3_HANDOFF_2026-09-30.md) for validation status.
