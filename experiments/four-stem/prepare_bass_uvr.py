#!/usr/bin/env python3
"""Prepare one KUIELab bass model for the existing sherpa-onnx UVR wrapper.

Copies model and adds sherpa metadata. This does NOT modify the source model,
change the model's learned weights, or claim that its first output is vocals.
Only use tested bass models with this script. For metadata requirements see
sherpa-onnx v1.13.8 scripts/uvr_mdx/add_meta_data_and_quantize.py.
"""
import argparse
from pathlib import Path
import onnx


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--input",type=Path,required=True)
    ap.add_argument("--output",type=Path,required=True)
    args=ap.parse_args()
    if args.output.resolve()==args.input.resolve():
        raise SystemExit("Refusing to overwrite original model")
    if args.input.name not in ("kuielab_b_bass.onnx",):
        raise SystemExit("Only known KUIELab bass models accepted")
    model=onnx.load(str(args.input))
    if len(model.graph.input)!=1 or len(model.graph.output)!=1:
        raise SystemExit("Model graph has unexpected I/O count")
    dims=[d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim]
    resultDims=[d.dim_value for d in model.graph.output[0].type.tensor_type.shape.dim]
    if len(dims)!=4 or dims[1]!=4 or dims[2]!=2048 or dims[3]!=256:
        raise SystemExit(f"Unexpected MDX bass spectrogram graph: {dims}")
    if len(resultDims)!=4 or resultDims[1:]!=dims[1:]:
        raise SystemExit(f"Unexpected output spectrogram graph: {resultDims}")
    # KUIELab's official Leaderboard_B ConvTDFNet_bass.yaml:
    # n_fft = win_length = 16384, dim_f = 2048, dim_t = 256.
    # The ONNX 2048 frequency-bin axis is a CROPPED STFT spectrum;
    # n_fft != 2*dim_f for bass. Using 4096 causes thin, almost bassless output.
    # https://github.com/kuielab/mdx-net/blob/Leaderboard_B/configs/model/ConvTDFNet_bass.yaml
    meta={
        "model_type":"UVR",
        "model_name":args.input.stem,
        "sample_rate":44100,
        "comment":"KUIELab MDX-Net bass specialist; prepared for sherpa UVR engine",
        "n_fft":16384,
        "center":1,
        "window_type":"hann",
        "win_length":16384,
        "hop_length":1024,
        "dim_t":dims[3],
        "dim_f":dims[2],
        "dim_c":dims[1],
        "stems":2
    }
    model.ClearField("metadata_props")
    for k,v in meta.items():
        field=model.metadata_props.add()
        field.key=k;field.value=str(v)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    onnx.save(model,str(args.output))
    checked=onnx.load(str(args.output))
    onnx.checker.check_model(checked)
    actual=dict((m.key,m.value) for m in checked.metadata_props)
    if actual.get("n_fft")!="16384" or actual.get("win_length")!="16384":
        raise SystemExit("Bass FFT metadata validation failed")
    if actual.get("dim_f")!="2048" or actual.get("dim_t")!="256":
        raise SystemExit("Bass model graph metadata validation failed")
    print(f"Metadata OK, saved {args.output} with KUIELab bass target (not vocals): {meta}")


if __name__=="__main__":
    main()
