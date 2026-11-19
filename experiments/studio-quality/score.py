"""Scores separated stems against MUSDB's true stems.

SDR here is the plain signal-to-distortion ratio per excerpt (10 log10 |s|^2 / |s - s_hat|^2), the measure the
MDX challenges use; museval's BSS-eval numbers run lower. Guitar and piano are folded into "other" for
the 6-stem models, since MUSDB has only four stems. Also reports how well each model's six stems add back up to
the mix, which says nothing about how clean the stems are.
"""
import sys, os, glob, json
import numpy as np
from scipy.signal import resample_poly
try:
    import soundfile as sf
except ImportError:  # scipy's reader, for a quick local run
    from scipy.io import wavfile
    class sf:
        @staticmethod
        def read(path, dtype="float64", always_2d=True):
            r, x = wavfile.read(path)
            if x.dtype == np.int16: x = x / 32768.0
            elif x.dtype == np.int32: x = x / 2147483648.0
            x = x.astype(dtype)
            return (x[:, None] if x.ndim == 1 else x), r

root, label, pattern = sys.argv[1], sys.argv[2], sys.argv[3]  # pattern: {dir} and {stem} placeholders

def load(path, rate=44100):
    x, r = sf.read(path, dtype="float64", always_2d=True)
    if r != rate:
        x = resample_poly(x, rate, r, axis=0)
    return x

def sdr(ref, est):
    n = min(len(ref), len(est))
    ref, est = ref[:n], est[:n]
    num, den = np.sum(ref ** 2), np.sum((ref - est) ** 2)
    if num < 1e-8:
        return None  # silent reference (no vocals in this excerpt): no score
    return 10 * np.log10(num / max(den, 1e-12))

rows, recon = {s: [] for s in ("vocals", "drums", "bass", "other")}, []
for d in sorted(glob.glob(os.path.join(root, "*"))):
    if not os.path.isdir(d): continue
    def stem(s):
        hits = glob.glob(pattern.format(dir=d, stem=s)) or glob.glob(pattern.format(dir=d, stem=s.capitalize()))
        return load(hits[0]) if hits else None
    est = {s: stem(s) for s in ("vocals", "drums", "bass", "other", "guitar", "piano")}
    if any(est[s] is None for s in ("vocals", "drums", "bass", "other")):
        print("MISSING", label, d); continue
    other = est["other"].copy()
    for extra in ("guitar", "piano"):
        if est[extra] is not None:
            n = min(len(other), len(est[extra])); other = other[:n] + est[extra][:n]
    est4 = {"vocals": est["vocals"], "drums": est["drums"], "bass": est["bass"], "other": other}
    for s in rows:
        v = sdr(load(os.path.join(d, f"ref_{s}.wav")), est4[s])
        if v is not None: rows[s].append(v)
    mix = load(os.path.join(d, "mix.wav"))
    total = sum(e[:len(mix)] for e in est.values() if e is not None)
    n = min(len(total), len(mix))
    recon.append(10 * np.log10(np.sum((total[:n] - mix[:n]) ** 2) / np.sum(mix[:n] ** 2)))
result = {s: (float(np.median(v)), float(np.mean(v)), len(v)) for s, v in rows.items()}
for s, (med, mean, n) in result.items():
    print(f"SDR {label} {s}: median {med:.2f} dB, mean {mean:.2f} dB over {n} excerpts")
print(f"SUM {label}: stems against the mix {np.median(recon):.1f} dB (median)")
json.dump({"label": label, "sdr": result, "sum_db": float(np.median(recon))}, open(f"score-{label}.json", "w"), indent=1)
