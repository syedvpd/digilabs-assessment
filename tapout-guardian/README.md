# TapOut Guardian

An Android app whose only job is to make sure you never forget the **evening attendance tap-out**.
It keeps following up until you have confirmed the tap-out **twice, separately** (default: at 6:00 PM, and again 5 minutes after your first confirmation).

* Morning reminder: 9:00 AM ("Have you completed your attendance tap-in?").
* Evening enforcement: 6:00 PM, then an escalating reminder every 2 min for 20 min, then every 5 min, until the second confirmation is done (or the attendance day ends at midnight, which is recorded as *missed*).
* Everything (times, delay, intervals, snooze, sound/vibration, attendance app) is configurable in **Settings**.
* All times use the device's local time zone.

> The app can only record **what you tell it**. It has no integration with your attendance system and never claims the system registered your tap-out.

---

## Installable artifacts

After a build (or from the committed `dist/` folder):

| File | Notes |
|---|---|
| `dist/tapout-guardian-debug.apk` | debuggable, signed with a throw-away local key |
| `dist/tapout-guardian-release.apk` | non-debuggable, signed with a throw-away local key (**replace the key for real distribution**) |

Package `com.tapoutguardian`, `minSdk 26` (Android 8.0), `targetSdk 34`, `compileSdk 34`.

### Install

```bash
adb install -r dist/tapout-guardian-release.apk      # or copy the APK to the phone and open it
```
Allow "install unknown apps" for your file manager/browser if installing by hand. Then open the app once, accept the first-run explanation, and visit the **Checks** tab until everything shows `[OK]`.

---

## Build from source

```bash
cd tapout-guardian
gradle test          # JVM unit tests (state machine, planner, SQL schema/migration)
gradle assembleAll   # debug + release APKs  -> build/outputs/apk/{debug,release}/
gradle dist          # also copies them to dist/
```

Requirements: JDK 17+, Gradle 8.x, and the Android command-line tools `aapt2`, `dx`, `zipalign`, `apksigner` plus an API-34 `android.jar`.
Paths are read from `local.properties` (`sdk.dir=...` containing `platforms/android-34/android.jar`) or the env vars `ANDROID_HOME`, `ANDROID_JAR`, `ANDROID_BUILD_TOOLS` (default `/usr/lib/android-sdk/build-tools/debian`, i.e. Debian/Ubuntu packages `aapt dalvik-exchange zipalign apksigner`).
The first build generates `keystore/{debug,release}.jks` (git-ignored, throw-away keys).

### Why not Jetpack Compose / Room / AGP?

The cloud sandbox this was built in could reach Maven Central and the Gradle plugin portal but **not Google's Maven repository or the Android SDK download host (`dl.google.com`)**. That means the Android Gradle Plugin, AndroidX, Room and Compose could not be downloaded at all. Rather than deliver nothing, the app is built as:

* **Kotlin** against the plain Android framework (UI is built programmatically with framework `View`s; no Compose, no AppCompat/Material).
* **SQLite via the framework** instead of Room: a small `SqlDb` abstraction (`core/Sql.kt`) with an Android adapter and a JDBC adapter. The **same DDL, migrations and queries** run in the JVM unit tests against a real SQLite engine.
* A plain Gradle + Kotlin-JVM build that drives `aapt2 → kotlinc → dx → zipalign → apksigner` itself (`build.gradle.kts`, `scripts/package-apk.sh`).

The `core` package has no Android dependency, so porting the UI to Compose/Room later does not touch the logic.

---

## What happens, step by step

**9:00 AM** – a distinct notification "Have you completed your attendance tap-in?" with *Open attendance app* (if configured) and *I've tapped in*. It is independent from the evening workflow, never repeats, and acknowledging it can never complete the evening.

**6:00 PM (stage 1)**
1. An alarm-clock alarm fires, the workflow for today's date is created in the database (`FIRST_REMINDER_PENDING`).
2. A high-priority, ongoing, lock-screen-visible notification is posted on an alarm-stream channel (sound + vibration, if enabled), with a full-screen intent when Android allows it.
3. The reminder screen says **DON'T LOSE TODAY'S ATTENDANCE / TAP-OUT NEEDS CONFIRMATION / "Your attendance tap-out is not yet confirmed."** with the big **I HAVE TAPPED OUT** button, *Open attendance app*, *Not yet / I forgot* and a bounded *Snooze*.
4. No confirmation → alerts continue on the grid 6:02, 6:04 … 6:20, 6:25, 6:30 … (configurable).
5. **I HAVE TAPPED OUT** records the time, **does not complete anything**, and shows a countdown. The second deadline is `firstConfirmation + delay` (confirm at 6:17 → second check 6:22).

**Second check (stage 2)** – when the persisted deadline passes, an alarm (or the UI/any trigger, whichever is first) moves the workflow to `SECOND_CHECK_DUE`, alerts loudly again and shows **"SECOND CHECK: Did you successfully tap out in your attendance app?"** with **YES, TAP-OUT IS DONE** and **NOT YET / I FORGOT**. The "YES" action exists *only* on this screen (not as a notification button) and is rejected before the deadline.
* YES → `COMPLETED`, completion time recorded, notifications cancelled.
* Not yet / I forgot → `RECOVERY`: instructions to open the attendance app (with a launch button), reminders continue, and a *fresh* first confirmation + second check is required.
* Nothing → escalating reminders continue until midnight.

### State machine

```
(no record = IDLE)
        │ evening time reached (alarm / boot / launch / time change; once per date)
        ▼
FIRST_REMINDER_PENDING ──I HAVE TAPPED OUT──► WAITING_FOR_SECOND_CONFIRMATION ──deadline passed──► SECOND_CHECK_DUE ──YES──► COMPLETED
        │  ▲                                              │                                            │
        │  └────────────── I HAVE TAPPED OUT ◄── RECOVERY ◄── Not yet / I forgot ──────────────────────┘
        └── Not yet / I forgot ──────────────────────►─────┘
any non-terminal state ──end of attendance day──► EXPIRED ("missed / interrupted")
```
Morning: `MORNING_NOTIFIED → MORNING_ACKNOWLEDGED | EXPIRED`. Test runs use a separate `TEST` record.

---

## Reliability design

| Concern | How it is handled |
|---|---|
| Single source of truth | One SQLite table row per `(attendance date, kind)`; every transition is one transaction (`core/Engine.kt`). Notifications/alarms are **derived from committed state afterwards**, so a failed commit never shows a completed state. |
| Duplicate / late / stale / concurrent events | Every entry point calls the same `advance()` first, which computes what is due from persisted deadlines. Commands are state-checked and idempotent (`APPLIED/DUPLICATE/TOO_EARLY/...`); a process-wide lock plus a single worker thread serialise alarm, boot, notification-action and UI events. |
| One workflow per date | Primary key `(dateKey, kind)`; completed/expired days are never re-created, even if the clock moves backwards. |
| Alarms | Three stable `PendingIntent` slots (evening start, morning start, tick) with `FLAG_IMMUTABLE`; re-arming replaces, never stacks. One-shot only; the next alarm is recomputed from the DB each time (no loops, no wakelocks, no foreground service). |
| Exact timing | Start alarms use `setAlarmClock` (no permission needed, Doze-exempt). Follow-up ticks use `setExactAndAllowWhileIdle` when "Alarms & reminders" is granted, else `setAlarmClock`, and an inexact fallback if a `SecurityException` occurs. |
| Reboot / update / time / time-zone | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`, exact-alarm-permission-changed all re-run `advance()` and re-arm alarms. Duplicate broadcasts are harmless. |
| Late delivery | A missed trigger is honoured for 3 h (`STARTED_LATE`); you get **one** catch-up alert, not a burst. A trigger that predates the app being armed (fresh install / settings edit) is ignored. |
| Clock changes during the countdown | The first confirmation stores wall time + `elapsedRealtime` + boot id. If they disagree by > 5 s on the same boot, the deadline is re-based on *elapsed* time (the clock cannot be used to skip or stretch the wait). After a reboot the persisted wall-clock deadline wins. |
| Midnight | A workflow belongs to the date it started on and expires at the end of that date (extended to deadline + 30 min if the second check falls after midnight). Expiry is recorded as missed; the next day is independent. |
| Dismissed notification | The notification is ongoing and has a delete-intent that re-posts it while the workflow is open. |
| Snooze | Delays only the next alert, max N per workflow (default 3), notification stays. |
| Permissions denied | Never crashes; state keeps advancing; the Checks tab explains what is broken and deep-links to the right Android settings page. |
| Corrupt DB / downgrade | The unreadable file is renamed and a fresh DB is created (no crash loop). Upgrades run transactional migrations (`Schema.migrate`). |

### Honest limits

* **Force stop**: Android cancels all alarms and blocks all receivers until you open the app again. Nothing can recover automatically before that; opening the app (or any later system broadcast it receives) re-evaluates and starts a late workflow if within 3 h.
* **OEM battery managers** (Xiaomi, Oppo/Realme, Vivo, Huawei, Samsung "sleeping apps", ...) may kill apps or block auto-start. Set the app to *Unrestricted* and allow auto-start; the Checks tab shows battery-optimisation status.
* **Do Not Disturb**: alerts use the alarm audio usage/category, so the "Alarms" exception applies; a DND that mutes alarms will mute these too.
* Android can and will let you press Home or open other apps (see below).

---

## Enforcement levels

| Mode | What it does | Requirements |
|---|---|---|
| **A. Normal (default)** | Ongoing high-priority notification, alarm sound/vibration, full-screen reminder when allowed, repeating alerts, re-open on app launch, notification buttons, recovery after reboot. **You can still leave the screen.** | Notifications permission. Recommended: Alarms & reminders, full-screen intent, battery "Unrestricted". |
| **B. Screen pinning (opt-in fallback)** | If you enable the kiosk switch on a non-provisioned phone, the app asks Android for screen pinning. Android shows its own confirmation and you can always unpin (Back + Overview). | User confirmation each time. |
| **C. Lock Task kiosk (opt-in)** | Real Lock Task Mode: Home/Recents removed while a tap-out is pending. Power menu + status info kept; the attendance app is allowlisted so you can actually tap out; a long-press *Emergency exit* is always shown. | App provisioned as **device owner** (see below). |

**True phone-wide blocking is NOT available in the default build/installation.** The app uses no Accessibility service, no overlay (`SYSTEM_ALERT_WINDOW`) and no device-admin policies. Android does not allow an ordinary app to prevent Home/Back/Settings/power; a full-screen intent or overlay would not guarantee it either.

### Optional kiosk setup (test or personally owned device)

Device-owner provisioning is a device-management change: it needs a device with **no accounts** (normally right after a factory reset), is not reversible from the UI, and changes what you can install/modify. The app never provisions anything itself.

```bash
# 1. factory-reset the device, skip account setup, enable USB debugging, install the APK
adb install -r dist/tapout-guardian-debug.apk
# 2. make it device owner
adb shell dpm set-device-owner com.tapoutguardian/.android.AdminReceiver
# 3. in the app: Settings > Kiosk > enable the switch; Checks should show Lock Task as available
# remove (debuggable builds only):
adb shell dpm remove-active-admin com.tapoutguardian/.android.AdminReceiver
```
Same text is available in-app under Settings → "Kiosk setup instructions". **This path is implemented but could not be executed here** (no device/emulator).

---

## Permissions

| Permission | Why | Notes |
|---|---|---|
| `POST_NOTIFICATIONS` | Show the reminder. | Runtime prompt on Android 13+, requested after an explanation. |
| `SCHEDULE_EXACT_ALARM` | Exactly-timed follow-up reminders. | User-granted "Alarms & reminders"; optional (falls back to alarm-clock alarms). |
| `USE_FULL_SCREEN_INTENT` | Open the reminder over the lock screen. | On Android 14+ can be revoked per-app; optional (falls back to heads-up). |
| `RECEIVE_BOOT_COMPLETED` | Re-arm after reboot. | |

Not requested: accessibility, overlay, device-admin policies, `QUERY_ALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (the app opens the system battery list instead), foreground-service permissions (not needed: alarms + notifications only), `VIBRATE` (vibration is played by the notification channel).
A `<queries>` entry for launcher apps lets the app open/pick your attendance app.

Supported: Android 8.0 (API 26) to Android 14 (API 34) by design; behaviour on newer versions follows the same APIs. **Not verified on any physical device or emulator** (see below).

---

## Tests

`gradle test` runs 50 JVM unit tests (`src/test/kotlin/.../EngineTest.kt`) with a fake clock, fake alarm/notification ports and a **real SQLite engine** (sqlite-jdbc):
two-stage confirmation rules, 5-minute deadline from the first confirmation (6:17 → 6:22), duplicate first/second confirmations, "not yet" recovery, escalation grid, bounded snooze, reboot/process-death recovery, late delivery, time-zone and DST handling, forward/backward manual clock changes, permission denial/restoration, dismissed notifications, concurrent commands (16 threads), injected DB failure (rollback), midnight boundary, morning/evening independence, duplicate boot broadcasts, repeated launches, v1→v2 database upgrade and failed-migration rollback, force-stop recovery, kiosk decision logic.

**Not executed / limits of the tests**
* No emulator or device was available (no KVM, no system images), so the **Android glue** (`android/`, `ui/`: notifications, `AlarmManager`, receivers, activities, `startLockTask`) is compile-checked against the API-34 framework and packaged/signature-verified, but **not run**. Robolectric could not be used because it needs `androidx.test` from Google's Maven repository.
* Real `AlarmManager`/Doze/OEM behaviour, full-screen intents on a locked screen, and notification sound can only be validated on hardware.
* Kiosk on a provisioned device (#24): only the decision logic is tested; Lock Task itself was not run.

---

## Troubleshooting

* **No reminder at 6 PM** → Checks tab: notifications off? channel silenced? battery "Unrestricted"? Was the app force-stopped? Open the app once; a missed trigger within 3 h is started late.
* **Reminder shows only as a banner** → "Full-screen reminder" is not allowed (Android 14: Settings → Apps → TapOut Guardian → Allow full-screen notifications).
* **No sound** → Alarm volume, Do Not Disturb, or the system notification-channel settings (Checks → *Open channel settings*).
* **Follow-ups slightly late** → grant "Alarms & reminders".
* **Wrong attendance app opens/none** → Settings → Attendance app → *Pick app*.
* **Locked in kiosk mode** → hold *Emergency exit*; or `adb shell dpm remove-active-admin ...` on debuggable builds.

## Layout

```
src/main/kotlin/com/tapoutguardian/
  core/      pure Kotlin: Model, Schedule (grid/DST), Sql (schema+migrations), Store, Engine (state machine), Planner, Coordinator, Capabilities
  android/   SQLite adapter, AlarmManager, notifications, receivers, device probe, kiosk, process wiring
  ui/        MainActivity (Home/History/Settings/Checks), ReminderActivity (full-screen two-stage flow)
src/test/kotlin/...   unit tests + fakes (JDBC SQLite, fake clock)
scripts/package-apk.sh  dex → align → sign
```
