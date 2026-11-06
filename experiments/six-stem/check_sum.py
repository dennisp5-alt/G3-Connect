"""Checks six stems add back to the song, and how much each one holds."""
import sys, wave
import numpy as np
from scipy import signal

def read(path):
    w = wave.open(path)
    n, rate, width = w.getnframes(), w.getframerate(), w.getsampwidth()
    b = np.frombuffer(w.readframes(n), dtype=np.uint8)
    if width == 2:
        x = np.frombuffer(b, dtype='<i2') / 32768.0
    else:
        b = b.reshape(-1, 3)
        x = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int8).astype(np.int32) << 16)) / 8388608.0
    return x.reshape(-1, w.getnchannels()), rate

song, rate = read(sys.argv[1])
if rate != 48000:
    song = signal.resample_poly(song, 48000, rate, axis=0)
parts = {}
for name in ('Vocals', 'Drums', 'Bass', 'Other', 'Guitar', 'Piano'):
    parts[name], _ = read(f'{sys.argv[2]}/{sys.argv[3]}_{name}_24bit.wav')
total = sum(parts.values())
n = min(len(total), len(song))
song = song[:n]
def db(x):
    return 10 * np.log10(np.mean(x ** 2) + 1e-20)
for name, p in parts.items():
    print(f'{name}: {db(p[:n]) - db(song):.1f} dB against the song')
print(f'Sum against the song: {db(total[:n] - song) - db(song):.1f} dB')
assert db(total[:n] - song) - db(song) < -35
print('SIX_STEM_SUM_PASS')
