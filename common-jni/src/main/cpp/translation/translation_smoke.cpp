#include "text_model.h"
#include <iostream>
int main(int argc, char** argv) {
 try {
  if (argc != 3) throw std::invalid_argument("Usage: translation_smoke MODEL.gguf PROMPT");
  transiber::TextModel model(argv[1]);
  std::cout << model.translate(argv[2]) << std::endl;
 } catch (const std::exception& e) { std::cerr << e.what() << std::endl; return 1; }
}
