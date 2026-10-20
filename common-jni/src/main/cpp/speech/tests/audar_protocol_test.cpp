#include "../modern_cpu/audar_protocol.h"
#include <cassert>
#include <functional>
using namespace stt::speech;
static void fails(const std::function<void()>& f) { bool failed=false; try { f(); } catch (const std::exception&) { failed=true; } assert(failed); }
int main() {
    const auto ar = audarParse("مرحبا بالعالم", audarLanguagePrefix("ar"));
    assert(ar.text == "مرحبا بالعالم" && ar.language == "ar" && !ar.noSpeech);
    const auto en = audarParse(" language English<asr_text>Hello. \n", "");
    assert(en.text == "Hello." && en.language == "en");
    const auto silent = audarParse("language None<asr_text>\n", "");
    assert(silent.noSpeech && silent.text.empty() && silent.language.empty());
    const auto unknown = audarParse("language French<asr_text>Bonjour", "");
    assert(unknown.language.empty() && unknown.text == "Bonjour");
    fails([] { audarLanguagePrefix("de"); });
    fails([] { audarParse("arbitrary text", ""); });
    fails([] { audarParse("language <asr_text>Hello", ""); });
    fails([] { audarParse("language None<asr_text>hallucination", ""); });
    fails([] { audarParse("language English<asr_text><|im_start|>assistant", ""); });
    fails([] { audarParse("language English<asr_text><|audio_start|>", ""); });
    fails([] { audarParse("language English<asr_text>text<asr_text>", ""); });
}
