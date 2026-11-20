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
#include <condition_variable>
#include <map>
#include <deque>
#include <mutex>
#include <functional>
#include <complex>
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
#include <cstdlib>
#include <cstdarg>
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
#include <dxgi1_6.h>
#endif

int adapterIndex();  // below: the graphics card's adapter number

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
int peakColumns=0;  // --peaks N: after each section, the stems' overview so far (the app fills its lanes live)
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
    runRange(in.data(),int64_t(in.size()),0,frames,out.data());
    return out;
  }
  /** Output samples [from, to) of the whole conversion of in[0..n), exactly as run() computes them;
      out points at the place for sample `from`. */
  void runRange(const float*in,int64_t n,size_t from,size_t to,float*out)const{
    for(size_t i=from;i<to;i++){
      if((i&65535)==0)checkCancel();
      const uint64_t place=uint64_t(i)*down_;
      const int64_t first=int64_t(place/up_)-31;
      const float*w=&table_[size_t(place%up_)*TAPS];
      if(first>=0&&first+TAPS<=n){
        const float*x=in+first;
        float value=0;
        for(int t=0;t<TAPS;t++)value+=w[t]*x[t];
        out[i-from]=value;
      }else{
        double value=0,weight=0;
        for(int t=0;t<TAPS;t++){
          const int64_t at=first+t;
          if(at<0||at>=n)continue;
          value+=w[t]*in[size_t(at)];
          weight+=w[t];
        }
        out[i-from]=std::abs(weight)<1e-8?0.f:float(value/weight);
      }
    }
  }
  /** The input sample an output's filter starts at (it reads 64 from there). */
  int64_t firstInput(size_t i)const{return int64_t(uint64_t(i)*down_/up_)-31;}
  static constexpr int taps(){return TAPS;}
private:
  static constexpr int TAPS=64;
  int up_,down_;
  std::vector<float> table_;
};

// One loaded model. The memory arena and pattern stay off, for the reason found in the four-stem
// build: left on, the runtime holds on to all its working memory and adds more.
std::string deviceName;
std::string profilePrefix;   // --profile: ONNX Runtime writes a per-node timing file (which device ran each step)
std::string tailPath;        // --tail: the model split in two; the tail (inverse STFT onwards) runs on the processor
int tailThreads=4;
bool nativeIstft=false;      // --native-istft: the tail done by the FFT code below instead of the model's tail
void parallelFor(int n,int threads,const std::function<void(int)>&f);

/**
 * The model's tail done natively: HT-Demucs's inverse STFT (_ispec: n_fft 4096, hop 1024, periodic Hann,
 * normalized, centred, cropped by 1536 samples) and the time branch added on. Checked against the model's
 * own tail in CI; the model's tail stores its transform tables in half precision, so this is the more exact
 * of the two (they differ by about 1.5e-4 of full scale at most). Input: the spectrum (1, 6, 2, 2, 2049, T:
 * stem, channel, real/imaginary, bin, frame) and the time branch (1, 6, 2, length).
 */
class Istft {
public:
  static constexpr int N=4096,HOP=1024;
  Istft():win_(N),tw_(N/2),rev_(N){
    const double pi=3.14159265358979323846;
    for(int n=0;n<N;n++)win_[n]=0.5-0.5*std::cos(2*pi*n/N);
    for(int k=0;k<N/2;k++)tw_[k]=std::polar(1.0,2*pi*k/N);   // e^{+i}: the inverse transform
    int bits=0;while((1<<bits)<N)bits++;
    for(int i=0;i<N;i++){int r=0;for(int b=0;b<bits;b++)if(i&(1<<b))r|=1<<(bits-1-b);rev_[i]=r;}
  }
  /** Writes the six stems (planar stereo, `length` samples each) to out. */
  void run(const float*z,int64_t frames,const float*xt,int64_t length,float*out,int threads)const{
    const int64_t F=N/2+1,T=frames,total=N+int64_t(HOP)*(T-1),le=int64_t(HOP)*(T-1),pad=HOP/2*3;
    if(pad+length>le)throw std::runtime_error("The split model's spectrum is shorter than its section");
    // The window's overlap (the same for every stem): divided out after the overlap-add, as torch.istft does.
    std::vector<double> inv(size_t(total),0.0);
    for(int64_t t=0;t<T;t++)for(int n=0;n<N;n++)inv[size_t(t*HOP+n)]+=win_[n]*win_[n];
    for(auto&v:inv)v=v>1e-11?1.0/v:1.0;
    const double scale=std::sqrt(double(N))/N;   // normalized=True, and the inverse transform's 1/N
    parallelFor(STEMS*2,threads,[&](int j){
      const float*re=z+size_t(j)*2*F*T,*im=re+F*T;
      std::vector<double> y(size_t(total),0.0);
      std::vector<std::complex<double>> a(N);
      // Two frames per transform: both outputs are real, so frame t goes in the real part and t+1 in the imaginary.
      for(int64_t t=0;t<T;t+=2){
        const bool two=t+1<T;
        for(int64_t k=0;k<=N/2;k++){
          const bool edge=k==0||k==N/2;   // the DC and Nyquist bins' imaginary parts are ignored, as irfft does
          const std::complex<double> x1(re[k*T+t],edge?0.0:im[k*T+t]);
          const std::complex<double> x2=two?std::complex<double>(re[k*T+t+1],edge?0.0:im[k*T+t+1]):0.0;
          const std::complex<double> i(0,1);
          a[size_t(k)]=x1+i*x2;
          if(!edge)a[size_t(N-k)]=std::conj(x1)+i*std::conj(x2);
        }
        fft(a);
        double*y1=y.data()+t*HOP;
        for(int n=0;n<N;n++)y1[n]+=a[size_t(n)].real()*scale*win_[n];
        if(two){double*y2=y1+HOP;for(int n=0;n<N;n++)y2[n]+=a[size_t(n)].imag()*scale*win_[n];}
      }
      float*o=out+size_t(j)*length;
      const float*x=xt+size_t(j)*length;
      for(int64_t i=0;i<length;i++){const size_t at=size_t(N/2+pad+i);o[i]=float(y[at]*inv[at]+x[i]);}
    });
  }
private:
  std::vector<double> win_;
  std::vector<std::complex<double>> tw_;
  std::vector<int> rev_;
  void fft(std::vector<std::complex<double>>&a)const{   // in place, radix 2, e^{+i}, not scaled
    for(int i=0;i<N;i++)if(i<rev_[i])std::swap(a[size_t(i)],a[size_t(rev_[i])]);
    for(int len=2;len<=N;len<<=1){
      const int half=len/2,step=N/len;
      for(int i=0;i<N;i+=len)for(int k=0;k<half;k++){
        const std::complex<double> u=a[size_t(i+k)],v=a[size_t(i+k+half)]*tw_[size_t(k*step)];
        a[size_t(i+k)]=u+v;a[size_t(i+k+half)]=u-v;
      }
    }
  }
};

class Model {
public:
  // gpu: 0 the processor; 2 DirectML on the high-performance card, fully optimised; 3 the same card with
  // DirectML's graph fusion off (each step runs on its own and working memory is reused between steps,
  // instead of the whole model as one job that reserves all of it at once: the way to fit a 4 GB card);
  // 1 DirectML on that card by its adapter number, basic optimisation. The processor runs any part the
  // card cannot.
  Model(const std::string&path,int threads,int gpu):api(OrtGetApiBase()->GetApi(ORT_API_VERSION)){
    const bool useGpu=gpu>0;
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
    check(api->SetSessionGraphOptimizationLevel(options,gpu==2||gpu==3?ORT_ENABLE_ALL:ORT_ENABLE_BASIC));
    if(gpu==3)check(api->AddSessionConfigEntry(options,"ep.dml.disable_graph_fusion","1"));
    if(!profilePrefix.empty()){
#ifdef _WIN32
      check(api->EnableProfiling(options,wide(profilePrefix).c_str()));
#else
      check(api->EnableProfiling(options,profilePrefix.c_str()));
#endif
      profiling=true;
    }
#ifdef STEM_DIRECTML
    if(useGpu){
      // The fastest card (on a laptop, the separate graphics card rather than the one in the processor).
      const OrtDmlApi*dml=nullptr;
      check(api->GetExecutionProviderApi("DML",ORT_API_VERSION,reinterpret_cast<const void**>(&dml)));
      if(gpu==2||gpu==3){
        OrtDmlDeviceOptions choice{};
        choice.Preference=OrtDmlPerformancePreference::HighPerformance;
        choice.Filter=OrtDmlDeviceFilter::Gpu;
        check(dml->SessionOptionsAppendExecutionProvider_DML2(options,&choice));
      }else check(dml->SessionOptionsAppendExecutionProvider_DML(options,adapterIndex()));
    }
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
    char*in=nullptr;
    check(api->SessionGetInputName(session,0,allocator,&in));
    inputName=in;
    check(api->AllocatorFree(allocator,in));
    size_t outs=0;
    check(api->SessionGetOutputCount(session,&outs));
    for(size_t i=0;i<outs;i++){
      char*o=nullptr;check(api->SessionGetOutputName(session,i,allocator,&o));headOutputs.push_back(o);check(api->AllocatorFree(allocator,o));
    }
    outputName=headOutputs.at(0);
    if(!tailPath.empty()){
      // The tail always runs on the processor, with its own threads.
      check(api->CreateSessionOptions(&tailOptions));
      check(api->SetIntraOpNumThreads(tailOptions,tailThreads));
      check(api->SetInterOpNumThreads(tailOptions,1));
      check(api->SetSessionExecutionMode(tailOptions,ORT_SEQUENTIAL));
      check(api->SetSessionGraphOptimizationLevel(tailOptions,ORT_ENABLE_BASIC));
#ifdef _WIN32
      check(api->CreateSession(env,wide(tailPath).c_str(),tailOptions,&tailSession));
#else
      check(api->CreateSession(env,tailPath.c_str(),tailOptions,&tailSession));
#endif
      size_t ins=0;
      check(api->SessionGetInputCount(tailSession,&ins));
      for(size_t i=0;i<ins;i++){
        char*t=nullptr;check(api->SessionGetInputName(tailSession,i,allocator,&t));tailInputs.push_back(t);check(api->AllocatorFree(allocator,t));
      }
      char*o=nullptr;check(api->SessionGetOutputName(tailSession,0,allocator,&o));outputName=o;check(api->AllocatorFree(allocator,o));
    }
  }
  bool split()const{return tailSession!=nullptr;}
  /** What the head hands the tail for one section (owned; give it to tail()). */
  struct Handoff{std::vector<OrtValue*> values;std::vector<float> input;double headSeconds=0;};
  Handoff head(const std::vector<float>&stereo){
    Handoff h;
    h.input=stereo;
    const int64_t shape[]{1,2,int64_t(SEGMENT)};
    OrtValue*input=nullptr;
    check(api->CreateTensorWithDataAsOrtValue(memory,h.input.data(),h.input.size()*sizeof(float),shape,3,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input));
    std::vector<const char*> names;for(auto&n:headOutputs)names.push_back(n.c_str());
    h.values.assign(names.size(),nullptr);
    const char*in=inputName.c_str();
    const OrtValue*inputs[]{input};
    const auto t=std::chrono::steady_clock::now();
    OrtStatus*status=api->Run(session,nullptr,&in,inputs,1,names.data(),names.size(),h.values.data());
    h.headSeconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
    api->ReleaseValue(input);
    if(status){for(auto*v:h.values)if(v)api->ReleaseValue(v);check(status);}
    return h;
  }
  /** Runs the tail on what head() returned; returns the six stems (planar stereo), and frees the hand-off. */
  std::vector<float> tail(Handoff&h,double*seconds=nullptr){
    if(nativeIstft)return tailNative(h,seconds);
    const int64_t shape[]{1,2,int64_t(SEGMENT)};
    OrtValue*input=nullptr;
    check(api->CreateTensorWithDataAsOrtValue(memory,h.input.data(),h.input.size()*sizeof(float),shape,3,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input));
    std::vector<const char*> names;std::vector<const OrtValue*> values;
    for(auto&n:tailInputs){
      names.push_back(n.c_str());
      if(n==inputName){values.push_back(input);continue;}
      const OrtValue*found=nullptr;
      for(size_t i=0;i<headOutputs.size();i++)if(headOutputs[i]==n)found=h.values[i];
      if(!found){api->ReleaseValue(input);throw std::runtime_error("The split model's halves do not fit together ("+n+")");}
      values.push_back(found);
    }
    OrtValue*out=nullptr;
    const char*o=outputName.c_str();
    const auto t=std::chrono::steady_clock::now();
    OrtStatus*status=api->Run(tailSession,nullptr,names.data(),values.data(),values.size(),&o,1,&out);
    if(seconds)*seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
    api->ReleaseValue(input);
    for(auto*v:h.values)if(v)api->ReleaseValue(v);
    h.values.clear();
    check(status);
    float*data=nullptr;
    OrtTensorTypeAndShapeInfo*info=nullptr;size_t count=0;
    check(api->GetTensorTypeAndShape(out,&info));
    OrtStatus*sized=api->GetTensorShapeElementCount(info,&count);
    api->ReleaseTensorTypeAndShapeInfo(info);
    if(sized){api->ReleaseValue(out);check(sized);}
    if(count!=SEGMENT*2*STEMS){api->ReleaseValue(out);throw std::runtime_error("This is not the six-stem model");}
    check(api->GetTensorMutableData(out,reinterpret_cast<void**>(&data)));
    std::vector<float> r(data,data+count);
    api->ReleaseValue(out);
    return r;
  }
  void release(Handoff&h){for(auto*v:h.values)if(v)api->ReleaseValue(v);h.values.clear();}
  /** The tail by the native inverse STFT: the spectrum is the 6-D hand-off, the time branch the 4-D one. */
  std::vector<float> tailNative(Handoff&h,double*seconds){
    const float*z=nullptr,*xt=nullptr;
    int64_t frames=0,length=0;
    try{
      for(auto*v:h.values){
        OrtTensorTypeAndShapeInfo*info=nullptr;
        check(api->GetTensorTypeAndShape(v,&info));
        size_t n=0;OrtStatus*st=api->GetDimensionsCount(info,&n);
        std::vector<int64_t> d(n);
        if(!st)st=api->GetDimensions(info,d.data(),n);
        api->ReleaseTensorTypeAndShapeInfo(info);
        check(st);
        float*data=nullptr;
        check(api->GetTensorMutableData(v,reinterpret_cast<void**>(&data)));
        if(n==6&&d[1]==STEMS&&d[2]==2&&d[3]==2&&d[4]==Istft::N/2+1){z=data;frames=d[5];}
        else if(n==4&&d[1]==STEMS&&d[2]==2){xt=data;length=d[3];}
      }
      if(!z||!xt||length!=int64_t(SEGMENT))throw std::runtime_error("The split model's head does not hand over a spectrum and a time branch");
      static const Istft istft;
      std::vector<float> r(SEGMENT*2*STEMS);
      const auto t=std::chrono::steady_clock::now();
      istft.run(z,frames,xt,length,r.data(),tailThreads);
      if(seconds)*seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-t).count();
      release(h);
      return r;
    }catch(...){release(h);throw;}
  }
  ~Model(){
    if(profiling)endProfiling();
    if(tailSession)api->ReleaseSession(tailSession);
    if(tailOptions)api->ReleaseSessionOptions(tailOptions);
    if(memory)api->ReleaseMemoryInfo(memory);
    if(session)api->ReleaseSession(session);
    if(options)api->ReleaseSessionOptions(options);
    if(env)api->ReleaseEnv(env);
  }
  Model(const Model&)=delete;
  Model&operator=(const Model&)=delete;
  // Takes planar stereo, returns six planar stereo stems. The result stays valid until the next run.
  const float*run(std::vector<float>&stereo){
    if(split()){
      Handoff h=head(stereo);
      double tailSeconds=0;
      splitOut=tail(h,&tailSeconds);
      lastHead=h.headSeconds;lastTail=tailSeconds;
      return splitOut.data();
    }
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
  /** Ends profiling and returns the file ONNX Runtime wrote, or "". */
  std::string endProfiling(){
    if(!profiling)return "";
    profiling=false;
    OrtAllocator*allocator=nullptr;
    char*path=nullptr;
    OrtStatus*st=api->GetAllocatorWithDefaultOptions(&allocator);
    if(!st)st=api->SessionEndProfiling(session,allocator,&path);
    if(st){api->ReleaseStatus(st);return "";}
    if(!path)return "";
    std::string p(path);
    if(OrtStatus*f=api->AllocatorFree(allocator,path))api->ReleaseStatus(f);
    return p;
  }
  double lastHead=0,lastTail=0;   // the split model's last section: card part, processor part (s)
private:
  bool profiling=false;
  OrtSession*tailSession=nullptr;
  OrtSessionOptions*tailOptions=nullptr;
  std::vector<std::string> headOutputs,tailInputs;
  std::vector<float> splitOut;
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

// ---------------------------------------------------------------------------------------------------
// Measuring: where the time goes, and what the processor, graphics card and memory are doing.
// ---------------------------------------------------------------------------------------------------
using Clock=std::chrono::steady_clock;
double since(Clock::time_point t){return std::chrono::duration<double>(Clock::now()-t).count();}

struct Timing{
  double load=0,downsample=0,sections=0,infer=0,inferGpu=0,inferCpu=0,assemble=0,upsample=0,tail=0,write=0,total=0;
  double splitHead=0,splitTail=0;   // the split model: time in the card's part and the processor's part
  int gpuSections=0,cpuSections=0;
} timing;

std::mutex outLock;   // lines on standard output come from several threads
void say(const char*fmt,...){
  std::lock_guard<std::mutex> g(outLock);
  va_list a;va_start(a,fmt);vprintf(fmt,a);va_end(a);fflush(stdout);
}

class Monitor{
public:
  struct Stats{double cpuAvg=0,cpuMax=0,procCpuAvg=0,gpuAvg=0,gpuMax=0;long vramPeak=-1,sharedPeak=-1,gpuUsedPeak=-1,ramPeak=0,tempStart=-1,tempMax=-1,powerMax=-1;int samples=0,gpuSamples=0;};
  void start(bool print){
    printing=print;stats=Stats();running=true;began=Clock::now();
    openGpu();
    sampleCpu(nullptr,nullptr);
    worker=std::thread([this]{
      while(running){
        std::unique_lock<std::mutex> l(m);
        cv.wait_for(l,std::chrono::milliseconds(250),[this]{return !running.load();});
        if(!running)break;
        l.unlock();
        sample();
      }
    });
  }
  Stats stop(){
    if(running){running=false;cv.notify_all();if(worker.joinable())worker.join();}
    sample();
    Stats s=stats;
    if(s.samples){s.cpuAvg/=s.samples;s.procCpuAvg/=s.samples;}
    if(s.gpuSamples)s.gpuAvg/=s.gpuSamples;else s.gpuAvg=s.gpuMax=-1;
    return s;
  }
private:
  void sample(){
    double cpu=0,proc=0;
    sampleCpu(&cpu,&proc);
    stats.samples++;stats.cpuAvg+=cpu;stats.procCpuAvg+=proc;stats.cpuMax=std::max(stats.cpuMax,cpu);
    long ram=long(peakMegabytes());stats.ramPeak=std::max(stats.ramPeak,ram);
    long gpu=-1,used=-1,temp=-1,power=-1,vram=-1,shared=-1;
    readGpu(gpu,used,temp,power,vram,shared);
    if(gpu>=0){stats.gpuSamples++;stats.gpuAvg+=gpu;stats.gpuMax=std::max(stats.gpuMax,double(gpu));}
    if(used>=0)stats.gpuUsedPeak=std::max(stats.gpuUsedPeak,used);
    if(temp>=0){if(stats.tempStart<0)stats.tempStart=temp;stats.tempMax=std::max(stats.tempMax,temp);}
    if(power>=0)stats.powerMax=std::max(stats.powerMax,power);
    if(vram>=0)stats.vramPeak=std::max(stats.vramPeak,vram);
    if(shared>=0)stats.sharedPeak=std::max(stats.sharedPeak,shared);
    if(printing&&(++tick%4==0))
      say("MON %.1f cpu=%.0f proc=%.0f ram=%ld gpu=%ld gpuused=%ld vram=%ld shared=%ld temp=%ld power=%ld\n",since(began),cpu,proc,ram,gpu,used,vram,shared,temp,power);
  }
#ifdef _WIN32
  static uint64_t ft(const FILETIME&f){return (uint64_t(f.dwHighDateTime)<<32)|f.dwLowDateTime;}
  void sampleCpu(double*cpu,double*proc){
    FILETIME idle,kernel,user,c,e,pk,pu;
    if(!GetSystemTimes(&idle,&kernel,&user)||!GetProcessTimes(GetCurrentProcess(),&c,&e,&pk,&pu))return;
    const uint64_t i=ft(idle),k=ft(kernel),u=ft(user),p=ft(pk)+ft(pu);
    if(cpu&&k+u>lastK+lastU){
      const double all=double((k-lastK)+(u-lastU));
      *cpu=100.0*(all-double(i-lastI))/all;
      *proc=100.0*double(p-lastP)/all;
    }
    lastI=i;lastK=k;lastU=u;lastP=p;
  }
  // The graphics card, through NVIDIA's own management library (in Windows with the driver) and DirectX.
  typedef int(*NvInit)();typedef int(*NvHandle)(unsigned,void**);
  struct NvUtil{unsigned gpu,memory;};struct NvMem{unsigned long long total,free,used;};
  typedef int(*NvGetUtil)(void*,NvUtil*);typedef int(*NvGetMem)(void*,NvMem*);typedef int(*NvGetTemp)(void*,int,unsigned*);typedef int(*NvGetPower)(void*,unsigned*);
  HMODULE nvml=nullptr;void*nvDev=nullptr;NvGetUtil nvUtil=nullptr;NvGetMem nvMem=nullptr;NvGetTemp nvTemp=nullptr;NvGetPower nvPower=nullptr;
#ifdef STEM_DIRECTML
  IDXGIAdapter3*adapter=nullptr;
#endif
  void openGpu(){
    if(!nvml){
      nvml=LoadLibraryW(L"nvml.dll");
      if(!nvml)nvml=LoadLibraryW(L"C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvml.dll");
      if(nvml){
        auto init=(NvInit)GetProcAddress(nvml,"nvmlInit_v2");auto handle=(NvHandle)GetProcAddress(nvml,"nvmlDeviceGetHandleByIndex_v2");
        nvUtil=(NvGetUtil)GetProcAddress(nvml,"nvmlDeviceGetUtilizationRates");nvMem=(NvGetMem)GetProcAddress(nvml,"nvmlDeviceGetMemoryInfo");
        nvTemp=(NvGetTemp)GetProcAddress(nvml,"nvmlDeviceGetTemperature");nvPower=(NvGetPower)GetProcAddress(nvml,"nvmlDeviceGetPowerUsage");
        if(!init||!handle||init()!=0||handle(0,&nvDev)!=0)nvDev=nullptr;
      }
    }
#ifdef STEM_DIRECTML
    if(!adapter){
      IDXGIFactory6*factory=nullptr;
      if(SUCCEEDED(CreateDXGIFactory1(__uuidof(IDXGIFactory6),reinterpret_cast<void**>(&factory)))){
        for(UINT i=0;;i++){
          IDXGIAdapter3*a=nullptr;
          if(FAILED(factory->EnumAdapterByGpuPreference(i,DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,__uuidof(IDXGIAdapter3),reinterpret_cast<void**>(&a))))break;
          DXGI_ADAPTER_DESC1 d{};a->GetDesc1(&d);
          if((d.Flags&DXGI_ADAPTER_FLAG_SOFTWARE)||d.VendorId==0x1414){a->Release();continue;}
          adapter=a;break;
        }
        factory->Release();
      }
    }
#endif
  }
  void readGpu(long&gpu,long&used,long&temp,long&power,long&vram,long&shared){
    if(nvDev){
      NvUtil u{};NvMem mem{};unsigned t=0,p=0;
      if(nvUtil&&nvUtil(nvDev,&u)==0)gpu=long(u.gpu);
      if(nvMem&&nvMem(nvDev,&mem)==0)used=long(mem.used/1048576);
      if(nvTemp&&nvTemp(nvDev,0,&t)==0)temp=long(t);
      if(nvPower&&nvPower(nvDev,&p)==0)power=long(p/1000);
    }
#ifdef STEM_DIRECTML
    if(adapter){
      DXGI_QUERY_VIDEO_MEMORY_INFO local{},nonLocal{};
      if(SUCCEEDED(adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_LOCAL,&local)))vram=long(local.CurrentUsage/1048576);
      if(SUCCEEDED(adapter->QueryVideoMemoryInfo(0,DXGI_MEMORY_SEGMENT_GROUP_NON_LOCAL,&nonLocal)))shared=long(nonLocal.CurrentUsage/1048576);
    }
#endif
  }
  uint64_t lastI=0,lastK=0,lastU=0,lastP=0;
#else
  void sampleCpu(double*cpu,double*proc){
    FILE*f=fopen("/proc/stat","r");if(!f)return;
    unsigned long long a[10]{};
    if(fscanf(f,"cpu %llu %llu %llu %llu %llu %llu %llu %llu",&a[0],&a[1],&a[2],&a[3],&a[4],&a[5],&a[6],&a[7])!=8){fclose(f);return;}
    fclose(f);
    unsigned long long idle=a[3]+a[4],all=0;for(int i=0;i<8;i++)all+=a[i];
    FILE*p=fopen("/proc/self/stat","r");unsigned long long ut=0,st=0;
    if(p){char buf[1024];if(fgets(buf,sizeof buf,p)){const char*q=strrchr(buf,')');if(q)sscanf(q+2,"%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu",&ut,&st);}fclose(p);}
    if(cpu&&all>lastAll){*cpu=100.0*double((all-lastAll)-(idle-lastIdle))/double(all-lastAll);*proc=100.0*double((ut+st)-lastProc)/double(all-lastAll);}
    lastAll=all;lastIdle=idle;lastProc=ut+st;
  }
  void openGpu(){}
  void readGpu(long&,long&,long&,long&,long&,long&){}
  unsigned long long lastAll=0,lastIdle=0,lastProc=0;
#endif
  std::atomic<bool> running{false};
  bool printing=false;int tick=0;
  Clock::time_point began;
  std::thread worker;
  std::mutex m;std::condition_variable cv;
  Stats stats;
} monitor;


void placement(const std::string&path);

std::array<std::string,STEMS+1> extract(const std::string&modelPath,const std::string&wav,
  const std::string&folder,const std::string&prefix,int start,int duration,int threads,int useGpu){
  if(busy.exchange(true))throw std::runtime_error("Already separating");
  struct Guard{~Guard(){busy.store(false);cancelled.store(false);}}guard;
  cancelled.store(stopAsked.load());   // a Stop that came before the run started still counts
  timing=Timing();
  const auto serialBegan=Clock::now();
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
    auto t=Clock::now();
    Audio input=loadWav(wav,offset,selected);
    timing.load=since(t);t=Clock::now();
    if(rate==MODEL_RATE){mixL=std::move(input.planes[0]);mixR=std::move(input.planes[1]);}
    else{
      const RateChange down(147,160);
      mixL=down.run(input.planes[0],frames);
      input.planes[0]=std::vector<float>();
      mixR=down.run(input.planes[1],frames);
    }
    timing.downsample=since(t);
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
    std::vector<float> rest(STRIDE*2);
    std::array<std::vector<float>,STEMS> columns;
    if(peakColumns>0)for(auto&c:columns)c.assign(size_t(peakColumns),0.f);
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
      const auto inferBegan=Clock::now();
      const float*out=model.run(in);
      timing.infer+=since(inferBegan);
      if(model.split()){timing.splitHead+=model.lastHead;timing.splitTail+=model.lastTail;}
      timing.gpuSections++;   // the first (only) session
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
      if(peakColumns>0)for(size_t i=0;i<ready;i++){rest[i]=mixL[written+i];rest[STRIDE+i]=mixR[written+i];}
      for(int s=0;s<STEMS;s++){
        if(FROM_MODEL[s]<0)continue;
        for(int c=0;c<2;c++){
          for(size_t i=0;i<ready;i++){
            const float x=float(double(held[2*s+c][i])/sum[i]*deviation+mean);
            if(!std::isfinite(x))throw std::runtime_error("Invalid stem sample");
            line[i]=x;
          }
          if(peakColumns>0)for(size_t i=0;i<ready;i++){
            rest[size_t(c)*STRIDE+i]-=line[i];
            float&col=columns[s][std::min(size_t(peakColumns-1),(written+i)*size_t(peakColumns)/frames)];
            col=std::max(col,std::abs(line[i]));
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
      if(peakColumns>0){
        // Other is what the five named stems leave of the song, as at the end.
        for(int c=0;c<2;c++)for(size_t i=0;i<ready;i++){
          float&col=columns[OTHER][std::min(size_t(peakColumns-1),(written+i)*size_t(peakColumns)/frames)];
          col=std::max(col,std::abs(rest[size_t(c)*STRIDE+i]));
        }
        const size_t first=written*size_t(peakColumns)/frames,last=std::min(size_t(peakColumns-1),(written+ready-1)*size_t(peakColumns)/frames);
        std::string out;
        for(int s=0;s<STEMS;s++){
          out+="PEAKS ";out+=NAMES[s];out+=" "+std::to_string(first)+" ";
          char v[16];
          for(size_t b=first;b<=last;b++){snprintf(v,sizeof v,b==first?"%.3f":",%.3f",columns[s][b]);out+=v;}
          out+="\n";
        }
        fputs(out.c_str(),stdout);fflush(stdout);
      }
      written+=ready;
    }
    model.finish();
    if(!profilePrefix.empty()){const std::string p=model.endProfiling();if(!p.empty()){say("PROFILE %s\n",p.c_str());placement(p);}}
    if(written!=frames)throw std::runtime_error("Assembled frame count mismatch");
    for(auto&f:files)if(f){
      if(fflush(f)!=0||ferror(f))throw std::runtime_error("Stem scratch flush failed");
      if(fclose(f)!=0)throw std::runtime_error("Stem scratch close failed");
      f=nullptr;
    }
  }
  const double modelSeconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-began).count();
  timing.sections=modelSeconds;
  const auto tailBegan=Clock::now();

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

  timing.upsample=since(tailBegan);
  const auto writeBegan=Clock::now();
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
  timing.write=since(writeBegan);
  timing.total=since(serialBegan);
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

/** Which device ran each step, from ONNX Runtime's profile: steps and time per device, copies between them. */
void placement(const std::string&path){
  FILE*f=openFile(path,"rb");
  if(!f){say("PLACEMENT none\n");return;}
  std::map<std::string,std::pair<long,double>> by;  // provider -> (steps, ms)
  long copies=0;double copyMs=0;
  char line[8192];
  while(fgets(line,sizeof line,f)){
    if(!strstr(line,"\"cat\" : \"Node\"")||!strstr(line,"_kernel_time"))continue;
    const char*d=strstr(line,"\"dur\" :");const char*pv=strstr(line,"\"provider\" : \"");const char*op=strstr(line,"\"op_name\" : \"");
    if(!d||!pv)continue;
    const double ms=atof(d+7)/1000.0;
    std::string prov(pv+14);prov=prov.substr(0,prov.find('"'));
    std::string name=op?std::string(op+13):"";name=name.substr(0,name.find('"'));
    if(name.find("Memcpy")!=std::string::npos){copies++;copyMs+=ms;}
    auto&e=by[prov];e.first++;e.second+=ms;
  }
  fclose(f);
  for(auto&e:by)say("PLACEMENT %s steps=%ld ms=%.0f\n",e.first.c_str(),e.second.first,e.second.second);
  say("PLACEMENT copies steps=%ld ms=%.0f\n",copies,copyMs);
}

/** Runs f(i) for i in [0, n) on up to `threads` threads. */
void parallelFor(int n,int threads,const std::function<void(int)>&f){
  threads=std::max(1,std::min(threads,n));
  if(threads==1){for(int i=0;i<n;i++)f(i);return;}
  std::atomic<int> next{0};
  std::exception_ptr error;
  std::mutex em;
  std::vector<std::thread> pool;
  for(int t=0;t<threads;t++)pool.emplace_back([&]{
    try{for(int i;(i=next++)<n;)f(i);}
    catch(...){std::lock_guard<std::mutex> g(em);if(!error)error=std::current_exception();next=n;}
  });
  for(auto&t:pool)t.join();
  if(error)std::rethrow_exception(error);
}

// ---------------------------------------------------------------------------------------------------
// The pipelined engine. Same model, sections, weights, rate changes, sum and 24-bit output as extract()
// (the serial one, kept as the reference); only the order work is done in changes, so the files match.
//   - The song is brought to 44.1 kHz on all processor threads at once (each sample computed as before).
//   - One thread feeds the model; another assembles finished sections, makes Other, and converts each
//     finished stretch to 48 kHz while the model works on the next section. At most three finished
//     sections wait at once (triple buffering), so memory stays bounded.
//   - parallel: a second session on the processor takes sections from the end of the song while the
//     graphics card works from the start, as long as it can finish them before the card gets there.
//   - The last step (24-bit files with the shared gain) writes the six files at once.
// ---------------------------------------------------------------------------------------------------
struct Plan{int primary=0;bool parallel=false;int inferThreads=1,cpuThreads=1,helpers=2;};

std::array<std::string,STEMS+1> extractPipelined(const std::string&modelPath,const std::string&wav,
  const std::string&folder,const std::string&prefix,int start,int duration,const Plan&plan){
  if(busy.exchange(true))throw std::runtime_error("Already separating");
  struct Guard{~Guard(){busy.store(false);cancelled.store(false);}}guard;
  cancelled.store(stopAsked.load());   // a Stop that came before the run started still counts
  timing=Timing();
  const auto began=Clock::now();
  if(start<0||start>1200||(duration!=0&&duration!=10&&duration!=60))
    throw std::runtime_error("Unsupported preview duration/position");
  const Audio info=loadWav(wav,0,0);
  const size_t rate=info.rate,total=info.totalFrames;
  const size_t preview=size_t(duration)*rate;
  const size_t offset=duration==0?0:std::min(size_t(start)*rate,total>preview?total-preview:0);
  const size_t selected=duration==0?total:std::min(preview,total-offset);
  const size_t frames=(uint64_t(selected)*MODEL_RATE+rate/2)/rate;
  const size_t outFrames=(uint64_t(selected)*OUT_RATE+rate/2)/rate;
  if(outFrames==0)throw std::runtime_error("No audio to separate");
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
  if(available<needed)throw std::runtime_error("Storage low: need "+std::to_string((needed+999999)/1000000)+" MB free");

  // The song at 44.1 kHz, both channels and all threads at once.
  std::vector<float> mixL,mixR;
  {
    auto t=Clock::now();
    Audio input=loadWav(wav,offset,selected);
    timing.load=since(t);t=Clock::now();
    if(rate==MODEL_RATE){mixL=std::move(input.planes[0]);mixR=std::move(input.planes[1]);}
    else{
      const RateChange down(147,160);
      mixL.assign(frames,0.f);mixR.assign(frames,0.f);
      const int pieces=std::max(2,plan.helpers*2);
      const size_t per=(frames+pieces-1)/pieces;
      parallelFor(pieces*2,plan.helpers,[&](int j){
        const int c=j%2;const size_t from=size_t(j/2)*per,to=std::min(frames,from+per);
        if(from<to)down.runRange(input.planes[c].data(),int64_t(input.planes[c].size()),from,to,(c?mixR:mixL).data()+from);
      });
    }
    timing.downsample=since(t);
  }
  double mean=0,square=0;
  for(size_t i=0;i<frames;i++){const double m=0.5*(mixL[i]+mixR[i]);mean+=m;square+=m*m;}
  mean/=double(frames);
  const double deviation=std::sqrt(std::max(1e-10,square/double(frames)-mean*mean));

  Scratch scratch(folder,prefix);
  const int count=int((frames+STRIDE-1)/STRIDE);
  // Finished stems at 44.1 kHz (planar L, R per stem), filled as sections finish.
  std::array<std::vector<float>,STEMS*2> stem44;
  for(auto&v:stem44)v.assign(frames,0.f);
  // 48 kHz floats go to a scratch file per stem in stretches (L then R), as they are made.
  std::array<FILE*,STEMS> loud{};
  struct Closer{std::array<FILE*,STEMS>&f;~Closer(){for(auto*x:f)if(x)fclose(x);}}closer{loud};
  for(int s=0;s<STEMS;s++){loud[s]=openFile(scratch.loud[s],"wb");if(!loud[s])throw std::runtime_error("Cannot create temporary stem");}
  std::vector<size_t> stretches;
  const RateChange up(160,147);
  size_t done48=0;
  std::array<double,STEMS> peaks{};

  // Sections finished by the model, waiting to be assembled, by section number.
  std::mutex qm;std::condition_variable qcv;
  std::map<int,std::pair<std::vector<float>,bool>> finished;   // section -> (stems, made on the processor's second session)
  int front=0,back=count-1,gpuHeld=0,cpuHeld=0;
  double gpuAvg=0,cpuAvg=0;
  bool failed=false;std::exception_ptr error;
  std::deque<std::pair<int,Model::Handoff>> handoffs;   // heads done on the card, waiting for their tail
  bool tailLane=false;
  auto fail=[&](std::exception_ptr e){std::lock_guard<std::mutex> g(qm);if(!error)error=e;failed=true;qcv.notify_all();};
  auto prepare=[&](int idx,std::vector<float>&in){
    const size_t from=size_t(idx)*STRIDE;
    for(size_t i=0;i<SEGMENT;i++){
      const bool inside=from+i<frames;
      in[i]=inside?float((mixL[from+i]-mean)/deviation):0.f;
      in[SEGMENT+i]=inside?float((mixR[from+i]-mean)/deviation):0.f;
    }
  };
  // One model on one device: takes sections (the card from the front, the processor from the back).
  auto worker=[&](Model*model,bool fromBack){
    try{
      std::vector<float> in(SEGMENT*2);
      for(;;){
        int idx=-1;
        {
          std::unique_lock<std::mutex> l(qm);
          for(;;){
            if(failed||cancelled.load())return;
            if(front>back)return;
            if(!fromBack){
              if(gpuHeld+int(handoffs.size())<3){idx=front++;break;}           // triple buffering
            }else{
              // Take the last section only if the card won't reach it first, and hold at most four.
              const int left=back-front+1;
              if(gpuAvg>0&&cpuHeld<4&&double(left)*gpuAvg>cpuAvg*1.25+gpuAvg){idx=back--;break;}
              if(gpuAvg>0&&double(left)*gpuAvg<=cpuAvg*1.25+gpuAvg)return;  // not worth starting another
            }
            qcv.wait_for(l,std::chrono::milliseconds(50));
          }
        }
        prepare(idx,in);
        const auto t=Clock::now();
        std::vector<float> copy;
        if(model->split()&&!fromBack&&tailLane){
          // The card runs this section's head while the processor runs the previous section's tail.
          Model::Handoff h=model->head(in);
          {
            std::unique_lock<std::mutex> l(qm);
            while(handoffs.size()>=2&&!failed)qcv.wait_for(l,std::chrono::milliseconds(50));
            if(failed){model->release(h);return;}
            timing.splitHead+=h.headSeconds;
            handoffs.emplace_back(idx,std::move(h));
          }
          qcv.notify_all();
          continue;
        }
        if(model->split()){
          const float*out=model->run(in);
          copy.assign(out,out+SEGMENT*2*STEMS);
          std::lock_guard<std::mutex> g(qm);
          timing.splitHead+=model->lastHead;timing.splitTail+=model->lastTail;
        }else{
          const float*out=model->run(in);
          copy.assign(out,out+SEGMENT*2*STEMS);
        }
        const double took=since(t);
        {
          std::lock_guard<std::mutex> g(qm);
          if(fromBack){cpuHeld++;timing.cpuSections++;timing.inferCpu+=took;cpuAvg=timing.inferCpu/timing.cpuSections;}
          else{gpuHeld++;timing.gpuSections++;timing.inferGpu+=took;gpuAvg=timing.inferGpu/timing.gpuSections;}
          finished[idx]=std::make_pair(std::move(copy),fromBack);
        }
        qcv.notify_all();
      }
    }catch(...){fail(std::current_exception());}
  };

  const auto sectionsBegan=Clock::now();
  std::unique_ptr<Model> first(new Model(modelPath,plan.inferThreads,plan.primary));
  std::unique_ptr<Model> second;
  if(plan.parallel)second.reset(new Model(modelPath,plan.cpuThreads,0));
  std::vector<std::thread> workers;
  tailLane=first->split()&&plan.primary!=0;
  bool headsDone=false;
  workers.emplace_back([&]{worker(first.get(),false);std::lock_guard<std::mutex> g(qm);headsDone=true;qcv.notify_all();});
  if(tailLane)workers.emplace_back([&]{
    // The processor's lane: tails of sections whose head the card has finished, in the order they come.
    try{
      for(;;){
        std::pair<int,Model::Handoff> job;
        {
          std::unique_lock<std::mutex> l(qm);
          while(handoffs.empty()&&!failed&&!(headsDone))qcv.wait_for(l,std::chrono::milliseconds(50));
          if(failed){for(auto&h:handoffs)first->release(h.second);handoffs.clear();return;}
          if(handoffs.empty())return;
          job=std::move(handoffs.front());handoffs.pop_front();
        }
        qcv.notify_all();
        double tailSeconds=0;
        std::vector<float> out=first->tail(job.second,&tailSeconds);
        {
          std::lock_guard<std::mutex> g(qm);
          timing.splitTail+=tailSeconds;
          gpuHeld++;timing.gpuSections++;timing.inferGpu+=job.second.headSeconds+tailSeconds;gpuAvg=timing.inferGpu/timing.gpuSections;
          finished[job.first]=std::make_pair(std::move(out),false);
        }
        qcv.notify_all();
      }
    }catch(...){fail(std::current_exception());}
  });
  if(second)workers.emplace_back(worker,second.get(),true);
  struct Joiner{std::vector<std::thread>&w;~Joiner(){for(auto&t:w)if(t.joinable())t.join();}}joiner{workers};

  // Assembling, on this thread, in section order: the same arithmetic as extract().
  std::vector<float> weight(SEGMENT),sum(SEGMENT,0.f);
  for(size_t i=0;i<SEGMENT;i++)weight[i]=float(std::min(i+1,SEGMENT-i));
  std::array<std::vector<float>,STEMS*2> held;
  for(int s=0;s<STEMS;s++)if(FROM_MODEL[s]>=0)held[2*s].assign(SEGMENT,0.f),held[2*s+1].assign(SEGMENT,0.f);
  std::array<std::vector<float>,STEMS> columns;
  if(peakColumns>0)for(auto&c:columns)c.assign(size_t(peakColumns),0.f);
  size_t written=0;
  std::vector<float> chunk;
  // If assembling fails, the model threads must stop too (they wait for room otherwise).
  struct StopWorkers{std::mutex&m;std::condition_variable&cv;bool&failed;bool armed=true;
    ~StopWorkers(){if(armed){{std::lock_guard<std::mutex> g(m);failed=true;}cv.notify_all();}}}stopWorkers{qm,qcv,failed};
  for(int idx=0;idx<count;idx++){
    std::vector<float> out;
    {
      std::unique_lock<std::mutex> l(qm);
      while(!failed&&finished.find(idx)==finished.end()){
        if(cancelled.load()){failed=true;qcv.notify_all();break;}
        qcv.wait_for(l,std::chrono::milliseconds(50));
      }
      if(failed){l.unlock();for(auto&t:workers)if(t.joinable())t.join();if(error)std::rethrow_exception(error);checkCancel();throw std::runtime_error("Stopped");}
      auto it=finished.find(idx);
      out=std::move(it->second.first);
      if(it->second.second)cpuHeld--;else gpuHeld--;
      finished.erase(it);
    }
    qcv.notify_all();
    const auto t=Clock::now();
    say("SECTION %d %d\n",idx,count);
    for(int s=0;s<STEMS;s++){
      if(FROM_MODEL[s]<0)continue;
      for(int c=0;c<2;c++){
        const float*source=out.data()+size_t(FROM_MODEL[s])*2*SEGMENT+size_t(c)*SEGMENT;
        float*into=held[2*s+c].data();
        for(size_t i=0;i<SEGMENT;i++)into[i]+=source[i]*weight[i];
      }
    }
    for(size_t i=0;i<SEGMENT;i++)sum[i]+=weight[i];
    const size_t ready=std::min(STRIDE,frames-written);
    for(int s=0;s<STEMS;s++){
      if(FROM_MODEL[s]<0)continue;
      for(int c=0;c<2;c++){
        float*dest=stem44[2*s+c].data()+written;
        for(size_t i=0;i<ready;i++){
          const float x=float(double(held[2*s+c][i])/sum[i]*deviation+mean);
          if(!std::isfinite(x))throw std::runtime_error("Invalid stem sample");
          dest[i]=x;
        }
        auto&h=held[2*s+c];
        std::copy(h.begin()+STRIDE,h.end(),h.begin());
        std::fill(h.end()-STRIDE,h.end(),0.f);
      }
    }
    std::copy(sum.begin()+STRIDE,sum.end(),sum.begin());
    std::fill(sum.end()-STRIDE,sum.end(),0.f);
    // Other: the song minus the five, subtracted in the same order as extract() does.
    for(int c=0;c<2;c++){
      const std::vector<float>&mix=c?mixR:mixL;
      float*o=stem44[2*OTHER+c].data();
      for(size_t i=written;i<written+ready;i++){
        float x=mix[i];
        for(int s:{0,1,2,4,5})x-=stem44[2*s+c][i];
        o[i]=x;
      }
    }
    if(peakColumns>0){
      for(int s=0;s<STEMS;s++)for(int c=0;c<2;c++)for(size_t i=written;i<written+ready;i++){
        float&col=columns[s][std::min(size_t(peakColumns-1),i*size_t(peakColumns)/frames)];
        col=std::max(col,std::abs(stem44[2*s+c][i]));
      }
      const size_t first=written*size_t(peakColumns)/frames,last=std::min(size_t(peakColumns-1),(written+ready-1)*size_t(peakColumns)/frames);
      std::string line;
      for(int s=0;s<STEMS;s++){
        line+="PEAKS ";line+=NAMES[s];line+=" "+std::to_string(first)+" ";
        char v[16];
        for(size_t b=first;b<=last;b++){snprintf(v,sizeof v,b==first?"%.3f":",%.3f",columns[s][b]);line+=v;}
        line+="\n";
      }
      say("%s",line.c_str());
    }
    written+=ready;
    timing.assemble+=since(t);
    // Convert every 48 kHz sample whose filter now has all its input (all of them after the last section).
    const auto u=Clock::now();
    size_t target=done48;
    if(written>=frames)target=outFrames;
    else while(target<outFrames&&up.firstInput(target)+RateChange::taps()<=int64_t(written))target++;
    if(target>done48){
      const size_t n=target-done48;
      chunk.assign(n*STEMS*2,0.f);
      parallelFor(STEMS*2,plan.helpers,[&](int j){
        up.runRange(stem44[j].data(),int64_t(frames),done48,target,chunk.data()+size_t(j)*n);
      });
      for(int s=0;s<STEMS;s++){
        const float*l=chunk.data()+size_t(2*s)*n,*r=chunk.data()+size_t(2*s+1)*n;
        for(size_t i=0;i<n;i++)peaks[s]=std::max(peaks[s],std::max(std::abs(double(l[i])),std::abs(double(r[i]))));
        if(fwrite(l,sizeof(float),n,loud[s])!=n||fwrite(r,sizeof(float),n,loud[s])!=n)throw std::runtime_error("Scratch storage full");
      }
      stretches.push_back(n);
      done48=target;
    }
    timing.upsample+=since(u);
  }
  stopWorkers.armed=false;
  for(auto&t:workers)if(t.joinable())t.join();
  {std::lock_guard<std::mutex> g(qm);if(error)std::rethrow_exception(error);}
  timing.sections=since(sectionsBegan);
  timing.infer=timing.inferGpu+timing.inferCpu;
  first->finish();
  if(!profilePrefix.empty()){const std::string p=first->endProfiling();if(!p.empty()){say("PROFILE %s\n",p.c_str());placement(p);}}
  first.reset();second.reset();
  for(auto&f:loud){if(fflush(f)!=0||ferror(f))throw std::runtime_error("Stem scratch flush failed");fclose(f);f=nullptr;}
  for(auto&v:stem44)std::vector<float>().swap(v);
  std::vector<float>().swap(mixL);std::vector<float>().swap(mixR);

  // The shared gain, then all six 24-bit files at once.
  const auto w=Clock::now();
  double peak=0;for(double p:peaks)peak=std::max(peak,p);
  const double gain=peak>0.9999?0.9999/peak:1.;
  progress(2,0,STEMS);
  parallelFor(STEMS,plan.helpers,[&](int s){
    FileHandle in(scratch.loud[s],"rb");
    FileHandle out(scratch.partial[s],"wb");
    writeHeader(out.get(),outFrames);
    std::vector<float> l,r;std::vector<uint8_t> pcm;
    for(size_t n:stretches){
      l.resize(n);r.resize(n);pcm.resize(n*6);
      if(fread(l.data(),sizeof(float),n,in.get())!=n||fread(r.data(),sizeof(float),n,in.get())!=n)throw std::runtime_error("Temporary stem is incomplete");
      for(size_t i=0;i<n;i++)for(int c=0;c<2;c++){
        const double x=std::max(-1.,std::min(0.99999988079,double(c?r[i]:l[i])*gain));
        const int32_t val=int32_t(std::llrint(x*8388608.));
        uint8_t*p=pcm.data()+6*i+3*c;
        p[0]=uint8_t(val);p[1]=uint8_t(val>>8);p[2]=uint8_t(val>>16);
      }
      if(fwrite(pcm.data(),1,6*n,out.get())!=6*n)throw std::runtime_error("24-bit WAV disk write error");
    }
    if(fflush(out.get())!=0||ferror(out.get()))throw std::runtime_error("WAV flush error");
  });
  for(int s=0;s<STEMS;s++){removeFile(scratch.loud[s]);if(renameFile(scratch.partial[s],scratch.finals[s])!=0)throw std::runtime_error("Cannot finalize stem WAV");scratch.partial[s].clear();}
  timing.write=since(w);
  timing.total=since(began);
  progress(3,STEMS,STEMS);
  std::array<std::string,STEMS+1> result{};
  for(int s=0;s<STEMS;s++)result[s]=scratch.finals[s];
  char summary[400];
  snprintf(summary,sizeof summary,"%s|%d|%s|%d|%.1f|%ld|%.2f",duration==0?"song":duration==60?"60s":"10s",count,
     deviceName.c_str(),plan.inferThreads,timing.infer,peakMegabytes(),20*std::log10(gain));
  result[STEMS]=summary;
  return result;
}

}

namespace {
/** The graphics card DirectML will use (the high-performance one), or "" when there is none. */
std::string bestGpu(){
#ifdef STEM_DIRECTML
  IDXGIFactory6*factory=nullptr;
  if(FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory6),reinterpret_cast<void**>(&factory))))return "";
  std::string found;
  for(UINT i=0;;i++){
    IDXGIAdapter1*adapter=nullptr;
    if(FAILED(factory->EnumAdapterByGpuPreference(i,DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,__uuidof(IDXGIAdapter1),reinterpret_cast<void**>(&adapter))))break;
    DXGI_ADAPTER_DESC1 d{};
    adapter->GetDesc1(&d);
    adapter->Release();
    // Software adapters (Microsoft Basic Render Driver, WARP) are no faster than the processor.
    if((d.Flags&DXGI_ADAPTER_FLAG_SOFTWARE)||d.VendorId==0x1414)continue;
    found=utf8(d.Description)+"|"+std::to_string(d.DedicatedVideoMemory/1048576);
    break;
  }
  factory->Release();
  return found;
#else
  return "";
#endif
}

}
/** The high-performance card's place in Windows' plain adapter list (what DirectML's device number means). */
int adapterIndex(){
#ifdef STEM_DIRECTML
  const std::string best=bestGpu();
  if(best.empty())return 0;
  const std::string name=best.substr(0,best.find('|'));
  IDXGIFactory1*factory=nullptr;
  if(FAILED(CreateDXGIFactory1(__uuidof(IDXGIFactory1),reinterpret_cast<void**>(&factory))))return 0;
  int found=0;
  for(UINT i=0;;i++){
    IDXGIAdapter1*adapter=nullptr;
    if(factory->EnumAdapters1(i,&adapter)==DXGI_ERROR_NOT_FOUND)break;
    DXGI_ADAPTER_DESC1 d{};
    adapter->GetDesc1(&d);
    adapter->Release();
    if(utf8(d.Description)==name){found=int(i);break;}
  }
  factory->Release();
  return found;
#else
  return 0;
#endif
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
  peakColumns=std::max(0,std::min(8192,atoi(arg(argc,argv,"--peaks","0"))));
  // --mode serial (the reference) | pipelined | parallel (card plus a processor session);
  // --gpu-way auto | fused | unfused | adapter; --monitor; --profile PREFIX; --cpu-threads N; --helpers N.
  const std::string mode=arg(argc,argv,"--mode","serial"),gpuWay=arg(argc,argv,"--gpu-way","auto");
  profilePrefix=arg(argc,argv,"--profile","");
  tailPath=arg(argc,argv,"--tail","");
  for(int i=1;i<argc;i++)nativeIstft|=strcmp(argv[i],"--native-istft")==0;
  bool monitorOn=false;
  for(int i=1;i<argc;i++)monitorOn|=strcmp(argv[i],"--monitor")==0;
  const int cores=int(std::max(1u,std::thread::hardware_concurrency()));
  tailThreads=std::max(1,atoi(arg(argc,argv,"--tail-threads",std::to_string(std::max(1,cores-2)).c_str())));
  const int threads=atoi(arg(argc,argv,"--threads",std::to_string(std::max(1,std::min(cores-1,16))).c_str()));
  bool probe=false;
  for(int i=1;i<argc;i++)probe|=strcmp(argv[i],"--probe")==0;
  if(probe){
    // What the app shows before a run: the card (name|dedicated MB) and the processor threads.
    const std::string gpu=bestGpu();
    printf("GPU %s\nCORES %d\n",gpu.empty()?"none":gpu.c_str(),cores);
    fflush(stdout);
    return 0;
  }
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
  std::vector<int> tries;
  const int way=gpuWay=="fused"?2:gpuWay=="unfused"?3:gpuWay=="adapter"?1:0;
#ifdef STEM_DIRECTML
  if(device!="cpu"&&!bestGpu().empty()){if(way)tries.push_back(way);else{tries.push_back(2);tries.push_back(3);}}
#else
  if(device=="gpu")tries.push_back(way?way:2);
#endif
  if(device!="gpu"||tries.empty())tries.push_back(0);
  // In parallel mode the processor session gets the threads the assembling does not need.
  const int helpers=std::max(1,std::min(atoi(arg(argc,argv,"--helpers","4")),cores));
  const int cpuThreads=std::max(1,atoi(arg(argc,argv,"--cpu-threads",std::to_string(std::max(1,cores-3)).c_str())));
  std::string lastError;
  for(size_t t=0;t<tries.size();t++){
    const int gpu=tries[t];
    deviceName=gpu?"gpu":"cpu";
    if(gpu){const std::string card=bestGpu();printf("DEVICE gpu %s\n",card.empty()?"unknown":card.substr(0,card.find('|')).c_str());}
    else printf("DEVICE cpu %d\n",threads);
    fflush(stdout);
    try{
      monitor.start(monitorOn);
      std::array<std::string,STEMS+1> results;
      try{
        if(mode=="serial")results=extract(model,in,out,prefix,start,seconds,threads,gpu);
        else{
          Plan plan;plan.primary=gpu;plan.parallel=mode=="parallel";plan.helpers=helpers;
          // On the processor alone, inference takes every thread; with a card, it needs one to feed it.
          plan.inferThreads=threads;plan.cpuThreads=cpuThreads;
          results=extractPipelined(model,in,out,prefix,start,seconds,plan);
        }
      }catch(...){monitor.stop();throw;}
      const Monitor::Stats st=monitor.stop();
      for(int s=0;s<STEMS;s++)say("STEM %s %s\n",NAMES[s],results[s].c_str());
      say("TIMING mode=%s device=%s load=%.2f downsample=%.2f sections=%.2f infer=%.2f infer_first=%.2f infer_second=%.2f first_sections=%d second_sections=%d assemble=%.2f upsample=%.2f write=%.2f total=%.2f split_head=%.2f split_tail=%.2f\n",
        mode.c_str(),gpu==0?"cpu":gpu==2?"gpu-fused":gpu==3?"gpu-unfused":"gpu-adapter",timing.load,timing.downsample,timing.sections,timing.infer,timing.inferGpu,timing.inferCpu,
        timing.gpuSections,timing.cpuSections,timing.assemble,timing.upsample,timing.write,timing.total,timing.splitHead,timing.splitTail);
      say("STATS cpu_avg=%.0f cpu_max=%.0f proc_cpu_avg=%.0f gpu_avg=%.0f gpu_max=%.0f vram_peak=%ld shared_peak=%ld gpu_used_peak=%ld ram_peak=%ld temp_start=%ld temp_max=%ld power_max=%ld samples=%d\n",
        st.cpuAvg,st.cpuMax,st.procCpuAvg,st.gpuAvg,st.gpuMax,st.vramPeak,st.sharedPeak,st.gpuUsedPeak,st.ramPeak,st.tempStart,st.tempMax,st.powerMax,st.samples);
      say("DONE %s\n",results[STEMS].c_str());
      return 0;
    }catch(const std::exception&e){
      lastError=e.what();
      if(stopAsked.load())break;
      // The graphics card could not run it (no DirectX 12 card, driver, memory): try the processor.
      if(gpu&&t+1<tries.size()){say("FALLBACK %s\n",lastError.c_str());continue;}
    }
  }
  printf("ERROR %s\n",stopAsked.load()?"Cancelled":lastError.c_str());
  fflush(stdout);
  return 1;
}
}

// The stdin watcher may be blocked reading when the run ends; a normal exit would wait on it to tidy the
// standard streams, so leave at once (output is flushed and every file is already closed).
[[noreturn]] void leave(int code){fflush(stdout);fflush(stderr);std::_Exit(code);}

#ifdef _WIN32
int wmain(int argc,wchar_t**wargv){
  std::vector<std::string> keep;
  std::vector<char*> argv;
  for(int i=0;i<argc;i++)keep.push_back(utf8(wargv[i]));
  for(auto&k:keep)argv.push_back(&k[0]);
  SetConsoleOutputCP(CP_UTF8);
  leave(run(argc,argv.data()));
}
#else
int main(int argc,char**argv){leave(run(argc,argv));}
#endif
