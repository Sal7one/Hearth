struct VoskModel; struct VoskRecognizer;
extern "C" {
VoskModel* vosk_model_new(const char* model_path) { return nullptr; }
void vosk_model_free(VoskModel* model) {  }
VoskRecognizer* vosk_recognizer_new(VoskModel* model, float sample_rate) { return nullptr; }
void vosk_recognizer_free(VoskRecognizer* recognizer) {  }
int vosk_recognizer_accept_waveform(VoskRecognizer* recognizer, const char* data, int length) { return length; }
int vosk_recognizer_accept_waveform_s(VoskRecognizer* recognizer, const short* data, int length) { return 0; }
int vosk_recognizer_accept_waveform_f(VoskRecognizer* recognizer, const float* data, int length) { return 0; }
const char* vosk_recognizer_result(VoskRecognizer* recognizer) { return nullptr; }
const char* vosk_recognizer_partial_result(VoskRecognizer* recognizer) { return nullptr; }
const char* vosk_recognizer_final_result(VoskRecognizer* recognizer) { return nullptr; }
void vosk_recognizer_reset(VoskRecognizer* recognizer) {  }
void vosk_set_log_level(int log_level) {  }
}
