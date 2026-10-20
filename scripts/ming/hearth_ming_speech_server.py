#!/usr/bin/env python3
"""Expose Ming Audio's official SGLang ASR chat path as /v1/audio/transcriptions.

ASR Tiny already has that endpoint in SGLang-Omni; it does not need this proxy.
No weights, remote code, tools, or general audio-chat tasks execute in this bridge.
"""
import argparse
import base64
from email import policy
from email.parser import BytesParser
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
import threading
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import wave

MODELS = ("inclusionAI/Ming-3.0-audio-tiny", "inclusionAI/Ming-3.0-audio-flash")
ASR_PROMPT = "Please recognize the language of this speech and transcribe it, placing a `\\t` between the identified language and the transcribe."
MAX_BODY = 6 * 1024 * 1024


def parse_upload(content_type, body, model):
    if len(body) > MAX_BODY or not content_type.lower().startswith("multipart/form-data;"):
        raise ValueError("Expected multipart/form-data of at most 6 MiB")
    message = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + content_type.encode("ascii") + b"\r\nMIME-Version: 1.0\r\n\r\n" + body)
    if not message.is_multipart() or message.defects:
        raise ValueError("Invalid multipart upload")
    fields = {}
    allowed = {"file", "model", "language", "prompt", "response_format", "stream"}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name not in allowed or name in fields or part.is_multipart() or part.defects:
            raise ValueError("Unknown, repeated or malformed multipart field")
        fields[name] = part.get_payload(decode=True)
    audio = fields.pop("file", b"")
    values = {k: v.decode("utf-8", errors="strict") for k, v in fields.items()}
    if values.get("model") != model or values.get("response_format", "json") != "json":
        raise ValueError("Select this server's exact model and response_format=json")
    if values.get("stream", "false").lower() != "false":
        raise ValueError("This bridge returns completed utterances; streaming partials are unsupported")
    language = values.get("language") or None
    if language not in (None, "en", "zh"):
        raise ValueError("Ming Audio's verified languages are en and zh")
    context = values.get("prompt", "")
    if len(context) > 1000 or any(ord(c) < 32 for c in context):
        raise ValueError("Context must be a single line of at most 1000 characters")
    with wave.open(io.BytesIO(audio)) as wav:
        if (wav.getsampwidth() != 2 or wav.getnchannels() not in (1, 2)
                or not 8000 <= wav.getframerate() <= 48000
                or not 0 < wav.getnframes() <= wav.getframerate() * 30
                or len(wav.readframes(wav.getnframes())) != wav.getnframes() * wav.getnchannels() * 2):
            raise ValueError("Upload a complete PCM16 WAV of at most 30 seconds, mono/stereo, 8–48 kHz")
    return audio, language, context


def transcript(content):
    if not isinstance(content, str) or len(content) > 65536:
        raise ValueError("Ming ASR response must be a bounded text string")
    # The official task returns a language label, then a tab. Do not feed that label
    # into Hearth's independent translation stage or silently accept reasoning/chat.
    label, separator, text = content.strip().replace("\\t", "\t", 1).partition("\t")
    if not separator or label.strip().lower() not in ("english", "chinese", "en", "zh", "中文", "英文", "英语", "汉语"):
        raise ValueError("Ming did not return its documented language-tab-transcript format")
    return text.strip()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class MingAudio:
    def __init__(self, model, endpoint, key=""):
        url = urlsplit(endpoint)
        if (model not in MODELS or url.scheme not in ("http", "https") or not url.hostname
                or url.username or url.password or url.query or url.fragment):
            raise ValueError("Select a Ming Audio checkpoint and an http(s) SGLang /v1 URL without credentials/query")
        self.model = model
        self.url = endpoint.rstrip("/") + "/chat/completions"
        self.key = key
        self.opener = urllib.request.build_opener(NoRedirect)

    def transcribe(self, audio, language, context):
        prompt = ASR_PROMPT
        if language:
            prompt += " The spoken language is " + {"en": "English", "zh": "Chinese"}[language] + "."
        if context:
            prompt += " Context/hotwords: " + context
        body = dict(model=self.model, messages=[dict(role="user", content=[
            dict(type="input_audio", input_audio=dict(data=base64.b64encode(audio).decode("ascii"), format="wav")),
            dict(type="text", text=prompt)])], temperature=0.0, max_tokens=512, stream=False)
        headers = {"Content-Type": "application/json"}
        if self.key:
            headers["Authorization"] = "Bearer " + self.key
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers)
        try:
            with self.opener.open(request, timeout=120) as response:
                raw = response.read(1024 * 1024 + 1)
            if len(raw) > 1024 * 1024:
                raise ValueError("Ming upstream response exceeds 1 MiB")
            result = json.loads(raw)
            choice = result["choices"][0]
            if choice.get("finish_reason") != "stop":
                raise ValueError("Ming upstream did not finish transcription (token limit, tools or protocol error)")
            if choice["message"].get("tool_calls"):
                raise ValueError("Ming returned tools instead of transcription")
            return transcript(choice["message"]["content"])
        except urllib.error.HTTPError as error:
            detail = error.read(65536).decode("utf-8", errors="replace")
            if self.key:
                detail = detail.replace(self.key, "<redacted>")
            raise RuntimeError(f"Ming upstream HTTP {error.code}: {detail}") from None


def handler(backend, token):
    lock = threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def send(self, status, value):
            raw = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def error(self, status, message):
            text = str(message).replace(token, "<redacted>")
            if backend.key:
                text = text.replace(backend.key, "<redacted>")
            self.send(status, dict(error=text))

        def authorized(self):
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode(), ("Bearer " + token).encode()):
                self.error(401, "Ming bridge authentication required")
                return False
            return True

        def do_GET(self):
            if not self.authorized(): return
            if self.path != "/v1/models": return self.error(404, "Unknown endpoint")
            self.send(200, dict(object="list", data=[dict(id=backend.model, object="model",
                architecture=dict(output_modalities=["transcription"]))]))

        def do_POST(self):
            if not self.authorized(): return
            if self.path != "/v1/audio/transcriptions": return self.error(404, "Only transcription is supported; use Hearth's separate translation stage")
            if not lock.acquire(blocking=False): return self.error(429, "Ming speech engine is busy")
            try:
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= MAX_BODY or self.headers.get("Transfer-Encoding"):
                        raise ValueError("Expected a fixed-length upload of at most 6 MiB")
                    raw = self.rfile.read(size)
                    if len(raw) != size: raise ValueError("Incomplete upload")
                    audio, language, context = parse_upload(self.headers.get("Content-Type", ""), raw, backend.model)
                except (ValueError, UnicodeError, wave.Error, EOFError) as error:
                    return self.error(400, error)
                try:
                    self.send(200, dict(text=backend.transcribe(audio, language, context)))
                except Exception as error:
                    self.error(502, f"{type(error).__name__}: {error}")
            finally:
                lock.release()
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, choices=MODELS)
    parser.add_argument("--upstream", default="http://127.0.0.1:8008/v1")
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8788)
    args = parser.parse_args()
    token = os.environ.get("HEARTH_SPEECH_API_KEY", "")
    if len(token) < 24 or not token.isascii() or any(c.isspace() for c in token):
        parser.error("Set HEARTH_SPEECH_API_KEY to at least 24 non-whitespace ASCII characters")
    backend = MingAudio(args.model, args.upstream, os.environ.get("MING_SGLANG_API_KEY", ""))
    server = ThreadingHTTPServer((args.bind, args.port), handler(backend, token))
    server.daemon_threads = True
    print(f"Hearth Ming ASR bridge on {args.bind}:{args.port}; put HTTPS in front for Android", flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == "__main__": main()
