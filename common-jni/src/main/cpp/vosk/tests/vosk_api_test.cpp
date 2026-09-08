#include "../vosk_api.h"
#include <cassert>
#include <iostream>
int main(int argc, char** argv) {
    assert(argc == 3);
    stt::VoskApi api(argv[1]);
    assert(api.vosk_recognizer_accept_waveform(nullptr, nullptr, 7) == 7);
    assert(dlsym(RTLD_DEFAULT, "vosk_recognizer_accept_waveform") == nullptr);
    try { stt::VoskApi missing("/nonexistent/transiber-vosk.so"); assert(false); }
    catch (const std::runtime_error& e) { assert(std::string(e.what()).find("transiber-vosk.so") != std::string::npos); }
    try { stt::VoskApi incomplete(argv[2]); assert(false); }
    catch (const std::runtime_error& e) { assert(std::string(e.what()).find("vosk_model_new") != std::string::npos); }
    std::cout << "Vosk API: local visibility, C call, missing library and missing symbol PASS\n";
}
