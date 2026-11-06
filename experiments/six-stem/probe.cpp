// Where does the six-stem model's memory go? One setup per run, printing memory at each step.
#include "onnxruntime/onnxruntime_c_api.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <random>
#include <vector>
static long field(const char*k){FILE*f=fopen("/proc/self/status","r");char l[256];long v=0;size_t n=strlen(k);while(fgets(l,256,f))if(!strncmp(l,k,n))sscanf(l+n+1,"%ld",&v);fclose(f);return v/1024;}
static const OrtApi*api;
static void ok(OrtStatus*s){if(s){printf("ERROR %s\n",api->GetErrorMessage(s));exit(1);}}
int main(int c,char**a){ // model threads level arena pattern prepack
  api=OrtGetApiBase()->GetApi(ORT_API_VERSION);
  int threads=atoi(a[2]),level=atoi(a[3]),arena=atoi(a[4]),pattern=atoi(a[5]),prepack=atoi(a[6]);
  OrtEnv*env;ok(api->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"p",&env));
  OrtSessionOptions*o;ok(api->CreateSessionOptions(&o));
  ok(api->SetIntraOpNumThreads(o,threads));ok(api->SetInterOpNumThreads(o,1));
  ok(api->SetSessionExecutionMode(o,ORT_SEQUENTIAL));
  if(!arena)ok(api->DisableCpuMemArena(o));
  if(!pattern)ok(api->DisableMemPattern(o));
  ok(api->SetSessionGraphOptimizationLevel(o,(GraphOptimizationLevel)level));
  if(!prepack)ok(api->AddSessionConfigEntry(o,"session.disable_prepacking","1"));
  OrtSession*s;auto t=std::chrono::steady_clock::now();
  ok(api->CreateSession(env,a[1],o,&s));
  printf("threads %d level %d arena %d pattern %d prepack %d: load %.1fs rss %ld peak %ld",threads,level,arena,pattern,prepack,
    std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count(),field("VmRSS:"),field("VmHWM:"));
  OrtMemoryInfo*m;ok(api->CreateCpuMemoryInfo(OrtDeviceAllocator,OrtMemTypeDefault,&m));
  OrtAllocator*al;ok(api->GetAllocatorWithDefaultOptions(&al));
  char*in,*out;ok(api->SessionGetInputName(s,0,al,&in));ok(api->SessionGetOutputName(s,0,al,&out));
  const size_t N=343980;std::vector<float> x(N*2);std::mt19937 r(1);std::normal_distribution<float> g(0,.3f);
  for(size_t i=0;i<N;i++){float v=float(.6*sin(i*.006)+.3*sin(i*.05))+g(r);x[i]=v;x[N+i]=v*.9f;}
  const int64_t shape[]{1,2,(int64_t)N};
  for(int i=0;i<3;i++){
    OrtValue*v;ok(api->CreateTensorWithDataAsOrtValue(m,x.data(),x.size()*4,shape,3,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&v));
    OrtValue*res=nullptr;const OrtValue*vv[]{v};const char*ii=in,*oo=out;
    t=std::chrono::steady_clock::now();
    ok(api->Run(s,nullptr,&ii,vv,1,&oo,1,&res));
    printf(" | run %.1fs rss %ld peak %ld",std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count(),field("VmRSS:"),field("VmHWM:"));
    api->ReleaseValue(res);api->ReleaseValue(v);
  }
  printf("\n");
}
