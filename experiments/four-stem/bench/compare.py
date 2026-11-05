#!/usr/bin/env python3
"""Compares old and new stems: how close they are, and the bass stem's low end."""
import sys, wave
import numpy as np
def load(path):
    with wave.open(path) as w:
        raw = np.frombuffer(w.readframes(w.getnframes()), dtype=np.uint8).reshape(-1, 6)
    out = (raw[:, 0::3].astype(np.int32) | raw[:, 1::3].astype(np.int32) << 8 | (raw[:, 2::3].astype(np.int8).astype(np.int32) << 16))
    return out / 8388608.0
old, new, prefix_old, prefix_new = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
sr = 48000
for name in ("Vocals", "Drums", "Bass", "Other"):
    a = load(f"{old}/{prefix_old}_{name}_24bit.wav"); b = load(f"{new}/{prefix_new}_{name}_24bit.wav")
    n = min(len(a), len(b)); a, b = a[:n], b[:n]
    # Old sections join every 6 s and new ones every 18 s, so skip 2 s around each old join.
    keep = np.ones(n, bool)
    for j in range(0, n, 6 * sr):
        keep[max(0, j - sr):j + sr] = False
    diff = 10 * np.log10(np.sum((a - b)[keep] ** 2) / max(1e-20, np.sum(a[keep] ** 2)) + 1e-20)
    spec = np.abs(np.fft.rfft(b.mean(1))) ** 2
    f = np.fft.rfftfreq(n, 1 / sr)
    low = 100 * spec[f < 150].sum() / max(1e-20, spec.sum())
    print(f"{name:7s} difference {diff:7.1f} dB   length {len(a)}   level {10*np.log10(np.mean(b**2)+1e-20):6.1f} dB   below 150 Hz {low:5.1f}%")
