#!/usr/bin/env python3
"""Compare the real native Perth adapter against the original Torch CNN modules.

Uses actual source Encoder/Decoder, torch STFT/ISTFT matching upstream's torchaudio
configuration. Same-rate32k isolates the algorithm; it does not establish parity
with librosa's24k resampler or measure robustness on real speech.
"""
import argparse
import importlib
import json
import math
import subprocess
import sys
import types
from pathlib import Path

import numpy as np
import torch


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native",type=Path,required=True)
    parser.add_argument("--binary",type=Path,required=True)
    parser.add_argument("--perth",type=Path,required=True)
    parser.add_argument("--work",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args();args.work.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    package=args.perth/"src/perth/perth_net/perth_net_implicit"
    namespace=types.ModuleType("perth_probe");namespace.__path__=[str(package)]
    sys.modules["perth_probe"]=namespace
    # The architecture's config import is a type annotation; no guessed model class.
    config=types.ModuleType("perth_probe.config");config.PerthConfig=type("PerthConfig",(),{})
    sys.modules["perth_probe.config"]=config
    Encoder=importlib.import_module("perth_probe.model.encoder").Encoder
    Decoder=importlib.import_module("perth_probe.model.decoder").Decoder
    source=args.perth/"src/perth/perth_net/pretrained/implicit/perth_net_250000.pth.tar"
    state=torch.load(source,map_location="cpu",weights_only=True)["model"]
    encoder=Encoder(256,128).eval();decoder=Decoder(256,128).eval()
    encoder.load_state_dict({k.removeprefix("encoder."):v for k,v in state.items() if k.startswith("encoder.")})
    decoder.load_state_dict({k.removeprefix("decoder."):v for k,v in state.items() if k.startswith("decoder.")})
    window=torch.hann_window(2048)

    def analyze(signal):
        spectrum=torch.stft(signal,n_fft=2048,hop_length=320,win_length=2048,window=window,center=True,pad_mode="reflect",return_complex=True)
        magnitude=(20*torch.log10(spectrum.abs().clamp_min(1e-9))+180)/195
        return magnitude,spectrum.angle()

    def detect(signal):
        value=float(decoder(analyze(signal)[0]).clamp(0,1).item())
        return value if math.isfinite(value) else None

    rng=np.random.default_rng(42);t=np.arange(10240,dtype=np.float64)/32000
    fixtures={"harmonic_chirp":(.15*np.sin(2*np.pi*(180*t+700*t*t))+.06*np.sin(2*np.pi*413*t)+rng.normal(0,.005,len(t))).astype("<f4"),
              "silence":np.zeros(10240,dtype="<f4")}
    cases=[]
    with torch.inference_mode():
        for name,pcm in fixtures.items():
            input_path=args.work/f"{name}.f32";input_path.write_bytes(pcm.tobytes())
            output_path=args.work/f"{name}-native.f32";receipt=args.work/f"{name}-native.json"
            subprocess.run([str(args.native.resolve()),str(args.binary.resolve()),str(input_path.resolve()),"32000",str(output_path.resolve()),str(receipt.resolve())],check=True)
            signal=torch.from_numpy(pcm.copy())[None]
            magnitude,phase=analyze(signal);watermarked,_=encoder(magnitude)
            complex_spec=10**(((watermarked*195-180)/20).clamp(max=10))*torch.exp(1j*phase)
            reference=torch.istft(complex_spec,n_fft=2048,hop_length=320,win_length=2048,window=window,center=True)[0].numpy()
            native=np.fromfile(output_path,dtype="<f4")
            if native.shape != reference.shape: raise RuntimeError(f"Perth output shape mismatch: {name}")
            error=native-reference
            row={"fixture":name,"sampleRate":32000,"samples":len(native),"maxAbsoluteError":float(np.max(np.abs(error))),"rmse":float(np.sqrt(np.mean(error**2))),
                 "referenceBefore":detect(signal),"referenceAfter":detect(torch.from_numpy(reference.copy())[None]),**json.loads(receipt.read_text())}
            detection_matches = (row["after"] is None) if row["referenceAfter"] is None else (row["after"] is not None and abs(row["referenceAfter"]-row["after"]) <= 2e-3)
            if row["maxAbsoluteError"] > 2e-4 or not detection_matches:
                raise RuntimeError(f"Perth parity failed: {row}")
            if name != "silence" and row["after"] < .9: raise RuntimeError(f"Perth marker not detected: {row}")
            cases.append(row)
    result={"host":"macOS ARM64 CPU","perthRevision":"ff1c8ac55a976971245cdd53c18d6131ca00d993","reference":"Original source Encoder/Decoder with torch STFT/ISTFT using matching torchaudio configuration; no resampling", "limitations":["Synthetic short PCM only","No phone, perceptual or resampling-quality claim"],"torch":torch.__version__,"cases":cases}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2,allow_nan=False)+"\n");print(json.dumps(result,allow_nan=False))


if __name__=="__main__":main()
