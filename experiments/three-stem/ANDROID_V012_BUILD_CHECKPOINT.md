# Dennis Stem Studio — experimental three-stem Android v0.1.2 checkpoint

Date: 16 October 2026  
Source commit used for successful build: `8df87684ac18ca0d04b7c9d45d94a4b99a6ee69b`  
Development branch: `experiment/stem-studio-three-stem-feasibility`  
GitHub Actions run: https://github.com/dennisp5-alt/G3-Connect/actions/runs/38761615485  
Result: **Android debug APK compiled successfully, and KUIELab B prepared model artifact built successfully.**

## Artifacts

- Experimental APK: `DennisStemStudio-v0.1.2-ThreeStem-EXPERIMENT` (artifact 11986880238)
- Compatible drum model: `DennisStemStudio-KUIELabB-Drum-Model-Prepared` (artifact 11985234445)
- Expected model import filename: `kuielab_b_drums_sherpa.onnx` (prepared 21,930,604-byte model)
- APK contains both `libdennisstem.so` (unchanged trusted two-stem engine) and `libdennisthree.so` (experimental three-stem engine), plus sherpa-onnx ARM64 and ONNX Runtime.
- Experimental Android application ID suffix: `.threestemtest`. It installs beside the trusted `com.dennis.stemstudio`, not over it.

## Features

- 10-second preview selection at 0:00, 0:30, 1:00, 1:30, 2:00, or 3:00
- Imports original vocal MDX-Net model and separately imports prepared KUIELab B drum model.
- Pass 1: vocals/instrumental; pass 2: drums from pass-1 instrumental (both sherpa UVR engine).
- Combines `other = instrumental - drums` in float before one 48-kHz resampling/export pass.
- Exports `Vocals`, `Drums`, `Other` in aligned stereo PCM24 WAV and optional ZIP for Dennis Stem Lab.
- One common anti-clipping gain; no individual stem normalization.
- Native stages reported to UI; measured device elapsed processing time shown when successful.

## Evidence limits

- GitHub Android compilation and model preparation succeeded.
- Linux host ONNX + sherpa separation ran with a synthetic test in run https://github.com/dennisp5-alt/G3-Connect/actions/runs/38756959708.
- **No three-stem Samsung Galaxy S23 Ultra runtime, measured performance, model quality, alignment or Stem Lab ingestion has yet been verified.**
- Do not claim three-stem separation will be faster than Demucs without a phone test.
- Leave `main`, Dennis Stem Lab Modern, Dennis AI Mastering Lab and all other apps unchanged.

## Device gate

1. Install the separate experimental APK.
2. Import same UVR vocals model already used for trusted two-stem extraction. It must be imported to the experimental app's own storage.
3. Import prepared KUIELab B file `kuielab_b_drums_sherpa.onnx`.
4. Import an existing stereo WAV of any ordinary length. Test a section where drums are playing (default 1:00 to 1:10).
5. Observe processing duration and whether the app remains responsive; export a 3-stem ZIP.
6. Compare audio clarity, rhythmic transients, bleed, alignment and mastering results; if too slow, keep production 2-stem only.
