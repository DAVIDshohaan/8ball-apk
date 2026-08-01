# Testing & Device Setup Guide (for other LLM models / contributors)

Full end-to-end workflow to build, install and test **Pool Aim Overlay** on a real device.
This is the exact process used during development (verified working).

---

## 1. Project Layout

- Project root: `D:\Workspace\.rev_sandbox\github\8ball-apk`
- Source: `app\src\main\java\com\poolaim\overlay\`
  - `MainActivity.java` — forces landscape, drives the consent flow, starts the service
  - `OverlayService.java` — foreground service; MediaProjection capture + transparent overlay window
  - `ScreenAnalyzer.java` — pure-Java CV: two-pass felt detection, ball detection, trajectory lines
  - `GameState.java` — ball/table data model
- Manifest: `app\src\main\AndroidManifest.xml`
- APK output: `app\build\outputs\apk\debug\app-debug.apk`

Package name: `com.poolaim.overlay`
Game package: `com.miniclip.eightballpool` (8 Ball Pool)

---

## 2. Local Toolchain (Windows)

These exact tools are installed and known-good:

| Tool | Path |
|------|------|
| JDK 17 | `D:\Tools\jdk17\jdk-17.0.20+8` |
| Gradle 8.5 | `D:\Tools\gradle85\gradle-8.5\bin\gradle.bat` |
| Android SDK | `C:\Users\RANAJA~1\AppData\Local\Android\Sdk` |
| adb | `C:\Users\RANAJA~1\AppData\Local\Android\Sdk\platform-tools\adb.exe` |

### Build the APK

```powershell
$env:JAVA_HOME = "D:\Tools\jdk17\jdk-17.0.20+8"
& "D:\Tools\gradle85\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
```

- APK lands at `app\build\outputs\apk\debug\app-debug.apk`.
- Gradle 8.5 is required (matches the wrapper version); the repo has no usable `gradlew.bat`, so use the system gradle.bat above.
- Build can be slow on first run; subsequent runs are incremental.

---

## 3. Connecting a Device

### Option A — Physical phone (USB)

1. Enable **Developer options** → **USB debugging** on the phone.
2. Plug in USB, accept the RSA fingerprint dialog on the phone ("Allow").
3. Verify:

```powershell
& "C:\Users\RANAJA~1\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
```

- Expected: `10FE5M05TT00036  device`
- If it says `unauthorized`, the user must tap **Allow** on the phone.
- If the device drops, restart adb:

```powershell
$adb = "C:\Users\RANAJA~1\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb kill-server
& $adb start-server
& $adb devices
```

### Option B — Cloud device (Firebase Device Streaming)

1. User reserves a device (e.g. Pixel 10, Android 16) in Firebase **Device Streaming**.
2. It exposes an adb endpoint like `localhost:61897`.
3. Use it with the `-s` flag everywhere:

```powershell
$d = "localhost:61897"
& $adb -s $d devices
```

- Reservation lasts ~15 minutes then expires. Re-reserve and re-run setup if it's gone.
- Install BOTH the game and the overlay APK on the cloud device.

---

## 4. Installing the APKs

```powershell
$adb = "C:\Users\RANAJA~1\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$d = "10FE5M05TT00036"          # physical phone, OR
$d = "localhost:61897"          # cloud device

# Remove old overlay first (signature mismatch blocks reinstall otherwise)
& $adb -s $d uninstall com.poolaim.overlay

# Install the overlay
& $adb -s $d install "D:\Workspace\.rev_sandbox\github\8ball-apk\app\build\outputs\apk\debug\app-debug.apk"

# Install the game (cloud device only — physical phone already has it)
& $adb -s $d install "D:\8+Ball+Pool_56.27.0_APKPure.apk"
```

- `8+Ball+Pool_56.27.0_APKPure.apk` is the game APK (56.27.0). Physical phone uses the Play Store version.
- Output `Performing Streamed Install / Success` = OK.

---

## 5. Granting Permissions (bypass dialogs)

Run **before** first launch so the consent flows auto-skip:

```powershell
& $adb -s $d shell appops set com.poolaim.overlay PROJECT_MEDIA allow
& $adb -s $d shell appops set com.poolaim.overlay SYSTEM_ALERT_WINDOW allow
```

- `PROJECT_MEDIA allow` skips the Android **screen-capture consent picker** entirely.
- `SYSTEM_ALERT_WINDOW allow` skips the overlay-permission Settings page.
- Only the **POST_NOTIFICATIONS** dialog cannot be skipped — must be tapped once (see §6).

---

## 6. Launch + Full Test Sequence

### Step 1 — Launch the app

```powershell
& $adb -s $d shell am start -n com.poolaim.overlay/.MainActivity
```

- The activity forces **landscape** via `setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE)` in `onCreate`. This is mandatory so the capture buffer matches the game's orientation (Android 16 forbids recreating the VirtualDisplay on rotation).

### Step 2 — Tap START and handle the notification dialog

Find the button coordinates with a UI dump:

```powershell
& $adb -s $d shell uiautomator dump /sdcard/ui.xml
& $adb -s $d shell cat /sdcard/ui.xml
```

Parse `text=` and `bounds="[x1,y1][x2,y2]"` from the XML, tap the button center:

```powershell
& $adb -s $d shell input tap 828 505    # START OVERLAY button center
```

This triggers the **POST_NOTIFICATIONS** dialog (`com.google.android.permissioncontroller`). Dump UI again, find the **Allow** button, tap it:

```powershell
& $adb -s $d shell input tap 827 427    # "Allow" button center
```

Because `PROJECT_MEDIA` and overlay are already granted via appops, the capture consent picker is skipped automatically → `onActivityResult(RESULT_OK)` → service starts → `moveTaskToBack(true)` + `finish()` → back to launcher. **This is expected behavior, not a bug.**

### Step 3 — Verify the service is alive

```powershell
& $adb -s $d shell pidof com.poolaim.overlay
```

A PID means the foreground service is running (e.g. `9282`).

### Step 4 — Watch the detector logs

```powershell
& $adb -s $d logcat -s PoolAim -d
```

Clear logcat first for a clean trace: `& $adb -s $d logcat -c`

Expected while the game is NOT showing a table:
```
no felt (n=...)
```

Expected ~50ms while a match table IS on screen (any menu that shows the table too):
```
TABLE found skin=Custom felt=48% 320x718 l=.. t=.. r=.. b=..
```

Expected on capture setup:
```
capture ... created
```

### Step 5 — Open the game and play

1. User opens 8 Ball Pool, enters a match.
2. Verify logcat shows `TABLE found skin=... felt=..%` repeatedly.
3. Ask the user whether they see the yellow (cue→target) and cyan (target→pocket) lines plus the white ghost ball drawn on the table.

---

## 7. Debugging Reference (logcat tag: `PoolAim`)

| Log | Meaning |
|-----|---------|
| `capture ... created` / `recreated` | VirtualDisplay created |
| `capture recreate failed: <msg>` + `keeping old capture WxH rot=N` | Android 16 forbids 2nd VirtualDisplay — old capture kept on purpose (projection stays alive, no crash) |
| `TABLE found skin=<Teal/Green/Gold/Blue/Adaptive> felt=<pct>% <w>x<h> l=.. t=.. r=.. b=..` | Table detected; `skin` = winning HSV profile (Adaptive = custom felt) |
| `no felt (n=<pixels>)` | Felt pixel count below 400 threshold → no table |
| Frame vote line every 64 frames: `buf 320x... felt t=.. g=.. gd=.. b=.. a=.. hist=..-..` | Per-profile counts (teal/green/gold/blue/adaptive) + histogram bins |

### Key engine facts

- Analysis buffer is fixed 320-wide (`ANALYZE_W = 320`), height proportional to capture.
- Capture is created at half display resolution and rotation-corrected.
- Two-pass felt detection: count each of 5 HSV profiles → winner → build felt mask from winner's ranges.
- Profiles: `P_TEAL`=London, `P_GREEN`=Classic, `P_GOLD`=GoldenShot, `P_BLUE`=LuckyShot, `P_ADAPT`=custom (adaptive).
- `GameState` tracks 9 ball colors (white/yellow/blue/red/purple/orange/green/brown/black) with solid/stripe flags.

---

## 8. Known Gotchas

1. **Android 16 (SDK 36):** `createVirtualDisplay` a second time on the same `MediaProjection` throws `SecurityException: Don't take multiple captures...`. The code catches it and keeps the old capture. Never "release then recreate" — on Android 14+ releasing the last VirtualDisplay stops the whole projection.
2. **Rotation:** The app forces landscape. Do NOT rotate the device during a match — the capture cannot be recreated on Android 16.
3. **Reinstall:** Must `uninstall` first; installing a new signature over an old one fails.
4. **Cloud device expiry:** Firebase streaming ends ~15 min; the adb endpoint stops responding (`device not found`). Re-reserve.
5. **`unauthorized` state:** User must tap Allow on the phone's RSA dialog.
6. **Auto-finish after START:** Service starts fine and MainActivity `finish()`es by design — check `pidof` before assuming a problem.

---

## 9. Version Info

- Current: `versionName 2.11`, `versionCode 13`
- minSdk 24 (Android 7.0), targetSdk/compileSdk 34, tested up to Android 16 (SDK 36)
- Felt skins supported: London, Classic, Golden Shot, Lucky Shot, custom (adaptive)

## 10. Related APKs (analysis only, not required)

- `D:\aim-tool-for-8-ball-pool-1-1.apk` — third-party "aim tool" from social media. **Verified FAKE**: no overlay/capture/accessibility, purely an ad scam (every feature shows an ad + fake animation dialog). Do not copy from it.
