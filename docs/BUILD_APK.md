# Building the APK

Two options: the automated script (Linux) or Android Studio (any OS).

---

## Option A — Automated script (Linux / Ubuntu)

Requires internet access to download the Android SDK on first run (~350 MB total).

```bash
# Clone the repo (or pull the branch)
git clone https://github.com/tyler262/voicetotext.git
cd voicetotext
git checkout claude/voice-command-floating-button-1v317i

# Build — downloads SDK automatically on first run
bash build.sh

# Output
# app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected Android device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Release build (unsigned)

```bash
bash build.sh release
# app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Option B — Android Studio (Windows / Mac / Linux)

1. Install [Android Studio](https://developer.android.com/studio) (Hedgehog or later)
2. Clone the repo and check out branch `claude/voice-command-floating-button-1v317i`
3. Open the `VoiceToText` folder — let Gradle sync finish
4. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
5. Click **locate** in the notification at the bottom right to find the APK

### Sideload the APK

- Enable **Developer Options** on the device: Settings → About → tap Build Number 7 times
- Enable **USB Debugging**: Developer Options → USB Debugging
- Enable **Install from Unknown Sources**: Settings → Security → Unknown Sources (or per-app in Android 8+)

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or transfer the APK to the device via USB/email and open it directly.

---

## What the build needs

| Dependency | Where it comes from |
|------------|---------------------|
| JDK 17+ | apt / bundled in Android Studio |
| Android SDK Platform 34 | `dl.google.com` (automated by `build.sh` or Android Studio) |
| Build Tools 34.0.0 | Same |
| AndroidX / Material libs | `maven.google.com` (downloaded by Gradle automatically) |
| Kotlin 1.9.24 | Downloaded by Gradle |

All dependencies are pulled automatically during the first build.

---

## Troubleshooting

**`ANDROID_HOME` not set**  
`build.sh` sets this automatically. In Android Studio it is handled by the IDE. If building manually, add to `~/.bashrc`:
```bash
export ANDROID_HOME=$HOME/.android-sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

**`SDK location not found`**  
Create `local.properties` in the project root:
```
sdk.dir=/path/to/your/android-sdk
```

**Gradle sync fails**  
Make sure `maven.google.com` and `services.gradle.org` are reachable. Both are required for the first sync.

**`SYSTEM_ALERT_WINDOW` permission dialog doesn't appear on Android 10+**  
This is expected — the overlay permission requires the user to go to Settings. The app's permission screen includes a button that opens the correct settings page.
