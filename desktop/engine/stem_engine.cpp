// Stem Studio for Windows: the phone's six-stem engine (app/src/main/cpp/six_stem_native.cpp) as a
// program the Windows app runs. Same HT-Demucs 6-stem model (MIT, Rouard et al. 2023), same sections,
// weights, rate changes and sum (Other is what is left). Differences: Windows paths and disk checks,
// the graphics card through DirectML when there is one (the processor otherwise), progress as lines on
// standard output, and "cancel" on standard input.
//
//   stemengine --model M.onnx --in song.wav --out FOLDER --prefix NAME [--device auto|gpu|cpu]
//              [--threads N] [--start S --seconds 10|60]
// Prints: DEVICE ..., SECTIONS n, SECTION i n, STAGE name i n, STEM name path, DONE summary | ERROR message
#include <thread>
#include <iostream>
#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#include <psapi.h>
#include <fcntl.h>
#include <io.h>
#else
#include <sys/statvfs.h>
#endif
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"
#ifdef STEM_DIRECTML
#include "dml_provider_factory.h"
#endif

namespace {
#ifdef _WIN32
std::wstring wide(const std::string&s){
  if(s.empty())return std::wstring();
  const int n=MultiByteToWideChar(CP_UTF8,0,s.data(),int(s.size()),nullptr,0);
  std::wstring w(size_t(n),L'\0');
  MultiByteToWideChar(CP_UTF8,0,s.data(),int(s.size()),&w[0],n);
  return w;
}
std::string utf8(const wchar_t*w){
  const int n=WideCharToMultiByte(CP_UTF8,0,w,-1,nullptr,0,nullptr,nullptr);
  std::string s(size_t(n>0?n-1:0),'\0');
  if(n>1)WideCharToMultiByte(CP_UTF8,0,w,-1,&s[0],n,nullptr,nullptr);
  return s;
}
FILE*openFile(const std::string&name,const char*mode){return _wfopen(wide(name).c_str(),wide(mode).c_str());}
int removeFile(const std::string&name){return _wremove(wide(name).c_str());}
int renameFile(const std::string&from,const std::string&to){_wremove(wide(to).c_str());return _wrename(wide(from).c_str(),wide(to).c_str());}
#define fseek _fseeki64
#define ftell _ftelli64
#else
FILE*openFile(const std::string&name,const char*mode){return fopen(name.c_str(),mode);}
int removeFile(const std::string&name){return std::remove(name.c_str());}
int renameFile(const std::string&from,const std::string&to){return std::rename(from.c_str(),to.c_str());}
#endif

struct Audio {
  uint32_t rate = 0;
  int channels = 0;
  size_t frames = 0;
  size_t totalFrames = 0;
  std::vector<std::vector<float>> planes;
};

uint16_t u16(const unsigned char *p) { return uint16_t(p[0]) | (uint16_t(p[1]) << 8); }
uint32_t u32(const unsigned char *p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
                                           (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }
void write16(FILE* f, uint16_t v) { unsigned char b[]{(unsigned char)v,(unsigned char)(v>>8)}; fwrite(b,1,2,f); }
void write32(FILE* f, uint32_t v) { unsigned char b[]{(unsigned char)v,(unsigned char)(v>>8),(unsigned char)(v>>16),(unsigned char)(v>>24)}; fwrite(b,1,4,f); }

class FileHandle {
 public:
  explicit FileHandle(const std::string& name, const char* mode): f_(openFile(name, mode)) {
    if (!f_) throw std::runtime_error("Cannot open WAV file: " + name);
  }
  ~FileHandle() { if (f_) fclose(f_); }
  FILE* get() const { return f_; }
  FileHandle(const FileHandle&) = delete;
  FileHandle& operator=(const FileHandle&) = delete;
 private: FILE* f_;
};

Audio loadWav(const std::string& name, size_t startFrame, size_t requestedFrames) {
  FileHandle file(name, "rb"); FILE* f = file.get();
  unsigned char riff[12];
  if (fread(riff,1,12,f)!=12 || memcmp(riff,"RIFF",4)!=0 || memcmp(riff+8,"WAVE",4)!=0)
    throw std::runtime_error("Only standard RIFF/WAVE input is supported in this test build");
  uint16_t format=0, channels=0, bits=0, blockAlign=0;
  uint32_t rate=0, dataBytes=0;
  int64_t dataAt=-1;
  for(int i=0;i<4096;i++) {
    unsigned char chunk[8];
    if(fread(chunk,1,8,f)!=8) break;
    uint32_t length=u32(chunk+4);
    int64_t start=ftell(f);
    if(start<0 || uint64_t(length)>uint64_t(std::numeric_limits<int64_t>::max()-start-1))
      throw std::runtime_error("WAV chunk exceeds supported file size");
    if(memcmp(chunk,"fmt ",4)==0) {
      if(length<16 || length>256) throw std::runtime_error("Invalid WAV format chunk");
      unsigned char fmt[256]{};
      if(fread(fmt,1,length,f)!=length) throw std::runtime_error("Truncated WAV format chunk");
      format=u16(fmt); channels=u16(fmt+2); rate=u32(fmt+4);
      blockAlign=u16(fmt+12); bits=u16(fmt+14);
      // WAVE_FORMAT_EXTENSIBLE: the first two GUID bytes identify PCM/IEEE float.
      if(format==0xfffe && length>=40) format=u16(fmt+24);
    } else if(memcmp(chunk,"data",4)==0) {
      dataAt=start; dataBytes=length;
      if(format!=0) break;
    }
    int64_t next=start+static_cast<int64_t>(length)+(length&1U);
    if(fseek(f,next,SEEK_SET)!=0) throw std::runtime_error("Cannot seek WAV chunk");
  }
  if(dataAt<0 || !dataBytes || !channels || !rate) throw std::runtime_error("WAV fmt/data missing");
  if(channels!=1 && channels!=2) throw std::runtime_error("Only mono or stereo WAV is supported");
  if(rate!=44100 && rate!=48000) throw std::runtime_error("Only 44.1 or 48 kHz WAV is supported");
  if(!((format==1 && (bits==16 || bits==24 || bits==32)) || (format==3 && bits==32)))
    throw std::runtime_error("Supported WAV: PCM 16/24/32 or float32 only");
  const int bytes=bits/8;
  if(blockAlign!=channels*bytes || dataBytes%blockAlign!=0)
    throw std::runtime_error("Invalid WAV block alignment");
  const size_t totalFrames=dataBytes/blockAlign;
  if(totalFrames==0 || totalFrames>size_t(rate)*20*60)
    throw std::runtime_error("The song must be under 20 minutes");

  if(startFrame>totalFrames || requestedFrames>totalFrames-startFrame)
    throw std::runtime_error("Selected source frames exceed WAV length");
  Audio audio; audio.rate=rate; audio.channels=2; audio.frames=requestedFrames;
  audio.totalFrames=totalFrames;
  if(requestedFrames==0)return audio;
  if(fseek(f,dataAt+int64_t(startFrame*blockAlign),SEEK_SET)!=0)
    throw std::runtime_error("Cannot seek WAV section");
  const size_t frames=requestedFrames;
  audio.planes.assign(2,std::vector<float>(frames));
  unsigned char b[8];
  for(size_t n=0;n<frames;n++) {
    for(int ch=0;ch<channels;ch++) {
      if(fread(b,1,bytes,f)!=size_t(bytes)) throw std::runtime_error("WAV sample data is truncated");
      float x=0;
      if(format==3) { uint32_t bits32=u32(b); memcpy(&x,&bits32,4); }
      else if(bits==16) x=static_cast<int16_t>(u16(b))/32768.f;
      else if(bits==24) {
        int32_t val=int32_t(b[0]) | (int32_t(b[1])<<8) | (int32_t(b[2])<<16);
        if(val & 0x800000) val |= ~0xffffff;
        x=float(val)/8388608.f;
      } else x=static_cast<int32_t>(u32(b))/2147483648.f;
      if(!std::isfinite(x)) throw std::runtime_error("WAV contains non-finite float sample");
      audio.planes[ch][n]=x;
    }
    if(channels==1) audio.planes[1][n]=audio.planes[0][n];
  }
  return audio;
}


constexpr uint32_t MODEL_RATE=44100,OUT_RATE=48000;
// The model takes exactly 7.8 seconds. Sections overlap by a quarter, as Demucs does by default.
constexpr size_t SEGMENT=343980,STRIDE=SEGMENT*3/4;
constexpr int STEMS=6,OTHER=3;
const char*const NAMES[STEMS]{"Vocals","Drums","Bass","Other","Guitar","Piano"};
// Which of the model's outputs (drums, bass, other, vocals, guitar, piano) fills each stem.
constexpr int FROM_MODEL[STEMS]{3,0,1,-1,4,5};

std::atomic<bool> busy{false};
std::atomic<bool> cancelled{false};
std::atomic<bool> stopAsked{false};  // set only by the app; survives the run's own reset
void checkCancel(){if(cancelled.load())throw std::runtime_error("Processing cancelled between sections");}

// Changes sample rate by a fixed ratio with a windowed-sinc filter. No EQ or level change.
class RateChange {
public:
  RateChange(int up,int down):up_(up),down_(down),table_(size_t(up)*TAPS){
    constexpr double pi=3.1415926535897932384626433832795;
    const double band=0.995*std::min(1.0,double(up)/down);
    for(int p=0;p<up;p++){
      const double frac=double(p)/up;
      double sum=0;
      for(int t=0;t<TAPS;t++){
        const double x=double(t-31)-frac;
        const double sinc=std::abs(x)<1e-12?band:std::sin(pi*band*x)/(pi*x);
        const double window=0.42+0.5*std::cos(pi*x/32.0)+0.08*std::cos(2.0*pi*x/32.0);
        table_[size_t(p)*TAPS+t]=float(sinc*window);
        sum+=sinc*window;
      }
      for(int t=0;t<TAPS;t++)table_[size_t(p)*TAPS+t]=float(table_[size_t(p)*TAPS+t]/sum);
    }
  }
  std::vector<float> run(const std::vector<float>&in,size_t frames)const{
    std::vector<float> out(frames);
    const int64_t n=int64_t(in.size());
    for(size_t i=0;i<frames;i++){
      if((i&65535)==0)checkCancel();
      const uint64_t place=uint64_t(i)*down_;
      const int64_t first=int64_t(place/up_)-31;
      const float*w=&table_[size_t(place%up_)*TAPS];
      if(first>=0&&first+TAPS<=n){
        const float*x=in.data()+first;
        float value=0;
        for(int t=0;t<TAPS;t++)value+=w[t]*x[t];
        out[i]=value;
      }else{
        double value=0,weight=0;
        for(int t=0;t<TAPS;t++){
          const int64_t at=first+t;
          if(at<0||at>=n)continue;
          value+=w[t]*in[size_t(at)];
          weight+=w[t];
        }
        out[i]=std::abs(weight)<1e-8?0.f:float(value/weight);
      }
    }
    return out;
  }
private:
  static constexpr int TAPS=64;
  int up_,down_;
  std::vector<float> table_;
};

// One loaded model. The memory arena and pattern stay off, for the reason found in the four-stem
// build: left on, the runtime holds on to all its working memory and adds more.
std::string deviceName;

class Model {
public:
  // useGpu: DirectML on the first graphics card (the CPU runs any part the card cannot).
  Model(const std::string&path,int threads,bool useGpu):api(OrtGetApiBase()->GetApi(ORT_API_VERSION)){
    if(!api)throw std::runtime_error("The AI runtime is the wrong version");
    check(api->CreateEnv(ORT_LOGGING_LEVEL_ERROR,"six-stem",&env));
    check(api->CreateSessionOptions(&options));
    check(api->SetIntraOpNumThreads(options,threads));
    check(api->SetInterOpNumThreads(options,1));
    check(api->SetSessionExecutionMode(options,ORT_SEQUENTIAL));
    check(api->DisableCpuMemArena(options));
    check(api->DisableMemPattern(options));
    // On the phone any graph optimization made loading peak at 6.1 GB. A computer has room for the
    // basic level (about 30% faster per section on the processor); the full level is no faster here.
    check(api->SetSessionGraphOptimizationLevel(options,useGpu?ORT_ENABLE_ALL:ORT_ENABLE_BASIC));
#ifdef STEM_DIRECTML
    if(useGpu)check(OrtSessionOptionsAppendExecutionProvider_DML(options,0));
#else
    if(useGpu)throw std::runtime_error("This engine was built without graphics card support");
#endif
#ifdef _WIN32
    check(api->CreateSession(env,wide(path).c_str(),options,&session));
#else
    check(api->CreateSession(env,path.c_str(),options,&session));
#endif
    check(api->CreateCpuMemoryInfo(OrtDeviceAllocator,OrtMemTypeDefault,&memory));
    OrtAllocator*allocator=nullptr;
    check(api->GetAllocatorWithDefaultOptions(&allocator));
    char*in=nullptr,*out=nullptr;
    check(api->SessionGetInputName(session,0,allocator,&in));
    check(api->SessionGetOutputName(session,0,allocator,&out));
    inputName=in;outputName=out;
    check(api->AllocatorFree(allocator,in));
    check(api->AllocatorFree(allocator,out));
  }
  ~Model(){
    if(memory)api->ReleaseMemoryInfo(memory);
    if(session)api->ReleaseSession(session);
    if(options)api->ReleaseSessionOptions(options);
    if(env)api->ReleaseEnv(env);
  }
  Model(const Model&)=delete;
  Model&operator=(const Model&)=delete;
  // Takes planar stereo, returns six planar stereo stems. The result stays valid until the next run.
  const float*run(std::vector<float>&stereo){
    if(output){api->ReleaseValue(output);output=nullptr;}
    const int64_t shape[]{1,2,int64_t(SEGMENT)};
    OrtValue*input=nullptr;
    check(api->CreateTensorWithDataAsOrtValue(memory,stereo.data(),stereo.size()*sizeof(float),shape,3,
       ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input));
    const char*in=inputName.c_str(),*out=outputName.c_str();
    const OrtValue*inputs[]{input};
    OrtStatus*status=api->Run(session,nullptr,&in,inputs,1,&out,1,&output);
    api->ReleaseValue(input);
    check(status);
    OrtTensorTypeAndShapeInfo*info=nullptr;
    check(api->GetTensorTypeAndShape(output,&info));
    size_t count=0;
    OrtStatus*sized=api->GetTensorShapeElementCount(info,&count);
    api->ReleaseTensorTypeAndShapeInfo(info);
    check(sized);
    if(count!=SEGMENT*2*STEMS)throw std::runtime_error("This is not the six-stem model");
    float*data=nullptr;
    check(api->GetTensorMutableData(output,reinterpret_cast<void**>(&data)));
    return data;
  }
  void finish(){if(output){api->ReleaseValue(output);output=nullptr;}}
private:
  void check(OrtStatus*status){
    if(!status)return;
    const std::string message=api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    throw std::runtime_error("AI runtime: "+message);
  }
  const OrtApi*api;
  OrtEnv*env=nullptr;
  OrtSessionOptions*options=nullptr;
  OrtSession*session=nullptr;
  OrtMemoryInfo*memory=nullptr;
  OrtValue*output=nullptr;
  std::string inputName,outputName;
};

long peakMegabytes(){
#ifdef _WIN32
  PROCESS_MEMORY_COUNTERS pmc{};
  if(GetProcessMemoryInfo(GetCurrentProcess(),&pmc,sizeof pmc))return long(pmc.PeakWorkingSetSize/1048576);
  return 0;
#endif
  FILE*f=fopen("/proc/self/status","r");
  if(!f)return 0;
  char line[256];long kb=0;
  while(fgets(line,sizeof line,f))if(sscanf(line,"VmHWM: %ld",&kb)==1)break;
  fclose(f);
  return kb/1024;
}
void progress(int phase,int chunk,int total){
  static const char*const STAGES[]{"section","resample","write","done"};
  if(phase==0)printf("SECTION %d %d\n",chunk,total);
  else printf("STAGE %s %d %d\n",STAGES[phase],chunk,total);
  fflush(stdout);
}

// Temporary files that always go, however the run ends.
struct Scratch {
  std::array<std::string,STEMS> model{},loud{},partial{},finals{};
  Scratch(const std::string&folder,const std::string&prefix){
    for(int i=0;i<STEMS;i++){
      const std::string base=folder+"/"+prefix+"_"+NAMES[i];
      model[i]=base+".modelpart";loud[i]=base+".floatpart";
      finals[i]=base+"_24bit.wav";partial[i]=finals[i]+".partial";
    }
  }
  ~Scratch(){
    for(auto*list:{&model,&loud,&partial})for(auto&p:*list)if(!p.empty())removeFile(p);
  }
};

void writeHeader(FILE*f,size_t frames){
  const uint64_t bytes=uint64_t(frames)*6;
  if(bytes>0xffffff00ULL)throw std::runtime_error("WAV exceeds classic RIFF maximum");
  if(fwrite("RIFF",1,4,f)!=4)throw std::runtime_error("WAV header failure");
  write32(f,uint32_t(36+bytes));
  if(fwrite("WAVEfmt ",1,8,f)!=8)throw std::runtime_error("WAV header failure");
  write32(f,16);write16(f,1);write16(f,2);
  write32(f,OUT_RATE);write32(f,OUT_RATE*6);write16(f,6);write16(f,24);
  if(fwrite("data",1,4,f)!=4)throw std::runtime_error("WAV header failure");
  write32(f,uint32_t(bytes));
}
void readAll(const std::string&path,float*left,float*right,size_t n){
  FileHandle in(path,"rb");
  if(fread(left,sizeof(float),n,in.get())!=n||fread(right,sizeof(float),n,in.get())!=n)
    throw std::runtime_error("Temporary stem is incomplete");
}

std::array<std::string,STEMS+1> extract(const std::string&modelPath,const std::string&wav,
  const std::string&folder,const std::string&prefix,int start,int duration,int threads,bool useGpu){
  if(busy.exchange(true))throw std::runtime_error("Already separating");
  struct Guard{~Guard(){busy.store(false);cancelled.store(false);}}guard;
  cancelled.store(false);
  if(start<0||start>1200||(duration!=0&&duration!=10&&duration!=60))
    throw std::runtime_error("Unsupported preview duration/position");
  if(threads<1||threads>64)throw std::runtime_error("Unsupported thread count");
  const Audio info=loadWav(wav,0,0);
  const size_t rate=info.rate,total=info.totalFrames;
  const size_t preview=size_t(duration)*rate;
  const size_t offset=duration==0?0:std::min(size_t(start)*rate,total>preview?total-preview:0);
  const size_t selected=duration==0?total:std::min(preview,total-offset);
  const size_t frames=(uint64_t(selected)*MODEL_RATE+rate/2)/rate;
  const size_t outFrames=(uint64_t(selected)*OUT_RATE+rate/2)/rate;
  if(outFrames==0)throw std::runtime_error("No audio to separate");
  // Six final WAVs, plus their scratch copies at both rates for the stem in hand.
  const uint64_t needed=uint64_t(outFrames)*(48+40)+150'000'000;
#ifdef _WIN32
  ULARGE_INTEGER freeBytes{};
  if(!GetDiskFreeSpaceExW(wide(folder).c_str(),&freeBytes,nullptr,nullptr))throw std::runtime_error("Cannot check free space");
  const uint64_t available=freeBytes.QuadPart;
#else
  struct statvfs fs{};
  if(statvfs(folder.c_str(),&fs)!=0)throw std::runtime_error("Cannot check free space");
  const uint64_t available=uint64_t(fs.f_bavail)*fs.f_frsize;
#endif
  if(available<needed)
    throw std::runtime_error("Storage low: need "+std::to_string((needed+999999)/1000000)+" MB free");

  // The whole song at the model's rate. Only its statistics and one section are used at a time.
  std::vector<float> mixL,mixR;
  {
    Audio input=loadWav(wav,offset,selected);
    if(rate==MODEL_RATE){mixL=std::move(input.planes[0]);mixR=std::move(input.planes[1]);}
    else{
      const RateChange down(147,160);
      mixL=down.run(input.planes[0],frames);
      input.planes[0]=std::vector<float>();
      mixR=down.run(input.planes[1],frames);
    }
  }
  // Demucs scales the song as a whole first, and scales the stems back afterwards.
  double mean=0,square=0;
  for(size_t i=0;i<frames;i++){const double m=0.5*(mixL[i]+mixR[i]);mean+=m;square+=m*m;}
  mean/=double(frames);
  const double deviation=std::sqrt(std::max(1e-10,square/double(frames)-mean*mean));

  Scratch scratch(folder,prefix);
  const int count=int((frames+STRIDE-1)/STRIDE);
  auto began=std::chrono::steady_clock::now();
  {
    std::array<FILE*,STEMS> files{};
    struct Closer{std::array<FILE*,STEMS>&f;~Closer(){for(auto*x:f)if(x)fclose(x);}}closer{files};
    for(int s=0;s<STEMS;s++){
      if(FROM_MODEL[s]<0)continue;
      files[s]=openFile(scratch.model[s],"wb");
      if(!files[s])throw std::runtime_error("Cannot create temporary stem");
    }
    Model model(modelPath,threads,useGpu);
    std::vector<float> weight(SEGMENT),sum(SEGMENT,0.f),in(SEGMENT*2);
    for(size_t i=0;i<SEGMENT;i++)weight[i]=float(std::min(i+1,SEGMENT-i));
    std::array<std::vector<float>,STEMS*2> held;
    for(int s=0;s<STEMS;s++)if(FROM_MODEL[s]>=0)held[2*s].assign(SEGMENT,0.f),held[2*s+1].assign(SEGMENT,0.f);
    std::vector<float> line(STRIDE);
    size_t written=0;
    for(int idx=0;idx<count;idx++){
      checkCancel();
      progress(0,idx,count);
      const size_t from=size_t(idx)*STRIDE;
      for(size_t i=0;i<SEGMENT;i++){
        const bool inside=from+i<frames;
        in[i]=inside?float((mixL[from+i]-mean)/deviation):0.f;
        in[SEGMENT+i]=inside?float((mixR[from+i]-mean)/deviation):0.f;
      }
      const float*out=model.run(in);
      for(int s=0;s<STEMS;s++){
        if(FROM_MODEL[s]<0)continue;
        for(int c=0;c<2;c++){
          const float*source=out+size_t(FROM_MODEL[s])*2*SEGMENT+size_t(c)*SEGMENT;
          float*into=held[2*s+c].data();
          for(size_t i=0;i<SEGMENT;i++)into[i]+=source[i]*weight[i];
        }
      }
      for(size_t i=0;i<SEGMENT;i++)sum[i]+=weight[i];
      // Everything before the next section's start is final, so it can leave memory now.
      const size_t ready=std::min(STRIDE,frames-written);
      for(int s=0;s<STEMS;s++){
        if(FROM_MODEL[s]<0)continue;
        for(int c=0;c<2;c++){
          for(size_t i=0;i<ready;i++){
            const float x=float(double(held[2*s+c][i])/sum[i]*deviation+mean);
            if(!std::isfinite(x))throw std::runtime_error("Invalid stem sample");
            line[i]=x;
          }
          // Left then right would need the file rewound, so each stem keeps two runs per section.
          if(fwrite(line.data(),sizeof(float),ready,files[s])!=ready)throw std::runtime_error("Scratch storage full");
          auto&h=held[2*s+c];
          std::copy(h.begin()+STRIDE,h.end(),h.begin());
          std::fill(h.end()-STRIDE,h.end(),0.f);
        }
      }
      std::copy(sum.begin()+STRIDE,sum.end(),sum.begin());
      std::fill(sum.end()-STRIDE,sum.end(),0.f);
      written+=ready;
    }
    model.finish();
    if(written!=frames)throw std::runtime_error("Assembled frame count mismatch");
    for(auto&f:files)if(f){
      if(fflush(f)!=0||ferror(f))throw std::runtime_error("Stem scratch flush failed");
      if(fclose(f)!=0)throw std::runtime_error("Stem scratch close failed");
      f=nullptr;
    }
  }
  const double modelSeconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-began).count();

  // Back to the output rate, one stem at a time. Other is whatever the rest did not take.
  const RateChange up(160,147);
  double peak=0;
  std::vector<float> left(frames),right(frames);
  for(int order=0;order<STEMS;order++){
    checkCancel();
    progress(1,order,STEMS);
    const int s=order<OTHER?order:order<STEMS-1?order+1:OTHER;
    if(s==OTHER){left.swap(mixL);right.swap(mixR);}
    else{
      // Each section wrote left then right, so the two are unpicked here.
      FileHandle in(scratch.model[s],"rb");
      size_t done=0;
      while(done<frames){
        const size_t ready=std::min(STRIDE,frames-done);
        if(fread(left.data()+done,sizeof(float),ready,in.get())!=ready||fread(right.data()+done,sizeof(float),ready,in.get())!=ready)
          throw std::runtime_error("Temporary stem is incomplete");
        done+=ready;
      }
      for(size_t i=0;i<frames;i++){mixL[i]-=left[i];mixR[i]-=right[i];}
    }
    removeFile(scratch.model[s]);
    const std::vector<float> outL=up.run(left,outFrames),outR=up.run(right,outFrames);
    FileHandle out(scratch.loud[s],"wb");
    if(fwrite(outL.data(),sizeof(float),outFrames,out.get())!=outFrames||fwrite(outR.data(),sizeof(float),outFrames,out.get())!=outFrames)
      throw std::runtime_error("Scratch storage full");
    for(size_t i=0;i<outFrames;i++)peak=std::max(peak,std::max(std::abs(double(outL[i])),std::abs(double(outR[i]))));
  }
  std::vector<float>().swap(mixL);std::vector<float>().swap(mixR);

  // A shared gain keeps the stems summing to the song if any stem would clip.
  const double gain=peak>0.9999?0.9999/peak:1.;
  left.resize(outFrames);right.resize(outFrames);
  std::vector<uint8_t> pcm(size_t(8192)*6);
  for(int s=0;s<STEMS;s++){
    checkCancel();
    progress(2,s,STEMS);
    readAll(scratch.loud[s],left.data(),right.data(),outFrames);
    removeFile(scratch.loud[s]);
    FileHandle out(scratch.partial[s],"wb");
    writeHeader(out.get(),outFrames);
    for(size_t done=0;done<outFrames;){
      const size_t n=std::min(size_t(8192),outFrames-done);
      for(size_t i=0;i<n;i++){
        for(int c=0;c<2;c++){
          const double x=std::max(-1.,std::min(0.99999988079,double(c?right[done+i]:left[done+i])*gain));
          const int32_t val=int32_t(std::llrint(x*8388608.));
          uint8_t*p=pcm.data()+6*i+3*c;
          p[0]=uint8_t(val);p[1]=uint8_t(val>>8);p[2]=uint8_t(val>>16);
        }
      }
      if(fwrite(pcm.data(),1,6*n,out.get())!=6*n)throw std::runtime_error("24-bit WAV disk write error");
      done+=n;
    }
    if(fflush(out.get())!=0||ferror(out.get()))throw std::runtime_error("WAV flush error");
  }
  for(int s=0;s<STEMS;s++){
    if(renameFile(scratch.partial[s],scratch.finals[s])!=0)
      throw std::runtime_error("Cannot finalize stem WAV");
    scratch.partial[s].clear();
  }
  progress(3,STEMS,STEMS);
  std::array<std::string,STEMS+1> result{};
  for(int s=0;s<STEMS;s++)result[s]=scratch.finals[s];
  char summary[400];
  snprintf(summary,sizeof summary,"%s|%d|%s|%d|%.1f|%ld|%.2f",duration==0?"song":duration==60?"60s":"10s",count,
     deviceName.c_str(),threads,modelSeconds,peakMegabytes(),20*std::log10(gain));
  result[STEMS]=summary;
  return result;
}

}

namespace {
const char*arg(int argc,char**argv,const char*name,const char*fallback){
  for(int i=1;i+1<argc;i++)if(strcmp(argv[i],name)==0)return argv[i+1];
  return fallback;
}

int run(int argc,char**argv){
  const std::string model=arg(argc,argv,"--model",""),in=arg(argc,argv,"--in",""),out=arg(argc,argv,"--out",""),
     prefix=arg(argc,argv,"--prefix","song"),device=arg(argc,argv,"--device","auto");
  const int start=atoi(arg(argc,argv,"--start","0")),seconds=atoi(arg(argc,argv,"--seconds","0"));
  const int cores=int(std::max(1u,std::thread::hardware_concurrency()));
  const int threads=atoi(arg(argc,argv,"--threads",std::to_string(std::max(1,std::min(cores-1,16))).c_str()));
  if(model.empty()||in.empty()||out.empty()){
    fprintf(stderr,"usage: stemengine --model M.onnx --in song.wav --out FOLDER --prefix NAME [--device auto|gpu|cpu]\n");
    return 2;
  }
  // With --watch-stdin (the app), "cancel" on standard input, or the app closing, stops the run at
  // the next section.
  bool watch=false;
  for(int i=1;i<argc;i++)watch|=strcmp(argv[i],"--watch-stdin")==0;
  if(watch)std::thread([]{
    std::string line;
    while(std::getline(std::cin,line))if(line.rfind("cancel",0)==0)break;
    stopAsked.store(true);
    cancelled.store(true);
  }).detach();
  std::vector<bool> tries;
#ifdef STEM_DIRECTML
  if(device!="cpu")tries.push_back(true);
#else
  if(device=="gpu")tries.push_back(true);
#endif
  if(device!="gpu")tries.push_back(false);
  std::string lastError;
  for(const bool gpu:tries){
    deviceName=gpu?"gpu":"cpu";
    printf("DEVICE %s\n",deviceName.c_str());fflush(stdout);
    try{
      const auto results=extract(model,in,out,prefix,start,seconds,threads,gpu);
      for(int s=0;s<STEMS;s++)printf("STEM %s %s\n",NAMES[s],results[s].c_str());
      printf("DONE %s\n",results[STEMS].c_str());
      fflush(stdout);
      return 0;
    }catch(const std::exception&e){
      lastError=e.what();
      if(stopAsked.load())break;
      // The graphics card could not run it (no DirectX 12 card, driver, memory): try the processor.
      if(gpu&&tries.size()>1){printf("FALLBACK %s\n",lastError.c_str());fflush(stdout);continue;}
    }
  }
  printf("ERROR %s\n",stopAsked.load()?"Cancelled":lastError.c_str());
  fflush(stdout);
  return 1;
}
}

#ifdef _WIN32
int wmain(int argc,wchar_t**wargv){
  std::vector<std::string> keep;
  std::vector<char*> argv;
  for(int i=0;i<argc;i++)keep.push_back(utf8(wargv[i]));
  for(auto&k:keep)argv.push_back(&k[0]);
  SetConsoleOutputCP(CP_UTF8);
  return run(argc,argv.data());
}
#else
int main(int argc,char**argv){return run(argc,argv);}
#endif
