# Server Setup — Whisper Transcription Service

The speech-to-text backend is a lightweight Python HTTP server running OpenAI's Whisper model via `faster-whisper`.  
It listens on port 8765, accepts a WAV audio upload, and returns the transcribed text as JSON.

---

## One-shot install

Run on the Ubuntu server that will be on the aircraft LAN:

```bash
cd server/
sudo bash install.sh
```

This script:
1. Installs `python3`, `ffmpeg`, `pip`, and `venv`
2. Creates a locked-down `whisper` system user
3. Sets up `/opt/whisper-server/` with a Python virtual environment
4. Downloads the `small.en` Whisper model (~240 MB, cached permanently)
5. Installs and enables the systemd service so it starts on every boot

### Verify it's running

```bash
systemctl status whisper-server
curl http://localhost:8765/health
# → {"status":"ok","model":"small.en"}
```

---

## API reference

### `POST /transcribe`

Accepts `multipart/form-data` with a field named `audio` (WAV file).

```bash
curl -X POST http://<server-ip>:8765/transcribe \
     -F "audio=@recording.wav"
# → {"text":"turn the master bedroom TV on bluray","duration":2.3}
```

Also accepts a raw WAV body:

```bash
curl -X POST http://<server-ip>:8765/transcribe \
     -H "Content-Type: audio/wav" \
     --data-binary @recording.wav
```

### `GET /health`

```bash
curl http://<server-ip>:8765/health
# → {"status":"ok","model":"small.en"}
```

---

## Model selection

Edit `/etc/systemd/system/whisper-server.service` and change `WHISPER_MODEL`:

| Model | Size | Speed (CPU) | Accuracy |
|-------|------|-------------|----------|
| `tiny.en` | 75 MB | ~50 ms | Good for simple commands |
| `base.en` | 145 MB | ~80 ms | Better |
| `small.en` | 240 MB | ~200 ms | **Default — recommended** |
| `medium.en` | 770 MB | ~500 ms | Excellent, needs ~4 GB RAM |

After editing:
```bash
sudo systemctl daemon-reload
sudo systemctl restart whisper-server
```

---

## GPU acceleration (optional)

If the server has an NVIDIA GPU:

```bash
# Install CUDA toolkit
sudo apt-get install -y cuda-toolkit-12-x

# Edit the service file
sudo nano /etc/systemd/system/whisper-server.service
# Change:
#   WHISPER_DEVICE=cpu   → WHISPER_DEVICE=cuda
#   WHISPER_COMPUTE=int8 → WHISPER_COMPUTE=float16

sudo systemctl daemon-reload && sudo systemctl restart whisper-server
```

GPU reduces transcription to under 50 ms for any model size.

---

## Logs

```bash
journalctl -u whisper-server -f          # follow live
journalctl -u whisper-server --since today
```

---

## Firewall

If `ufw` is active, open the port for the aircraft LAN subnet:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 8765 proto tcp
```

---

## Starting / stopping manually

```bash
sudo systemctl start   whisper-server
sudo systemctl stop    whisper-server
sudo systemctl restart whisper-server
sudo systemctl disable whisper-server   # prevent auto-start
sudo systemctl enable  whisper-server   # re-enable auto-start
```
