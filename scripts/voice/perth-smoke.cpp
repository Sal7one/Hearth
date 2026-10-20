// Real pinned-weight probe; no Android/device performance claim.
#include "chatterbox/watermark.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <cmath>

// Standalone parity checks do not request cancellation; production binds its owner.
extern "C" void hearth_chatterbox_check_watermark() {}

int main(int argc, char** argv) {
  if (argc != 6) return 2;
  try {
    auto marker = chatterbox::PerthWatermarker::from_file(argv[1]);
    chatterbox::Audio input; input.sample_rate = std::stoi(argv[3]);
    std::ifstream source(argv[2], std::ios::binary | std::ios::ate);
    const auto length = source.tellg();
    if (length <= 0 || length > 4*32000*10 || length % 4) throw std::runtime_error("Invalid probe PCM");
    input.samples.resize(static_cast<size_t>(length)/4); source.seekg(0);
    source.read(reinterpret_cast<char*>(input.samples.data()), length);
    const float before=marker.detect(input);
    const auto start=std::chrono::steady_clock::now();
    auto output=marker.apply(input);
    const double elapsed=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
    const float after=marker.detect(output);
    for (float value:output.samples) if (!std::isfinite(value)) throw std::runtime_error("Non-finite Perth output");
    std::ofstream sink(argv[4],std::ios::binary); sink.write(reinterpret_cast<const char*>(output.samples.data()),output.samples.size()*sizeof(float));
    std::ofstream result(argv[5]);
    result.precision(10);
    result << "{\"before\":";
    if (std::isfinite(before)) result << before; else result << "null";
    result << ",\"after\":";
    if (std::isfinite(after)) result << after; else result << "null";
    result << ",\"applySeconds\":" << elapsed << ",\"samples\":" << output.samples.size() << "}\n";
  } catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
