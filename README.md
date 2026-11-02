# Transcriber

Free, offline Android app that transcribes WhatsApp voice messages (and any audio file) with OpenAI Whisper, running on-device through whisper.cpp. Much more accurate than small Vosk models, and it adds punctuation.

Usage: in WhatsApp, long-press a voice message → **Share** → **Transcriber**. The text can be copied or shared, and is saved in the history.

Models (downloaded once, then fully offline):
- **Precise** — Whisper small, ~180 MB (default)
- **Fast** — Whisper base, ~57 MB

Language: automatic detection, or pick one in the app.

APK: push to GitHub → **Actions** tab → latest run → artifact `Transcriber-debug` (arm64 phones; the first build takes ~10 minutes).
