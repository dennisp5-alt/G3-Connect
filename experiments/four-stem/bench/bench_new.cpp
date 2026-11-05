// Runs the app's real four-stem engine on a computer, for timing and comparison.
#include "../../../app/src/main/cpp/four_stem_sequential_native.cpp"
#include <cstdio>
int main(int argc,char**argv){
  if(argc<9)return 2;
  auto t=std::chrono::steady_clock::now();
  auto r=extract(nullptr,argv[1],argv[2],argv[4],argv[5],argv[6],argv[3],0,atoi(argv[7]),atoi(argv[8]),nullptr);
  printf("NEW %.1f seconds: %s\n",std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count(),r[4].c_str());
}
