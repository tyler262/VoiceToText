#!/usr/bin/env bash
# Install and enable the Whisper transcription server on Ubuntu.
# Run as root:  sudo bash install.sh
set -euo pipefail

INSTALL_DIR="/opt/whisper-server"
SERVICE_NAME="whisper-server"
SERVICE_USER="whisper"

echo "==> Installing system dependencies"
apt-get update -q
apt-get install -y python3 python3-pip python3-venv ffmpeg

echo "==> Creating service user '$SERVICE_USER'"
id "$SERVICE_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"

echo "==> Setting up $INSTALL_DIR"
mkdir -p "$INSTALL_DIR/.cache"
cp server.py "$INSTALL_DIR/server.py"
cp requirements.txt "$INSTALL_DIR/requirements.txt"
chown -R "$SERVICE_USER:$SERVICE_USER" "$INSTALL_DIR"

echo "==> Creating Python virtual environment"
python3 -m venv "$INSTALL_DIR/venv"
"$INSTALL_DIR/venv/bin/pip" install --upgrade pip -q
"$INSTALL_DIR/venv/bin/pip" install -r "$INSTALL_DIR/requirements.txt"

echo "==> Pre-downloading Whisper model (small.en, ~240 MB)"
# Run as the service user so the cache is written to the right place
sudo -u "$SERVICE_USER" HF_HOME="$INSTALL_DIR/.cache" \
    "$INSTALL_DIR/venv/bin/python" -c "
from faster_whisper import WhisperModel
print('Downloading model…')
WhisperModel('small.en', device='cpu', compute_type='int8')
print('Done.')
"

echo "==> Installing systemd service"
cp whisper-server.service "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload
systemctl enable "$SERVICE_NAME"
systemctl restart "$SERVICE_NAME"

echo ""
echo "==> Done! Service status:"
systemctl status "$SERVICE_NAME" --no-pager

echo ""
echo "Test with:"
echo "  curl http://localhost:8765/health"
echo "  curl -X POST http://localhost:8765/transcribe -F 'audio=@sample.wav'"
