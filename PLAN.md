



# Plan.MD

The project itself should include the **Gradle Wrapper**:

```text
gradlew
gradlew.bat
gradle/wrapper/...
```

so Codex can simply execute:

```powershell
.\gradlew.bat assembleDebug
```

and receive:

```text
app\build\outputs\apk\debug\app-debug.apk
```

---

# 2. Project specification for the AI agent

Working name:

**PowerWatch**

Primary purpose:

> Continuously determine whether AC power, the local router, and the internet are available, while the Android device is idle and its screen is off. Record outages locally with accurate start/end timestamps and durations.

Do **not** overengineer this.

### Technology

Use:

```text
Language:       Kotlin
UI:             Android Views/XML
Architecture:   Single Activity + Foreground Service
Storage:        Append-only CSV files
Networking:     Android native APIs
Build:          Gradle
Database:       NONE
Cloud:          NONE
Accounts:       NONE
Analytics:      NONE
Server:         NONE
```

I would deliberately use traditional Android Views instead of Compose here. The UI is tiny, and Views reduce dependencies and compatibility issues on an old phone.

---

# 3. First thing the agent should do

Before choosing `minSdk`, have Codex inspect the actual phone:

```powershell
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
```

Example:

```text
Android version: 11
SDK:             30
Manufacturer:    Samsung
Model:           SM-G960U
```

Then set:

```text
minSdk <= device SDK
compileSdk = 36
targetSdk = 36
```

Don't arbitrarily choose a minimum SDK until we know how old the phone actually is.

---

# 4. Core architecture

The app should have four components:

```text
MainActivity
     │
     ▼
MonitoringService ─────────────┐
     │                         │
     ├── PowerMonitor          │
     ├── NetworkMonitor        │
     ├── ProbeEngine           │
     │                         │
     ▼                         ▼
 StateMachine             EventLogger
                               │
                               ▼
                         events.csv
```

### `MainActivity`

Only responsible for:

- current status
- start monitoring
- stop monitoring
- configuration
- last outage
- export log
- clear log

It must **not** perform monitoring itself.

---

# 5. Foreground monitoring service

Create:

```text
MonitoringService
```

It should run as an Android **foreground service** with a persistent notification:

```text
PowerWatch
Monitoring power and network

Power:    Connected
Router:   Online
Internet: Online
```

Foreground services are the Android mechanism for ongoing user-visible work while the user isn't interacting with the app. Android 14+ also requires a declared foreground-service type. :chatgpt-content-reference{index="6"}

For this unusual continuous monitoring case, use:

```xml
android:foregroundServiceType="specialUse"
```

plus:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
```

and declare the special-use subtype as something like:

```text
Continuous local AC-power and network-outage monitoring
```

`specialUse` exists specifically for valid foreground-service cases not represented by another type. :chatgpt-content-reference{index="7"}

Do **not** abuse `dataSync`; this isn't data synchronization.

---

# 6. Power monitoring

This is the most important component.

Use Android's native broadcasts:

```text
ACTION_POWER_CONNECTED
ACTION_POWER_DISCONNECTED
```

Android explicitly documents these for detecting when external power is added or removed, and says apps can be awakened for these broadcasts. :chatgpt-content-reference{index="8"}

Also inspect:

```text
BatteryManager.EXTRA_PLUGGED
```

at service startup so we know the initial state. Android reports `0` when running on battery and nonzero when externally powered. :chatgpt-content-reference{index="9"}

State:

```kotlin
enum class PowerState {
    CONNECTED,
    DISCONNECTED
}
```

Log immediately.

No polling.

For example:

```text
2026-09-28T22:31:14.221-06:00,POWER,DOWN
```

When power comes back:

```text
2026-09-28T22:31:18.407-06:00,POWER,UP,4186
```

### Important interpretation

This proves:

> The **charger powering the phone** lost external power.

If that charger is plugged directly into the wall on the home's electrical system, that's an excellent proxy for your electrical outages.

---

# 7. Router detection

Do **not hard-code**:

```text
192.168.0.1
```

Auto-detect the Wi-Fi gateway.

Use:

```text
ConnectivityManager
NetworkCallback
LinkProperties
RouteInfo
```

Android provides gateway information through the routes in `LinkProperties`; `RouteInfo.getGateway()` exposes the next-hop address. :chatgpt-content-reference{index="10"}

Expected result could be:

```text
192.168.0.1
```

but the application should discover it.

Allow a manual override in Settings.

---

# 8. Network monitoring

Use `ConnectivityManager.NetworkCallback` to immediately learn about:

```text
onAvailable()
onLost()
onLinkPropertiesChanged()
```

Android provides these specifically for network-state changes. :chatgpt-content-reference{index="11"}

However, **do not treat Android saying Wi-Fi is connected as proof that the router or internet actually works.**

That's where active probing comes in.

---

# 9. Active probes

Every:

```text
1000 ms
```

check:

```text
Router gateway
Internet target
```

Default:

```text
Router:   auto-detected
Internet: 1.1.1.1
```

Run them concurrently.

### Router

Use:

```kotlin
InetAddress.isReachable(...)
```

with the Wi-Fi network interface specified.

Android's implementation attempts an **ICMP Echo Request first**, and if that fails falls back to TCP Echo. This is actually documented by Android. :chatgpt-content-reference{index="12"}

Use roughly:

```text
timeout = 700 ms
TTL = 2
```

The AI should nevertheless isolate this behind:

```kotlin
interface HostProbe
```

so we can swap it later if the particular phone/router behaves strangely.

---

# 10. Don't let cellular hide an outage

This is important.

Suppose:

```text
Router dies
→ Android Wi-Fi dies
→ phone switches to Verizon/AT&T
→ 1.1.1.1 still works
```

The app must **not** report internet as healthy.

Network probes should explicitly use the **Wi-Fi network/interface** acquired from `ConnectivityManager`.

That means you do **not** need to permanently disable cellular data.

We want to measure:

```text
HOUSE INTERNET VIA WI-FI
```

not:

```text
CAN THIS PHONE GET ONLINE SOMEHOW?
```

---

# 11. Outage state machine

Don't log a new error every second.

Maintain:

```text
UNKNOWN
UP
SUSPECTED_DOWN
DOWN
```

For power:

```text
CONNECTED -> DISCONNECTED

log immediately
```

For network:

```text
UP
 │
 ├─ failure #1
 ▼
SUSPECTED_DOWN
 │
 ├─ failure #2
 ▼
DOWN
```

But record the outage's starting timestamp as **failure #1**.

Therefore if probes happen:

```text
10:00:00 success
10:00:01 failure
10:00:02 failure
```

the log says:

```text
Outage started: 10:00:01
```

rather than:

```text
10:00:02
```

Recovery requires one successful probe:

```text
DOWN → success → UP
```

This prevents one lost packet from becoming a false outage without throwing away the first second of the real outage.

---

# 12. Timestamp design

Use **two clocks**.

For human-readable timestamps:

```kotlin
System.currentTimeMillis()
```

For outage duration:

```kotlin
SystemClock.elapsedRealtime()
```

This matters.

If Android's clock gets adjusted by NTP while an outage is underway, something like:

```text
endTime - startTime
```

using wall time can give an incorrect duration.

Monotonic time doesn't have that problem.

---

# 13. Logging

Do not log every successful ping.

At one check per second that would generate:

```text
86,400 router records/day
86,400 internet records/day
```

for no useful reason.

Instead log **state changes plus periodic heartbeat records**.

For example:

```csv
timestamp,event,target,state,duration_ms,detail
2026-09-28T22:31:14.221-06:00,outage_start,power,down,,
2026-09-28T22:31:14.851-06:00,outage_start,router,down,,192.168.0.1
2026-09-28T22:31:15.102-06:00,outage_start,internet,down,,1.1.1.1
2026-09-28T22:31:18.407-06:00,outage_end,power,up,4186,
2026-09-28T22:31:29.223-06:00,outage_end,router,up,14372,192.168.0.1
2026-09-28T22:31:31.847-06:00,outage_end,internet,up,16745,1.1.1.1
```

Also create a heartbeat maybe every:

```text
5 minutes
```

such as:

```text
2026-09-28T22:35:00,heartbeat,service,up
```

That lets us distinguish:

> Nothing went wrong

from:

> Android killed the monitoring service six hours ago.

---

# 14. Screen-off reliability

This is where the agent needs to spend testing effort.

For one-second monitoring while the screen is asleep, acquire a:

```text
PARTIAL_WAKE_LOCK
```

while monitoring is enabled.

Android documents that a partial wake lock keeps the CPU running while allowing the screen to remain off. :chatgpt-content-reference{index="13"}

Required:

```xml
<uses-permission android:name="android.permission.WAKE_LOCK"/>
```

Do **not** keep the screen on.

Initially, don't hold a Wi-Fi lock. Test without it.

If the test phone suspends Wi-Fi while sleeping, then add a:

```text
WifiLock
```

Android provides `WifiLock` specifically to prevent the Wi-Fi radio from sleeping, though Google cautions against using it unnecessarily. :chatgpt-content-reference{index="14"}

---

# 15. Initial permission set

Something approximately like:

```xml
INTERNET
ACCESS_NETWORK_STATE
WAKE_LOCK

FOREGROUND_SERVICE
FOREGROUND_SERVICE_SPECIAL_USE

POST_NOTIFICATIONS
RECEIVE_BOOT_COMPLETED
```

Do not request:

```text
Location
Camera
Microphone
Contacts
Storage-wide access
Bluetooth
Accessibility
VPN
```

None are needed.

---

# 16. Main UI

Keep version 1 roughly this simple:

```text
┌──────────────────────────────┐
│ PowerWatch                   │
│                              │
│ Monitoring ●                 │
│                              │
│ AC POWER                     │
│ Connected                    │
│                              │
│ ROUTER                       │
│ 192.168.0.1                  │
│ Online · 3 ms                │
│                              │
│ INTERNET                     │
│ 1.1.1.1                      │
│ Online · 18 ms               │
│                              │
│ LAST POWER OUTAGE            │
│ Sep 28, 10:31 PM             │
│ 4.19 seconds                 │
│                              │
│ [ View Log ]                 │
│ [ Export CSV ]               │
│                              │
│ [ Stop Monitoring ]          │
└──────────────────────────────┘
```

That's enough.

---

# 17. Settings

Version 1 settings:

```text
Check interval
    1 second

Router
    Auto detect
    Manual override

Internet test IP
    1.1.1.1

Failure threshold
    2

Start after reboot
    Yes/No
```

Do not add a giant configuration system.

---

# 18. Boot handling

After the core monitoring works, implement:

```text
BOOT_COMPLETED
```

and optionally restart monitoring after the device reboots if the user had monitoring enabled.

Android allows `BOOT_COMPLETED` to be one of the exceptions for starting a foreground service from the background, although modern Android places additional restrictions on some foreground-service types. :chatgpt-content-reference{index="15"}

Use:

```text
START_STICKY
```

for the monitoring service.

But don't assume that guarantees survival on every OEM Android build.

---

# 19. Build sequence for Codex

Tell the agent to work in this order:

1. Create minimal Kotlin Android project.
2. Make `assembleDebug` succeed before writing functionality.
3. Detect connected phone with `adb`.
4. Make blank app install and launch.
5. Build foreground service.
6. Implement power connected/disconnected logging.
7. Test unplugging the charger.
8. Implement Wi-Fi `NetworkCallback`.
9. Implement automatic gateway discovery.
10. Implement router probe.
11. Implement internet probe.
12. Build outage state machine.
13. Implement CSV logger.
14. Add wake lock.
15. Test with screen off.
16. Add UI status.
17. Add CSV export.
18. Add boot restart.
19. Run long-duration reliability testing.
20. Produce signed release APK.

**Do not start with the UI.**

The first meaningful milestone should be:

> Put phone to sleep → unplug charger → plug charger back in → open log → exact outage duration exists.

---

# 20. Testing matrix

The agent should physically test these scenarios using the old Android phone:

| Test | Expected result |
|---|---|
| Unplug phone charger | Power outage logged immediately |
| Plug charger back in | Power recovery + duration |
| Unplug router only | Power remains UP; router/internet DOWN |
| Disconnect ISP WAN | Power/router UP; internet DOWN |
| Turn phone screen off 30 min | Monitoring continues |
| Leave screen off several hours | Heartbeats/probes continue |
| Enable cellular + unplug router | Wi-Fi internet recorded DOWN |
| Reboot phone | Monitoring resumes if enabled |
| Kill app UI | Service continues |
| Swipe app from Recents | Monitoring continues |
| Force-stop app | Monitoring stops — expected Android behavior |
| Change wall clock | Duration remains accurate |
| Router changes DHCP address | Gateway rediscovered |

The **cellular test is particularly important**.

---

# 21. Reliability test before trusting it

Before using the app to diagnose your electrical problem:

Run it for **24 hours** with no intentional outages.

Then check:

```text
heartbeat continuity
service uptime
false router failures
false internet failures
battery temperature
CPU/battery usage
```

Then perform ten controlled tests:

```text
1 second outage
2 second outage
3 second outage
5 second outage
10 second outage
```

twice each.

Compare the actual unplug duration against what PowerWatch records.

The goal should be roughly:

```text
Power detection:    <250 ms event latency
Router detection:   ~1–2 sec
Internet detection: ~1–2 sec
Outage duration:    ±1 sec for network
```

Power transitions should be considerably more precise because they're event-driven.

--

# 22. Release APK

During development:

```powershell
.\gradlew.bat assembleDebug
```

Install:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

For your permanent installation, create a **release signing key** and build a signed release APK.

Back up the keystore.

That matters because subsequent versions must be signed with the **same key** to update the installed app. Otherwise you'll have to uninstall the previous version and potentially lose its app data.

---

