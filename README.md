# Dennis Stem Studio · v0.1.0-engine-test

Standalone **offline** Android stem separator for Galaxy S23 Ultra (arm64).
Independent application ID `com.dennis.stemstudio`; does not access or modify Dennis AI Mastering Lab.

## Status

This repository contains an **unverified first implementation** for a 2-stem, quality-first engine test.
It is not validated on a device, and no successful APK build is implied by the presence of source code.
The C ABI / library compatibility and imported model must be verified with a real build and phone test.

## What v0.1 actually implements

- A native C API connection to sherpa-onnx MDX-Net/UVR through prebuilt Android arm64 libraries.
- Pick an ONNX UVR model file from local storage, persist a private copy for subsequent offline operation.
- Pick an ordinary stereo WAV (PCM 16/24/32 or float32, 44.1/48 kHz).
- Run CPU inference on a background thread without estimating fake progress.
- Save two **24-bit PCM stereo WAV** outputs in an app-private working folder.
- Audition both stems; export both to a user-chosen document folder.
- Output diagnostics include common attenuation (only when needed to avoid 24-bit clipping).

**Not in this version:** 4-/6-stem separation, automated model downloads, MP3/FLAC decoding,
true progress, background jobs, model ensembles/refinement, artefact repair, automatic model verification,
quality scoring. Do not call this version studio-grade before critical listening tests.

## Build

Prerequisites: Android SDK 35; Android NDK 27.2.12479018; CMake 3.22.1; JDK17; Gradle 8.11.1.

```bash
./scripts/prepare_native.sh
# sets up pinned sherpa-onnx 1.13.8 public Android C API binaries and header
gradle :app:assembleDebug
```

The included GitHub Actions workflow can produce an APK once the files are committed to the repurposed **G3-Connect** repository (original G3 code archived on `archive/g3-connect-v0.6.2`).
The source package deliberately does not contain 3rd-party native .so binaries or pre-trained model weights.

## Model installation (user action)

Download a *compatible* UVR / MDX-Net ONNX model through an authorised source
(e.g. the official sherpa-onnx source-separation model release), save it locally on your phone,
then press **Install separation model** and select its `.onnx` file.

Suggested initial compatibility test model: `UVR_MDXNET_9482.onnx` from
https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models

Model redistribution and commercial usage require verification of the **model weights' own license**;
the Apache-2.0 license covering the sherpa-onnx engine does not automatically license its models.

## Test plan

1. Install model (a user-selected local file); turn off Wi-Fi and cellular data.
2. Import a 30-second stereo WAV clip. Run separation.
3. Audition vocals for consonants, reverb tails, metallic smear and instrument bleed.
4. Audition accompaniment for missing percussion, phase issues and residual vocals.
5. Export and compare sample rates, alignment, channel count, peak, stem sum and silent gaps.
6. Test entire songs and monitor process memory, thermal limits and battery use.
7. Compare at least two licensed compatible models using the same audio sample.

## Limitations

Separation estimates sources; it cannot recreate hidden original multitrack masters.
The separation API is currently run on a whole file and may run out of memory on long songs;
chunk-safe processing is a later milestone after establishing device performance.
There is no external storage or network permission requested by the Android application.
