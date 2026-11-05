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

## Phone test, in order
1. Use the same song and the 60-second section in v0.1.4 and in this build at 2 threads.
2. Repeat at 4, then 5 threads. More threads can be slower if the phone's small cores join in.
3. Compare the bass stem, as this is the first build with the corrected bass model built in to the test.
4. Only then run a full song, and watch the memory figure and heat.
