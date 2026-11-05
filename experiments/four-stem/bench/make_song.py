#!/usr/bin/env python3
"""A synthetic 48 kHz test song with vocal-like, drum, bass and guitar-like parts."""
import sys, wave
import numpy as np
sr, seconds = 48000, int(sys.argv[2])
t = np.arange(sr * seconds) / sr
rng = np.random.default_rng(7)
beat = 0.5
notes = [55, 65.4, 73.4, 49]
root = np.array(notes)[((t / (beat * 8)).astype(int)) % 4]
bass = 0.28 * np.tanh(3 * np.sin(2 * np.pi * np.cumsum(root) / sr))
phase = (t % beat) / beat
kick = 0.5 * np.exp(-phase * 14) * np.sin(2 * np.pi * (45 + 80 * np.exp(-phase * 30)) * t) * (((t / beat).astype(int) % 2) == 0)
snare = 0.25 * np.exp(-phase * 18) * rng.standard_normal(len(t)) * (((t / beat).astype(int) % 2) == 1)
hat = 0.05 * np.exp(-((t % (beat / 2)) / (beat / 2)) * 30) * np.diff(rng.standard_normal(len(t) + 1))
guitar = sum(0.05 / (i + 1) * np.sign(np.sin(2 * np.pi * root * 3 * (i + 1) * t + i)) for i in range(4))
pitch = 220 * 2 ** (np.array([0, 3, 5, 7, 5, 3, 0, -2])[((t / (beat * 2)).astype(int)) % 8] / 12)
voice = sum(0.12 / (k ** 1.1) * np.sin(2 * np.pi * np.cumsum(pitch * k * (1 + .004 * np.sin(2 * np.pi * 5.5 * t))) / sr) for k in range(1, 10))
voice *= (0.5 + 0.5 * np.sin(2 * np.pi * t / (beat * 4))) ** 2
left = bass + kick + snare + hat + 0.9 * guitar + voice
right = bass + kick + snare + 0.7 * hat + 1.1 * np.roll(guitar, 240) + voice
pcm = np.stack([left, right], 1)
pcm = pcm / np.abs(pcm).max() * 0.85
data = (pcm * 8388607).astype(np.int32)
raw = np.zeros((len(data), 6), dtype=np.uint8)
for ch in range(2):
    for b in range(3):
        raw[:, ch * 3 + b] = (data[:, ch] >> (8 * b)) & 255
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(2); w.setsampwidth(3); w.setframerate(sr); w.writeframes(raw.tobytes())
