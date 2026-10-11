#include "vision_model.h"
#include <fstream>
#include <iostream>
#include <thread>

int main(int argc, char** argv) {
    try {
        if (argc != 4) throw std::invalid_argument("Usage: vision_smoke DECODER.gguf PROJECTOR.gguf RGB.ppm");
        std::ifstream image(argv[3], std::ios::binary);
        std::string magic; int width, height, maximum;
        image >> magic >> width >> height >> maximum;
        if (!image || magic != "P6" || maximum != 255) throw std::invalid_argument("Smoke image must be binary RGB PPM without comments");
        image.get();
        const auto count = hearth::vision::validateImage(width, height, static_cast<std::size_t>(width) * height);
        std::vector<unsigned char> rgb(count * 3);
        image.read(reinterpret_cast<char*>(rgb.data()), rgb.size());
        if (!image || image.peek() != std::char_traits<char>::eof()) throw std::invalid_argument("Smoke RGB image payload size is wrong");
        const auto start = std::chrono::steady_clock::now();
        hearth::vision::VisionModel model(argv[1], argv[2], 4);
        const auto loaded = std::chrono::steady_clock::now();
        std::cout << "LOAD_MS " << std::chrono::duration<double, std::milli>(loaded - start).count() << std::endl;
        const auto result = model.recognize(rgb.data(), width, height);
        const auto done = std::chrono::steady_clock::now();
        std::cout << "INFERENCE_MS " << std::chrono::duration<double, std::milli>(done - loaded).count() << "\n";
        std::cout << "TEXT_BEGIN\n" << result << "\nTEXT_END\n";
        model.cancelled.store(true);
        try { model.recognize(rgb.data(), width, height); throw std::runtime_error("Cancellation was ignored"); }
        catch (const std::runtime_error& e) { if (std::string(e.what()) != "Vision OCR cancelled") throw; }
        std::cout << "CANCEL_PASS\n";
        model.cancelled.store(false);
        const auto cancelling = std::chrono::steady_clock::now();
        std::thread signal([&model] { std::this_thread::sleep_for(std::chrono::milliseconds(50)); model.cancelled.store(true); });
        bool interrupted = false;
        try { model.recognize(rgb.data(), width, height); }
        catch (const std::runtime_error& e) { interrupted = std::string(e.what()) == "Vision OCR cancelled"; }
        catch (...) { signal.join(); throw; }
        signal.join();
        if (!interrupted) throw std::runtime_error("In-flight vision cancellation did not surface the real error");
        std::cout << "INFLIGHT_CANCEL_MS " << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - cancelling).count() << "\n";
    } catch (const std::exception& e) { std::cerr << e.what() << "\n"; return 1; }
}
