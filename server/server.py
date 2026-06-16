#!/usr/bin/env python3
"""
Whisper transcription server for the aircraft voice-command system.
Accepts a WAV audio upload and returns the transcribed text as JSON.

POST /transcribe  multipart/form-data  field: audio (WAV file)
  -> {"text": "turn the master bedroom TV on bluray"}

POST /transcribe  raw bytes  Content-Type: audio/wav
  -> {"text": "..."}
"""

import os
import tempfile
from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from faster_whisper import WhisperModel
import uvicorn

# ── Configuration (override via environment variables) ────────────────────────
MODEL_SIZE   = os.getenv("WHISPER_MODEL",   "small.en")   # tiny.en | base.en | small.en | medium.en
DEVICE       = os.getenv("WHISPER_DEVICE",  "cpu")        # cpu | cuda
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE", "int8")       # int8 (CPU) | float16 (GPU)
HOST         = os.getenv("WHISPER_HOST",    "0.0.0.0")
PORT         = int(os.getenv("WHISPER_PORT", "8765"))

# ── Load model at startup (downloaded once, then cached) ──────────────────────
print(f"Loading Whisper model '{MODEL_SIZE}' on {DEVICE} ({COMPUTE_TYPE})…")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("Model ready.")

app = FastAPI(title="Whisper Voice Server")


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_SIZE}


@app.post("/transcribe")
async def transcribe(request: Request, audio: UploadFile = File(default=None)):
    """
    Accepts either:
      - multipart/form-data with a field named 'audio'
      - raw audio/wav body
    Returns {"text": "..."} or {"error": "..."}
    """
    suffix = ".wav"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp_path = tmp.name
        if audio is not None:
            tmp.write(await audio.read())
        else:
            # Raw body upload
            body = await request.body()
            if not body:
                raise HTTPException(status_code=400, detail="No audio data received")
            tmp.write(body)

    try:
        segments, info = model.transcribe(
            tmp_path,
            beam_size=5,
            language="en",
            vad_filter=True,          # skip silent sections
            vad_parameters={"min_silence_duration_ms": 300},
        )
        text = " ".join(seg.text for seg in segments).strip()
        return {"text": text, "duration": round(info.duration, 2)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
