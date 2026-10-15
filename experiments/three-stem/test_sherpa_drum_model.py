#!/usr/bin/env python3
"""Host compatibility/speed test for sherpa-onnx 1.13.8 with DRUM MDX model.

Generate tiny synthetic sine/noise stereo input. This establishes whether
the UVR interface can load and produce 2 aligned outputs, NOT sound quality.
Output stem[0] is a drum estimate, NOT vocals, even if the wrapper says so.
"""
import argparse
import json
import resource
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--model",type=Path,required=True)
    parser.add_argument("--report",type=Path,required=True)
    args=parser.parse_args()
    report={"status":"started","model":args.model.name,"engine":"sherpa-onnx"}
    try:
        opts=sherpa_onnx.OfflineSourceSeparationConfig(
            model=sherpa_onnx.OfflineSourceSeparationModelConfig(
                uvr=sherpa_onnx.OfflineSourceSeparationUvrModelConfig(
                    model=str(args.model)),
                num_threads=2,
                debug=False,
                provider="cpu"
            )
        )
        if not opts.validate():
            raise RuntimeError("sherpa UVR model config rejected")
        t=time.perf_counter()
        engine=sherpa_onnx.OfflineSourceSeparation(opts)
        report["load_seconds"]=round(time.perf_counter()-t,3)
        sr=44100
        seconds=3
        frames=sr*seconds
        tm=np.arange(frames,dtype=np.float32)/sr
        left=(.04*np.sin(2*np.pi*103*tm)+.02*np.sin(2*np.pi*435*tm)).astype(np.float32)
        right=(.04*np.sin(2*np.pi*157*tm)+.02*np.sin(2*np.pi*782*tm)).astype(np.float32)
        mixture=np.ascontiguousarray(np.stack([left,right]),dtype=np.float32)
        t=time.perf_counter()
        separated=engine.process(sample_rate=sr,samples=mixture)
        report["inference_seconds"]=round(time.perf_counter()-t,3)
        report["num_stems"]=len(separated.stems)
        report["output_sample_rate"]=separated.sample_rate
        stems=[]
        for i,stem in enumerate(separated.stems):
            x=np.asarray(stem.data)
            stems.append({"index":i,"shape":list(x.shape),
                          "finite":bool(np.isfinite(x).all()),"peak":float(np.max(abs(x)))})
        report["stem_outputs"]=stems
        if len(stems)!=2 or any(s["shape"] != [2,frames] or not s["finite"] for s in stems):
            raise RuntimeError("UVR wrapper returned malformed stem layout")
        peak=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report["peak_host_rss_mb"]=round(peak/(1024*1024 if sys.platform=="darwin" else 1024),2)
        report["stem_role_warning"]="Index 0 is estimated DRUMS, not vocals; 1 is remainder of input"
        report["status"]="sherpa_cpu_synthetic_pass"
        code=0
    except Exception as exc:
        report["status"]="error"
        report["error"]=type(exc).__name__+": "+str(exc)
        code=1
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,indent=2))
    return code


if __name__=="__main__":
    raise SystemExit(main())
