"""Splits the six-stem model in two at the inverse STFT, so the graphics card can run the part it has
memory for (everything up to the spectrum's turn back into sound) and the processor runs the rest.

  python split_model.py model.onnx head.onnx tail.onnx

The tail is the inverse STFT ('/real_istft/...'), everything after it, and every constant-only step
feeding it (weights cast from half precision), so no weights cross between the two. What crosses is
printed. The split is checked on the processor: the two halves together must give the whole model's
output (same arithmetic, so the largest difference should be zero or float rounding).
"""
import sys
import numpy as np
import onnx
from onnx import utils, shape_inference

src, head_path, tail_path = sys.argv[1], sys.argv[2], sys.argv[3]
model = onnx.load(src)
g = model.graph
init = {i.name for i in g.initializer}
graph_inputs = [i.name for i in g.input if i.name not in init]
graph_outputs = [o.name for o in g.output]
producer = {}
for n in g.node:
    for o in n.output:
        producer[o] = n
consumers = {}
for n in g.node:
    for i in n.input:
        consumers.setdefault(i, []).append(n)

# Steps that depend only on weights and constants.
const = set()
changed = True
while changed:
    changed = False
    for n in g.node:
        if n.name in const:
            continue
        if all((not i) or i in init or (i in producer and producer[i].name in const) for i in n.input):
            const.add(n.name); changed = True

# The tail: the inverse STFT and everything downstream of it.
seeds = [n for n in g.node if n.name.startswith('/real_istft/')]
if not seeds:
    sys.exit('No /real_istft/ steps in this model; names: ' + ', '.join(sorted({n.name.split('/')[1] for n in g.node if n.name.count('/') > 1})[:40]))
tail = set()
stack = list(seeds)
while stack:
    n = stack.pop()
    if n.name in tail:
        continue
    tail.add(n.name)
    for o in n.output:
        stack.extend(consumers.get(o, []))
# Pull in constant-only ancestors of the tail (so weights stay on their side).
stack = [n for n in g.node if n.name in tail]
while stack:
    n = stack.pop()
    for i in n.input:
        p = producer.get(i)
        if p is not None and p.name in const and p.name not in tail:
            tail.add(p.name); stack.append(p)

boundary = []
for n in g.node:
    if n.name not in tail:
        continue
    for i in n.input:
        if not i or i in init or i in boundary:
            continue
        p = producer.get(i)
        if p is None or p.name not in tail:
            boundary.append(i)
print('TAIL steps', len(tail), 'of', len(g.node))
print('BOUNDARY', boundary)

head_outputs = [b for b in boundary if b not in graph_inputs]
e = utils.Extractor(model)
head = e.extract_model(graph_inputs, head_outputs)
tailm = e.extract_model(boundary, graph_outputs)
onnx.save(head, head_path)
onnx.save(tailm, tail_path)

# What crosses, with sizes (shape inference on the head).
inferred = shape_inference.infer_shapes(head)
shapes = {v.name: v for v in list(inferred.graph.value_info) + list(inferred.graph.output)}
for b in boundary:
    v = shapes.get(b)
    dims = [d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim] if v is not None else '?'
    print('CROSSES', b, dims)

# Check: whole model against head + tail, on the processor.
import onnxruntime as ort
so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
whole = ort.InferenceSession(src, so, providers=['CPUExecutionProvider'])
h = ort.InferenceSession(head_path, so, providers=['CPUExecutionProvider'])
t = ort.InferenceSession(tail_path, so, providers=['CPUExecutionProvider'])
inp = whole.get_inputs()[0]
x = (np.random.default_rng(1).standard_normal((1, 2, 343980)) * 0.3).astype(np.float32)
ref = whole.run(None, {inp.name: x})[0]
hv = dict(zip([o.name for o in h.get_outputs()], h.run(None, {inp.name: x})))
feed = {i.name: (x if i.name == inp.name else hv[i.name]) for i in t.get_inputs()}
got = t.run(None, feed)[0]
diff = float(np.max(np.abs(ref - got)))
crossed = sum(v.nbytes for v in hv.values())
print(f'SPLIT_CHECK max difference {diff:.3g}, {crossed / 1e6:.1f} MB crosses per section, tail inputs {[i.name for i in t.get_inputs()]}')
if diff > 1e-3:
    sys.exit('SPLIT_FAIL')
print('SPLIT_PASS')
