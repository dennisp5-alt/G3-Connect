# Dennis Stem Studio v0.1.4 — Four-stem sequential experiment

Built: 16 Oct 2026.
Branch: `experiment/stem-studio-four-stem-sequential-v014`.
Successful APK source commit: `9ea4e4528261c7b9454a46901611f62b096f1f13`.
CI: https://github.com/dennisp5-alt/G3-Connect/actions/runs/38784447789 — ALL STEPS SUCCESS.

## Artifacts validated and extracted

- APK `DennisStemStudio-v0.1.4-FourStem-Sequential.apk`, 13,511,536 bytes, SHA-256 `ed82b8d00938c108fe7b83e37af9f2fb91e517f585c534699de51a1c5245b931`.
- Bass ONNX `kuielab_b_bass_sherpa.onnx`, 29,703,493 bytes, SHA-256 `e3017accda2c4e94ccbd5c03d9410d8119d11a6a59f04d4d3018da8ca7731c5f`.
- Drums ONNX `kuielab_b_drums_sherpa.onnx`, 21,930,604 bytes, SHA-256 `e1a97fd05a3e96756c09fc0e7c5ae3a07cd784c016e70491fd47a01cc7a5079a` (reuse from earlier 3-stem workflow).
- APK ZIP integrity and native libraries verified: libdennisstem.so, libdennisthree.so, libdennisfour.so, libonnxruntime.so, libsherpa-onnx-c-api.so.
- Bass host synthetic 3-second sherpa test passed; 2.448 seconds inference, 644.77 MB peak Linux process RSS. Not Android measurements.
- Original unprepared bass model SHA-256 `b4b7080fe501d0bece62076c5d4eda4d6590c5207ed78ec84a57bac0740a061d`. Shape [dynamic,4,2048,256]. Model metadata added by `experiments/four-stem/prepare_bass_uvr.py`.

## Modes

Four-stem mode is separate from the original two-stem and v0.1.3 three-stem screens. Experimental APK has application ID `com.dennis.stemstudio.sequentialfourtest` so it installs alongside the trusted v0.1.1 and three-stem v0.1.3. Model imports are app-private, so imported models must be installed in this new experimental instance.
Four-stem sequential order:
1. Original UVR vocals + instrumental
2. KUIELab B drums extracted from instrumental
3. KUIELab B bass extracted from instrumental minus drums
4. 'Other' residual = instrumental minus drums minus bass
5. One common anti-clip attenuation, four aligned 48 kHz 24-bit stereo WAV outputs and optional ZIP for Dennis Stem Lab.
Six-second inference core + 1-second context, 100ms crossfades, disk streaming floats, two-pass global peak/export. Full-song, 60-second and 10-second preview choices; **default 10-second 1:00–1:10**. The bass pass is non-trivial; full song not yet device-tested.

## Safety and next tests

- Protected `main` unchanged `8489043f038def1d4c4cf6c693c937bee9a84cd8`. Original `app/src/main/cpp/stem_native.cpp` unchanged. Previous three-stem branch intact.
- No files changed in either mastering app.
- On S23 Ultra first import existing original vocal model plus separate prepared drum and bass files. Select 10 sec section with bass and drums. Time it and audition all 4.
- Only try 60 sec and full song if 10-second device processing is stable, memory/heat reasonable and stems sound good.
- Host checks alone cannot prove separation accuracy, phone memory capacity, full-song performance or complete consistency with 2-stem. Do not claim production release.
