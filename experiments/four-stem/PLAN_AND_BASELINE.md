# Dennis Stem Studio — quality-first four-stem experiment
Date: 15 October 2026
Status: SOURCE/ARCHITECTURE CHECKPOINT — NOT YET A FOUR-STEM APK

## Why this experiment exists
The user's current Android v0.1.1 offline separation app provides **vocals and instrumental** 48 kHz/24-bit stereo WAV plus directly compatible Stem Lab ZIP. The separate Dennis Stem Lab Modern v0.5.0 app already accepts **1–32** intact 48 kHz mono/stereo WAVs and has produced good two-stem mastering QC results. The goal now is to objectively compare 2-stem and 4-stem separation quality using the **same song and mastering settings**. Do not change the trusted Dennis AI Mastering Lab v0.15.7, the Stem Lab Modern mastering DSP, or the existing two-stem extraction mode.

## Verified current source constraints
- `app/src/main/cpp/stem_native.cpp` uses the pinned sherpa-onnx C API with `config.model.uvr.model` and explicitly rejects `output->num_stems != 2`.
- `app/src/main/java/com/dennis/stemstudio/MainActivity.java` has two fixed result buttons, two-stem export loops, and two-stem ZIP output.
- The current upstream sherpa-onnx separation documentation describes UVR/MDX-Net and Spleeter as 2-stem outputs. Loading the existing UVR model a second time does not in itself produce valid drums/bass/other separation.
- Do not pretend that changing `num_stems!=2` or adding two file names creates a real four-stem separator.

## Experimental backend recommendation
Prototype a **separate** four-output engine using a genuinely four-stem model (vocals/drums/bass/other), preferably an ONNX HT-Demucs model *after compatibility and licence verification*. Candidate upstream: `StemSplitio/htdemucs-onnx` (Hugging Face, MIT model card), a 316 MB FP32 single-file 4-stem model with a 166 MB FP16-weight storage variant; source: https://huggingface.co/StemSplitio/htdemucs-onnx. It uses fixed-size ~7.8-second segments and needs an overlap-add host pipeline. Do NOT feed that model through `sherpa-onnx`'s UVR configuration: it is a different tensor graph and inference protocol. Validate CPU ONNX Runtime model load, graph operators, mobile memory and latency on the Galaxy S23 Ultra before integrating UI. Quality takes precedence over runtime.

## Requirements for a real implementation
1. Keep the two-stem UVR path byte-for-byte unchanged in an independent selectable mode.
2. Add a separate four-stem backend/model path with typed metadata and clear model compatibility checks. All inference offline.
3. Process chunks with deterministic overlap-add/window compensation and preserve exact sample alignment/length across four output tracks.
4. Handle model-native 44.1k sample rate with high-quality 44.1→48 kHz resampling **at the end of extraction**; avoid channel swapping or fractional frame drift.
5. Use stereo 24-bit PCM outputs: `*_Vocals_24bit.wav`, `*_Drums_24bit.wav`, `*_Bass_24bit.wav`, `*_Other_24bit.wav`.
6. Use one **shared** clipping-protection gain for all four stems, not independent normalization; preserve energy balance.
7. Export a Stem Lab ZIP holding all four original-aligned WAVs; verify ZIP CRCs, rate, channels, equal frame count, peaks and duration. Add source-model identifier, stem hashes, per-stem technical QC and (when available) sum-versus-input diagnostics.
8. Provide user-visible cancellation, storage budget checks and failure cleanup; guard against phone thermal/memory exhaustion.
9. Compare against current 2-stem reference for each song. Validate vocals, drums, bass, other, leakage, artefacts, transients, stereo, and listening results. Use the same final mastering settings and match loudness when A/B auditioning.
10. No main-branch changes or production release until it passes device verification.

## Source-level four-stem mastering compatibility already verified
On the separate `dennisp5-alt/Mastering-app` branch `experiment/dennis-stem-lab-modern-v050`, `FOUR_STEM_BASELINE_TEST.md` records a 12-second Java host fixture test: two-stem and four-stem synthetic integer-equivalent material produced **byte-identical 32-bit float premixes** (SHA-256 `a2b7068d96a35e0d17ead246c3106ba735492c45be6dd6f023d4f22f1adc2a06`). That establishes format/algorithm compatibility, not real-world superiority.

## Next engineering gate
First compile and benchmark the 4-stem ONNX graph in a deliberately isolated native host/device test with a short ~8-second WAV fixture. Record peak resident RAM, time, model/operator compatibility, output tensor order, and reconstruction accuracy. Only when this works should JNI, final resampling/export and UI wiring be added. Do not create an Android APK merely for this architecture note.

No app/DSP source file is modified by this checkpoint.
