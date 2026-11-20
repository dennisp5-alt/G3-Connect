"""Works out what the model's tail (the inverse STFT and the final add) computes, so the engine can do it
natively: prints the crossing tensors' shapes, the tail's big constants, and how close a plain numpy
inverse STFT (torch.istft semantics, as in HT-Demucs _ispec) comes to the tail's own output.

  python istft_probe.py head.onnx tail.onnx
"""
import sys, math, collections
import numpy as np
import onnx, onnxruntime as ort
from onnx import numpy_helper

head_path, tail_path = sys.argv[1], sys.argv[2]
so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
h = ort.InferenceSession(head_path, so, providers=['CPUExecutionProvider'])
t = ort.InferenceSession(tail_path, so, providers=['CPUExecutionProvider'])
inp = h.get_inputs()[0]
rng = np.random.default_rng(3)
x = (rng.standard_normal((1, 2, 343980)) * 0.2).astype(np.float32)
hv = dict(zip([o.name for o in h.get_outputs()], h.run(None, {inp.name: x})))
for k, v in hv.items():
    print('HEAD_OUT', k, v.shape, v.dtype, 'absmax', float(np.abs(v).max()))
feed = {i.name: hv[i.name] for i in t.get_inputs()}
outs = t.run(None, feed)
for o, v in zip(t.get_outputs(), outs):
    print('TAIL_OUT', o.name, v.shape, v.dtype)
ref = outs[0]

m = onnx.load(tail_path)
ops = collections.Counter(n.op_type for n in m.graph.node)
print('TAIL_OPS', dict(ops.most_common(25)))
for init in m.graph.initializer:
    a = numpy_helper.to_array(init)
    if a.size >= 1000:
        print('TAIL_INIT', init.name, a.shape, a.dtype)
for n in m.graph.node:
    if n.op_type == 'Constant':
        for at in n.attribute:
            if at.t is not None and at.t.dims and np.prod(at.t.dims) >= 1000:
                print('TAIL_CONST', n.output[0], list(at.t.dims))
first = [n for n in m.graph.node if n.op_type not in ('Constant', 'Cast')][:40]
for n in first:
    print('NODE', n.op_type, n.name, list(n.input)[:3], '->', list(n.output)[:2])

# The numpy inverse STFT: n_fft 4096, hop 1024, periodic Hann, normalized, centred, as HT-Demucs.
z_name = [k for k in hv if 'Pad' in k][0]
add_name = [k for k in hv if 'Add' in k][0]
Z, XT = hv[z_name].astype(np.float64), hv[add_name].astype(np.float64)
length = XT.shape[-1]
nfft, hop = 4096, 1024
win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(nfft) / nfft)
pad = hop // 2 * 3
le = hop * int(math.ceil(length / hop)) + 2 * pad
print('LENGTH', length, 'le', le, 'Z', Z.shape)

def istft(spec, scale):  # spec: (F=2049, T) complex
    F, T = spec.shape
    frames = np.fft.irfft(spec * scale, n=nfft, axis=0)  # (nfft, T)
    total = nfft + hop * (T - 1)
    y = np.zeros(total); env = np.zeros(total)
    for k in range(T):
        y[k * hop:k * hop + nfft] += frames[:, k] * win
        env[k * hop:k * hop + nfft] += win * win
    y = y / np.where(env > 1e-11, env, 1)
    y = y[nfft // 2: nfft // 2 + le]
    return y[pad: pad + length]

def layouts(Z):
    s = Z.shape
    # Try where the real/imaginary pair sits and where stems/channels sit.
    if len(s) == 6 and s[3] == 2:            # (B, S, C, 2, F, T)
        yield 'B,S,C,2,F,T', lambda st, ch: Z[0, st, ch, 0] + 1j * Z[0, st, ch, 1]
    if len(s) == 6 and s[-1] == 2:           # (B, S, C, F, T, 2)
        yield 'B,S,C,F,T,2', lambda st, ch: Z[0, st, ch, ..., 0] + 1j * Z[0, st, ch, ..., 1]
    if len(s) == 6 and s[2] == 2:            # (B, S, 2, C, F, T)
        yield 'B,S,2,C,F,T', lambda st, ch: Z[0, st, 0, ch] + 1j * Z[0, st, 1, ch]
    if len(s) == 6 and s[2] == 4:            # (B, S, C*2, F, T, ?) unlikely
        pass

refd = ref.astype(np.float64)
for name, get in layouts(Z):
    for scale_name, scale in (('sqrt(n)', math.sqrt(nfft)), ('1', 1.0)):
        worst = 0.0
        for st in range(min(2, Z.shape[1])):
            for ch in range(2):
                spec = get(st, ch)
                if spec.shape[0] != 2049:
                    spec = spec.T
                y = istft(spec, scale) + XT[0, st, ch]
                r = refd[0, st, ch]
                worst = max(worst, float(np.max(np.abs(y - r))) / max(1e-12, float(np.max(np.abs(r)))))
        print('TRY', name, 'scale', scale_name, 'worst relative difference', f'{worst:.3g}')
