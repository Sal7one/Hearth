#include "../../vision/vision_limits.h"
#include <cassert>
#include <functional>
#include <iostream>

int main() {
    using namespace hearth::vision;
    assert(validateImage(1, 1, 1) == 1);
    assert(validateImage(2048, 2048, 4194304) == 4194304);
    for (const auto& invalid : {std::function<void()>([]{ validateImage(0, 10, 0); }),
        std::function<void()>([]{ validateImage(-1, 10, 0); }),
        std::function<void()>([]{ validateImage(2049, 1, 2049); }),
        std::function<void()>([]{ validateImage(10, 20, 199); })}) {
        bool rejected = false;
        try { invalid(); } catch (const std::invalid_argument&) { rejected = true; }
        assert(rejected);
    }
    assert(opaqueChannel(0xff123456, 16) == 0x12);
    assert(opaqueChannel(0xff123456, 8) == 0x34);
    assert(opaqueChannel(0xff123456, 0) == 0x56);
    assert(opaqueChannel(0x00123456, 16) == 255);
    assert(opaqueChannel(0x80000000, 16) == 127);
    assert(opaqueChannel(0x80ffffff, 0) == 255);
    std::cout << "vision_input_test: 12 checks PASS\n";
}
