#include "model_integrity.h"

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using stt::ExpectedSha256;
using stt::ModelIntegrityError;
using stt::Sha256;
using stt::sha256ToHex;
using stt::verifySha256;

namespace {
int checks = 0;
void check(bool ok, const char* what) {
    ++checks;
    if (!ok) {
        std::fprintf(stderr, "sha256 FAIL: %s\n", what);
        std::exit(1);
    }
}
std::string hexOf(const std::string& message) { return sha256ToHex(Sha256::hash(message)); }
}  // namespace

int main() {
    // FIPS 180-4 / NIST CAVP example vectors.
    check(hexOf("") == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "empty");
    check(hexOf("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "abc");
    check(hexOf("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq") ==
          "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1", "448-bit message");
    check(hexOf("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu") ==
          "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1", "896-bit message");
    check(hexOf(std::string(1000000, 'a')) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
          "one million 'a'");

    // Padding boundaries (references from Python hashlib).
    const std::pair<size_t, const char*> boundaries[] = {
        {55, "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318"},
        {56, "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a"},
        {63, "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34"},
        {64, "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"},
        {65, "635361c48bb9eab14198e76ea8ab7f1a41685d6ad62aa9146d301d4f17eb0ae0"},
        {119, "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb"},
        {120, "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c"},
    };
    for (const auto& [length, expected] : boundaries) {
        check(hexOf(std::string(length, 'a')) == expected, "padding boundary");
    }

    // Incremental feeding at every split point equals the one-shot digest,
    // and digest() does not disturb a running hash.
    std::string bytes;
    for (int i = 0; i < 256; ++i) bytes.push_back(static_cast<char>(i));
    const std::string all = "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880";
    check(hexOf(bytes) == all, "bytes 0..255");
    for (size_t split = 0; split <= bytes.size(); ++split) {
        Sha256 running;
        check(running.update(bytes.data(), split), "update first part");
        (void)running.digest();  // non-destructive peek
        check(running.update(bytes.data() + split, bytes.size() - split), "update second part");
        if (sha256ToHex(running.digest()) != all) check(false, "split-point digest");
    }
    {
        Sha256 bytewise;
        for (char c : bytes) bytewise.update(&c, 1);
        check(sha256ToHex(bytewise.digest()) == all, "one byte at a time");
        check(bytewise.update(nullptr, 0) && !bytewise.update(nullptr, 1), "null data only valid for size 0");
        bytewise.reset();
        check(sha256ToHex(bytewise.digest()) == hexOf(""), "reset returns to the empty digest");
    }

    // Expected-digest parsing and verification.
    ModelIntegrityError error = ModelIntegrityError::NONE;
    ExpectedSha256 parsed;
    check(ExpectedSha256::parse(all, parsed, error) && error == ModelIntegrityError::NONE, "lowercase hex parses");
    check(parsed.bytes == Sha256::hash(bytes.data(), bytes.size()), "parsed bytes match digest");
    std::string upper = all;
    for (auto& c : upper) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    check(!ExpectedSha256::parse(upper, parsed, error) && error == ModelIntegrityError::INVALID_EXPECTED_SHA256,
          "uppercase hex rejected (matches Kotlin [0-9a-f]{64})");
    check(!ExpectedSha256::parse("", parsed, error) && error == ModelIntegrityError::MISSING_EXPECTED_SHA256, "missing");
    check(!ExpectedSha256::parse(all.substr(1), parsed, error) && error == ModelIntegrityError::INVALID_EXPECTED_SHA256, "short");
    check(!ExpectedSha256::parse(all.substr(0, 63) + "g", parsed, error), "non-hex nibble");
    const auto digest = Sha256::hash(bytes.data(), bytes.size());
    check(static_cast<bool>(verifySha256(digest, all)), "verify match");
    std::string flipped = all;
    flipped[63] = flipped[63] == '0' ? '1' : '0';
    check(verifySha256(digest, flipped).error == ModelIntegrityError::SHA256_MISMATCH, "verify mismatch");

    std::printf("sha256: %d checks PASS\n", checks);
    return 0;
}
