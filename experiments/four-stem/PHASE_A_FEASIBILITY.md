# Dennis Stem Studio — four-stem Phase A feasibility gate

Date: 15 October 2026  
Branch: `experiment/stem-studio-four-stem-v012`  
Status: **SOURCE-LEVEL TEST HARNESS ADDED. A real ONNX run and S23 Ultra hardware verification remain outstanding.**

## Application protection

This branch is the only writable target for this experiment.
**Do not update `main`, modify the existing UVR 2-stem C++/JNI interface, edit the separate Stem Lab or AI Mastering Lab, or trigger Android APK builds for planning-only work.**

## Verified model contract (published upstream, NOT yet verified on S23 Ultra)

Candidate: [StemSplitio/htdemucs-onnx](https://huggingface.co/StemSplitio/htdemucs-onnx) (MIT model card).

| Item | Contract |
| --- | --- |
| Model | `htdemucs.onnx` (FP32 weights, ~316 MB), alternatively `htdemucs_fp16weights.onnx` (~166 MB on disk) |
| ONNX opset | 17 (upstream) |
| Input | `mix` float32 [1,2,343980], native stereo 44,100 Hz (7.8 s) |
| Output | `stems` float32 [1,4,2,343980] |
| **Model output order** | **drums, bass, other, vocals** |
| **Required user / Stem Lab export order** | **vocals, drums, bass, other** |
| Duration | Fixed-sized ONNX segment; later use overlap/add for arbitrary tracks |
| fp16weights | Disk-size optimization only; upstream says same inference RAM and cost |

References:
- https://huggingface.co/StemSplitio/htdemucs-onnx/blob/main/README.md
- https://huggingface.co/StemSplitio/htdemucs-onnx/raw/main/infer.py
- https://onnxruntime.ai/docs/get-started/with-c.html
- https://onnxruntime.ai/docs/build/android.html

**These published measurements are not S23 Ultra measurements.** The upstream reports about 1.1 GB RAM for a 7.8-second inference on an Apple M4 Pro; actual Android memory may be substantially different. The S23 Ultra may have 8 GB or 12 GB RAM depending on the variant, and allocation failure remains a gating risk.

## Probe (separate from Android app)

`probe_htdemucs_onnx.py` is an intentionally standalone host CPU benchmark.

Prerequisites on a Linux/macOS host: Python 3, numpy, scipy, soundfile, onnxruntime.

```bash
python -m pip install numpy scipy soundfile onnxruntime
python experiments/four-stem/probe_htdemucs_onnx.py \
  --model /absolute/path/htdemucs_fp16weights.onnx \
  --threads 2 \
  --output-json /tmp/stem-studio-phase-a-synthetic.json

# Prefer an 8-second real stereo WAV (source must be licensed for local testing):
python experiments/four-stem/probe_htdemucs_onnx.py \
  --model /absolute/path/htdemucs_fp16weights.onnx \
  --wav /absolute/path/audio-test.wav \
  --threads 2 \
  --output-json /tmp/stem-studio-phase-a-real.json
```

The probe validates exact graph names/shapes/types, performs one real model run, reports process RAM, elapsed load/inference times, measured stem peaks and sum-versus-mixture RMS error. WAV inputs at 48 kHz are resampled to 44.1 kHz for this test only.

It does **not** generate a mastering-ready 48 kHz WAV, use overlap/add, run on Android, or prove acoustic quality. This distinction is deliberate.

## Acceptance gates BEFORE Android app integration

1. **Host ONNX load and run succeeds**, on supported ONNX Runtime, with expected four output stems. Save ONNX file SHA-256 and JSON report.
2. **Android CPU operator compatibility**: separately prove an ARM64 ONNX Runtime build can load and execute the exact model graph; never assume the sherpa-onnx UVR wrapper can run this graph. Verify existing `libonnxruntime.so` build has all required operators or choose a separate, compatible runtime without duplicate-library collision.
3. **S23 Ultra safety**: benchmark one 7.8-second segment on the device. Record total/peak process RSS, device RAM, runtime, thermal status, and repeated-run stability. Reject unacceptable memory, ANR or thermal behavior. Do not claim specific timings before testing.
4. **Algorithm**: after gates 1–3, implement one-session sequential-chunk processing with 25% overlap and normalized overlap-add; trim or pad only at the model boundaries to ensure exact input frame count, and resample **48 kHz input to 44.1 kHz BEFORE inference**.
5. **Output engineering**: resample 44.1→48 kHz after recombination, preserving identical sample count for all 4 stems. Ensure stereo; one shared anti-clipping gain for all 4; no per-stem normalization or mastering effects. Export ordered WAV files plus one Stem Lab ZIP, with sample counts / peak / clipping / residual QC.
6. **Integration**: create a separate four-stem ONNX-specific engine and distinct native API, never modify the existing two-stem UVR inference logic. Add a mode selector only after engine-gate pass.
7. **Real music**: same song A/B across two-stem and four-stem extraction, match loudness of Stem Lab masters and compare artifacts, drum transients, bass, vocal isolation, stereo consistency.

## Licensing checkpoint

The HT-Demucs ONNX model is published with an MIT license and the export project's authorship attribution. The original Demucs training data includes MUSDB material licensed for noncommercial research. Legal obligations for redistribution / commercial use of model weights deserve review before bundling or distributing a product. The test app should initially **import a locally chosen model**, without committing any model binary to Git.

## First technical checkpoint

- Confirmed the experimental branch starts from the trusted v0.1.1 two-stem implementation.
- Inspected the two-stem C++ file: UVR-only API and hard-coded output count; reuse only common WAV/QC patterns later, not the model inference.
- Source-only ONNX compatibility probe added to `experiments/four-stem/probe_htdemucs_onnx.py`.
- No 4-stem Android app has been built. No changes to `main` or unrelated apps.
- Host inference not run in this session: the current execution environment does not have ONNX Runtime and does not permit installing it from the package network. **No CPU, ARM64 or phone compatibility result is claimed.**
