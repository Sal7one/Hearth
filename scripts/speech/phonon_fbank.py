#!/usr/bin/env python3
"""128-bin log-mel features for phonon-2 export verification.

Matches the feature front-end sherpa-onnx uses for nemo_transducer models
(k2-fsa scripts/nemo/parakeet-tdt test settings): hann window, no dither, no
DC-offset removal, librosa-style mel filterbank, 128 bins, 16 kHz. Prefer
kaldi-native-fbank when installed — it is bit-identical to sherpa's C++ path —
and fall back to a librosa approximation (adequate for transcript parity, not
for numeric logit comparison).
"""
from __future__ import annotations

import numpy as np

NUM_BINS = 128
SAMPLE_RATE = 16000


def compute_fbank(audio: np.ndarray) -> np.ndarray:
    if audio.ndim != 1:
        raise ValueError("expected mono 1-D audio")
    try:
        import kaldi_native_fbank as knf
    except ImportError:
        return _librosa_fallback(audio)

    opts = knf.FbankOptions()
    opts.frame_opts.dither = 0
    opts.frame_opts.remove_dc_offset = False
    opts.frame_opts.window_type = "hann"
    opts.mel_opts.low_freq = 0
    opts.mel_opts.num_bins = NUM_BINS
    opts.mel_opts.is_librosa = True
    fbank = knf.OnlineFbank(opts)
    fbank.accept_waveform(SAMPLE_RATE, audio.astype(np.float32))
    frames = [np.array(fbank.get_frame(i)) for i in range(fbank.num_frames_ready)]
    if not frames:
        raise ValueError("no feature frames computed")
    return np.stack(frames)  # (T, C)


def _librosa_fallback(audio: np.ndarray) -> np.ndarray:
    import librosa

    mel = librosa.feature.melspectrogram(y=audio.astype(np.float32), sr=SAMPLE_RATE, n_fft=512,
                                         hop_length=160, win_length=400, window="hann", center=True,
                                         n_mels=NUM_BINS, fmin=0, htk=False, norm=None, power=2.0)
    log_mel = np.log(np.maximum(mel, 1e-10))
    print("warning: kaldi_native_fbank not installed; using librosa approximation", file=__import__("sys").stderr)
    return log_mel.T.astype(np.float32)  # (T, C)
