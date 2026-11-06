# Six stems, v0.1.8

Vocals, Drums, Bass, Guitar, Piano and Other from one model, HT-Demucs 6-stem (MIT), exported to
ONNX by StemSplit. The fp16-weights file is 136 MB. sherpa-onnx has no Demucs support, so
`six_stem_native.cpp` calls ONNX Runtime's C API directly, using the same bundled library.

- The song goes to 44.1 kHz, then through 7.8-second sections overlapping by a quarter, with Demucs's
  triangular weights. Finished audio leaves memory after each section.
- Other is the song minus the five named stems, so all six add back up to the song.
- The memory arena and pattern stay off, as v0.1.7 proved they must.
- Stems are 48 kHz, 24-bit, named like the four-stem ones, and Master Room accepts them from 0.8.1.
- Installs as `.six018`, beside the other builds. Nothing on `main` or in the two, three and
  four-stem screens changed.

The publisher states 1.1 GB of memory on an Apple M4 Pro. A stand-in model proved the sections,
joins, rate changes and sum: the stems add back to within -70 dB of the song on a real one.
