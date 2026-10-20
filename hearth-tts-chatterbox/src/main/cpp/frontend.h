#pragma once
#include "vendor/json.hpp"
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
extern "C" {
struct HearthBpeBuffer { unsigned char* data; size_t len; };
void* hearth_bpe_create(const unsigned char*, size_t, HearthBpeBuffer*);
int hearth_bpe_encode(const void*, const unsigned char*, size_t, HearthBpeBuffer*, HearthBpeBuffer*);
void hearth_bpe_destroy(void*);
void hearth_bpe_free_buffer(HearthBpeBuffer);
}
namespace {
struct Buffer { HearthBpeBuffer value{nullptr,0};~Buffer(){hearth_bpe_free_buffer(value);}std::string text()const{return value.data?std::string(reinterpret_cast<char*>(value.data),value.len):std::string();} };
std::string readFile(const std::string& path,size_t limit){std::ifstream in(path,std::ios::binary|std::ios::ate);if(!in||in.tellg()<1||size_t(in.tellg())>limit)throw std::runtime_error("Invalid Chatterbox tokenizer");std::string out(size_t(in.tellg()),'\0');in.seekg(0);if(!in.read(out.data(),out.size()))throw std::runtime_error("Cannot read Chatterbox tokenizer");return out;}
}
namespace hearth::chatterbox {
class Tokenizer {
 void* handle=nullptr;
public:
 explicit Tokenizer(const std::string& path){
  const auto text=readFile(path,1024*1024);Buffer error;handle=hearth_bpe_create(reinterpret_cast<const unsigned char*>(text.data()),text.size(),&error.value);if(!handle)throw std::runtime_error("Chatterbox tokenizer: "+error.text());
  try {auto metadata=nlohmann::json::parse(text);if(metadata["normalizer"]["normalizers"][0]["type"]!="NFKD")throw std::runtime_error("Chatterbox V3 requires full-case NFKD tokenizer");const auto& special=metadata["post_processor"]["special_tokens"];if(special["EXAGGERATION"]["ids"]!=nlohmann::json::array({6563})||special["BOS"]["ids"]!=nlohmann::json::array({255})||special["EOS"]["ids"]!=nlohmann::json::array({0})||special["START_SPEECH"]["ids"]!=nlohmann::json::array({6561}))throw std::runtime_error("Chatterbox V3 special token contract mismatch");}
  catch(...){hearth_bpe_destroy(handle);handle=nullptr;throw;}
 }
 ~Tokenizer(){if(handle)hearth_bpe_destroy(handle);}
 Tokenizer(const Tokenizer&)=delete;Tokenizer& operator=(const Tokenizer&)=delete;
 std::vector<int64_t> encode(const std::string& text,const std::string& language){if(language!="ar"&&language!="en")throw std::runtime_error("Chatterbox native frontend currently supports Arabic and English");Buffer output,error;const std::string prompt="["+language+"]"+text;if(!hearth_bpe_encode(handle,reinterpret_cast<const unsigned char*>(prompt.data()),prompt.size(),&output.value,&error.value))throw std::runtime_error("Chatterbox text encode: "+error.text());auto ids=nlohmann::json::parse(output.text()).at("ids").get<std::vector<int64_t>>();if(ids.empty()||ids.size()>512)throw std::runtime_error("Chatterbox text tokens exceed bound");for(auto id:ids)if(id<0||id>=6561)throw std::runtime_error("Chatterbox text contains reserved speech tokens");ids.insert(ids.begin(),{6563,255});ids.insert(ids.end(),{0,6561,6561});return ids;}
};
inline std::vector<int64_t> positions(const std::vector<int64_t>& ids){std::vector<int64_t> out;int64_t position=0;for(auto id:ids)out.push_back(id>=6561?0:position++);return out;}
}
