"""Does the AI runtime's basic graph optimisations (constant folding, removing redundant steps: the ones that
do not depend on the device) once, at build time, and saves the result, so opening the model on Dennis's
laptop has less to do. Checks the optimised head gives the same output as the head on the processor.

  python optimise_head.py head.onnx head_opt.onnx
"""
import sys, os, time
import numpy as np
import onnxruntime as ort

src, dst = sys.argv[1], sys.argv[2]
so = ort.SessionOptions()
so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
so.optimized_model_filepath = dst
t = time.time(); ort.InferenceSession(src, so, providers=['CPUExecutionProvider']); print(f'OPTIMISE wrote {dst} in {time.time() - t:.1f} s')

def opened(path):
    o = ort.SessionOptions(); o.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    t = time.time(); s = ort.InferenceSession(path, o, providers=['CPUExecutionProvider']); return s, time.time() - t

a, ta = opened(src)
b, tb = opened(dst)
print(f'OPTIMISE open on the processor: original {ta:.2f} s, optimised {tb:.2f} s; size {os.path.getsize(src) / 1e6:.0f} MB -> {os.path.getsize(dst) / 1e6:.0f} MB')
x = (np.random.default_rng(7).standard_normal((1, 2, 343980)) * 0.2).astype(np.float32)
name = a.get_inputs()[0].name
ra, rb = a.run(None, {name: x}), b.run(None, {name: x})
worst = max(float(np.max(np.abs(p - q))) / max(1e-12, float(np.max(np.abs(p)))) for p, q in zip(ra, rb))
print(f'OPTIMISE_CHECK worst relative difference {worst:.3g}')
print('OPTIMISE_PASS' if worst < 1e-4 else 'OPTIMISE_FAIL')
