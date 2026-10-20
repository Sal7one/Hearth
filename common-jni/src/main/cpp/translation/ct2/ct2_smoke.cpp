#include "ct2_model.h"
#include <iostream>
int main(int argc, char** argv) {
    try {
        if (argc == 3 && std::string(argv[2]) == "--fixture") {
            for (auto type : {ctranslate2::ComputeType::FLOAT32, ctranslate2::ComputeType::INT8_FLOAT32}) {
                ctranslate2::Translator translator(argv[1],ctranslate2::Device::CPU,type);
                ctranslate2::TranslationOptions options; options.beam_size=1;
                const auto out=translator.translate_batch({{"آ","ت","ز","م","و","ن"}},options);
                if(out.size()!=1 || out[0].output()!=std::vector<std::string>({"a","t","z","m","o","n"}))
                    throw std::runtime_error("Real CT2 Arabic transliteration fixture failed");
            }
            std::cout << "FLOAT32 and INT8_FLOAT32 real CT2 Arabic fixture passed\n";return 0;
        }
        if (argc != 5) throw std::invalid_argument("Usage: ct2_smoke DIRECTORY MODE TARGET TEXT");
        hearth::Ct2Model model(argv[1], std::stoi(argv[2]));
        std::cout << model.translate(argv[4], argv[3]) << '\n';
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
