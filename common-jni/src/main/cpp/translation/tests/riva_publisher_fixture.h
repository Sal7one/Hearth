#pragma once

// Exact Jinja2 rendering of NVIDIA's pinned tokenizer_config.json, not a
// transcription of TextModel's C++ escape sequences. Rendering/provenance:
// docs/research/mt-riva-publisher-prompt-2026-10-20.json
// Revision: 040d958b128018ff0bed2542a7b51005e9ea563c
// tokenizer_config.json SHA-256:
// 26ab1e654158b484c413919acfb6ff0480b2dfdc7b2ad6e05f8216f010a9e6c3
// Input: system=en-ar, user="The meeting starts at nine.", generation prompt=true.
constexpr const char* rivaPublisherRenderedPrompt = R"fixture(<s>System
You are an expert at translating text from English to Arabic.</s>
<s>User
The meeting starts at nine.</s>
<s>Assistant
)fixture";
