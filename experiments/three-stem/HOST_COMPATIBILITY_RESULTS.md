# Three-Stem Feasibility — Verified Host Model Tests

**Recorded:** 16 October 2026  
**Repo/branch:** `dennisp5-alt/G3-Connect` / `experiment/stem-studio-three-stem-feasibility`  
**Build status:** No 3-stem Android app APK built. Source-level compatibility proven on Linux x86-64 only.

## What is now verified

Both KUIELab MDX-Net drum-specialist ONNX models were downloaded with known SHA-256 digests and run using CPU ONNX Runtime 1.31.0 on a GitHub Ubuntu x86-64 runner:

| Model | ONNX bytes | Input/output tensor | Repeat per-tensor inference | Process peak RSS | Status |
|---|---:|---|---:|---:|---|
| `kuielab_a_drums.onnx` | 29,703,204 | float32 [1, 4, 2048, 512] | 4.683 seconds | 2,196 MB | Pass |
| **`kuielab_b_drums.onnx`** | **21,930,313** | float32 **[1, 4, 2048, 128]** | **1.094 seconds** | **610 MB** | **Pass** |

Times above are for random spectrogram tensor input, not a song-duration end-to-end workload. The host is not an S23 Ultra; these measurements do not predict Android CPU speed.

- KUIELab A SHA-256: `40f586b7091934dd6f5563f0cba8f14bad57ce88440da1098bf388ea716c2901`
- KUIELab B SHA-256: `a6fecee758059b33ed99f6dabba297439b3e7cacfac4b1097bd324aff8052208`
- Download source: https://huggingface.co/Politrees/UVR_resources/tree/main/models/MDXNet
- Upstream: https://github.com/kuielab/mdx-net
- Earlier A test failure was a **test-harness guard limit** of 4M tensor elements; rerun with corrected 8M limit passed. The model had not actually failed inference.

## Existing sherpa-onnx compatibility: **PASS ON HOST**

On a separate repeatable GitHub Actions test:
1. Downloaded B's original drum-specialist ONNX with SHA verification.
2. Used `prepare_drum_uvr.py` to **copy** the model and add the exact metadata required by sherpa-onnx v1.13.8 (n_fft 4096, hop 1024, dim_c 4, dim_f 2048, dim_t 128, 44.1kHz).
3. Successfully initialized `sherpa_onnx.OfflineSourceSeparation` in UVR model mode and processed a 3-second stereo synthetic WAV.
4. Received two finite, equal-length stereo outputs at 44.1kHz: [2, 132300] each.
5. Loading took 0.092s; **3-second synthetic signal processed in 2.42s** on the GitHub Linux runner. Peak process RSS **621 MB**.

**IMPORTANT:** The model predicts **drums**, not vocals; its first output is drums even though the existing application exports the first UVR output under the filename "Vocals". Never pass the drum model through the existing 2-stem user interface and represent its output as vocals. A separate 3-stem mode must correctly label and combine outputs.

GitHub workflow/run: https://github.com/dennisp5-alt/G3-Connect/actions/runs/38756959708  
CI report artifact: https://github.com/dennisp5-alt/G3-Connect/actions/runs/38756959708/artifacts/11984310807

## Three-stem engineering recommendation

**Prototype on an experimental package/branch, NOT on main**:

- First sherpa UVR inference using trusted original vocal+instrumental model.
- Second sherpa UVR inference using the **metadata-prepared KUIELab B drums model**, preferably on the instrumental. Use the drums prediction from output 0.
- Retain trusted vocal samples; make `other = instrumental - drums` in float at a common native sample rate (44.1 kHz), to preserve relative balance, alignment and mixture reconstruction.
- Export 3 aligned stereo 48kHz, PCM24 WAVs: vocals, drums, other. Shared safety gain across all three (including post-resampling peaks); no independent stem normalization. Export ZIP compatible with Dennis Stem Lab.
- Benchmark native Android ARM64 model load, RAM/PSS, latency, temperature, and full-song time, ideally first in a selectable short-preview mode.
- Compare matched song masters against existing 2-stem mode; do not presume audio quality improves. If too slow or instruments bleed excessively, stop and retain the proven two-stem path.
- Keep `NativeSeparator` original two-stem code untouched; new mode gets a separate C++/JNI path, clear filename and model validation and rollback checkpoints.

## Licensing

KUIELab's source code license is MIT per original repo. Models hosted by third parties should be checked for any additional model/data redistribution restrictions before bundling. Keep model as a user-imported local file for prototype; do not commit model bytes to Git.

## Protection

No other applications modified. The repo's `main` at v0.1.1 continues to be the trusted two-stem release.
The old 4-stem experiment remains untouched on its separate branch.
