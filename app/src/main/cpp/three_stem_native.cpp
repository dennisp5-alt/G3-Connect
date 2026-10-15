// Dennis Stem Studio experimental three-stem engine, isolated from trusted two-stem code.
// Uses sherpa-onnx's upstream C API (Apache-2.0). Own WAV I/O is deliberately
// transparent and does not perform EQ, compression, denoising or loudness normalization.
#include <jni.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <memory>
#include <atomic>
#include <vector>
#include <sys/statvfs.h>
#include "sherpa-onnx/c-api/c-api.h"

namespace {
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
  explicit FileHandle(const std::string& name, const char* mode): f_(fopen(name.c_str(), mode)) {
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
  long dataAt=-1;
  for(int i=0;i<4096;i++) {
    unsigned char chunk[8];
    if(fread(chunk,1,8,f)!=8) break;
    uint32_t length=u32(chunk+4);
    long start=ftell(f);
    if(start<0 || uint64_t(length)>uint64_t(std::numeric_limits<long>::max()-start-1))
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
    long next=start+static_cast<long>(length)+(length&1U);
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
  if(totalFrames==0 || totalFrames>size_t(rate)*12*60)
    throw std::runtime_error("Audio must be between one sample and 12 minutes in the engine test");

  if(startFrame>totalFrames || requestedFrames>totalFrames-startFrame)
    throw std::runtime_error("Selected source frames exceed WAV length");
  Audio audio; audio.rate=rate; audio.channels=2; audio.frames=requestedFrames;
  audio.totalFrames=totalFrames;
  if(requestedFrames==0)return audio;
  if(fseek(f,dataAt+long(startFrame*blockAlign),SEEK_SET)!=0)
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

// Source-separation models may generate 44.1 kHz audio. Stem Lab needs 48 kHz.
// Polyphase windowed-sinc resampling is performed only for 44.1 -> 48 kHz,
// without changing tempo, applying an EQ, or normalizing the stem amplitudes.
struct QualityResampler48 {
  static constexpr int kPhases=160;
  static constexpr int kTaps=64;
  std::array<std::array<double,kTaps>,kPhases> coefficients{};
  QualityResampler48() {
    constexpr double pi=3.1415926535897932384626433832795;
    for(int p=0;p<kPhases;p++) {
      const double frac=double(p)/double(kPhases);
      double sum=0.0;
      for(int t=0;t<kTaps;t++) {
        const double x=double(t-31)-frac;
        const double band=0.995;
        const double sinc=std::abs(x)<1e-12 ? band : std::sin(pi*band*x)/(pi*x);
        const double window=0.42+0.5*std::cos(pi*x/32.0)+0.08*std::cos(2.0*pi*x/32.0);
        coefficients[p][t]=sinc*window;
        sum+=coefficients[p][t];
      }
      if(std::abs(sum)<1e-8) throw std::runtime_error("Cannot initialize sample-rate filter");
      for(auto& x:coefficients[p]) x/=sum;
    }
  }
  uint32_t outputFrames(int32_t n) const {
    return uint32_t((uint64_t(n)*160ULL+73ULL)/147ULL);
  }
  double at(const SherpaOnnxSourceSeparationStem& stem,int channel,uint32_t frame) const {
    const uint64_t pos=uint64_t(frame)*147ULL;
    const int64_t start=int64_t(pos/160ULL)-31LL;
    const auto& weights=coefficients[pos%160ULL];
    double value=0.0,weight=0.0;
    for(int t=0;t<kTaps;t++) {
      const int64_t index=start+t;
      if(index<0 || index>=stem.n) continue;
      value+=weights[t]*stem.samples[channel][index];
      weight+=weights[t];
    }
    if(std::abs(weight)<1e-8) throw std::runtime_error("Resampling failed at WAV boundary");
    return value/weight;
  }
};
const QualityResampler48& resampler48() {
  static const QualityResampler48 r{};
  return r;
}

// Three-stem sum-preserving construction: vocals + drums + other
// equals vocals + instrumental in floating point before quantizing.
struct OutputCleaner {
  void operator()(const SherpaOnnxSourceSeparationOutput* output) const {
    if(output) SherpaOnnxDestroySourceSeparationOutput(output);
  }
};
using OutputPtr=std::unique_ptr<const SherpaOnnxSourceSeparationOutput,OutputCleaner>;
std::atomic<bool> busy{false};
std::atomic<bool> cancelled{false};
void checkCancel(){if(cancelled.load())throw std::runtime_error("Processing cancelled between sections");}
void validateOutput(const SherpaOnnxSourceSeparationOutput* output,const std::string&label) {
  if(!output || output->num_stems!=2 || !output->stems)
    throw std::runtime_error(label+": separator returned no valid 2-stem output");
  if(output->sample_rate!=44100 && output->sample_rate!=48000)
    throw std::runtime_error(label+": invalid sample rate");
  for(int st=0;st<2;st++){
    const auto& stem=output->stems[st];
    if(stem.num_channels!=2 || stem.n<=0 || !stem.samples || !stem.samples[0] || !stem.samples[1])
      throw std::runtime_error(label+": invalid stereo channel buffers");
  }
  if(output->stems[0].n!=output->stems[1].n)
    throw std::runtime_error(label+": unequal stem lengths");
}
OutputPtr runModel(const std::string& model,int rate,int frames,const float*left,const float*right) {
  SherpaOnnxOfflineSourceSeparationConfig config{};
  config.model.uvr.model=model.c_str();
  config.model.num_threads=2;
  config.model.provider="cpu";
  auto*engine=SherpaOnnxCreateOfflineSourceSeparation(&config);
  if(!engine)throw std::runtime_error("Could not load model. Use a compatible metadata-prepared MDX ONNX file.");
  const float*channels[]{left,right};
  auto*output=SherpaOnnxOfflineSourceSeparationProcess(engine,channels,2,frames,rate);
  SherpaOnnxDestroyOfflineSourceSeparation(engine);
  OutputPtr result(output);
  if(!result)throw std::runtime_error("Separation failed or phone ran out of memory");
  validateOutput(result.get(),"MDX");
  return result;
}
void progress(JNIEnv*env,jobject target,int phase,int chunk,int total){
  if(!target)return;
  jclass cls=env->GetObjectClass(target);
  if(!cls)throw std::runtime_error("Progress listener unavailable");
  jmethodID method=env->GetMethodID(cls,"onProgress","(III)V");
  if(method)env->CallVoidMethod(target,method,phase,chunk,total);
  env->DeleteLocalRef(cls);
  if(env->ExceptionCheck())throw std::runtime_error("Progress callback threw");
}

double outputSample(const SherpaOnnxSourceSeparationOutput*first,
  const SherpaOnnxSourceSeparationOutput*second,int role,int ch,uint32_t frame) {
  const bool up=(first->sample_rate==44100);
  auto read=[&](const SherpaOnnxSourceSeparationStem&stem)->double{
    return up?resampler48().at(stem,ch,frame):double(stem.samples[ch][frame]);
  };
  if(role==0)return read(first->stems[0]);
  const double drums=read(second->stems[0]);
  if(role==1)return drums;
  return read(first->stems[1])-drums;
}
void writeThreeStem24(const std::string&path,const SherpaOnnxSourceSeparationOutput*first,
       const SherpaOnnxSourceSeparationOutput*second,int role,float sharedGain,uint32_t frames) {
  const uint64_t bytes64=uint64_t(frames)*6;
  if(bytes64>0xffffff00ULL)throw std::runtime_error("WAV is too large");
  FileHandle file(path,"wb");FILE*f=file.get();
  const uint32_t bytes=uint32_t(bytes64);
  fwrite("RIFF",1,4,f);write32(f,36U+bytes);fwrite("WAVEfmt ",1,8,f);write32(f,16);
  write16(f,1);write16(f,2);write32(f,48000);write32(f,48000*6);
  write16(f,6);write16(f,24);
  fwrite("data",1,4,f);write32(f,bytes);
  unsigned char sample[3];
  for(uint32_t i=0;i<frames;i++){
    for(int ch=0;ch<2;ch++){
      double x=outputSample(first,second,role,ch,i)*sharedGain;
      if(!std::isfinite(x))throw std::runtime_error("Model produced invalid samples");
      x=std::max(-1.,std::min(0.99999988079,x));
      int32_t pcm=static_cast<int32_t>(std::llrint(x*8388608.));
      sample[0]=uint8_t(pcm);sample[1]=uint8_t(pcm>>8);sample[2]=uint8_t(pcm>>16);
      if(fwrite(sample,1,3,f)!=3)throw std::runtime_error("Insufficient storage writing WAV");
    }
  }
  if(fflush(f)!=0||ferror(f))throw std::runtime_error("WAV export failed");
}

constexpr size_t CHUNK_SECONDS=18, PAD_SECONDS=1, BLEND48=4800;
struct Scratch {
  std::array<std::string,3> raw{},partial{},finals{};
  std::array<FILE*,3> fp{};
  std::array<std::vector<float>,3> tail{};
  size_t pending=0,written=0;
  double peak=0;
  Scratch(const std::string&folder,const std::string&prefix){
    const char*names[]{"Vocals","Drums","Other"};
    for(int i=0;i<3;i++){
      raw[i]=folder+"/"+prefix+"_"+names[i]+".floatpart";
      finals[i]=folder+"/"+prefix+"_"+names[i]+"_24bit.wav";
      partial[i]=finals[i]+".partial";
      fp[i]=fopen(raw[i].c_str(),"wb");
      if(!fp[i])throw std::runtime_error("Cannot create temporary stem");
    }
  }
  ~Scratch(){
    for(auto&f:fp)if(f){fclose(f);f=nullptr;}
    for(auto&p:raw)if(!p.empty())std::remove(p.c_str());
    for(auto&p:partial)if(!p.empty())std::remove(p.c_str());
  }
  void append(const std::array<std::vector<float>,3>&v,size_t begin,size_t count){
    for(int i=0;i<3;i++){
      if(v[i].size()<2*(begin+count))throw std::runtime_error("Bad stem buffer");
      const float*data=v[i].data()+2*begin;
      for(size_t j=0;j<2*count;j++){
        if(!std::isfinite(data[j]))throw std::runtime_error("Invalid stem sample");
        peak=std::max(peak,std::abs(double(data[j])));
      }
      if(fwrite(data,sizeof(float),2*count,fp[i])!=2*count)
        throw std::runtime_error("Scratch storage full");
    }
    written+=count;
  }
  void saveTail(const std::array<std::vector<float>,3>&v,size_t count){
    pending=count;
    for(int i=0;i<3;i++)
      tail[i].assign(v[i].end()-2*count,v[i].end());
  }
  void join(const std::array<std::vector<float>,3>&left){
    if(!pending)return;
    std::array<std::vector<float>,3> joined;
    for(int s=0;s<3;s++){
      joined[s].resize(pending*2);
      for(size_t i=0;i<pending;i++){
        const double t=(i+0.5)/double(pending);
        for(int c=0;c<2;c++)
          joined[s][2*i+c]=float((1-t)*tail[s][2*i+c]+t*left[s][2*i+c]);
      }
    }
    append(joined,0,pending);
    pending=0;
  }
  void finishScratch(){
    if(pending){
      std::array<std::vector<float>,3> end=tail;
      append(end,0,pending);
      pending=0;
    }
    for(auto&f:fp)if(f){
      if(fflush(f)!=0||ferror(f))throw std::runtime_error("Stem scratch flush failed");
      if(fclose(f)!=0)throw std::runtime_error("Stem scratch close failed");
      f=nullptr;
    }
  }
};
std::array<std::vector<float>,3> render(const SherpaOnnxSourceSeparationOutput*voice,
     const SherpaOnnxSourceSeparationOutput*drum,size_t local48,size_t frames){
  std::array<std::vector<float>,3> result;
  for(auto&v:result)v.resize(frames*2);
  for(size_t i=0;i<frames;i++){
    if((i&65535)==0)checkCancel();
    for(int c=0;c<2;c++){
      const double vocal=outputSample(voice,drum,0,c,uint32_t(local48+i));
      const double drums=outputSample(voice,drum,1,c,uint32_t(local48+i));
      const double other=outputSample(voice,drum,2,c,uint32_t(local48+i));
      const double x[]{vocal,drums,other};
      for(int s=0;s<3;s++){
        if(!std::isfinite(x[s]))throw std::runtime_error("Nonfinite separated sample");
        result[s][2*i+c]=float(x[s]);
      }
    }
  }
  return result;
}
void writeHeader(FILE*f,size_t frames){
  const uint64_t bytes=uint64_t(frames)*6;
  if(bytes>0xffffff00ULL)throw std::runtime_error("WAV exceeds classic RIFF maximum");
  if(fwrite("RIFF",1,4,f)!=4)throw std::runtime_error("WAV header failure");
  write32(f,uint32_t(36+bytes));
  if(fwrite("WAVEfmt ",1,8,f)!=8)throw std::runtime_error("WAV header failure");
  write32(f,16);write16(f,1);write16(f,2);
  write32(f,48000);write32(f,288000);write16(f,6);write16(f,24);
  if(fwrite("data",1,4,f)!=4)throw std::runtime_error("WAV header failure");
  write32(f,uint32_t(bytes));
}
std::array<std::string,4> extract(JNIEnv*env,
  const std::string&vocalModel,const std::string&drumModel,const std::string&wav,
  const std::string&folder,const std::string&prefix,int start,
  int duration,jobject callback){
  if(busy.exchange(true))throw std::runtime_error("Already separating three stems");
  struct Guard{~Guard(){busy.store(false);cancelled.store(false);}}guard;
  cancelled.store(false);
  if(start<0||start>720||(duration!=0&&duration!=10&&duration!=60))
    throw std::runtime_error("Unsupported preview duration/position");
  Audio info=loadWav(wav,0,0);
  const size_t rate=info.rate,total=info.totalFrames;
  const size_t preview=size_t(duration)*rate;
  const size_t offset=duration==0?0:std::min(size_t(start)*rate,
                                            total>preview?total-preview:0);
  const size_t selected=duration==0?total:std::min(preview,total-offset);
  const size_t outFrames=(uint64_t(selected)*48000+rate/2)/rate;
  if(outFrames==0)throw std::runtime_error("No audio to separate");
  struct statvfs fs{};
  if(statvfs(folder.c_str(),&fs)!=0)throw std::runtime_error("Cannot check free space");
  const uint64_t needed=uint64_t(outFrames)*42+100'000'000;
  if(uint64_t(fs.f_bavail)*fs.f_frsize<needed)
    throw std::runtime_error("Storage low: need "+std::to_string((needed+999999)/1000000)+
                             " MB free for temporary and 24-bit stems");
  const size_t core=CHUNK_SECONDS*rate;
  const int count=int((selected+core-1)/core);
  Scratch writer(folder,prefix);
  for(int idx=0;idx<count;idx++){
    checkCancel();
    const size_t from=size_t(idx)*core,to=std::min(selected,from+core);
    const size_t padFrom=from>PAD_SECONDS*rate?from-PAD_SECONDS*rate:0;
    const size_t padTo=std::min(selected,to+PAD_SECONDS*rate);
    progress(env,callback,0,idx,count);
    Audio input=loadWav(wav,offset+padFrom,padTo-padFrom);
    OutputPtr vox=runModel(vocalModel,int(input.rate),int(input.frames),
                           input.planes[0].data(),input.planes[1].data());
    input.planes.clear();
    progress(env,callback,1,idx,count);
    const auto&instrumental=vox->stems[1];
    OutputPtr drums=runModel(drumModel,vox->sample_rate,instrumental.n,
                           instrumental.samples[0],instrumental.samples[1]);
    validateOutput(vox.get(),"Vocal inference");
    validateOutput(drums.get(),"Drum inference");
    if(vox->sample_rate!=drums->sample_rate||vox->stems[0].n!=drums->stems[0].n)
      throw std::runtime_error("Second-pass drum sample alignment mismatch");
    progress(env,callback,2,idx,count);
    const size_t from48=(uint64_t(from)*48000+rate/2)/rate;
    const size_t to48=(uint64_t(to)*48000+rate/2)/rate;
    const size_t padded48=(uint64_t(padFrom)*48000+rate/2)/rate;
    const size_t coreLocal=from48-padded48;
    if(writer.pending){
      if(coreLocal<writer.pending)
        throw std::runtime_error("Insufficient left-context samples");
      auto left=render(vox.get(),drums.get(),coreLocal-writer.pending,writer.pending);
      writer.join(left);
    }
    const size_t frames=to48-from48;
    const size_t hold=idx+1<count?std::min(BLEND48,frames):0;
    auto current=render(vox.get(),drums.get(),coreLocal,frames);
    writer.append(current,0,frames-hold);
    if(hold)writer.saveTail(current,hold);
  }
  writer.finishScratch();
  if(writer.written!=outFrames)throw std::runtime_error("Assembled WAV frame count mismatch");
  const double gain=writer.peak>0.9999?0.9999/writer.peak:1.;
  for(int st=0;st<3;st++){
    checkCancel();
    progress(env,callback,3,st,count);
    FileHandle in(writer.raw[st],"rb");
    FileHandle out(writer.partial[st],"wb");
    writeHeader(out.get(),outFrames);
    std::vector<float> block(16384);
    std::vector<uint8_t> pcm(16384*3);
    size_t done=0;
    while(done<outFrames){
      checkCancel();
      const size_t n=std::min(size_t(8192),outFrames-done);
      if(fread(block.data(),sizeof(float),2*n,in.get())!=2*n)
        throw std::runtime_error("Scratch stem is incomplete");
      for(size_t i=0;i<2*n;i++){
        const double x=std::max(-1.,std::min(0.99999988079,double(block[i])*gain));
        const int32_t val=int32_t(std::llrint(x*8388608.));
        pcm[3*i]=uint8_t(val);pcm[3*i+1]=uint8_t(val>>8);pcm[3*i+2]=uint8_t(val>>16);
      }
      if(fwrite(pcm.data(),1,6*n,out.get())!=6*n)
        throw std::runtime_error("24-bit WAV disk write error");
      done+=n;
    }
    if(fflush(out.get())!=0||ferror(out.get()))throw std::runtime_error("WAV flush error");
  }
  for(int st=0;st<3;st++){
    if(std::rename(writer.partial[st].c_str(),writer.finals[st].c_str())!=0)
      throw std::runtime_error("Cannot finalize stem WAV");
    writer.partial[st].clear();
  }
  progress(env,callback,4,count,count);
  std::array<std::string,4> result{};
  for(int st=0;st<3;st++)result[st]=writer.finals[st];
  result[3]=std::string(duration==0?"Full song":duration==60?"60s test":"10s test")+
     " · "+std::to_string(count)+" sections · shared gain "+
     std::to_string(20*std::log10(gain))+" dB";
  return result;
}

std::string jstr(JNIEnv* env,jstring s) {
  if(!s) throw std::runtime_error("Missing file path");
  const char *p=env->GetStringUTFChars(s,nullptr);
  if(!p) throw std::runtime_error("Cannot decode file path");
  std::string result(p); env->ReleaseStringUTFChars(s,p);
  return result;
}
void javaError(JNIEnv *env, const std::string& msg) {
  jclass ex=env->FindClass("java/lang/IllegalStateException");
  if(ex) env->ThrowNew(ex,msg.c_str());
}
}


extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dennis_stemstudio_ThreeStemNative_separate(JNIEnv*env,jclass,
  jstring vocalModel,jstring drumModel,jstring wav,jstring folder,
  jstring prefix,jint startSeconds,jint durationSeconds,jobject listener){
  try{
    const auto results=extract(env,jstr(env,vocalModel),jstr(env,drumModel),
       jstr(env,wav),jstr(env,folder),jstr(env,prefix),int(startSeconds),int(durationSeconds),listener);
    jclass cls=env->FindClass("java/lang/String");
    if(!cls)return nullptr;
    jobjectArray values=env->NewObjectArray(4,cls,nullptr);
    for(int i=0;i<4;i++){
      jstring str=env->NewStringUTF(results[i].c_str());
      env->SetObjectArrayElement(values,i,str);
      env->DeleteLocalRef(str);
    }
    return values;
  }catch(const std::exception&error){
    if(!env->ExceptionCheck())javaError(env,error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_dennis_stemstudio_ThreeStemNative_cancel(JNIEnv*,jclass){
  cancelled.store(true);
}
