# Bass model FFT fix — Dennis Stem Studio v0.1.4

Diagnosis confirmed 16 October 2026 from real full-song `Count_Me_In_4Stems_48kHz_24bit.zip` (bass faint/thin and only 0.1% of bass-file power below 150Hz). A critical mistake in the previous model-preparation script set `n_fft = 2*dim_f = 4096` and `win_length = 4096`.

**The original KUIELab bass model MUST use `n_fft=16384`, `win_length=16384`, `dim_f=2048`, `dim_t=256`, `hop_length=1024`.** `dim_f` is the truncated number of spectral bins, not half the native FFT length.

Source of truth:
- https://github.com/kuielab/mdx-net/blob/Leaderboard_B/configs/model/ConvTDFNet_bass.yaml
- https://github.com/kuielab/mdx-net/blob/Leaderboard_A/configs/model/ConvTDFNet_bass.yaml

## Fix

`experiments/four-stem/prepare_bass_uvr.py` now emits correct `n_fft` and `win_length` in the copied ONNX metadata, and validates the result. No weights or actual ONNX inference graph are changed. The model retains the same import filename `kuielab_b_bass_sherpa.onnx`.

A dedicated CI workflow downloads the exact pinned source (SHA256 `b4b7080fe501d0bece62076c5d4eda4d6590c5207ed78ec84a57bac0740a061d`), applies corrected STFT metadata, checks the ONNX, executes sherpa-onnx with a short synthetic stereo input, then publishes the corrected model. No new APK required: the existing v0.1.4 four-stem experimental app imports this compatible model by file name, overwriting the old one.

## Validation gate

1. Import the corrected `kuielab_b_bass_sherpa.onnx` into the **4 STEMS** screen. Keep original vocal and drum models unchanged.
2. Run 10-second preview of a bass-heavy section.
3. Audition Bass; confirm the actual bass line is present without persistent high-frequency whine.
4. Export four-stem ZIP and compare energy below 150 Hz and artefact frequencies with previous baseline.
5. Only attempt a full 3:48 song again after this short test. Do NOT claim corrected quality before on-device audition.
6. If bass still sounds wrong, change which input is fed into the bass specialist (instrumental versus instrumental-minus-drums) and compare alternatives as a separate experiment.

Protected v0.1.1 two-stem `main` and proven three-stem branch remain untouched.
