#!/usr/bin/env python3
"""Isolated four-stem ONNX host benchmark (does not modify the Android app).

Usage:
 python -m pip install numpy scipy soundfile onnxruntime
 python experiments/four-stem/probe_htdemucs_onnx.py --model /path/to/htdemucs_fp16weights.onnx \
      --output-json /tmp/four-stem-probe.json
 python experiments/four-stem/probe_htdemucs_onnx.py --model /path/to/htdemucs.onnx \
      --wav /path/to/eight-second.wav --output-json /tmp/probe-audio.json

Probe ONLY: 1 fixed-length segment; no production stem WAVs, JNI changes or APK build.
Synthetic audio checks API/shape/operator execution, NOT musical separation quality.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import resource
import sys
import time
from pathlib import Path


RATE = 44100
FRAMES = 343980
# Published StemSplitio/htdemucs-onnx tensor contract: NOT vocals-first.
STEM_ORDER = ("drums", "bass", "other", "vocals")


def process_rss_mb() -> float | None:
    """Current Linux resident size; fall back to maximum RSS elsewhere."""
    try:
        with open("/proc/self/status", encoding="ascii") as stream:
            for line in stream:
                if line.startswith("VmRSS:"):
                    return float(line.split()[1]) / 1024
    except OSError:
        pass
    max_rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return max_rss / (1024 * 1024 if sys.platform == "darwin" else 1024)


def max_rss_mb() -> float:
    v = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return v / (1024 * 1024 if sys.platform == "darwin" else 1024)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            data = handle.read(1024 * 1024)
            if not data:
                break
            digest.update(data)
    return digest.hexdigest()


def load_short_audio(np, wav: Path | None):
    if wav is None:
        # Small deterministic synthetic mixture for structural inference testing.
        t = np.arange(FRAMES, dtype=np.float64) / RATE
        left = 0.10 * np.sin(2 * np.pi * 220 * t) + 0.04 * np.sin(2 * np.pi * 900 * t)
        right = 0.10 * np.sin(2 * np.pi * 233 * t) + 0.04 * np.sin(2 * np.pi * 1300 * t)
        return np.stack((left, right), axis=0).astype(np.float32), "synthetic"
    try:
        import soundfile as sf
        from scipy.signal import resample_poly
    except ImportError as exc:
        raise RuntimeError("A WAV input additionally requires scipy and soundfile") from exc
    with sf.SoundFile(str(wav), mode="r") as stream:
        if stream.channels not in (1, 2) or stream.samplerate not in (RATE, 48000):
            raise ValueError("Input WAV must be 44.1/48 kHz, mono or stereo")
        # Read only enough audio to fill a single ONNX segment.
        read_frames = int(FRAMES * stream.samplerate / RATE) + 2
        samples = stream.read(frames=read_frames, dtype="float32", always_2d=True).T
        sample_rate = stream.samplerate
    if samples.shape[1] == 0:
        raise ValueError("Input WAV is empty")
    if samples.shape[0] == 1:
        samples = np.repeat(samples, 2, axis=0)
    if sample_rate == 48000:
        samples = resample_poly(samples, 147, 160, axis=1).astype(np.float32)
    samples = samples[:, :FRAMES]
    if samples.shape[1] < FRAMES:
        samples = np.pad(samples, ((0, 0), (0, FRAMES - samples.shape[1])))
    return np.ascontiguousarray(samples, dtype=np.float32), str(wav)


def require_shape(actual, expected, name):
    if list(actual) != list(expected):
        raise ValueError(f"Unexpected {name} tensor shape {actual!r}; expected {expected!r}")


def run(args):
    try:
        import numpy as np
        import onnxruntime as ort
    except ImportError as exc:
        raise RuntimeError("Install numpy and onnxruntime in the host environment") from exc
    model = args.model.resolve()
    if not model.is_file():
        raise FileNotFoundError(f"ONNX model not found: {model}")
    if args.threads < 1 or args.threads > 8:
        raise ValueError("--threads must be 1 through 8")
    report = {
        "status": "started",
        "model_file": model.name,
        "model_bytes": model.stat().st_size,
        "sha256": sha256_file(model),
        "platform": platform.platform(),
        "cpu": platform.processor(),
        "onnxruntime_version": ort.__version__,
        "threads": args.threads,
        "published_stem_order": list(STEM_ORDER),
        "segment_frames": FRAMES,
        "model_sample_rate": RATE,
        "rss_before_mb": process_rss_mb(),
    }
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = args.threads
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    start = time.perf_counter()
    session = ort.InferenceSession(str(model), sess_options=opts, providers=["CPUExecutionProvider"])
    report["model_load_seconds"] = round(time.perf_counter() - start, 3)
    report["rss_after_load_mb"] = process_rss_mb()
    report["providers"] = session.get_providers()
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    if len(inputs) != 1 or len(outputs) != 1:
        raise ValueError("Expected a single ONNX input and output")
    if inputs[0].name != "mix" or inputs[0].type != "tensor(float)":
        raise ValueError(f"Wrong ONNX input contract: {inputs[0].name}, {inputs[0].type}")
    if outputs[0].name != "stems" or outputs[0].type != "tensor(float)":
        raise ValueError(f"Wrong ONNX output contract: {outputs[0].name}, {outputs[0].type}")
    require_shape(inputs[0].shape, (1, 2, FRAMES), "input")
    require_shape(outputs[0].shape, (1, 4, 2, FRAMES), "output")
    audio, source = load_short_audio(np, args.wav)
    report["audio_source"] = source
    if not bool(np.isfinite(audio).all()):
        raise ValueError("Input audio contains NaN/inf")
    if np.max(np.abs(audio)) > 1.001:
        raise ValueError("Input audio exceeds [-1, 1]")
    feed = np.ascontiguousarray(audio[None, :, :], dtype=np.float32)
    start = time.perf_counter()
    tensors = session.run(["stems"], {"mix": feed})
    report["inference_seconds_per_7_8s_chunk"] = round(time.perf_counter() - start, 3)
    stems = tensors[0]
    require_shape(stems.shape, (1, 4, 2, FRAMES), "observed output")
    if not np.isfinite(stems).all():
        raise ValueError("Model produced NaN/inf")
    actual = stems[0]
    residual = audio.astype(np.float64) - actual.astype(np.float64).sum(axis=0)
    input_rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)))
    residual_rms = float(np.sqrt(np.mean(residual ** 2)))
    report["input_rms"] = input_rms
    report["residual_to_input_rms_ratio"] = residual_rms / max(input_rms, 1e-10)
    report["stem_peaks"] = {name: float(np.max(np.abs(actual[index]))) for index, name in enumerate(STEM_ORDER)}
    report["rss_after_run_mb"] = process_rss_mb()
    report["peak_rss_mb"] = max_rss_mb()
    report["status"] = "pass"
    report["scope"] = "Host CPU one-segment compatibility only; no Android, audio-quality or licensing validation."
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Downloaded 4-stem ONNX graph")
    parser.add_argument("--wav", type=Path, help="Optional 8-second WAV; default synthetic signal")
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--output-json", type=Path, help="Write structured results")
    args = parser.parse_args()
    try:
        report = run(args)
        code = 0
    except Exception as exc:
        report = {"status": "fail", "error": str(exc), "exception_type": type(exc).__name__}
        code = 1
    print(json.dumps(report, indent=2, sort_keys=True))
    if args.output_json is not None:
        args.output_json.parent.mkdir(parents=True, exist_ok=True)
        args.output_json.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
