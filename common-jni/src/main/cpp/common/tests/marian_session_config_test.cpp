#include "../../marian/marian_session_config.h"
#include <iostream>
#include <string>

namespace {
std::string key, value;
bool fail = false;
int released = 0;
OrtStatus* ORT_API_CALL add(OrtSessionOptions*, const char* name, const char* setting) noexcept {
    key = name;
    value = setting;
    return fail ? reinterpret_cast<OrtStatus*>(0x1) : nullptr;
}
const char* ORT_API_CALL message(const OrtStatus*) noexcept { return "provider rejected configuration"; }
void ORT_API_CALL release(OrtStatus*) noexcept { ++released; }
}

int main() {
    OrtApi api{};
    api.AddSessionConfigEntry = add;
    api.GetErrorMessage = message;
    api.ReleaseStatus = release;
    auto* options = reinterpret_cast<OrtSessionOptions*>(0x1);
    std::string error;
    int checks = 0;
    auto check = [&](bool condition) {
        ++checks;
        if (!condition) std::cerr << "FAIL check " << checks << '\n';
        return condition;
    };
    bool okay = true;
    okay &= check(stt::marian::configureIntraOpSpinning(&api, options, nullptr, &error));
    okay &= check(key == "session.intra_op.allow_spinning" && value == "0");
    okay &= check(stt::marian::configureIntraOpSpinning(&api, options, "0", &error) && value == "0");
    okay &= check(stt::marian::configureIntraOpSpinning(&api, options, "1", &error) && value == "1");
    okay &= check(stt::marian::configureIntraOpSpinning(&api, options, "garbage", &error) && value == "0");
    fail = true;
    okay &= check(!stt::marian::configureIntraOpSpinning(&api, options, nullptr, &error));
    okay &= check(error.find("provider rejected configuration") != std::string::npos && released == 1);
    api.AddSessionConfigEntry = nullptr;
    okay &= check(!stt::marian::configureIntraOpSpinning(&api, options, nullptr, &error));
    okay &= check(error == "ORT session-config API is unavailable");
    if (!okay) return 1;
    std::cout << "marian_session_config_test: " << checks << " checks PASS\n";
    return 0;
}
