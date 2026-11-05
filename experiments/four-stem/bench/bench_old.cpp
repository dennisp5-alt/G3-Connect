// The v0.1.4 engine, unchanged, on the same audio and models.
#include "old_engine.cpp"
#include <chrono>
#include <cstdio>
int main(int argc,char**argv){
  if(argc<8)return 2;
  auto t=std::chrono::steady_clock::now();
  auto r=extract(nullptr,argv[1],argv[2],argv[4],argv[5],argv[6],argv[3],0,atoi(argv[7]),nullptr);
  printf("OLD %.1f seconds: %s\n",std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count(),r[4].c_str());
}
