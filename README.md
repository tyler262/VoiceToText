# VoiceToText — Aircraft Voice Command

A floating-button Android app that lets crew issue voice commands without navigating a GUI.  
Tap the mic, say "turn the master bedroom TV on bluray", tap again — the command is sent as a UDP multicast packet to every system on the aircraft LAN.

---

## How it works

```
Crew taps mic → AudioRecord captures PCM
                → WAV POSTed to Whisper server on LAN
                       → transcribed text returned
                              → JSON envelope sent as UDP multicast
                                     → AV / lighting systems receive & act
```

**No internet required.** Speech recognition runs on an Ubuntu server on the aircraft network.

---

## Repository layout

```
├── app/                    Android application source
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/voicetotext/aircraft/
│       │   ├── MainActivity.kt          – Settings & permission wizard
│       │   ├── FloatingButtonService.kt – Overlay button + audio recording
│       │   ├── WhisperClient.kt         – Audio capture → HTTP → transcription
│       │   ├── WifiMonitor.kt           – Auto-show button on target SSID
│       │   ├── UdpMulticastSender.kt    – Send UDP multicast packet
│       │   ├── CommandFormatter.kt      – Format text → JSON envelope (stub)
│       │   ├── BootReceiver.kt          – Auto-start after device reboot
│       │   └── Prefs.kt                 – SharedPreferences keys & defaults
│       └── res/
├── server/                 Python Whisper server (runs on Ubuntu)
│   ├── server.py           FastAPI + faster-whisper HTTP API
│   ├── requirements.txt
│   ├── install.sh          One-shot installer (creates systemd service)
│   └── whisper-server.service
├── docs/
│   ├── SETUP_SERVER.md     Ubuntu server setup guide
│   ├── BUILD_APK.md        How to build the APK
│   └── USAGE.md            Day-to-day usage guide
├── build.sh                One-command build script (Linux)
└── README.md               ← you are here
```

---

## Quick start

### 1 — Set up the Whisper server (Ubuntu machine on aircraft LAN)

```bash
cd server/
sudo bash install.sh
curl http://localhost:8765/health   # should return {"status":"ok","model":"small.en"}
```

See [docs/SETUP_SERVER.md](docs/SETUP_SERVER.md) for full details, model tuning, and GPU setup.

### 2 — Build the APK

```bash
bash build.sh          # downloads Android SDK automatically, outputs debug APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in **Android Studio → Build → Build APK(s)**.

See [docs/BUILD_APK.md](docs/BUILD_APK.md).

### 3 — First launch

1. Tap **Grant Permissions** — follow the prompts for microphone, location (needed to read WiFi SSID), and the overlay permission (opens system settings)
2. Enter:
   - **Whisper server URL** — `http://<server-lan-ip>:8765`
   - **Target WiFi SSID** — the aircraft network name (leave blank to always show the button)
   - **Multicast IP / Port** — match your AV system's listener (default `239.255.0.1:5005`)
3. Tap **Save Settings**
4. Tap **Start Floating Button**

See [docs/USAGE.md](docs/USAGE.md).

---

## UDP message format

```json
{"type":"voice_command","text":"turn the master bedroom TV on bluray","timestamp":1749962400000}
```

`CommandFormatter.kt` is a stub that produces this envelope.  
The field `text` will contain exactly what Whisper transcribed — command parsing logic will be added once the message format is finalised.

---

## Minimum requirements

| Component | Requirement |
|-----------|-------------|
| Android device | Android 8.0+ (API 26), microphone |
| Whisper server | Ubuntu 20.04+ (or any Linux), 2 GB RAM, ffmpeg |
| Network | Local WiFi LAN — no internet required |
