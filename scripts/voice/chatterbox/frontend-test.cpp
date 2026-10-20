#include "frontend.h"
#include <iostream>
#include <stdexcept>
void check(bool valid,const char* message){if(!valid)throw std::runtime_error(message);}
template<class F>void rejects(F f){bool failed=false;try{f();}catch(const std::exception&){failed=true;}check(failed,"Expected frontend rejection");}
int main(int argc,char** argv){if(argc!=2)return 2;hearth::chatterbox::Tokenizer tokenizer(argv[1]);
 check(tokenizer.encode("مرحبا بكم.","ar")==std::vector<int64_t>({6563,255,721,1491,1471,1467,1462,1456,2,1462,1489,1491,9,0,6561,6561}),"Arabic golden tokenizer IDs");
 check(tokenizer.encode("Hello from Hearth.","en")==std::vector<int64_t>({6563,255,708,284,18,84,28,2,154,2,284,18,59,40,9,0,6561,6561}),"English golden tokenizer IDs");
 check(tokenizer.encode("HELLO","en")!=tokenizer.encode("hello","en"),"V3 must preserve case");
 check(tokenizer.encode("أهلا","ar")==tokenizer.encode("أهلا","ar"),"NFKD must canonicalize Arabic hamza");
 check(hearth::chatterbox::positions({6563,255,708,123,0,6561,6561})==std::vector<int64_t>({0,0,1,2,3,0,0}),"Independent text/speech prompt positions");
 rejects([&]{tokenizer.encode("hello","zh");});rejects([&]{tokenizer.encode(std::string(10000,'X'),"en");});
 std::cout<<"Chatterbox V3 frontend golden IDs, NFKD, full case, positions and bounds PASS\n";
}
