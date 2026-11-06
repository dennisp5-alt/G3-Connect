// Runs the app's real six-stem engine on a computer, for timing, memory and a sum check.
#include "../../app/src/main/cpp/six_stem_native.cpp"
int main(int argc,char**argv){
  if(argc<7)return 2;
  auto t=std::chrono::steady_clock::now();
  auto r=extract(nullptr,argv[1],argv[2],argv[3],argv[4],0,atoi(argv[5]),atoi(argv[6]),nullptr);
  printf("SIX %.1f seconds: %s\n",std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count(),r[STEMS].c_str());
}
