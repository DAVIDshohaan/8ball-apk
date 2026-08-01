# Pool Aim Overlay - 8 Ball Pool Aiming Assistant

A floating overlay app for 8 Ball Pool (`com.miniclip.eightballpool`) that captures the screen via MediaProjection and draws trajectory guide lines on a transparent overlay window.

**Version 2.11** — Android 16 (SDK 36) compatible, rotation-correct capture, two-pass adaptive felt detection.

## How It Works

```
Screen capture (MediaProjection)
        │
        ▼
ImageReader → 320-wide analysis buffer (rotation-corrected)
        │
        ▼
ScreenAnalyzer (pure Java, no native code)
  ├─ Two-pass felt detection (5 HSV profiles)
  │    Teal = London, Green = Classic, Gold = GoldenShot,
  │    Blue = LuckyShot, Adaptive = Custom (histogram winner)
  ├─ Table rect + ball detection (9 colors, stripes/solids)
  ├─ Cue ball tracking (white)
  └─ Trajectory: cue→target line, target→pocket line, ghost ball,
     path-blocking check between balls
        │
        ▼
OverlayView (TYPE_APPLICATION_OVERLAY, touch-through)
  └─ Draws lines only when table found
```

The app is forced to landscape so the capture always matches the game's landscape orientation. The capture is created once and never re-created (Android 14+ stops the projection when the last VirtualDisplay is released; Android 16 forbids creating a second VirtualDisplay on the same MediaProjection entirely).

## How to Build

### Option 1: Local Build (Windows)
Requirements: JDK 17+, Android SDK, Gradle 8.5

```powershell
$env:JAVA_HOME = "D:\Tools\jdk17\jdk-17.0.20+8"
& "D:\Tools\gradle85\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon

# APK at: app\build\outputs\apk\debug\app-debug.apk
```

### Option 2: GitHub Actions
1. Push this repo to GitHub
2. Go to Actions tab -> "Build APK" -> "Run workflow"
3. Download the APK from the completed workflow

### Option 3: Android Studio
1. Open this folder in Android Studio
2. Build -> Build APK

## How to Use

1. Install the APK: `adb install app-debug.apk`
2. Open "Pool Aim Overlay" (opens in landscape)
3. Tap START — grant notifications + overlay permission, then allow screen capture
   - To skip the consent picker: `adb shell appops set com.poolaim.overlay PROJECT_MEDIA allow`
   - And: `adb shell appops set com.poolaim.overlay SYSTEM_ALERT_WINDOW allow`
4. Open 8 Ball Pool (landscape) and start a match
5. Overlay draws:
   - **Yellow line**: cue ball → target ball trajectory
   - **Cyan line**: target ball → pocket path
   - **White circle**: ghost ball (where to aim)
6. Tap STOP in the app to disable

## Debugging

Log tag: `PoolAim` (`adb logcat -s PoolAim`)

- `TABLE found skin=<profile> felt=<pct>% <w>x<h> l=.. t=.. r=.. b=..` — table detected every ~50ms
- `no felt (n=...)` — no table; felt pixels below 400 threshold
- `capture ... created|recreated` / `keeping old capture ...` — capture lifecycle (Android 16: recreate is forbidden, so old capture is kept)
- Felt vote line every 64 frames: `buf 320x... felt t=.. g=.. gd=.. b=.. a=.. hist=..-..`

## Safety (No Ban Risk)

The overlay is **completely external** to 8 Ball Pool:
- ✓ Does NOT modify game files or APK
- ✓ Does NOT read game memory (no root, no Frida, no Xposed)
- ✓ Does NOT inject code
- ✓ Only screenshots the screen (standard MediaProjection API) and draws on top
- ✓ The game process has no idea the overlay exists

## Compatibility

- Android 7.0+ (SDK 24), tested up to Android 16 (SDK 36)
- 8 Ball Pool in landscape orientation
- Felt skins: London, Classic, Golden Shot, Lucky Shot, custom skins (adaptive)

## Known Limitations

- Overlay starts in landscape; do not rotate during a match (Android 16 cannot recreate the capture)
- Aim lines are a visual guide only — you still aim and shoot normally
- No touch simulation — the overlay is touch-through, the game receives your touches unchanged
