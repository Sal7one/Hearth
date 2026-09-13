import io
import json
import threading
import unittest
import urllib.error
import urllib.request
import wave
from types import SimpleNamespace
from unittest.mock import patch
from http.server import ThreadingHTTPServer
import hearth_voice_server as server

TOKEN = "a-test-credential-not-a-real-secret"

def wav():
    out = io.BytesIO()
    with wave.open(out, "wb") as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(24000); f.writeframes(b"\0\0" * 200)
    return out.getvalue()

class Fake:
    model_id="test-engine"; label="Test"; languages=["en", "ar"]; voices=["default"]
    def __init__(self): self.calls=[]; self.failure=None; self.wait=None
    def speech(self, text, language, voice):
        self.calls.append((text, language, voice))
        if self.wait: self.wait.wait(5)
        if self.failure: raise self.failure
        return wav()

class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.engine=Fake(); self.http=ThreadingHTTPServer(("127.0.0.1",0),server.handler(self.engine,TOKEN))
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True); self.thread.start()
        self.base=f"http://127.0.0.1:{self.http.server_port}"
    def tearDown(self):
        self.http.shutdown(); self.http.server_close(); self.thread.join()
    def request(self, data=None, token=TOKEN, path=None):
        headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"}
        req=urllib.request.Request(self.base+(path or ("/speech" if data is not None else "/capabilities")),
            json.dumps(data).encode() if data is not None else None,headers)
        try:
            with urllib.request.urlopen(req,timeout=6) as result: return result.status,result.read()
        except urllib.error.HTTPError as result: return result.code,result.read()
    def payload(self, **changes):
        return dict(text="مرحبا",language="ar",voice="default",model="test-engine",**changes)
    def test_capabilities_auth_and_wav(self):
        self.assertEqual(self.request(token="wrong")[0],401)
        status,raw=self.request(); self.assertEqual(status,200); self.assertEqual(json.loads(raw)["languages"],["en","ar"])
        status,raw=self.request(self.payload()); self.assertEqual(status,200); self.assertEqual(raw,wav())
        self.assertEqual(self.engine.calls,[("مرحبا","ar","default")])
    def test_rejects_bad_languages_models_voices_and_bounds_before_inference(self):
        for key,value in [("model","wrong"),("language","zh"),("voice","missing"),("text","x"*301),("text",None)]:
            data=self.payload(); data[key]=value
            self.assertEqual(self.request(data)[0],400)
        self.assertFalse(self.engine.calls)
        self.assertEqual(self.request(path="/other")[0],404)
    def test_failure_is_an_error_and_secret_is_redacted(self):
        self.engine.failure=RuntimeError("native failed "+TOKEN)
        status,raw=self.request(self.payload()); self.assertEqual(status,502)
        self.assertIn(b"native failed",raw); self.assertNotIn(TOKEN.encode(),raw)
    def test_busy_is_bounded_and_next_request_can_recover(self):
        event=threading.Event(); self.engine.wait=event
        first=threading.Thread(target=lambda:self.request(self.payload()),daemon=True);first.start()
        import time
        for _ in range(100):
            if self.engine.calls: break
            time.sleep(.01)
        self.assertEqual(self.request(self.payload())[0],429)
        event.set();first.join();self.engine.wait=None
        self.assertEqual(self.request(self.payload())[0],200)

class AdapterTest(unittest.TestCase):
    def test_qwen_passes_explicit_language_and_user_reference(self):
        engine=server.Qwen.__new__(server.Qwen); engine.reference="/user/voice.wav";engine.transcript="user transcript"
        captured={}
        def generate(**kwargs): captured.update(kwargs);return [[0.1]],24000
        engine.engine=SimpleNamespace(generate_voice_clone=generate)
        with patch.object(server,"encode_wav",return_value=wav()): engine.speech("hello","en","reference")
        self.assertEqual(captured,dict(text="hello",language="English",ref_audio="/user/voice.wav",ref_text="user transcript"))
        self.assertNotIn("ar",server.QWEN_LANGUAGES)
    def test_chatterbox_passes_language_and_uses_its_sample_rate(self):
        engine=server.Chatterbox.__new__(server.Chatterbox);engine.reference=None;captured={}
        def generate(text,**kwargs): captured.update(kwargs);return [0.1]
        engine.engine=SimpleNamespace(generate=generate,sr=24000)
        with patch.object(server,"encode_wav",return_value=wav()) as encode: engine.speech("مرحبا","ar","default")
        self.assertEqual(captured,dict(language_id="ar",audio_prompt_path=None));encode.assert_called_once_with([0.1],24000)
    def test_fish_uses_official_endpoint_without_fake_language_field(self):
        args=SimpleNamespace(fish_url="http://127.0.0.1:8080",fish_model="s2-pro",fish_languages="en,ar",fish_reference="my-voice")
        engine=server.Fish(args);captured={}
        class Response(io.BytesIO): pass
        def open_request(request,timeout): captured.update(url=request.full_url,body=json.loads(request.data)); return Response(wav())
        engine.opener=SimpleNamespace(open=open_request)
        self.assertEqual(engine.speech("مرحبا","ar","my-voice"),wav())
        self.assertEqual(captured["url"],"http://127.0.0.1:8080/v1/tts")
        self.assertEqual(captured["body"],dict(text="مرحبا",format="wav",streaming=False,normalize=True,reference_id="my-voice"))
    def test_audio_and_capabilities_reject_invalid_data(self):
        with self.assertRaises(ValueError):server.validate_wav(b"not audio")
        engine=Fake();engine.languages=["all languages"]
        with self.assertRaises(ValueError):server.capabilities(engine)

if __name__=="__main__":unittest.main()
