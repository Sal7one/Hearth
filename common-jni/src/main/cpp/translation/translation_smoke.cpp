#include "text_model.h"
#include <iomanip>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

static std::string jsonEscape(const std::string& value) {
 std::ostringstream out;
 for (unsigned char c : value) {
  switch (c) {
   case '"': out << "\\\""; break;
   case '\\': out << "\\\\"; break;
   case '\b': out << "\\b"; break;
   case '\f': out << "\\f"; break;
   case '\n': out << "\\n"; break;
   case '\r': out << "\\r"; break;
   case '\t': out << "\\t"; break;
   default:
    if (c < 0x20) out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(c) << std::dec;
    else out << static_cast<char>(c);
  }
 }
 return out.str();
}

int main(int argc, char** argv) {
 try {
  if (argc == 3 && std::string(argv[2]) != "--stream") {
   transiber::TextModel model(argv[1]);
   std::cout << model.translate(argv[2]) << std::endl;
   return 0;
  }
  if ((argc != 6 && argc != 7) || std::string(argv[2]) != "--stream")
   throw std::invalid_argument("Usage: translation_smoke MODEL.gguf --stream THREADS GPU_LAYERS [STOP-ON-ERROR]");
  const int threads = std::max(1, std::stoi(argv[3]));
  const int gpuLayers = std::max(0, std::stoi(argv[4]));
  if (std::string(argv[5]) != "NUL-DELIMITED-STDIN") throw std::invalid_argument("Invalid stream marker");
  const bool stopOnError = argc == 7 && std::string(argv[6]) == "STOP-ON-ERROR";
  if (argc == 7 && !stopOnError) throw std::invalid_argument("Invalid stream error policy");
  const auto loadStart = std::chrono::steady_clock::now();
  transiber::TextModel model(argv[1], threads, gpuLayers);
  const auto loadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - loadStart).count();
  std::cout << "{\"event\":\"ready\",\"loadMs\":" << loadMs << "}" << std::endl;
  std::string prompt;
  char ch;
  while (std::cin.get(ch)) {
   if (ch != '\0') { prompt.push_back(ch); continue; }
   const auto start = std::chrono::steady_clock::now();
   try {
    const auto output = model.translate(prompt);
    const auto elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << "{\"elapsedMs\":" << elapsed << ",\"text\":\"" << jsonEscape(output) << "\"}" << std::endl;
   } catch (const std::exception& e) {
    const auto elapsed = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << "{\"elapsedMs\":" << elapsed << ",\"error\":\"" << jsonEscape(e.what()) << "\"}" << std::endl;
    prompt.clear();
    if (stopOnError) break;
   }
   prompt.clear();
  }
  if (!prompt.empty()) throw std::runtime_error("Benchmark stream ended without a NUL delimiter");
 } catch (const std::exception& e) { std::cerr << e.what() << std::endl; return 1; }
}
