"""Compares two folders of six 24-bit stems: identical, or the largest difference and its level."""
import sys, os, glob
import numpy as np

def read24(path):
    b = open(path, 'rb').read()[44:]
    a = np.frombuffer(b[:len(b) // 3 * 3], dtype=np.uint8).reshape(-1, 3).astype(np.int32)
    v = a[:, 0] | (a[:, 1] << 8) | (a[:, 2] << 16)
    return np.where(v >= 1 << 23, v - (1 << 24), v)

ref, got, label = sys.argv[1], sys.argv[2], sys.argv[3]
worst_db, worst_lsb, same = -999.0, 0, 0
for f in sorted(glob.glob(os.path.join(ref, '*.wav'))):
    g = os.path.join(got, os.path.basename(f))
    if open(f, 'rb').read() == open(g, 'rb').read():
        same += 1; continue
    x, y = read24(f), read24(g)
    if len(x) != len(y):
        sys.exit(f'COMPARE {label}: length differs on {os.path.basename(f)}')
    d = np.abs(x - y); worst_lsb = max(worst_lsb, int(d.max()))
    worst_db = max(worst_db, 10 * np.log10(np.sum(d.astype(np.float64) ** 2) / max(1, np.sum(x.astype(np.float64) ** 2))))
print(f'COMPARE {label}: {same} of 6 identical; largest difference {worst_lsb} steps of 24-bit, {worst_db:.1f} dB below the stem (worst)')
