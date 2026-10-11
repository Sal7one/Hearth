#include "../paddle_ocr.h"
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>

// Optional real-weight host probe, separate from the fast deterministic geometry suite.
// Pixels are packed native uint32 ARGB. No JNI, application context or network dependency.
int main(int argc, char** argv) {
    try {
        if (argc != 7) throw std::invalid_argument("usage: recognizer-smoke detector.onnx recognizer.onnx classes pixels.argb width height");
        const int classes = std::stoi(argv[3]), width = std::stoi(argv[5]), height = std::stoi(argv[6]);
        if (width < 1 || height < 1 || width > 2048 || height > 2048) throw std::invalid_argument("pixel dimensions exceed native OCR limits");
        std::vector<uint32_t> pixels(static_cast<size_t>(width) * height);
        std::ifstream input(argv[4], std::ios::binary);
        if (!input.read(reinterpret_cast<char*>(pixels.data()), static_cast<std::streamsize>(pixels.size() * sizeof(uint32_t))))
            throw std::runtime_error("ARGB fixture is truncated");
        if (input.peek() != std::ifstream::traits_type::eof()) throw std::runtime_error("ARGB fixture has extra data");
        hearth::ocr::PaddleOcr model(argv[1], argv[2], classes);
        const auto lines = model.recognize(pixels.data(), width, height, 960);
        std::cout << "[";
        for (size_t i = 0; i < lines.size(); ++i) {
            if (i) std::cout << ",";
            std::cout << "{\"confidence\":" << lines[i].text.confidence << ",\"tokens\":[";
            for (size_t j = 0; j < lines[i].text.tokens.size(); ++j) {
                if (j) std::cout << ",";
                std::cout << lines[i].text.tokens[j];
            }
            std::cout << "]}";
        }
        std::cout << "]\n";
        return lines.empty() ? 2 : 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << "\n";
        return 1;
    }
}
