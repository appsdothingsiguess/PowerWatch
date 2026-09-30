# PowerWatch — Android Studio Project Rules

Use this text in:

**Android Studio → Settings → Tools → AI → Prompt Library → Rules**

Set **Scope = Project**.

`AGENTS.md` remains the canonical shared instruction file. These rules are intentionally short because Android Studio prepends Rules to every prompt.

---

- Read `AGENTS.md` and `plan.md` before making architectural or behavioral changes.
- Treat the user's latest explicit instruction as higher priority than `plan.md`; treat `plan.md` as higher priority than implementation assumptions.
- Keep PowerWatch minimal. Do not add Room, Firebase, cloud services, dependency injection, repository layers, Clean Architecture, Compose, analytics, accounts, or unnecessary dependencies unless explicitly approved.
- Use Kotlin and Android Views/XML unless the user changes the stack.
- The foreground monitoring service is product-critical; the Activity is only UI/configuration.
- Power-loss detection must be event-driven.
- Router and internet health require explicit reachability probes; Wi-Fi association alone is not proof of reachability.
- Internet probes must be bound to the monitored Wi-Fi network and must never silently succeed through cellular.
- Use monotonic elapsed time for outage durations and wall-clock time only for human-readable timestamps.
- Log state transitions and periodic heartbeats, not every successful probe.
- Preserve buildability after every meaningful change and run `.\gradlew.bat assembleDebug`.
- Prefer the physical Android phone over emulator assumptions for power, network, foreground-service, reboot, Doze, screen-off, and long-running reliability behavior.
- Do not call a runtime feature complete merely because it compiles.
- Keep patches small and independently testable.
- Prefer Android platform APIs over third-party libraries.
- UI polish is lower priority than monitoring reliability.
- Never expose secrets, signing keys, local credentials, or unrelated personal/device data.
