#include "../marian_tokenizer.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>
#include <cstdlib>
#include <unistd.h>

namespace fs = std::filesystem;
using stt::marian::MarianTokenizer;
int failures = 0;
#define CHECK(condition) do { if (!(condition)) { \
    ++failures; std::cerr << "FAIL " << __LINE__ << ": " #condition "\n"; \
} } while (0)

static void writeFile(const fs::path& path, const std::string& data) {
    std::ofstream file(path, std::ios::binary | std::ios::trunc);
    file.write(data.data(), static_cast<std::streamsize>(data.size()));
    CHECK(file.good());
}

static std::string modelPiece(const std::string& text, char type) {
    std::string piece;
    piece += '\x0a'; piece += static_cast<char>(text.size()); piece += text;
    piece += '\x18'; piece += type;
    return std::string(1, '\x0a') + static_cast<char>(piece.size()) + piece;
}

int main() {
    char temporary[] = "/tmp/hearth-marian-XXXXXX";
    const int temporaryFd = ::mkstemp(temporary);
    CHECK(temporaryFd >= 0);
    if (temporaryFd < 0) return 1;
    ::close(temporaryFd);
    fs::remove(temporary);
    const fs::path root(temporary);
    CHECK(fs::create_directory(root));
    const fs::path spm = root / "source.spm";
    const fs::path json = root / "tokenizer.json";
    const std::string model = modelPiece("<unk>", '\x02') +
        modelPiece("\xe2\x96\x81" "a", '\x01');
    const std::string vocab = R"({"model":{"vocab":[["<unk>",0],["</s>",0],["<pad>",0],["▁a",0]]},"added_tokens":[{"content":"<unk>","id":0},{"content":"</s>","id":1},{"content":"<pad>","id":2}]})";
    writeFile(spm, model);
    writeFile(json, vocab);
    MarianTokenizer tokenizer;
    std::string error;
    CHECK(MarianTokenizer::load(spm.string(), json.string(), tokenizer, &error));
    if (!error.empty()) std::cerr << error << '\n';
    const auto encoded = tokenizer.encode("a");
    CHECK(encoded.ok);
    CHECK(encoded.ids == std::vector<int64_t>({3, 1}));

    writeFile(json, R"({"model":{"vocab":[["<unk>",0],["</s>",0],["<pad>",0],["▁a",0]]},"added_tokens":[{"content":"<unk>","id":1e300}]})");
    CHECK(!MarianTokenizer::load(spm.string(), json.string(), tokenizer, &error));
    CHECK(error.find("finite int64") != std::string::npos);
    writeFile(json, vocab);

    fs::resize_file(spm, 16U * 1024U * 1024U + 1U);
    CHECK(!MarianTokenizer::load(spm.string(), json.string(), tokenizer, &error));
    CHECK(error.find("spm model exceeds") != std::string::npos);
    writeFile(spm, model);
    fs::resize_file(json, 64U * 1024U * 1024U + 1U);
    CHECK(!MarianTokenizer::load(spm.string(), json.string(), tokenizer, &error));
    CHECK(error.find("tokenizer.json exceeds") != std::string::npos);

    fs::remove_all(root);
    if (failures) {
        std::cerr << "marian_tokenizer_test: " << failures << " failures\n";
        return 1;
    }
    std::cout << "marian_tokenizer_test: ALL PASS\n";
    return 0;
}
