"""Real-music excerpts with true stems: MUSDB18's 7-second test previews (research use; never committed or uploaded).

Writes <out>/<n>/mix.wav and the reference vocals, drums, bass and other, all 44.1 kHz float WAV.
"""
import sys, os
import numpy as np
import soundfile as sf
import musdb

out, count = sys.argv[1], int(sys.argv[2])
db = musdb.DB(download=True, subsets="test")
tracks = sorted(db.tracks, key=lambda t: t.name)
step = max(1, len(tracks) // count)
for t in tracks[::step][:count]:
    d = os.path.join(out, t.name.replace("/", "_"))
    os.makedirs(d, exist_ok=True)
    sf.write(os.path.join(d, "mix.wav"), t.audio.astype(np.float32), t.rate, subtype="FLOAT")
    for s in ("vocals", "drums", "bass", "other"):
        sf.write(os.path.join(d, f"ref_{s}.wav"), t.targets[s].audio.astype(np.float32), t.rate, subtype="FLOAT")
    print("EXCERPT", t.name, f"{t.audio.shape[0] / t.rate:.1f} s")
