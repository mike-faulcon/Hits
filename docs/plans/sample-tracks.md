# Sample tracks

**Shipped.** One-shot WAV tracks, three generated built-ins, a companion SoundFont on MIDI export, and a preset per sample so two patterns on one row can play two files.

## Still deferred

- Stereo samples. Files are downmixed to mono 16-bit before they go into the bank.
- Looping samples, velocity layers, and round-robin.
- A start offset in milliseconds. Start is a frame count.
- Embedding the SoundFont inside the MIDI file. Export writes a companion `<name>-samples.sf2` instead.
- Playing the WAV on an external MIDI output. That output hears the row's drum note on channel 9.
- Deleting files in `<beat>.samples/` that the beat no longer names. Collect only copies.
- CC 75. Decay stays on CC 72 for drum and note rows. A sample row does not send CC 72. See the plans index.
- A one-shot cannot ring longer than its file. Decay above 100% matches 100% and the inspector marks that part of the slider `file`.

## What the code does

`TrackMode.SAMPLE` and a `sample` object are version 2. A beat that never uses them is still written as version 1, byte for byte. Optional `gain` is omitted at +8 dB. Optional `invert` is omitted when false. A sample file that has no `gain` field plays at +8 dB. `"gain": 0` is the quieter level from the first sample-track release. Drum and note rows do not use either field.

`SampleBank` writes an in-memory SoundFont 2 file and `MidiSystem.getSoundbank` reads it. The bank number is 64 in the file, which the built-in synth maps to MIDI bank 8192 (CC 0 = 64, CC 32 = 0). That bank is not 120, 121, or 128. Each distinct sample on a row is its own program: the same file at a different decay, gain, or polarity is a different preset, because those are baked into the voice. The first sample on a row keeps program = row. Further samples take the next free program. Playback, WAV, and MIDI send that program and stay on bank 64.

A missing file, or a voice that does not fit, falls back to the row's drum note on channel 9. Gervill will not switch a melodic channel from bank 64 to the drum bank, so the fallback must be channel 9, and it must not send bank 120 on that channel. `hits render` and `hits info` print the warning on stderr. `hits info` lists the path and `ready` or `missing`.

One-shot is a sample that does not loop, with a release long enough that note-off does not cut it. Choke uses a short release. Decay is the volume-envelope decay, sustain, and release of that preset. Below 100%, both modes fade to silence in that fraction of the file. A choke at 100% still stops on note-off. Above 100%, a choke lengthens the release by the same timecents CC 72 would have added, and a one-shot stays at the file. CC 72 is not sent for a sample that is playing. If another pattern on the channel used decay, the sample pattern sends 64 so the earlier value does not stick.

Gain is SF2 `initialAttenuation`. Gervill scales that generator by about −0.376 before adding it to mixer gain, so a negative amount is a boost. The default +8 dB is that boost. Polarity inverts the PCM before it is written into the bank.

A sample row that plays sends reverb 0 unless the row sets a send. The channel starts at 40, so an unset sample would otherwise ring on after the file. A fallback on channel 9 does not send reverb 0, because that channel is the shared drum kit.

Cutoff for a sample row is 0–64. The voice has no filter of its own, so 64 is already open and a higher controller does not brighten it. Kit drums, bass, and other notes keep the ranges measured for those instruments.

WAV export with no beat file resolves a relative sample against the working directory and throws if the file is not there. Export with a beat file still warns and falls back when the file is missing.

The built-in 808 is a sine from 80 Hz to 68 Hz. Its −20 dB point, measured from the start to the last sample still at that level, is about 400 ms. The old 406 ms figure was the −40 dB point, and the old −20 dB point was about 205 ms. Click's rendered onset centroid, over the first 70 ms, is about 1.4 kHz. Snap, with a 300 Hz high-pass and a window from 5 ms before the onset to 70 ms after, is about 2.2 kHz. The unfiltered full-file Snap centroid is lower because of the quiet low body.
