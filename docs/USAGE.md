# Usage Guide

---

## First-time setup

### 1. Grant permissions

Open the app — the **Permissions** card shows what's needed:

| Permission | Why |
|------------|-----|
| **Overlay** (Draw over other apps) | Shows the floating button on top of everything else |
| **Microphone** | Records your voice |
| **Location** (Fine) | Required by Android 10+ to read the WiFi network name (SSID) |
| **Notifications** | Shows the persistent "Voice Command Active" notification |

Tap **Grant Permissions**. The overlay permission opens a system settings screen — toggle it on and press Back. The others use the standard Android permission dialog.

### 2. Configure settings

| Setting | Description | Example |
|---------|-------------|---------|
| **Whisper server URL** | LAN address of the Ubuntu speech server | `http://192.168.1.50:8765` |
| **Target WiFi SSID** | Network name that triggers the floating button. Leave blank to always show it. | `AircraftNet` |
| **Multicast IP** | Destination multicast group | `239.255.0.1` |
| **UDP Port** | Port your AV/lighting systems listen on | `5005` |
| **Auto-start** | Automatically show the button when connected to the target network | on/off |

Tap **Save Settings** after any change.

### 3. Start the floating button

Tap **Start Floating Button**. A blue circular mic button appears on screen.

---

## Daily use

### Giving a command

1. **Tap the mic button once** — it turns red, "Recording… tap to send" appears
2. **Speak your command** — "dim the lights in the lounge to 40 percent"
3. **Tap the mic button again** — "Transcribing…" appears while Whisper processes the audio
4. The transcribed text appears briefly under the button and the UDP packet is sent

The button auto-stops recording after 45 seconds if you forget to tap again.

### Moving the button

**Drag** the button to any position on screen — it stays there until moved again.

### Stopping the service

Either tap **Stop** in the notification shade, or open the app and tap **Stop Floating Button**.

---

## Behaviour by scenario

| Scenario | What happens |
|----------|--------------|
| Connected to target WiFi + Auto-start on | Button appears automatically |
| Disconnect from target WiFi | Button hides automatically |
| Launched from another app | Button appears regardless of WiFi (force-show mode) |
| Device reboots + Auto-start on | Service restarts automatically |
| Whisper server unreachable | "Error: …" shown under button for 5 seconds, no UDP sent |
| No speech detected | "Error: No speech detected" shown |

---

## Launching from another app

Any app on the same device can show the floating button:

```kotlin
// Kotlin (Android)
val intent = Intent("com.voicetotext.aircraft.ACTION_SHOW_BUTTON")
    .setPackage("com.voicetotext.aircraft")
context.startForegroundService(intent)
```

```java
// Java
Intent intent = new Intent("com.voicetotext.aircraft.ACTION_SHOW_BUTTON");
intent.setPackage("com.voicetotext.aircraft");
context.startForegroundService(intent);
```

---

## UDP packet format

Every recognised command produces one UDP multicast packet:

```json
{
  "type": "voice_command",
  "text": "turn the master bedroom TV on bluray",
  "timestamp": 1749962400000
}
```

- `type` — always `"voice_command"` (reserved for future command types)
- `text` — raw Whisper transcription, exactly as spoken
- `timestamp` — Unix milliseconds (device clock)

The packet is broadcast to the configured multicast group (e.g. `239.255.0.1:5005`). Every device that has joined that multicast group will receive it.

Command parsing (mapping natural language to specific AV/lighting instructions) will be implemented in `CommandFormatter.kt` once the message format is finalised.

---

## Listening for packets (test)

Quick test on any Linux machine on the same network:

```python
import socket, struct

GROUP  = "239.255.0.1"
PORT   = 5005

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("", PORT))
mreq = struct.pack("4sL", socket.inet_aton(GROUP), socket.INADDR_ANY)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)

print(f"Listening on {GROUP}:{PORT} …")
while True:
    data, addr = sock.recvfrom(4096)
    print(f"{addr[0]}: {data.decode()}")
```

```bash
python3 listen_test.py
```

Say a command on the tablet — you should see the JSON packet appear immediately.
