// Link stub for host tests: ort_session.cpp and sign_classifier.cpp reach
// ONNX Runtime only through OrtGetApiBase(); sign tests exercise pure
// geometry/decode logic and never run inference, so this one-symbol stub
// satisfies the linker (any accidental inference call aborts loudly).
extern "C" const void* OrtGetApiBase() { __builtin_trap(); }
