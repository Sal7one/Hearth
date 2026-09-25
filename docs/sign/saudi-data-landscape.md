# Saudi Sign Language — Data Landscape

Companion to `research/sign/arsl/REPORT.md`. Status of public Saudi /
Saudi-collected sign language data as of 2026-09, and how it relates to what
this release ships.

## What shipped in this release (Phase 1)

**Static alphabet fingerspelling only**: one hand, one frame, 28 canonical
Arabic letters, classified from 63 MediaPipe landmark floats by a ~80 KB MLP.
Training data provenance is genuinely Saudi-inclusive — the joint-training
rows are ArSL2018, collected in **Al Khobar, Saudi Arabia** — and the
alphabet is the standard Arabic fingerspelling used across Arab deaf
communities including KSA. **No word- or sentence-level (SASL) recognition is
claimed or supported by this artifact.**

## The datasets

### ArSL2018 / ArASL — static alphabet, Saudi-collected (USED here)

- **Contents**: 54,049 labeled 64×64 grayscale images (28 letters + variants),
  collected in **Al Khobar, Saudi Arabia** with an iPhone 6S, 40+ participants.
- **License / access**: CC BY 4.0, Mendeley Data,
  DOI [10.17632/y7pckrw6z2.1](https://data.mendeley.com/datasets/y7pckrw6z2/1);
  companion paper Latif et al., "ArASL: Arabic Alphabet Sign Language
  Dataset", Data in Brief 23 (2019) 103777,
  [DOI 10.1016/j.dib.2019.103777](https://doi.org/10.1016/j.dib.2019.103777).
- **Role here**: only 6.7% of letter images survived MediaPipe landmark
  extraction (3,168 of 47,447 — tiny grayscale hands); those survivors joined
  AASL as weighted (0.3) training rows and a 473-image holdout. This is what
  makes the training set Saudi-collected beyond the pan-Arab AASL corpus.

### Isharah — continuous SSL at scale (Phase 2 candidate)

- **Repo**: [github.com/snalyami/Isharah_CSLR](https://github.com/snalyami/Isharah_CSLR)
  ([project page](https://snalyami.github.io/Isharah_CSLR));
  paper ["Isharah: A Large-Scale Multi-Scene Dataset for Continuous Sign
  Language Recognition"](https://arxiv.org/html/2506.03615v1).
- **Contents**: 30,000+ video samples, 2,000+ unique **Saudi Sign Language
  sentences**, signed by deaf and professional signers, recorded "in the
  wild" with smartphone cameras; annotated with **word-level glosses plus
  Arabic sentence translations** — targets both CSLR and sign language
  translation (SLT).
- **Fit**: the strongest public starting point for Phase 2 temporal
  recognition: native-Saudi signing, smartphone capture matching the app's
  expected input distribution, and gloss+sentence supervision exactly matching
  a CTC / encoder-decoder pipeline.

### KAU-CSSL — first continuous SSL dataset, medical domain (Phase 2 candidate)

- **Paper**: ["Continuous Saudi Sign Language Recognition: A Vision
  Transformer Approach"](https://arxiv.org/abs/2509.03467) (2025).
- **Contents**: continuous **medical-scenario SSL sentences** — the first
  continuous Saudi Sign Language dataset; introduced with a ViT-based
  recognition approach.
- **Fit**: narrower domain (healthcare communication) and younger/smaller
  than Isharah, but uniquely valuable for a medical phrases model and as a
  held-out Saudi test domain.

### KArSL — 502 isolated words on Kinect (REJECTED for Phase 1)

- **Paper**: Sidig, Luqman, Mahmoud, Mohandes, "KArSL: Arabic Sign Language
  Database", ACM TALLIP 20(1), 2021 (KFUPM).
- **Contents**: ~75,300 videos of **502 isolated sign words** (static and
  dynamic) from the ArSL dictionary, captured with Microsoft Kinect (RGB +
  depth + body skeleton).
- **Why rejected for the static alphabet**: representation mismatch —
  two-handed dynamic word signs captured by a fixed depth sensor do not map
  onto a single-hand, single-frame, 21-landmark phone-camera model. Reusing
  it for Phase 1 would mean silently changing the task. It remains a Phase 2
  candidate for isolated-word recognition after re-extraction, and its
  Kinect-era capture (lab sensor) is a domain gap even then.

### KSU-ArSL / KSU-SSL — isolated words + alphabet (Phase 2 auxiliary)

- King Saud University dataset (Albuhairi et al.): ~16,000 RGB videos,
  **80 sign classes** (words, alphabet letters, numbers), 40 subjects,
  multi-camera capture.
- **Fit**: useful auxiliary volume for isolated word/sign classification and
  for Saudi-domain validation; mixed alphabet/word/number taxonomy means it
  cannot serve as a clean single-task benchmark without re-labeling.

A useful catalog of these and other Arabic SL resources:
[AraSLP datasets page](https://github.com/Hamzah-Luqman/AraSLP/blob/main/datasets.html).

## Phase 2 — word/sentence recognition (temporal)

Everything beyond fingerspelling needs **temporal models over video
sequences**: sliding-window landmark/pose streams fed to an encoder
(BiLSTM / Transformer / TCN over the same MediaPipe landmark front-end, or a
ViT over RGB frames as in KAU-CSSL), trained with **CTC** for gloss
sequences (Isharah CSLR setup) or an encoder-decoder for Arabic sentence
translation (SLT). The Phase 1 static classifier remains useful inside that
stack — per-frame alphabet disambiguation for fingerspelled spans — so this
release is a component of, not a replacement for, Phase 2.

## Closing the remaining gap: Saudi device-capture pack

The public Saudi datasets still leave one gap: modern phone-camera,
app-conditioned capture of the alphabet and common phrases from deaf signers
in KSA (ArSL2018 is 64×64 grayscale from 2018-era hardware; only 6.7% of it
even survives our landmark extractor). The plan:

1. Build a **device capture pack** that runs through the real Hearth
   frontend: guided per-letter / per-phrase recording screens, live
   MediaPipe landmark preview (so failures are visible at capture time),
   signer consent + provenance metadata, and local-only storage.
2. Capture from deaf and hearing signers in KSA across devices, lighting and
   backgrounds — the exact input distribution the app will see.
3. Retrain the joint recipe (AASL + ArSL2018 survivors + device captures,
   sample-weighted) and report on a signer-disjoint split — which public
   data cannot provide and which is the real generalization guarantee.

## One-line capability label (UI)

> Arabic alphabet fingerspelling (28 letters, static single-hand poses) —
> includes Saudi-collected (Al Khobar) training data; not word/sentence
> sign language.
