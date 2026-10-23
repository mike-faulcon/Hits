# Changelog

## Row EQ

Each row has a parametric EQ under Low cut and Presence: two peaking bands and a high cut. A band runs from 30 Hz to 12 kHz, gain −18 to +12 dB, Q 0.3 to 8. High cut is a 12 dB/oct low-pass from 1 kHz to 20 kHz and stays off until it is turned on. Flat bands are left out of the file. The inspector draws a small combined response from 30 Hz to 20 kHz.

The filter order on a sample is invert, low cut, EQ bands, high cut, presence, then gain compensation. A drum or note row is captured dry, those stages run on the recording, and the playback gain is part of the compensation. Live playback and WAV export, including `hits render`, use that bank. MIDI export and `--samples-sf2` keep the ordinary note. `hits info`, `hits render` to MIDI, and the export dialog name the rows.

Sample gain, presence, and EQ gain are stored in 0.1 dB steps. A whole number is written without a decimal, so a beat that does not use a fraction saves the same bytes as before. A value that is not on a step is rounded, and a value outside the range is clamped. `hits info` and `hits render` say which row, what was asked, and what was stored. Presence above +12 dB used to clamp with no warning. An EQ gain does the same clamp, and says so.

The inspector's painted controls report the state they draw. A slope or mode button that is filled is selected, so accessibility sees it as checked, and a control drawn on is enabled. On an external MIDI output the filter controls stay grey, not selected, and disabled.

A GM kick (note 36) with a peaking band at 90 Hz, −12 dB, Q 1.4 drops 60–120 Hz by about 9 dB. 30–60 Hz moves about 3.6 dB, because that Q is already about −5 dB at 60 Hz and the kick's sub sits near the top of the band. 200–600 Hz stays inside 1 dB. A GM snare (note 38) with a high cut at 4 kHz lowers the power-weighted spectral centroid (about 244 Hz to about 153 Hz) and the band above 3 kHz by about 8 dB. The centroid stays low because most of the snare's power is in the body, not the hiss.

## Filter fixes

A low cut or presence on one pattern no longer changes the plain drum on that row in another pattern. The filtered voice uses its own sample-bank channel. The plain voice keeps the channel it would have had alone. Chaining a filtered kick (tune +15 st, presence +12) into an unfiltered kick (tune +19 st) left a +2.3 dB residual in the second bar, with the lows down 4.7 dB and the highs up 3.4 dB. The same comparison is now a −80.8 dB residual, and those bands match within 0.1 dB.

The recording trims the synth's leading latency. A filtered kick lined up 12.9 ms late with no tune, and 5.7 ms late at +15 st. Both are now within one sample (0.02 ms).

The recording is dry. Reverb and chorus come from the row at playback, the same as an unfiltered row. Low cut 20 Hz with reverb 0 used to ring 545 ms to −40 dB against 165 ms unfiltered. Both are now about 180 ms.

A hot kick is no longer turned down to stay inside 16-bit. Peaks that would not fit are scaled, and the SoundFont gain puts the level back. A low cut at 20 Hz was 2.4–3.0 dB quiet. At velocities 80, 100, and 127 it is now +0.18 dB against the unfiltered kick. A note row was 1.5–3.7 dB quiet, with a wobbling loop and the release cut at note-off. The same saw is now +0.24 dB, the loop wobble matches the unfiltered note (0.27 dB), and the release runs past 300 ms (about 970 ms against about 830 ms unfiltered).

A sample low cut that overshoots 16-bit is scaled the same way. A near-full-scale file at low cut 40 Hz / 24 dB peaked at +2.3 dBFS with 331 flat-topped samples. The stored PCM no longer flat-tops, and the 1 kHz level is unchanged.

MIDI export and `--samples-sf2` still leave low cut and presence out. `hits render` of a MIDI file, `hits render --samples-sf2`, and `hits info` say so on stderr and name the rows. The export dialog does too. A WAV render does not print that warning. A sample gain outside −24 to +24 dB is clamped, named on stderr, and the inspector readout shows the clamp. `hits info` also says that +8 dB is the default when a sample row does not set gain. `hits render` and Export WAV report when the master clips or the limiter reduces it by more than 1 dB. A relative sample path resolves against the beat file even when that path is a bare name.

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
