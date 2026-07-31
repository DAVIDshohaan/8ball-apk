# Pool Aim Overlay - 8 Ball Pool Aiming Assistant

A floating overlay app that draws trajectory guide lines on top of 8 Ball Pool.

## How to Build

### Option 1: GitHub Actions (Recommended)
1. Push this repo to GitHub
2. Go to Actions tab -> "Build APK" -> "Run workflow"
3. Download the APK from the completed workflow

### Option 2: Local Build
Requirements: JDK 17+, Android SDK (command-line tools)

```bash
# Set ANDROID_HOME to your SDK path
export ANDROID_HOME=/path/to/android-sdk

# Build
chmod +x gradlew
./gradlew assembleDebug

# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: Android Studio
1. Open this folder in Android Studio
2. Build -> Build APK

## How to Use (No Root Required)

1. Install the APK on your device
2. Open "Pool Aim Overlay"
3. Grant overlay permission when prompted
4. Tap "Start Overlay"
5. Open 8 Ball Pool and play
6. The overlay shows:
   - **Yellow lines**: Cue ball to target ball trajectory
   - **Cyan lines**: Target ball to pocket path
   - **White circle**: Ghost ball (where to aim)
7. Tap and drag on the overlay for manual aiming guide
8. Use "Stop Overlay" to disable

## Safety (No Ban Risk)
This overlay is **completely external** to 8 Ball Pool:
- ✓ Does NOT modify game files or APK
- ✓ Does NOT read game memory
- ✓ Does NOT inject code
- ✓ Only draws a transparent window on top
- ✓ Uses standard Android API (SYSTEM_ALERT_WINDOW)
- ✓ Cannot be detected by the game

The game sees your touches as completely normal.

## Features
- Automatic trajectory lines from each ball to nearest pocket
- Ghost ball indicator (where to aim for the perfect shot)
- Touch-based manual aiming guide
- Power indicator bar
- Works on any Android 7.0+ device
- No root required
