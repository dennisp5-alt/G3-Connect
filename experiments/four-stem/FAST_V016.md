# Dennis Stem Studio v0.1.6: faster four stems

Branch `experiment/stem-studio-four-stem-fast-v016`, started from the v0.1.5 bass fix.
`main`, the 2-stem screen and the 3-stem screen are unchanged. It installs beside other
builds as `com.dennis.stemstudio.fast016`.

## What changed
- Each model loads once and runs the whole song, instead of three loads per section.
  Only one model is in memory at a time, with results kept on storage between passes.
- Sections are 18 seconds, as proven by the 3-stem build, so context costs 11% and not 33%.
- Thread count is chosen on screen: 4 to start, 2 to compare with v0.1.4.
- The join and export code is compiled with -O3 even in debug APKs.

The vocal, drum and bass models, their order and the final 24-bit 48 kHz export are unchanged.

## Measured on a GitHub computer, not a phone
60 seconds of a synthetic test song, same three models:
- v0.1.4: 182 seconds
- v0.1.6 at 2 threads: 147 seconds, 19% faster
- v0.1.6 at 4 threads: 161 seconds on this small computer, which cannot show what the phone's cores will do

Model loading turned out to be cheap here. Almost all the time is the AI itself, so most of
this gain comes from less repeated context. Thread count is the main thing left to measure,
and it can only be measured on the phone. The corrected bass stem had 97.7% of its power
below 150 Hz, against 0.1% in the v0.1.4 report. Stems are not identical to v0.1.4 because
the sections fall differently, so compare them by ear.

## Phone test, in order
1. Use the same song and the 60-second section in v0.1.4 and in this build at 2 threads.
2. Repeat at 4, then 5 threads. More threads can be slower if the phone's small cores join in.
3. Compare the bass stem, as this is the first build with the corrected bass model built in to the test.
4. Only then run a full song, and watch the memory figure and heat.
