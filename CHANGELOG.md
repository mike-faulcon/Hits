# Changelog

## Sample-track retest

Sample rows can name a different sample in each pattern. Each distinct sample on a row, including the same file at a different decay, gain, or polarity, is its own preset in bank 64. Playback, WAV, and MIDI keep that program. A sample that cannot be loaded plays its drum note on channel 9, and that note is audible. `hits render` and `hits info` print the warning on stderr. `hits info` lists each sample path and whether it is ready.

Decay on a sample row is the SoundFont volume envelope. One-shot and choke both get shorter below 100%. A one-shot above 100% plays the file. A choke above 100% rings past the step. CC 72 is not added on top of that envelope.

Sample gain defaults to +8 dB and is omitted from the file at that value. Sample rows saved without `gain` play at this louder level. `"gain": 0` keeps the quieter level from the first sample-track release. Drum and note rows are unchanged. Invert flips the phase and is omitted when off.

A sample row sends reverb 0 unless the row sets a send. The built-in synth would otherwise leave the channel at 40.

The built-in 808 rings to about 400 ms at −20 dB. The old “406 ms” figure was the −40 dB point. Click and Snap descriptions name the measurement: a rendered click centroid near 1.4 kHz over the first 70 ms, and a Snap centroid near 2.2 kHz after a 300 Hz high-pass from 5 ms before the onset to 70 ms after.

WAV export with no beat file resolves a relative sample against the working directory and throws if the file is not there.

### Decay controller, from the earlier decay retest

The decay percent was remapped onto the CC 72 values the built-in synth actually uses. CC 72 is 7-bit, so one step is as fine as that controller gets. There is no migration: the stored percent is the same, and older beats sound like the new mapping.

| Decay | Before | Now |
| --- | --- | --- |
| 10% | — | CC 72 = 56 |
| 90% (rim) | CC 72 = 56 | CC 72 = 63 |
| 100% | nothing | nothing (64 is neutral) |
| 120% (boom) | CC 72 = 76 | CC 72 = 77 |
| 200% | — | CC 72 = 127 |

On Syn Bass near 120%, one CC 72 step is about 95 ms. A boom at 120% rings about 404 ms before the remap and about 499 ms after it. Sample rows do not use this controller.
