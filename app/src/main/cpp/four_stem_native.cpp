// Experimental HT-Demucs ONNX four-source engine. The approved 2-stem file is untouched.
// Four outputs [drums,bass,other,vocals], 44.1kHz, stereo, 343980 frames/segment.
// CPU-only ONNX Runtime, quarter overlap, deterministic summation and shared peak protection.
#include <jni.h>
#include <atomic>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <climits>
#include <cstdio>
#include <cstring>
#include <memory>
#include <limits>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
constexpr int MODEL_RATE = 44100;
constexpr int OUTPUT_RATE = 48000;
constexpr int SEGMENT = 343980;
constexpr int OVERLAP = SEGMENT/4;
constexpr int STRIDE = SEGMENT-OVERLAP;
constexpr double PI=3.14159265358979323846;
std::atomic<bool> cancelled{false};
std::atomic<bool> inProgress{false};

void require(bool ok, const std::string &error){if(!ok)throw std::runtime_error(error);}
uint16_t rd16(const uint8_t*p){return uint16_t(p[0])|(uint16_t(p[1])<<8);}
uint32_t rd32(const uint8_t*p){return uint32_t(p[0])|(uint32_t(p[1])<<8)|(uint32_t(p[2])<<16)|(uint32_t(p[3])<<24);}
void wr16(FILE*f,uint16_t n){uint8_t b[]{uint8_t(n),uint8_t(n>>8)};require(fwrite(b,1,2,f)==2,"WAV write error");}
void wr32(FILE*f,uint32_t n){uint8_t b[]{uint8_t(n),uint8_t(n>>8),uint8_t(n>>16),uint8_t(n>>24)};require(fwrite(b,1,4,f)==4,"WAV write error");}
void cancelledCheck(){if(cancelled.load())throw std::runtime_error("Four-stem separation cancelled");}
std::string jstr(JNIEnv*env,jstring val){
  require(val!=nullptr,"Missing file path");
  const char *p=env->GetStringUTFChars(val,nullptr);
  require(p!=nullptr,"Cannot read file path");
  std::string result(p);env->ReleaseStringUTFChars(val,p);return result;
}
void javaError(JNIEnv*env,const std::string&text){
  jclass cls=env->FindClass("java/lang/IllegalStateException");
  if(cls)env->ThrowNew(cls,text.c_str());
}
using File=std::unique_ptr<FILE,decltype(&fclose)>;
File fileOpen(const std::string&path,const char*mode){
  File f(fopen(path.c_str(),mode),fclose);
  require(bool(f),"Cannot open file: "+path);return f;
}
struct Audio{
  int rate=0;
  size_t frames=0;
  std::array<std::vector<float>,2> chan;
};
Audio readWav(const std::string&path,int previewSeconds){
  auto f=fileOpen(path,"rb");
  uint8_t hdr[12];require(fread(hdr,1,12,f.get())==12 && !memcmp(hdr,"RIFF",4) &&
    !memcmp(hdr+8,"WAVE",4),"Four-stem mode accepts RIFF/WAV only");
  int fmt=0,nch=0,bits=0,align=0,rate=0;
  long at=-1;uint32_t bytes=0;
  for(int i=0;i<4096;i++){
    uint8_t head[8];if(fread(head,1,8,f.get())!=8)break;
    uint32_t length=rd32(head+4);long here=ftell(f.get());
    require(here>=0 && length<0x7FFFFFFF && long(length)<=LONG_MAX-here-1,"Invalid WAV chunks");
    if(!memcmp(head,"fmt ",4)){
      require(length>=16 && length<=256,"Invalid WAV fmt header");
      uint8_t data[256]{};require(fread(data,1,length,f.get())==length,"Truncated WAV fmt");
      fmt=rd16(data);nch=rd16(data+2);rate=int(rd32(data+4));align=rd16(data+12);bits=rd16(data+14);
      if(fmt==0xfffe && length>=40)fmt=rd16(data+24);
    }else if(!memcmp(head,"data",4)){at=here;bytes=length;if(fmt)break;}
    require(fseek(f.get(),here+long(length)+(length&1),SEEK_SET)==0,"WAV seeking failed");
  }
  require(at>=0 && bytes>0 && (nch==1 || nch==2),"WAV must be mono or stereo");
  require(rate==44100 || rate==48000,"Input must be 44.1 or 48 kHz");
  require((fmt==1 && (bits==16 || bits==24 || bits==32)) || (fmt==3 && bits==32),
      "Input must be PCM 16/24/32 or floating-point 32-bit WAV");
  require(align==nch*(bits/8) && (bytes%align)==0,"Invalid WAV block alignment");
  const size_t wholeFrames=bytes/align;
  const size_t frames=previewSeconds>0 ? std::min(wholeFrames,size_t(rate)*size_t(previewSeconds)) : wholeFrames;
  require(frames>=1 && frames<=size_t(rate)*12*60,"Song must be <=12 minutes");
  require(fseek(f.get(),at,SEEK_SET)==0,"Cannot seek to samples");
  Audio audio;audio.rate=rate;audio.frames=frames;
  audio.chan[0].resize(frames);audio.chan[1].resize(frames);
  uint8_t buf[4]{};
  for(size_t n=0;n<frames;n++){
    if((n&262143)==0)cancelledCheck();
    for(int c=0;c<nch;c++){
      require(fread(buf,1,bits/8,f.get())==size_t(bits/8),"Truncated audio samples");
      float v;
      if(fmt==3){uint32_t raw=rd32(buf);memcpy(&v,&raw,4);}
      else if(bits==16)v=int16_t(rd16(buf))/32768.f;
      else if(bits==24){int32_t raw=int32_t(buf[0])|(int32_t(buf[1])<<8)|(int32_t(buf[2])<<16);
        if(raw&0x800000)raw|=~0xffffff;v=float(raw)/8388608.f;}
      else v=int32_t(rd32(buf))/2147483648.f;
      require(std::isfinite(v),"Non-finite samples in WAV");
      audio.chan[c][n]=v;
    }
    if(nch==1)audio.chan[1][n]=audio.chan[0][n];
  }
  return audio;
}

class SincResampler{
  static constexpr int TAP=64;
  int src,dst,phases;
  std::vector<std::array<double,TAP>> kernels;
public:
  SincResampler(int from,int to):src(from),dst(to),phases(to/std::gcd(from,to)),kernels(phases){
    const double fc=0.985*std::min(1.,double(dst)/src);
    for(int p=0;p<phases;p++){
      const double frac=double(p)/phases;
      double sum=0;
      for(int t=0;t<TAP;t++){
        const double x=double(t-31)-frac;
        const double s=std::abs(x)<1e-12?fc:std::sin(PI*fc*x)/(PI*x);
        const double w=0.42+0.5*std::cos(PI*x/32.)+0.08*std::cos(2*PI*x/32.);
        kernels[p][t]=s*w;sum+=s*w;
      }
      require(std::abs(sum)>1e-10,"Resampling kernel error");
      for(double &v:kernels[p])v/=sum;
    }
  }
  size_t count(size_t inputFrames)const{return size_t((uint64_t(inputFrames)*dst+src/2)/src);}
  double value(const float* samples,size_t size,size_t outputIndex)const{
    const uint64_t pos=uint64_t(outputIndex)*uint64_t(src);
    const size_t at=size_t(pos/dst);
    const size_t phase=(pos%dst)/std::gcd(src,dst);
    const auto&kernel=kernels[phase];
    double acc=0,weights=0;
    for(int t=0;t<TAP;t++){
      const int64_t index=int64_t(at)+t-31;
      if(index>=0 && uint64_t(index)<size){
        acc+=kernel[t]*samples[index];weights+=kernel[t];
      }
    }
    require(std::abs(weights)>1e-10,"Resampling boundary failure");
    return acc/weights;
  }
  std::vector<float> convert(const std::vector<float>&srcSamples)const{
    const size_t n=count(srcSamples.size());
    std::vector<float> out(n);
    for(size_t i=0;i<n;i++){if((i&131071)==0)cancelledCheck();
      out[i]=float(value(srcSamples.data(),srcSamples.size(),i));}
    return out;
  }
};

const OrtApi* api=nullptr;
void ortCheck(OrtStatus*status){
  if(!status)return;
  std::string message=api?api->GetErrorMessage(status):"Unknown ONNX Runtime error";
  if(api)api->ReleaseStatus(status);
  throw std::runtime_error("Four-stem ONNX Runtime: "+message);
}
struct OrtSessionHolder{
  OrtEnv*env=nullptr;OrtSessionOptions*opts=nullptr;OrtSession*session=nullptr;
  OrtMemoryInfo*memory=nullptr;
  ~OrtSessionHolder(){
    if(!api)return;
    if(memory)api->ReleaseMemoryInfo(memory);
    if(session)api->ReleaseSession(session);
    if(opts)api->ReleaseSessionOptions(opts);
    if(env)api->ReleaseEnv(env);
  }
};
void validateTensor(OrtSession*session,bool input){
  OrtTypeInfo*type=nullptr;
  ortCheck(input?api->SessionGetInputTypeInfo(session,0,&type):api->SessionGetOutputTypeInfo(session,0,&type));
  const OrtTensorTypeAndShapeInfo*info=nullptr;
  ortCheck(api->CastTypeInfoToTensorInfo(type,&info));
  require(info!=nullptr,"ONNX input/output must be tensors");
  ONNXTensorElementDataType elem;
  ortCheck(api->GetTensorElementType(info,&elem));
  require(elem==ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,"Four-stem model I/O must be float32");
  size_t n=0;ortCheck(api->GetDimensionsCount(info,&n));
  const std::vector<int64_t> wanted=input?
    std::vector<int64_t>{1,2,SEGMENT}:std::vector<int64_t>{1,4,2,SEGMENT};
  require(n==wanted.size(),"Unsupported model tensor rank");
  std::vector<int64_t> dims(n);ortCheck(api->GetDimensions(info,dims.data(),n));
  api->ReleaseTypeInfo(type);
  require(dims==wanted,"Incompatible HT-Demucs model tensor shape");
}
void progress(JNIEnv*env,jobject listener,int done,int total){
  if(!listener)return;
  jclass cls=env->GetObjectClass(listener);
  if(!cls)throw std::runtime_error("Progress callback class unavailable");
  jmethodID method=env->GetMethodID(cls,"onProgress","(II)V");
  if(method)env->CallVoidMethod(listener,method,done,total);
  env->DeleteLocalRef(cls);
  if(env->ExceptionCheck())throw std::runtime_error("Progress callback threw an exception");
}
void writeWave(const std::string&path,const std::array<std::vector<float>,2>&data,
               size_t outputFrames,const SincResampler&resample,bool sameRate,double gain){
  const uint64_t bytes=uint64_t(outputFrames)*2*3;
  require(bytes<0xffffff00ULL,"WAV output too large");
  auto f=fileOpen(path,"wb");
  require(fwrite("RIFF",1,4,f.get())==4,"WAV write failed");wr32(f.get(),uint32_t(36+bytes));
  require(fwrite("WAVEfmt ",1,8,f.get())==8,"WAV header failed");wr32(f.get(),16);
  wr16(f.get(),1);wr16(f.get(),2);wr32(f.get(),OUTPUT_RATE);wr32(f.get(),OUTPUT_RATE*6);
  wr16(f.get(),6);wr16(f.get(),24);require(fwrite("data",1,4,f.get())==4,"WAV header failed");
  wr32(f.get(),uint32_t(bytes));
  uint8_t b[6];
  for(size_t i=0;i<outputFrames;i++){
    if((i&131071)==0)cancelledCheck();
    for(int c=0;c<2;c++){
      double x=sameRate?double(data[c][i]):resample.value(data[c].data(),data[c].size(),i);
      x=std::max(-1.,std::min(0.99999988079,x*gain));
      int32_t v=int32_t(std::llrint(x*8388608.0));
      b[3*c]=uint8_t(v);b[3*c+1]=uint8_t(v>>8);b[3*c+2]=uint8_t(v>>16);
    }
    require(fwrite(b,1,6,f.get())==6,"WAV disk write error");
  }
  require(fflush(f.get())==0 && !ferror(f.get()),"WAV flush error");
}

// ================================================================
// v0.1.4: bounded-memory streaming four-stem engine.
//
// Unlike the v0.1.2 full-song array implementation, this engine keeps
// at most one 7.8-second model input/output segment and its 25% overlap
// in RAM. Temporary raw float stereo stems are spooled to disk, then
// sample-accurately resampled and exported after a global peak scan.
// ================================================================
class StreamWav {
  File file;
  int fmt=0,chans=0,bits=0,align=0;
  long at=-1;
public:
  int rate=0;
  size_t frames=0;
  explicit StreamWav(const std::string&path):file(fileOpen(path,"rb")){
    uint8_t hdr[12];
    require(fread(hdr,1,12,file.get())==12 && !memcmp(hdr,"RIFF",4) &&
      !memcmp(hdr+8,"WAVE",4),"Only RIFF WAV input is supported");
    uint32_t bytes=0;
    for(int i=0;i<4096;i++){
      uint8_t head[8];if(fread(head,1,8,file.get())!=8)break;
      uint32_t length=rd32(head+4);
      long here=ftell(file.get());
      require(here>=0 && length<0x7FFFFFFF && long(length)<=LONG_MAX-here-1,"Invalid WAV chunk length");
      if(!memcmp(head,"fmt ",4)){
        require(length>=16 && length<=256,"Invalid WAV format chunk");
        uint8_t b[256]{};
        require(fread(b,1,length,file.get())==length,"Truncated WAV fmt");
        fmt=rd16(b);chans=rd16(b+2);rate=int(rd32(b+4));
        align=rd16(b+12);bits=rd16(b+14);
        if(fmt==0xfffe && length>=40)fmt=rd16(b+24);
      }else if(!memcmp(head,"data",4)){at=here;bytes=length;if(fmt)break;}
      require(fseek(file.get(),here+long(length)+(length&1),SEEK_SET)==0,"WAV seek error");
    }
    require(at>=0 && bytes>0 && (chans==1 || chans==2),"WAV requires mono or stereo input");
    require(rate==44100 || rate==48000,"WAV input must be 44.1 or 48 kHz");
    require((fmt==1 && (bits==16 || bits==24 || bits==32)) ||
      (fmt==3 && bits==32),"Unsupported WAV sample encoding");
    require(align==chans*bits/8 && bytes%align==0,"WAV block alignment invalid");
    frames=bytes/align;
    require(frames>0 && frames<=size_t(rate)*12*60,"Input WAV length exceeds 12 minutes");
  }
  std::array<std::vector<float>,2> block(size_t first,size_t count){
    require(first<=frames && count<=frames-first,"Audio read would exceed WAV");
    std::array<std::vector<float>,2> data;
    for(auto&channel:data)channel.resize(count);
    const size_t bytes=count*size_t(align);
    require(bytes<=size_t(INT_MAX),"Audio block exceeds size limit");
    std::vector<uint8_t> raw(bytes);
    require(fseek(file.get(),at+long(first*size_t(align)),SEEK_SET)==0,"Unable to seek WAV samples");
    require(fread(raw.data(),1,bytes,file.get())==bytes,"Audio WAV is truncated");
    for(size_t i=0;i<count;i++){
      if((i&131071)==0)cancelledCheck();
      const uint8_t*frame=raw.data()+i*align;
      for(int c=0;c<chans;c++){
        const uint8_t*b=frame+c*(bits/8);
        float value=0;
        if(fmt==3){uint32_t rawBits=rd32(b);memcpy(&value,&rawBits,4);}
        else if(bits==16)value=int16_t(rd16(b))/32768.f;
        else if(bits==24){
          int32_t v=int32_t(b[0])|(int32_t(b[1])<<8)|(int32_t(b[2])<<16);
          if(v&0x800000)v|=~0xffffff;
          value=float(v)/8388608.f;
        }else value=int32_t(rd32(b))/2147483648.f;
        require(std::isfinite(value),"Non-finite WAV samples");
        data[c][i]=value;
      }
      if(chans==1)data[1][i]=data[0][i];
    }
    return data;
  }
};

double windowedSample(const SincResampler&resampler,const std::vector<float>&buf,
       size_t sourceOffset,size_t wholeSource,size_t absoluteOutFrame,int srcRate,int dstRate){
  const uint64_t pos=uint64_t(absoluteOutFrame)*uint64_t(srcRate);
  const size_t at=size_t(pos/dstRate);
  const size_t phase=(pos%dstRate)/std::gcd(srcRate,dstRate);
  // SincResampler coefficients are private: allow absolute windows via a
  // local interpolation using the same 64-tap Blackman window.
  constexpr int TAPS=64;
  constexpr double pi=3.14159265358979323846;
  const double fc=0.985*std::min(1.,double(dstRate)/srcRate);
  const double fraction=double(phase)/(dstRate/std::gcd(srcRate,dstRate));
  double accumulated=0,weight=0;
  for(int k=0;k<TAPS;k++){
    const int64_t ix=int64_t(at)+k-31;
    if(ix<0 || uint64_t(ix)>=wholeSource)continue;
    require(size_t(ix)>=sourceOffset && size_t(ix)<sourceOffset+buf.size(),
      "Resampler read window missing data");
    const double x=k-31-fraction;
    const double sinc=std::abs(x)<1.e-12?fc:std::sin(pi*fc*x)/(pi*x);
    const double win=0.42+0.5*std::cos(pi*x/32.)+0.08*std::cos(2*pi*x/32.);
    const double tap=sinc*win;
    accumulated+=tap*buf[size_t(ix)-sourceOffset];
    weight+=tap;
  }
  require(std::abs(weight)>1.e-10,"Resampling boundary failed");
  return accumulated/weight;
}

void loadModelChunk(StreamWav&input,size_t selectionStart,size_t selectedFrames,
          size_t modelStart,size_t modelCount,std::vector<float>&mix){
  std::fill(mix.begin(),mix.end(),0.f);
  if(input.rate==MODEL_RATE){
    auto b=input.block(selectionStart+modelStart,modelCount);
    for(int c=0;c<2;c++)std::copy(b[c].begin(),b[c].end(),mix.begin()+c*SEGMENT);
    return;
  }
  // Input is 48 kHz; model is 44.1 kHz. Read only this segment, plus
  // sinc support at the edges. Do not downsample the entire song in RAM.
  const size_t readFrom= size_t(uint64_t(modelStart)*48000/44100);
  const size_t start=readFrom>34?readFrom-34:0;
  const size_t last=size_t(uint64_t(modelStart+modelCount-1)*48000/44100)+35;
  const size_t end=std::min(selectedFrames,last);
  require(end>start,"Invalid 48 kHz input window");
  auto b=input.block(selectionStart+start,end-start);
  SincResampler down(48000,44100);
  for(int c=0;c<2;c++){
    for(size_t j=0;j<modelCount;j++){
      if((j&131071)==0)cancelledCheck();
      mix[size_t(c)*SEGMENT+j]=float(windowedSample(down,b[c],start,selectedFrames,
                                                modelStart+j,48000,44100));
    }
  }
}

struct ScratchFiles {
  std::array<std::string,4> paths{};
  std::array<FILE*,4> writer{};
  std::array<std::string,4> partial{};
  bool keepFinal=false;
  explicit ScratchFiles(const std::string&dir,const std::string&prefix){
    static const char*names[]{"Drums","Bass","Other","Vocals"};
    for(int i=0;i<4;i++){
      paths[i]=dir+"/"+prefix+"_"+names[i]+"_model44100.rawpart";
      writer[i]=fopen(paths[i].c_str(),"wb");
      require(writer[i]!=nullptr,"Cannot create temporary stem buffer");
    }
  }
  void finish(){
    for(auto&f:writer){
      if(f){require(fflush(f)==0 && !ferror(f),"Scratch WAV flush failure");
        require(fclose(f)==0,"Scratch close failure");f=nullptr;}
    }
  }
  ~ScratchFiles(){
    for(auto&f:writer){if(f)fclose(f);}
    for(const auto&p:paths)if(!p.empty())std::remove(p.c_str());
    for(const auto&p:partial)if(!p.empty())std::remove(p.c_str());
  }
};

void appendRaw(ScratchFiles&scratch,float* prediction,
               size_t count,const std::array<std::array<std::vector<float>,2>,4>&last,
               bool hasTail){
  constexpr size_t BLOCK=4096;
  std::array<std::vector<float>,4> buffers;
  for(auto&buf:buffers)buf.resize(BLOCK*2);
  for(size_t base=0;base<count;base+=BLOCK){
    cancelledCheck();
    const size_t n=std::min(BLOCK,count-base);
    for(size_t j=0;j<n;j++){
      const size_t local=base+j;
      const float fraction=hasTail && local<OVERLAP ?
          float(local+0.5f)/float(OVERLAP):1.f;
      for(int st=0;st<4;st++){
        for(int c=0;c<2;c++){
          const float current=prediction[(st*2+c)*size_t(SEGMENT)+local];
          float value=current;
          if(fraction<1.f)value=last[st][c][local]*(1.f-fraction)+current*fraction;
          require(std::isfinite(value),"Model returned invalid audio");
          buffers[st][j*2+c]=value;
        }
      }
    }
    for(int st=0;st<4;st++)
      require(fwrite(buffers[st].data(),sizeof(float),n*2,scratch.writer[st])==n*2,
              "Temporary stem write failed");
  }
}

class FloatStem {
  File file;
  size_t frames;
public:
  FloatStem(const std::string&path,size_t length):file(fileOpen(path,"rb")),frames(length){}
  std::array<std::vector<float>,2> block(size_t start,size_t count){
    require(start<=frames && count<=frames-start,"Float stem read exceeds end");
    require(fseek(file.get(),long(start*2*sizeof(float)),SEEK_SET)==0,"Stem seek error");
    std::vector<float> interleaved(count*2);
    require(fread(interleaved.data(),sizeof(float),interleaved.size(),file.get())==interleaved.size(),
      "Float stem scratch data is incomplete");
    std::array<std::vector<float>,2> channels;
    for(auto&v:channels)v.resize(count);
    for(size_t i=0;i<count;i++){
      channels[0][i]=interleaved[i*2];
      channels[1][i]=interleaved[i*2+1];
    }
    return channels;
  }
};

template<class Callback>
void each48Block(FloatStem&source,size_t modelFrames,size_t outFrames,Callback&&callback){
  constexpr size_t BLOCK=8192;
  SincResampler up(MODEL_RATE,OUTPUT_RATE);
  (void)up;
  for(size_t base=0;base<outFrames;base+=BLOCK){
    cancelledCheck();
    const size_t n=std::min(BLOCK,outFrames-base);
    const size_t at=size_t(uint64_t(base)*MODEL_RATE/OUTPUT_RATE);
    const size_t start=at>34?at-34:0;
    const size_t finalAt=size_t(uint64_t(base+n-1)*MODEL_RATE/OUTPUT_RATE)+35;
    const size_t end=std::min(modelFrames,finalAt);
    require(end>start,"Invalid output resampling window");
    auto b=source.block(start,end-start);
    std::vector<float> output(n*2);
    for(size_t j=0;j<n;j++){
      for(int c=0;c<2;c++){
        const double value=windowedSample(up,b[c],start,modelFrames,
                                          base+j,MODEL_RATE,OUTPUT_RATE);
        require(std::isfinite(value),"Nonfinite 48 kHz output");
        output[j*2+c]=float(value);
      }
    }
    callback(output);
  }
}

void waveHeader(FILE*f,size_t frames){
  const uint64_t bytes=uint64_t(frames)*2*3;
  require(bytes<0xffffff00ULL,"WAV exceeds classic RIFF limit");
  require(fwrite("RIFF",1,4,f)==4,"WAV header write failed");wr32(f,uint32_t(bytes+36));
  require(fwrite("WAVEfmt ",1,8,f)==8,"WAV header write failed");wr32(f,16);
  wr16(f,1);wr16(f,2);wr32(f,OUTPUT_RATE);wr32(f,OUTPUT_RATE*6);
  wr16(f,6);wr16(f,24);
  require(fwrite("data",1,4,f)==4,"WAV header failed");wr32(f,uint32_t(bytes));
}

struct RunningGuard{RunningGuard(){require(!inProgress.exchange(true),"Four-stem processing already active");cancelled.store(false);}
  ~RunningGuard(){inProgress.store(false);cancelled.store(false);}};

std::array<std::string,5> separate(JNIEnv*env,const std::string&model,const std::string&wav,
           const std::string&folder,const std::string&prefix,int previewSeconds,
           int startSeconds,jobject listener){
  RunningGuard running;
  require(previewSeconds==0 || (previewSeconds>=1 && previewSeconds<=30),
      "Preview must be 1-30 seconds, or zero for full song");
  require(startSeconds>=0 && startSeconds<=720,"Preview offset out of range");
  StreamWav input(wav);
  const size_t totalFrames=input.frames;
  const size_t wantedPreview=size_t(previewSeconds)*size_t(input.rate);
  // If a requested preview begins too near the end, choose the last 10s
  // instead of silently exporting a tiny invalid section.
  const size_t offset=previewSeconds>0 ?
       std::min(size_t(startSeconds)*input.rate,
                totalFrames>wantedPreview?totalFrames-wantedPreview:0):0;
  const size_t selectedFrames=previewSeconds>0 ?
       std::min(wantedPreview,totalFrames-offset):totalFrames;
  require(selectedFrames>0,"Empty audio selection");
  const size_t modelFrames=size_t(
       (uint64_t(selectedFrames)*MODEL_RATE+input.rate/2)/input.rate);
  require(modelFrames>0,"No source model frames");
  const size_t nchunks=modelFrames<=SEGMENT?1:
       1+(modelFrames-SEGMENT+STRIDE-1)/STRIDE;
  require(nchunks<=200,"Song has too many inference blocks");

  // Demucs has different input/output protocol from the protected UVR engine.
  api=OrtGetApiBase()->GetApi(ORT_API_VERSION);
  require(api!=nullptr,"ONNX Runtime C API mismatch");
  OrtSessionHolder sess;
  ortCheck(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING,"DennisFourStem",&sess.env));
  ortCheck(api->CreateSessionOptions(&sess.opts));
  ortCheck(api->SetIntraOpNumThreads(sess.opts,1));
  ortCheck(api->SetInterOpNumThreads(sess.opts,1));
  ortCheck(api->DisableCpuMemArena(sess.opts));
  ortCheck(api->DisableMemPattern(sess.opts));
  ortCheck(api->SetSessionGraphOptimizationLevel(sess.opts,ORT_ENABLE_ALL));
  progress(env,listener,0,int(nchunks));
  ortCheck(api->CreateSession(sess.env,model.c_str(),sess.opts,&sess.session));
  size_t ni=0,no=0;
  ortCheck(api->SessionGetInputCount(sess.session,&ni));
  ortCheck(api->SessionGetOutputCount(sess.session,&no));
  require(ni==1 && no==1,"Incompatible four-stem ONNX tensor count");
  validateTensor(sess.session,true);validateTensor(sess.session,false);
  OrtAllocator*allocator=nullptr;
  ortCheck(api->GetAllocatorWithDefaultOptions(&allocator));
  char *inName=nullptr,*outName=nullptr;
  ortCheck(api->SessionGetInputName(sess.session,0,allocator,&inName));
  ortCheck(api->SessionGetOutputName(sess.session,0,allocator,&outName));
  const std::string in=inName?inName:"",out=outName?outName:"";
  if(inName)ortCheck(api->AllocatorFree(allocator,inName));
  if(outName)ortCheck(api->AllocatorFree(allocator,outName));
  require(in=="mix" && out=="stems","Model is not compatible HT-Demucs (mix -> stems)");
  ortCheck(api->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&sess.memory));

  // Exactly one chunk of input plus one 25% overlap of output in RAM.
  std::vector<float> mix(2*SEGMENT);
  std::array<std::array<std::vector<float>,2>,4> tail;
  for(auto&stem:tail)for(auto&ch:stem)ch.resize(OVERLAP);
  bool hasTail=false;
  const int64_t dims[]{1,2,SEGMENT};
  const char*inputNames[]{"mix"},*outputNames[]{"stems"};
  ScratchFiles scratch(folder,prefix);
  size_t writtenFrames=0;
  for(size_t chunk=0;chunk<nchunks;chunk++){
    cancelledCheck();
    const size_t start=chunk*size_t(STRIDE);
    const size_t count=std::min(size_t(SEGMENT),modelFrames-start);
    loadModelChunk(input,offset,selectedFrames,start,count,mix);
    OrtValue *inTensor=nullptr,*outTensor=nullptr;
    ortCheck(api->CreateTensorWithDataAsOrtValue(sess.memory,mix.data(),
        mix.size()*sizeof(float),dims,3,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&inTensor));
    try{
      const OrtValue*inputArray[]{inTensor};
      ortCheck(api->Run(sess.session,nullptr,inputNames,inputArray,1,
                        outputNames,1,&outTensor));
      OrtTensorTypeAndShapeInfo*shape=nullptr;
      ortCheck(api->GetTensorTypeAndShape(outTensor,&shape));
      size_t rank=0;ortCheck(api->GetDimensionsCount(shape,&rank));
      require(rank==4,"HT-Demucs output must have 4 dimensions");
      int64_t actual[4]{};
      ortCheck(api->GetDimensions(shape,actual,4));
      api->ReleaseTensorTypeAndShapeInfo(shape);
      require(actual[0]==1 && actual[1]==4 && actual[2]==2 && actual[3]==SEGMENT,
          "HT-Demucs produced an unexpected output shape");
      float*predicted=nullptr;
      ortCheck(api->GetTensorMutableData(outTensor,reinterpret_cast<void**>(&predicted)));
      require(predicted!=nullptr,"Model returned null audio");
      // Only the first STRIDE is finalized while a further chunk exists.
      const bool last=(chunk+1==nchunks);
      const size_t finalized=last?count:std::min(size_t(STRIDE),count);
      appendRaw(scratch,predicted,finalized,tail,hasTail);
      writtenFrames+=finalized;
      if(!last){
        require(count==SEGMENT,"Non-final model chunk must be complete");
        for(int st=0;st<4;st++)for(int ch=0;ch<2;ch++){
          const float *src=predicted+(size_t(st)*2+ch)*SEGMENT+STRIDE;
          std::copy_n(src,OVERLAP,tail[st][ch].begin());
        }
        hasTail=true;
      }
      api->ReleaseValue(outTensor);outTensor=nullptr;
      api->ReleaseValue(inTensor);inTensor=nullptr;
    }catch(...){
      if(outTensor)api->ReleaseValue(outTensor);
      if(inTensor)api->ReleaseValue(inTensor);
      throw;
    }
    progress(env,listener,int(chunk+1),int(nchunks));
  }
  require(writtenFrames==modelFrames,"Overlap reconstruction lost or duplicated frames");
  scratch.finish();

  // Make the 48 kHz outputs have the same precise length as selected
  // original 48 kHz input, or the same rounded 44.1->48 resampled length.
  const size_t outputFrames=input.rate==OUTPUT_RATE?selectedFrames:
      size_t((uint64_t(selectedFrames)*OUTPUT_RATE+MODEL_RATE/2)/MODEL_RATE);
  require(outputFrames>0,"No output frames");
  const char*labels[]{"Vocals","Drums","Bass","Other"};
  const int order[]{3,0,1,2};
  progress(env,listener,-1,int(nchunks));
  // Shared gain from the largest true output sample, across all four stems.
  double peak=0;
  for(int st=0;st<4;st++){
    cancelledCheck();
    FloatStem reader(scratch.paths[st],modelFrames);
    each48Block(reader,modelFrames,outputFrames,
      [&](const std::vector<float>&block){
        for(float sample:block){
          require(std::isfinite(sample),"Invalid resampled sample");
          peak=std::max(peak,std::abs(double(sample)));
        }
      });
  }
  const double gain=peak>0.9999?0.9999/peak:1.;
  progress(env,listener,-2,int(nchunks));
  std::array<std::string,5> result{};
  for(int i=0;i<4;i++){
    cancelledCheck();
    const std::string target=folder+"/"+prefix+"_"+labels[i]+"_24bit.wav";
    const std::string partial=target+".partial";
    scratch.partial[i]=partial;
    auto out=fileOpen(partial,"wb");
    waveHeader(out.get(),outputFrames);
    FloatStem reader(scratch.paths[order[i]],modelFrames);
    each48Block(reader,modelFrames,outputFrames,
      [&](const std::vector<float>&block){
        std::vector<uint8_t> pcm(block.size()*3);
        for(size_t n=0;n<block.size();n++){
          const double x=std::max(-1.,std::min(0.99999988079,double(block[n])*gain));
          int32_t v=int32_t(std::llrint(x*8388608.0));
          pcm[3*n]=uint8_t(v);
          pcm[3*n+1]=uint8_t(v>>8);
          pcm[3*n+2]=uint8_t(v>>16);
        }
        require(fwrite(pcm.data(),1,pcm.size(),out.get())==pcm.size(),
           "Insufficient storage writing 24-bit WAV");
      });
    require(fflush(out.get())==0 && !ferror(out.get()),"Cannot flush output WAV");
    out.reset();
    result[i]=target;
  }
  // Only promote valid completely-written files.
  for(int i=0;i<4;i++){
    if(std::rename(scratch.partial[i].c_str(),result[i].c_str())!=0)
      throw std::runtime_error("Cannot finalize "+result[i]);
    scratch.partial[i].clear();
  }
  result[4]=std::string(previewSeconds>0?"Preview":"Full song")+
      " complete · 4 x 48kHz stereo 24-bit · "+std::to_string(nchunks)+
      " Demucs chunks · shared gain "+std::to_string(20*std::log10(gain))+" dB";
  return result;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_dennis_stemstudio_FourStemNative_cancel(JNIEnv*,jclass){cancelled.store(true);}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dennis_stemstudio_FourStemNative_separate(JNIEnv*env,jclass,jstring model,jstring wav,
                   jstring folder,jstring prefix,jint previewSeconds,jint startSeconds,jobject progressListener){
  try{
    auto results=separate(env,jstr(env,model),jstr(env,wav),jstr(env,folder),
                          jstr(env,prefix),int(previewSeconds),int(startSeconds),progressListener);
    jclass cls=env->FindClass("java/lang/String");
    if(!cls)return nullptr;
    jobjectArray array=env->NewObjectArray(5,cls,nullptr);
    for(int i=0;i<5;i++){jstring val=env->NewStringUTF(results[i].c_str());
      env->SetObjectArrayElement(array,i,val);env->DeleteLocalRef(val);}
    return array;
  }catch(const std::exception&e){
    if(!env->ExceptionCheck())javaError(env,e.what());
    return nullptr;
  }catch(...){if(!env->ExceptionCheck())javaError(env,"Unexpected four-stem processing failure");return nullptr;}
}
