# Phase 1 — security, reliability, optional root layer

Branch: `phase1-root-reliability`

## Changes
1. **Remote auth** — PBKDF2 (salt, 120k iter) replaces unsalted SHA-256; old hashes still work and upgrade on next successful login. Constant-time compare. Min length 4 → 8 (new passwords only). Per-IP login lockout: 5 free failures, then 30s doubling up to 15 min (HTTP 429 + Retry-After).
2. **Camera reconnect** — `CameraManager.AvailabilityCallback` triggers an immediate retry when the camera is freed; polling now uses back-off (1,2,2,3,5s) instead of fixed 2s.
3. **Resume after kill** — if the process died mid-recording (`is_recording_in_progress` was true at service start) and the system restarts the service with a null intent, a new recording segment starts automatically. Guard: max 3 resumes / 10 min.
4. **Root layer (optional, no new deps)** — `com.fadcam.root.RootBridge` (plain `su`) + `RootTuner`: while recording, every 30s applies Doze whitelist, RUN_(ANY_)IN_BACKGROUND appops, active standby bucket, lowered `oom_score_adj`; reverts on stop. Kill-switch pref `root_mode_enabled`.
5. **Magisk module** `magisk-module/fadcam-guardian` — root watchdog: restarts RecordingService if the flag is true but the process is dead (after kill or reboot).

## Test plan
- Build: `./gradlew :app:assembleDefaultDebug` (not compiled by the author — fix any compile errors first).
- Auth: set a password, log in from a browser; 6 wrong tries → 429. Old-password upgrade: logcat `Password hash upgraded to PBKDF2`.
- Root tuner: record, `adb logcat -s RootTuner` → compare `before=` / `after=`. If `before` keeps returning to its default each tick, the system overrides oom_score_adj (expected on some ROMs).
- Resume: record, `su -c "kill -9 $(pidof com.fadcam)"` (adjust package) → logcat `auto-resuming`. Repeat with Magisk module installed and the app force-stopped.
- Camera: record, open another camera app, then close it → logcat `became available -> immediate reconnect`.
- Baseline first: run a 1h screen-off recording with `root_mode_enabled=false`, then enabled; compare kill counts.
