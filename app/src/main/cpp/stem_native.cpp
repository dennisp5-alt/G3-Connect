// Dennis Stem Studio: WAV -> offline UVR inference -> stereo 24-bit WAV stems.
// Uses sherpa-onnx's upstream C API (Apache-2.0). Own WAV I/O is deliberately
// transparent and does not perform EQ, compression, denoising or loudness normalization.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include "sherpa-onnx/c-api/c-api.h"

namespace {
struct Audio {
  uint32_t rate = 0;
  int channels = 0;
  size_t frames = 0;
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

Audio loadWav(const std::string& name) {
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
  const size_t frames=dataBytes/blockAlign;
  if(frames==0 || frames>size_t(rate)*12*60)
    throw std::runtime_error("Audio must be between one sample and 12 minutes in the engine test");
  if(fseek(f,dataAt,SEEK_SET)!=0) throw std::runtime_error("Cannot locate WAV samples");
  Audio audio; audio.rate=rate; audio.channels=2; audio.frames=frames;
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

void savePcm24(const std::string& name, const SherpaOnnxSourceSeparationOutput *output,
               int stemIndex, float sharedGain) {
  const auto& s=output->stems[stemIndex];
  if(s.num_channels<1 || s.num_channels>2 || s.n<1 || output->sample_rate<8000)
    throw std::runtime_error("Separation produced invalid stem geometry");
  const uint32_t frames=uint32_t(s.n);
  const uint32_t bytes=frames*uint32_t(s.num_channels)*3U;
  if(bytes>0xffffff00) throw std::runtime_error("Output exceeds standard WAV size limit");
  FileHandle file(name,"wb"); FILE* f=file.get();
  fwrite("RIFF",1,4,f); write32(f,36U+bytes); fwrite("WAVE",1,4,f);
  fwrite("fmt ",1,4,f); write32(f,16); write16(f,1);
  write16(f,uint16_t(s.num_channels)); write32(f,uint32_t(output->sample_rate));
  write32(f,uint32_t(output->sample_rate)*uint32_t(s.num_channels)*3U);
  write16(f,uint16_t(s.num_channels*3)); write16(f,24);
  fwrite("data",1,4,f); write32(f,bytes);
  unsigned char sample[3];
  for(uint32_t n=0;n<frames;n++) {
    for(int ch=0;ch<s.num_channels;ch++) {
      float x=s.samples[ch][n]*sharedGain;
      if(!std::isfinite(x)) throw std::runtime_error("Model produced invalid output sample");
      double limited=std::max(-1.,std::min(0.99999988079, double(x)));
      int32_t pcm=static_cast<int32_t>(std::lrint(limited*8388608.0));
      sample[0]=uint8_t(pcm); sample[1]=uint8_t(pcm>>8); sample[2]=uint8_t(pcm>>16);
      if(fwrite(sample,1,3,f)!=3) throw std::runtime_error("Failed writing stem WAV");
    }
  }
  if(fflush(f)!=0 || ferror(f)) throw std::runtime_error("Failed flushing stem WAV");
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
Java_com_dennis_stemstudio_NativeSeparator_separate(JNIEnv* env,jclass,
  jstring modelPath,jstring inputWav,jstring outputDir,jstring outputPrefix) {
  try {
    std::string model=jstr(env,modelPath);
    std::string input=jstr(env,inputWav);
    std::string dir=jstr(env,outputDir);
    std::string prefix=jstr(env,outputPrefix);
    Audio audio=loadWav(input);
    SherpaOnnxOfflineSourceSeparationConfig config{};
    config.model.uvr.model=model.c_str();
    config.model.num_threads=2;
    config.model.provider="cpu";
    auto *engine=SherpaOnnxCreateOfflineSourceSeparation(&config);
    if(!engine) throw std::runtime_error("Cannot open UVR model; check its compatibility and file integrity");
    std::vector<const float*> ptrs{audio.planes[0].data(),audio.planes[1].data()};
    auto *output=SherpaOnnxOfflineSourceSeparationProcess(engine,ptrs.data(),
      2, int32_t(audio.frames), int32_t(audio.rate));
    SherpaOnnxDestroyOfflineSourceSeparation(engine);
    if(!output) throw std::runtime_error("AI separation failed. Model may be incompatible or memory exhausted");
    try {
      if(output->num_stems!=2) throw std::runtime_error("Model did not return exactly 2 stems");
      float maxPeak=0.f;
      for(int st=0;st<2;st++) {
        const auto &s=output->stems[st];
        if(s.n<1 || s.num_channels!=2 || !s.samples)
          throw std::runtime_error("Expected stereo stems from the selected model");
        for(int ch=0;ch<s.num_channels;ch++) {
          if(!s.samples[ch]) throw std::runtime_error("Invalid stem channel");
          for(int n=0;n<s.n;n++) {
            const float x=s.samples[ch][n];
            if(!std::isfinite(x)) throw std::runtime_error("Model produced NaN or infinity");
            maxPeak=std::max(maxPeak,std::abs(x));
          }
        }
      }
      float commonGain=maxPeak>0.9999f ? (0.9999f/maxPeak) : 1.f;
      std::string vocals=dir+"/"+prefix+"_Vocals_24bit.wav";
      std::string music=dir+"/"+prefix+"_Instrumental_24bit.wav";
      savePcm24(vocals,output,0,commonGain);
      savePcm24(music,output,1,commonGain);
      std::string info="Completed: stereo 24-bit WAV, "+std::to_string(output->sample_rate)+" Hz";
      if(commonGain<0.99999f) info+=" · Common anti-clipping gain " +std::to_string(20.0*log10(commonGain)).substr(0,6)+" dB";
      SherpaOnnxDestroySourceSeparationOutput(output);
      jclass cls=env->FindClass("java/lang/String");
      jobjectArray ret=env->NewObjectArray(3,cls,nullptr);
      env->SetObjectArrayElement(ret,0,env->NewStringUTF(vocals.c_str()));
      env->SetObjectArrayElement(ret,1,env->NewStringUTF(music.c_str()));
      env->SetObjectArrayElement(ret,2,env->NewStringUTF(info.c_str()));
      return ret;
    } catch(...) {
      SherpaOnnxDestroySourceSeparationOutput(output); throw;
    }
  } catch(const std::exception& err) {
    javaError(env,err.what()); return nullptr;
  }
}
