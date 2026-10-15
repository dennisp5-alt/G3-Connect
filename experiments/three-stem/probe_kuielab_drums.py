#!/usr/bin/env python3
"""ONNX structure/speed probe for light KUIELab drums models.

HOST-ONLY feasibility test. Not music separation; random spectral input is
a graph/operator/inference smoke test. Does not evaluate audio, Android latency,
sherpa-onnx wrapper compatibility or output stem quality.
"""
import argparse
import hashlib
import json
import os
import platform
import resource
import sys
import time
from pathlib import Path


def peak_rss_mb():
    rss = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return rss / (1024 * 1024 if sys.platform == "darwin" else 1024)


def hash_file(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def probe(path, threads):
    import numpy as np
    import onnxruntime as ort

    start = time.perf_counter()
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    session = ort.InferenceSession(str(path), sess_options=options,
                                   providers=["CPUExecutionProvider"])
    load_time = time.perf_counter() - start
    inputs = session.get_inputs()
    outputs = session.get_outputs()
    info = {
        "candidate": path.name,
        "model_size_bytes": path.stat().st_size,
        "sha256": hash_file(path),
        "host": platform.platform(),
        "ort_version": ort.__version__,
        "num_threads": threads,
        "load_seconds": round(load_time, 4),
        "inputs": [{"name": i.name, "type": i.type, "shape": i.shape} for i in inputs],
        "outputs": [{"name": o.name, "type": o.type, "shape": o.shape} for o in outputs],
    }
    if len(inputs) != 1:
        raise RuntimeError("This experimental probe expects one graph input")
    inp = inputs[0]
    if inp.type not in ("tensor(float)", "tensor(float16)"):
        raise RuntimeError("Unknown input tensor dtype; manual investigation required")
    if len(inp.shape) != 4:
        raise RuntimeError(f"Input tensor must be rank-4, got {inp.shape}")
    shape = [1 if not isinstance(n, int) or n < 1 else n for n in inp.shape]
    if shape[0] != 1 or np.prod(shape) > 8_000_000 or np.prod(shape) < 256:
        raise RuntimeError(f"Unsafe/invalid input tensor shape: {shape}")
    seed = np.random.default_rng(3405)
    data = seed.standard_normal(tuple(shape)).astype(
        np.float16 if inp.type == "tensor(float16)" else np.float32) * 0.05
    info["inference_input_shape"] = shape
    start = time.perf_counter()
    values = session.run(None, {inp.name: data})
    info["first_inference_seconds"] = round(time.perf_counter() - start, 4)
    info["outputs_observed"] = [{
        "name": outputs[k].name,
        "dtype": str(v.dtype),
        "shape": list(v.shape),
        "finite": bool(np.all(np.isfinite(v))),
        "peak_abs": float(np.max(np.abs(v))),
    } for k, v in enumerate(values)]
    if not all(np.isfinite(v).all() for v in values):
        raise RuntimeError("Non-finite model outputs")
    t0 = time.perf_counter()
    session.run(None, {inp.name: data})
    info["repeat_inference_seconds"] = round(time.perf_counter() - t0, 4)
    info["host_peak_rss_mb"] = round(peak_rss_mb(), 2)
    info["status"] = "host_inference_ok"
    info["limitations"] = ("Random spectral tensor only. Does NOT demonstrate an "
        "Android CPU benchmark, correctly decoded drum audio or sherpa-onnx compatibility.")
    return info


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    out = {"status": "error", "candidate": args.model.name}
    try:
        out = probe(args.model, args.threads)
        return_code = 0
    except Exception as err:
        out["error"] = f"{type(err).__name__}: {err}"
        return_code = 1
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(out, indent=2))
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
