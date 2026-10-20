#include "hearth_oute.h"
#include <atomic>
#include <chrono>
#include <iostream>
#include <thread>
int receive(void* value,const float*,int,int){++*static_cast<std::atomic_int*>(value);return 1;}
int main(int argc,char** argv){
 if(argc!=3)return 2;auto* ctx=hearth_oute_create(argv[1],argv[2],2);if(!ctx){std::cerr<<hearth_oute_last_error();return 3;}
 std::atomic_int chunks{0};std::thread cancel([&]{std::this_thread::sleep_for(std::chrono::milliseconds(100));hearth_oute_cancel(ctx);});
 auto start=std::chrono::steady_clock::now();int status=hearth_oute_synthesize(ctx,"This sentence must be cancelled before any audio is delivered.",receive,&chunks);cancel.join();
 const auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
 if(status!=1||chunks!=0){std::cerr<<"Cancellation failed "<<status<<" "<<hearth_oute_last_error();hearth_oute_destroy(ctx);return 4;}
 status=hearth_oute_synthesize(ctx,"Cancelled session stays cancelled.",receive,&chunks);hearth_oute_destroy(ctx);
 if(status!=1||chunks!=0)return 5;std::cout<<"{\"cancelMs\":"<<elapsed<<",\"audioChunks\":0,\"sticky\":true}\n";
}
