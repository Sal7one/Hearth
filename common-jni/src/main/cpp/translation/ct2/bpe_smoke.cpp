#include "byte_bpe.h"
#include <iostream>
int main(int argc, char** argv) {
    try {
        if (argc != 3) throw std::invalid_argument("Usage: bpe_smoke tokenizer.json text");
        hearth::ByteBpe tokenizer(argv[1]);
        auto result = tokenizer.encodeWithIds(argv[2]);
        result["decoded"] = tokenizer.decode(result.at("tokens").get<std::vector<std::string>>());
        std::cout << result.dump() << '\n';
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
