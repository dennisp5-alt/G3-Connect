# Studio Quality experiment (BS-RoFormer SW six stems)

Nothing here changes Stem Studio's working engine, window, exports or Master Room ZIP. This folder and
`.github/workflows/studio-quality-probe.yml` only measure.

The probe runs on GitHub's processors (no graphics card):
- what the BS-RoFormer SW checkpoint is (parameters, settings), fetched and hash-checked by bs-roformer-infer;
- both models on the same real-music excerpts with true stems (MUSDB18 7-second test previews): SDR per stem,
  time, peak memory, and how well the stems add back up;
- the HT-Demucs six-stem engine Stem Studio ships, built from desktop/engine, as the baseline.

The excerpts and the model weights are downloaded inside the run, never committed or uploaded. Only the
numbers are kept. BS-RoFormer SW's weights have no stated licence; see the feasibility report before any use
beyond this measurement.
