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

## v0.1.7: the crash on full songs
Phones climbed past 3,000 MB and then crashed. Measured on a computer with the real models:
the AI runtime keeps its working memory, then builds a second block when the section size
changes, so a 20-second section took 1.9 GB on the first run and 3.8 GB on the next.
Each model does this. Sections cannot be made safe by shortening them, as the bass model
still peaked at 1.7 GB at 11.5 seconds.

v0.1.7 turns off the runtime's memory arena and memory pattern through sherpa-onnx's provider
config file. The peak falls to about 1.7 GB, and memory drops back to about 0.3 GB between
runs, with no speed cost. The result line now reports the peak memory for the run.
The app id is `.fast017`, so it installs beside v0.1.6.

Measured on a computer at 2 threads, with Catch Me Before I Fall2:
- 60 seconds, v0.1.6: peak 3,889 MB, 78.7 seconds
- 60 seconds, v0.1.7: peak 1,729 MB, 81.2 seconds, and all four stems are bit-identical
- Whole song, 3:42, v0.1.7: peak 1,735 MB, 305 seconds, 13 sections

## Phone test, in order
1. Use the same song and the 60-second section in v0.1.4 and in this build at 2 threads.
2. Repeat at 4, then 5 threads. More threads can be slower if the phone's small cores join in.
3. Compare the bass stem, as this is the first build with the corrected bass model built in to the test.
4. Only then run a full song, and watch the memory figure and heat.
