# Dennis Stem Studio — Three-Stem Feasibility Checkpoint
Date: 16 October 2026
Status: RESEARCH ONLY — no model integrated, no APK built, no audio-quality/speed results established.

## Development boundary

This branch starts from the trusted 2-stem `main` and must not change `main` or any other app.
The previous 4-stem HT-Demucs experimental branch remains intact for reference.
2-stem exports and the trusted `NativeSeparator` backend remain unchanged.

## Practical user goal

Original 2-stem mode is fast enough for regular use. Four-stem HT-Demucs was
unacceptably slow on the Galaxy S23 Ultra. Investigate whether a separate,
optional 3-stem mode is actually worthwhile: **Vocals / Drums / Other instruments
(including bass)**. The goal is better remix/mastering control WITHOUT the
delay of the prior 4-stem system; do not assume fewer output files means
faster AI inference.

## Candidate A — two-stage separation, strong baseline

1. Keep the user's proven sherpa-onnx UVR model for vocals + instrumental.
2. With a *genuine drums-trained model*, isolate drums from the original mix
   or the instrumental (compare which gives cleaner results); never pretend
   the available 'Inst' / vocal UVR models are drums models.
3. Correct for relative alignment and output any shared anti-clipping
   attenuation. Construct "Other" as instrumental minus drums, ideally
   in floating point at a common sample rate before quantizing once.
4. Result stems: vocals, drums, other (bass included); same exact duration,
   48kHz stereo PCM24, one ZIP for Dennis Stem Lab.
5. A second inference pass costs time: speed depends entirely on the drum model.
   Do not claim this path beats the existing full-4-stem implementation without
   phone measurements.

Candidate drums specialist: https://huggingface.co/StemSplitio/htdemucs-ft-drums-onnx
- ONNX opset 17, 316MB model, licensed MIT per model card.
- Inputs: 44.1kHz stereo, fixed 343980-sample (~7.8s) chunks; output shape
  [1,4,2,343980], drums row 0.
- On the *publisher's* M4 Pro CPU: about 22 seconds for a 3-minute song.
  **NOT a Samsung S23 Ultra benchmark.**
- Publisher's claimed ~4x speedup is versus a *four-specialist ensemble*;
  it does NOT imply a 4x improvement against this app's prior single ONNX
  HT-Demucs 4-stem graph. The specialist is still computationally demanding.

## Candidate B — lightweight single model, 4-way output then merge to 3

Spleeter 4-stem outputs vocals/drums/bass/other. Combining bass+other gives
the exact requested 3 *output* stems without a second model pass. The
architecture may be faster than HT-Demucs on a phone, but must be benchmarked
and quality may lag. IMPORTANT: current sherpa-onnx Spleeter integration only
provides **two-stem Spleeter**; the four-stem TensorFlow model needs a genuine
compatible Android execution path / conversion before claiming viability.

References:
- https://github.com/deezer/spleeter/wiki/3.-Models
- https://k2-fsa.github.io/sherpa/onnx/source-separation/models.html
- https://github.com/deezer/spleeter

## Recommendation / gates

- DEFAULT: Keep existing 2-stem production workflow as-is.
- FIRST: Host and device benchmark a small real drums-specialist ONNX candidate
  on an 8-to-10-second clip, recording time, peak RSS/PSS and success/failure.
  Do NOT build a full experimental app before knowing the model runs well.
- ALTERNATIVE: research practical 4-stem Spleeter export/ONNX-compatible backend;
  if viable, run a 10-second single-pass test and map 4-to-3 via bass+other.
- Test 2-stem and 3-stem on the SAME songs; compare matched-level auditions
  for quality, residual vocal bleed, drum transients, bass, stereo, time.
- Only progress if measured performance AND sound justify the extra runtime.
- Use an independent experimental build/application ID for any eventual APK.
- Do not modify Dennis Stem Lab Modern, Dennis AI Mastering Lab or any other app.
- This note is a feasibility checkpoint, not evidence that a 3-stem system works.

## Existing safe two-stem app

`dennisp5-alt/G3-Connect` main branch, `com.dennis.stemstudio`,
v0.1.1, unchanged. Existing 4-stem work stays on
`experiment/stem-studio-four-stem-v012` for reference.
