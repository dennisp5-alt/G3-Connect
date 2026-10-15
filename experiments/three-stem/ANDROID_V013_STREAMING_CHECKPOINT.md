# Dennis Stem Studio v0.1.3 — 3-stem full-song streaming checkpoint

Date: 16 October 2026.
Branch: experiment/stem-studio-three-stem-feasibility.
Successfully built source commit: 17bb33b6459a973c788346c317a6c9b1c59c3d7c.
Build: https://github.com/dennisp5-alt/G3-Connect/actions/runs/38767904962 — SUCCESS.

## Build artifacts
- Android ARM64 APK: DennisStemStudio-v0.1.3-ThreeStem-Streaming (artifact ID 11989747807)
- Optional replacement of prepared KUIELab B ONNX: DennisStemStudio-KUIELabB-Drum-Model-Prepared (artifact ID 11989908118)
- APK extracted SHA256: 4947db52b2b24786de143c651d3b79f875def8ab4717e3148d983f8e53e2d86a; 13,314,604 bytes.
- Prepared model extracted SHA256: e1a97fd05a3e96756c09fc0e7c5ae3a07cd784c016e70491fd47a01cc7a5079a; 21,930,604 bytes.
- ZIP and APK integrity verified; APK has libdennisstem.so, libdennisthree.so, libonnxruntime.so and libsherpa-onnx-c-api.so.
- User who installed v0.1.2 experimental app should install v0.1.3 **over it**, reusing previously imported models. Distinct app ID suffix `.threestemtest` preserves protected v0.1.1 two-stem app.

## Implemented features
- Full WAV up to 12 minutes, 60-second test sections or prior selectable 10-second tests.
- 18-second core chunks, 1-second AI inference context both sides; aligned 100-millisecond boundary crossfades.
- Sequential sherpa UVR vocal, then KUIELab B drum model on instrumental.
- Drum stem from second model, 'Other' = initial instrumental minus estimated drums at a common rate.
- 3 mono? NO — 3 **stereo** disk-backed temporary floats; 48kHz/24-bit stereo WAV final output and Stem Lab ZIP.
- One global anti-clipping gain based on all three float scratch outputs. No per-stem normalization.
- Native storage preflight checks for scratch+output; cancel at AI-chunk boundaries.
- UI elapsed timer, stage-aware chunk progress and diagnostic approximate process memory PSS.
- Crash-marker recovered on relaunch if Android kills process during inference.
- Preserved original two-stem native file and all mastering apps.

## Quality gates still outstanding
- GitHub host build does not establish runtime on S23 Ultra.
- On-device full-song timing, heat, RAM, seamless joint listening and residual bleed unverified.
- First test 60-second segment, then full-song if time and result reasonable.
- Export ZIP and compare against original mix; check all 3 WAVs sample-aligned and sum gain.
- Do not merge three-stem experiment into the original stable main until tested.

## Protected baseline
Repo `main` unchanged at 8489043f038def1d4c4cf6c693c937bee9a84cd8.
`app/src/main/cpp/stem_native.cpp` unchanged.
