#!/usr/bin/env python3
"""Explicit, single-engine Hearth TTS bridge. See docs/voices.md for isolated installs.
No model package is imported until its backend is selected. No reference voice is bundled.
"""
import argparse
import hmac
import io
import json
import os
from pathlib import Path
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request
import urllib.error
import wave

QWEN_LANGUAGES = dict(en="English", zh="Chinese", ja="Japanese", ko="Korean", de="German",
                      fr="French", ru="Russian", pt="Portuguese", es="Spanish", it="Italian")
MAX_AUDIO = 12 * 1024 * 1024


def validate_wav(raw):
    if not 44 <= len(raw) <= MAX_AUDIO:
        raise ValueError("Voice WAV is empty or exceeds 12 MiB")
    with wave.open(io.BytesIO(raw)) as wav:
        if (wav.getsampwidth() != 2 or wav.getnchannels() not in (1, 2)
                or not 8000 <= wav.getframerate() <= 48000
                or not 0 < wav.getnframes() <= wav.getframerate() * 120
                or len(wav.readframes(wav.getnframes())) != wav.getnframes() * wav.getnchannels() * 2):
            raise ValueError("Voice must return complete PCM16 mono/stereo WAV, 8–48 kHz, at most 120 seconds")
    return raw


def encode_wav(samples, rate):
    import numpy as np
    if hasattr(samples, "detach"):
        samples = samples.detach().cpu().numpy()
    data = np.asarray(samples).reshape(-1)
    if not len(data) or not np.isfinite(data).all() or len(data) > int(rate) * 120:
        raise ValueError("Model returned empty, non-finite or oversized audio")
    pcm = (np.clip(data, -1, 1) * 32767).astype("<i2").tobytes()
    out = io.BytesIO()
    with wave.open(out, "wb") as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(int(rate)); wav.writeframes(pcm)
    return validate_wav(out.getvalue())


class Qwen:
    def __init__(self, args):
        import torch
        from qwen_tts import Qwen3TTSModel
        self.model_id = "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
        self.label = "Qwen3-TTS 0.6B Base · reference voice"
        if not args.reference or not args.transcript:
            raise ValueError("Qwen Base requires --reference WAV and --transcript UTF-8 file")
        self.reference = str(Path(args.reference).resolve(strict=True))
        self.transcript = Path(args.transcript).read_text(encoding="utf-8").strip()
        if not self.transcript:
            raise ValueError("Qwen reference transcript is empty")
        self.engine = Qwen3TTSModel.from_pretrained(self.model_id,
            device_map=args.device, dtype=torch.bfloat16 if args.device.startswith("cuda") else torch.float32)
        self.languages = list(QWEN_LANGUAGES)
        self.voices = ["reference"]

    def speech(self, text, language, voice):
        wavs, rate = self.engine.generate_voice_clone(text=text, language=QWEN_LANGUAGES[language],
            ref_audio=self.reference, ref_text=self.transcript)
        return encode_wav(wavs[0], rate)


class Chatterbox:
    def __init__(self, args):
        from chatterbox.mtl_tts import ChatterboxMultilingualTTS
        self.engine = ChatterboxMultilingualTTS.from_pretrained(device=args.device, t3_model="v3")
        self.model_id = "chatterbox-multilingual-v3"
        self.label = "Chatterbox Multilingual V3"
        self.languages = list(self.engine.get_supported_languages())
        self.reference = str(Path(args.reference).resolve(strict=True)) if args.reference else None
        self.voices = ["reference" if self.reference else "default"]

    def speech(self, text, language, voice):
        return encode_wav(self.engine.generate(text, language_id=language, audio_prompt_path=self.reference), self.engine.sr)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Fish:
    """Proxy the official Fish /v1/tts server; never invent its language inventory."""
    def __init__(self, args):
        from urllib.parse import urlsplit
        endpoint = urlsplit(args.fish_url)
        if (endpoint.scheme not in ("http", "https") or not endpoint.hostname or endpoint.username
                or endpoint.password or endpoint.query or endpoint.fragment):
            raise ValueError("Fish URL must be http(s), without credentials/query/fragment")
        if not args.fish_languages or not args.fish_model:
            raise ValueError("Set --fish-model and --fish-languages for the actual deployed checkpoint")
        self.url = args.fish_url.rstrip("/") + "/v1/tts"
        self.languages = list(dict.fromkeys(args.fish_languages.split(",")))
        self.model_id = args.fish_model
        self.label = "Fish Speech · " + self.model_id
        self.reference = args.fish_reference
        self.voices = [self.reference or "default"]
        self.key = os.environ.get("FISH_API_KEY", "")
        self.opener = urllib.request.build_opener(NoRedirect)

    def speech(self, text, language, voice):
        # Official Fish infers language from the text; no fake language parameter.
        body = dict(text=text, format="wav", streaming=False, normalize=True)
        if self.reference:
            body["reference_id"] = self.reference
        headers = {"Content-Type": "application/json"}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers)
        try:
            with self.opener.open(request, timeout=120) as response:
                raw = response.read(MAX_AUDIO + 1)
        except urllib.error.HTTPError as error:
            detail = error.read(65536).decode("utf-8", errors="replace")
            if self.key:
                detail = detail.replace(self.key, "<redacted>")
            raise RuntimeError(f"Fish HTTP {error.code}: {detail}") from None
        return validate_wav(raw)


def capabilities(backend):
    result = dict(model=backend.model_id, label=backend.label, languages=list(backend.languages), voices=list(backend.voices))
    for field in ("model", "label"):
        if not isinstance(result[field], str) or not 1 <= len(result[field]) <= 150 or any(ord(c) < 32 for c in result[field]):
            raise ValueError("Invalid backend identity")
    if not 1 <= len(result["languages"]) <= 200 or not all(re.fullmatch(r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*", x) for x in result["languages"]):
        raise ValueError("Invalid backend languages")
    if not 1 <= len(result["voices"]) <= 100 or not all(isinstance(x, str) and 1 <= len(x) <= 120 and all(ord(c) >= 32 for c in x) for x in result["voices"]):
        raise ValueError("Invalid backend voices")
    return result


def handler(backend, token):
    caps = capabilities(backend)
    lock = threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        # No source text, tokens or reference paths in request/access logs.
        def log_message(self, *args):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def send(self, status, raw, mime="application/json"):
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Client cancelled; discard result, never queue it for replay.

        def error(self, status, message):
            self.send(status, json.dumps({"error": str(message).replace(token, "<redacted>") if token else str(message)}, ensure_ascii=False).encode())

        def authorized(self):
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + token).encode()):
                self.error(401, "Voice API authentication required")
                return False
            return True

        def do_GET(self):
            if not self.authorized(): return
            if self.path != "/capabilities": return self.error(404, "Unknown voice endpoint")
            self.send(200, json.dumps(caps).encode())

        def do_POST(self):
            if not self.authorized(): return
            if self.path != "/speech": return self.error(404, "Unknown voice endpoint")
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 65536 or self.headers.get_content_type() != "application/json":
                    raise ValueError("Expected a JSON body of at most 64 KiB")
                raw = self.rfile.read(size)
                if len(raw) != size: raise ValueError("Incomplete request body")
                request = json.loads(raw)
                if not isinstance(request, dict): raise ValueError("Expected a JSON object")
                text = request.get("text")
                if not isinstance(text, str) or not text.strip() or len(text) > 300:
                    raise ValueError("Voice requests accept 1–300 characters; split longer text in the client")
                if (request.get("model") != caps["model"] or request.get("language") not in caps["languages"]
                        or request.get("voice") not in caps["voices"]):
                    raise ValueError("Model, language or voice is not advertised by this server")
            except (ValueError, TypeError) as error:
                return self.error(400, error)
            if not lock.acquire(blocking=False): return self.error(429, "Voice engine is busy; retry after current speech finishes")
            try:
                audio = validate_wav(backend.speech(text, request["language"], request["voice"]))
                self.send(200, audio, "audio/wav")
            except Exception as error:
                self.error(502, f"{type(error).__name__}: {error}")
            finally:
                lock.release()
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", choices=["qwen", "chatterbox", "fish"])
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", default=8787, type=int)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--reference"); parser.add_argument("--transcript")
    parser.add_argument("--fish-url", default="http://127.0.0.1:8080")
    parser.add_argument("--fish-model"); parser.add_argument("--fish-languages"); parser.add_argument("--fish-reference")
    args = parser.parse_args()
    token = os.environ.get("HEARTH_VOICE_API_KEY", "")
    if len(token) < 24 or not token.isascii() or any(c.isspace() for c in token):
        parser.error("Set HEARTH_VOICE_API_KEY to at least 24 non-whitespace ASCII characters")
    backend = {"qwen": Qwen, "chatterbox": Chatterbox, "fish": Fish}[args.backend](args)
    server = ThreadingHTTPServer((args.bind, args.port), handler(backend, token))
    server.daemon_threads = True
    print(f"Hearth voice bridge: {backend.model_id} on {args.bind}:{args.port}; put HTTPS in front for Android", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == "__main__": main()
