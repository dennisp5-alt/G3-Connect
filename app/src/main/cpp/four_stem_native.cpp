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
Audio readWav(const std::string&path){
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
  const size_t frames=bytes/align;
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
struct RunningGuard{RunningGuard(){require(!inProgress.exchange(true),"Four-stem processing already active");cancelled.store(false);}
  ~RunningGuard(){inProgress.store(false);cancelled.store(false);}};

std::array<std::string,5> separate(JNIEnv*env,const std::string&model,const std::string&wav,
                const std::string&folder,const std::string&prefix,jobject listener){
  RunningGuard running;
  auto input=readWav(wav);
  const size_t sourceFrames=input.frames;
  // 48k input must be resampled BEFORE inference; HT-Demucs consumes 44.1k.
  if(input.rate==48000){
    SincResampler down(48000,MODEL_RATE);
    for(auto&ch:input.chan)ch=down.convert(ch);
  }
  size_t frames=input.chan[0].size();
  require(frames==input.chan[1].size(),"Input channel length mismatch");
  require(frames<=size_t(MODEL_RATE)*12*60,"Excessive song length");
  const size_t nchunks=frames<=SEGMENT?1:1+(frames-SEGMENT+STRIDE-1)/STRIDE;
  require(nchunks<=200,"Excessive number of inference chunks");
  // Do not use the old sherpa UVR config: HT-Demucs is a different ONNX graph.
  api=OrtGetApiBase()->GetApi(ORT_API_VERSION);
  require(api!=nullptr,"Bundled ONNX Runtime is older than the four-stem API header");
  OrtSessionHolder sess;
  ortCheck(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING,"DennisFourStem",&sess.env));
  ortCheck(api->CreateSessionOptions(&sess.opts));
  ortCheck(api->SetIntraOpNumThreads(sess.opts,2));
  ortCheck(api->SetInterOpNumThreads(sess.opts,1));
  ortCheck(api->SetSessionGraphOptimizationLevel(sess.opts,ORT_ENABLE_ALL));
  ortCheck(api->CreateSession(sess.env,model.c_str(),sess.opts,&sess.session));
  size_t ni=0,no=0;
  ortCheck(api->SessionGetInputCount(sess.session,&ni));
  ortCheck(api->SessionGetOutputCount(sess.session,&no));
  require(ni==1 && no==1,"Four-stem ONNX model must have one input and one output");
  validateTensor(sess.session,true);validateTensor(sess.session,false);
  OrtAllocator*allocator=nullptr;
  ortCheck(api->GetAllocatorWithDefaultOptions(&allocator));
  char *inName=nullptr,*outName=nullptr;
  ortCheck(api->SessionGetInputName(sess.session,0,allocator,&inName));
  ortCheck(api->SessionGetOutputName(sess.session,0,allocator,&outName));
  std::string in(inName?inName:""),out(outName?outName:"");
  if(inName)ortCheck(api->AllocatorFree(allocator,inName));
  if(outName)ortCheck(api->AllocatorFree(allocator,outName));
  require(in=="mix" && out=="stems","Wrong ONNX names; expected mix -> stems");
  ortCheck(api->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&sess.memory));

  std::array<std::array<std::vector<float>,2>,4> sources;
  for(auto&stem:sources)for(auto&ch:stem)ch.assign(frames,0.f);
  std::vector<float> weights(frames,0.f);
  std::vector<float> audio(2*SEGMENT,0.f);
  const int64_t inDims[]{1,2,SEGMENT};
  const char *inNames[]{"mix"},*outNames[]{"stems"};
  for(size_t chunk=0;chunk<nchunks;chunk++){
    cancelledCheck();
    const size_t start=chunk*size_t(STRIDE);
    require(start<frames,"Invalid chunk index");
    const size_t count=std::min(size_t(SEGMENT),frames-start);
    std::fill(audio.begin(),audio.end(),0.f);
    for(int ch=0;ch<2;ch++){
      std::copy_n(input.chan[ch].data()+start,count,audio.data()+size_t(ch)*SEGMENT);
    }
    OrtValue *tensor=nullptr,*prediction=nullptr;
    ortCheck(api->CreateTensorWithDataAsOrtValue(sess.memory,audio.data(),audio.size()*sizeof(float),
        inDims,3,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&tensor));
    try{
      const OrtValue *inputs[]{tensor};
      ortCheck(api->Run(sess.session,nullptr,inNames,inputs,1,outNames,1,&prediction));
      OrtTensorTypeAndShapeInfo*shape=nullptr;
      ortCheck(api->GetTensorTypeAndShape(prediction,&shape));
      size_t rank=0;ortCheck(api->GetDimensionsCount(shape,&rank));
      require(rank==4,"Expected rank-4 output from HT-Demucs");
      int64_t dims[4];ortCheck(api->GetDimensions(shape,dims,4));api->ReleaseTensorTypeAndShapeInfo(shape);
      require(dims[0]==1 && dims[1]==4 && dims[2]==2 && dims[3]==SEGMENT,
              "HT-Demucs generated an unexpected tensor shape");
      float *samples=nullptr;
      ortCheck(api->GetTensorMutableData(prediction,reinterpret_cast<void**>(&samples)));
      require(samples!=nullptr,"HT-Demucs returned null output");
      for(size_t j=0;j<count;j++){
        const size_t i=start+j;
        const float up=float(std::min(1.,double(j+1)/OVERLAP));
        const float down=float(std::min(1.,double(SEGMENT-j)/OVERLAP));
        const float w=std::max(0.0001f,std::min(up,down));
        weights[i]+=w;
        for(int st=0;st<4;st++)for(int c=0;c<2;c++){
          const size_t idx=(size_t(st)*2+size_t(c))*SEGMENT+j;
          const float x=samples[idx];
          require(std::isfinite(x),"Non-finite separation output");
          sources[st][c][i]+=w*x;
        }
      }
      api->ReleaseValue(prediction);prediction=nullptr;
      api->ReleaseValue(tensor);tensor=nullptr;
    }catch(...){
      if(prediction)api->ReleaseValue(prediction);
      if(tensor)api->ReleaseValue(tensor);
      throw;
    }
    progress(env,listener,int(chunk+1),int(nchunks));
  }
  // Model source order -> export order: vocals (3), drums (0), bass (1), other (2).
  const int order[]{3,0,1,2};
  for(size_t i=0;i<frames;i++){
    if((i&131071)==0)cancelledCheck();
    require(weights[i]>0.f,"Missing overlap-add coverage");
    for(auto&stem:sources)for(auto&ch:stem)ch[i]/=weights[i];
  }
  // Release original input before the 48k true-sample peak scan.
  for(auto&ch:input.chan){std::vector<float> empty;ch.swap(empty);}
  const bool rate44=true; // HT-Demucs always outputs 44.1k.
  SincResampler up(MODEL_RATE,OUTPUT_RATE);
  const size_t outFrames=(input.rate==48000)?sourceFrames:up.count(frames);
  require(outFrames>0,"No output frames");
  double peak=0;
  for(int s=0;s<4;s++)for(int c=0;c<2;c++){
    const auto &samples=sources[s][c];
    for(size_t i=0;i<outFrames;i++){
      if((i&131071)==0)cancelledCheck();
      double x=rate44?up.value(samples.data(),samples.size(),i):samples[i];
      require(std::isfinite(x),"Non-finite resampled output");
      peak=std::max(peak,std::abs(x));
    }
  }
  const double sharedGain=peak>0.9999?0.9999/peak:1.;
  const char* labels[]{"Vocals","Drums","Bass","Other"};
  std::array<std::string,5> result;
  std::vector<std::string> toDelete;
  try{
    for(int s=0;s<4;s++){
      cancelledCheck();
      const int index=order[s];
      const std::string path=folder+"/"+prefix+"_"+labels[s]+"_24bit.wav";
      toDelete.push_back(path);
      writeWave(path,sources[index],outFrames,up,false,sharedGain);
      result[s]=path;
    }
  }catch(...){for(const auto&p:toDelete)std::remove(p.c_str());throw;}
  result[4]="4 stems · stereo 48 kHz / 24-bit · "+std::to_string(nchunks)+
       " chunks · common gain "+std::to_string(20*std::log10(sharedGain))+" dB";
  return result;
}
} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_dennis_stemstudio_FourStemNative_cancel(JNIEnv*,jclass){cancelled.store(true);}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dennis_stemstudio_FourStemNative_separate(JNIEnv*env,jclass,jstring model,jstring wav,
                   jstring folder,jstring prefix,jobject progressListener){
  try{
    auto results=separate(env,jstr(env,model),jstr(env,wav),jstr(env,folder),
                          jstr(env,prefix),progressListener);
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
