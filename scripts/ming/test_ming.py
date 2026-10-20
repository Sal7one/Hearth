import base64
import hashlib
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from types import SimpleNamespace

import hearth_ming_speech_server as bridge
import ming_catalog

TOKEN = "test-only-hearth-speech-credential"


def wav():
    import wave
    output=io.BytesIO()
    with wave.open(output,"wb") as audio:
        audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000);audio.writeframes(b"\0\0"*100)
    return output.getvalue()


def upload(model=bridge.MODELS[0], **extra):
    boundary="hearth-test-boundary"
    body=bytearray()
    fields=dict(model=model,response_format="json",**extra)
    for name,value in [*fields.items(),("file",wav())]:
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        body.extend(value if isinstance(value,bytes) else value.encode());body.extend(b"\r\n")
    body.extend(f'--{boundary}--\r\n'.encode())
    return f"multipart/form-data; boundary={boundary}",bytes(body)


class CatalogTest(unittest.TestCase):
    def test_all_five_have_full_components_and_separate_active_parameters(self):
        models=ming_catalog.load_catalog()["models"]
        dense=next(m for m in models if m["id"].endswith("tts-0.5B"))
        self.assertEqual(dense["announcedBackboneParameters"],500_000_000)
        self.assertGreater(dense["storedTopLevelSafetensorsParameters"],1_400_000_000)
        self.assertGreater(dense["profiles"]["speech"]["bytes"],3_000_000_000)
        for model in models:
            self.assertFalse(model["androidLocal"])
            self.assertNotIn("ar",model["languages"])
        audio=next(m for m in models if m["id"].endswith("audio-tiny"))
        self.assertGreater(audio["profiles"]["withTalker"]["bytes"],audio["profiles"]["speech"]["bytes"])

    def test_verifier_rejects_corruption_and_wrong_size(self):
        catalog=ming_catalog.load_catalog()
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/"weight.bin").write_bytes(b"valid")
            model=catalog["models"][0]
            model["files"]=[dict(path="weight.bin",bytes=5,sha256=hashlib.sha256(b"valid").hexdigest(),
                url=f'https://huggingface.co/{model["id"]}/resolve/{model["revision"]}/weight.bin')]
            model["profiles"]={"speech":dict(files=["weight.bin"],bytes=5)}
            path=root/"catalog.json";path.write_text(json.dumps(catalog))
            ming_catalog.verify_bundle(model["id"],root,catalog_path=path)
            (root/"weight.bin").write_bytes(b"WRONG")
            with self.assertRaisesRegex(ValueError,"integrity mismatch"):
                ming_catalog.verify_bundle(model["id"],root,catalog_path=path)
            (root/"weight.bin").write_bytes(b"bad")
            with self.assertRaisesRegex(ValueError,"wrong-size"):
                ming_catalog.verify_bundle(model["id"],root,catalog_path=path)


class MingProxyTest(unittest.TestCase):
    def test_official_sglang_asr_message_and_finished_transcript(self):
        engine=bridge.MingAudio(bridge.MODELS[0],"http://127.0.0.1:8008/v1","upstream-key")
        captured={}
        def open_request(request,timeout):
            captured.update(body=json.loads(request.data),url=request.full_url,auth=request.get_header("Authorization"))
            return io.BytesIO(json.dumps(dict(choices=[dict(finish_reason="stop",message=dict(content="English\thello"))])).encode())
        engine.opener=SimpleNamespace(open=open_request)
        self.assertEqual(engine.transcribe(wav(),"en","Hearth"),"hello")
        self.assertEqual(captured["url"],"http://127.0.0.1:8008/v1/chat/completions")
        self.assertEqual(captured["auth"],"Bearer upstream-key")
        content=captured["body"]["messages"][0]["content"]
        self.assertEqual(base64.b64decode(content[0]["input_audio"]["data"]),wav())
        self.assertIn(bridge.ASR_PROMPT,content[1]["text"])
        self.assertNotIn("tools",captured["body"])

    def test_language_prefix_and_malformed_responses_do_not_leak_into_captions(self):
        self.assertEqual(bridge.transcript("Chinese\\t你好"),"你好")
        for response in ("an audio-chat answer", "<think>reasoning</think>\ttext",None):
            with self.assertRaises(ValueError):bridge.transcript(response)
        engine=bridge.MingAudio(bridge.MODELS[0],"http://localhost/v1")
        engine.opener=SimpleNamespace(open=lambda *a,**kw:io.BytesIO(b'{"choices":[{"finish_reason":"length","message":{"content":"English\\thello"}}]}'))
        with self.assertRaisesRegex(ValueError,"did not finish"):engine.transcribe(wav(),None,"")

    def test_upload_rejects_unsupported_language_or_streaming_before_inference(self):
        mime,body=upload(language="zh",prompt="蚂蚁集团")
        self.assertEqual(bridge.parse_upload(mime,body,bridge.MODELS[0]),(wav(),"zh","蚂蚁集团"))
        for fields in (dict(language="ar"),dict(stream="true"),dict(prompt="context\nnew line")):
            mime,body=upload(**fields)
            with self.assertRaises(ValueError):bridge.parse_upload(mime,body,bridge.MODELS[0])
        mime,body=upload(model="wrong")
        with self.assertRaises(ValueError):bridge.parse_upload(mime,body,bridge.MODELS[0])

    def test_http_auth_endpoint_and_error_contract(self):
        calls=[]
        backend=SimpleNamespace(model=bridge.MODELS[0],key="upstream-private-key",
            transcribe=lambda *args:calls.append(args) or "Recognized")
        http=ThreadingHTTPServer(("127.0.0.1",0),bridge.handler(backend,TOKEN))
        thread=threading.Thread(target=http.serve_forever,daemon=True);thread.start()
        def request(path,body=None,mime="application/json",token=TOKEN):
            req=urllib.request.Request(f"http://127.0.0.1:{http.server_port}"+path,body,
                {"Authorization":"Bearer "+token,"Content-Type":mime})
            try:
                with urllib.request.urlopen(req,timeout=5) as response:return response.status,json.load(response)
            except urllib.error.HTTPError as response:return response.code,json.load(response)
        try:
            self.assertEqual(request("/v1/models",token="wrong")[0],401)
            self.assertEqual(request("/v1/models")[1]["data"][0]["architecture"]["output_modalities"],["transcription"])
            mime,body=upload(language="en")
            self.assertEqual(request("/v1/audio/transcriptions",body,mime),(200,dict(text="Recognized")))
            self.assertEqual(len(calls),1)
            self.assertEqual(request("/v1/audio/translations",body,mime)[0],404)
            def fail(*args):raise RuntimeError("engine failed "+TOKEN+" upstream-private-key")
            backend.transcribe=fail
            status,result=request("/v1/audio/transcriptions",body,mime)
            self.assertEqual(status,502);self.assertIn("engine failed",result["error"])
            self.assertNotIn(TOKEN,result["error"]);self.assertNotIn(backend.key,result["error"])
        finally:
            http.shutdown();http.server_close();thread.join()


if __name__=="__main__":unittest.main()
