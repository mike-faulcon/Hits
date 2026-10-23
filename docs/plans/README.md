# Plans

Notes for work that is not in the app yet. Michael Faulcon decides when a plan starts. Until then it stays here and the code stays as it is.

| Plan | Status |
| --- | --- |
| [Sample tracks](sample-tracks.md) | One-shots shipped. Still deferred: one sample per row across patterns, stereo, loops, embedding the SoundFont in the MIDI file, and external-output playback of the WAV. |
| [Live input](live-input.md) | On hold until Michael is ready. Computer-keyboard step entry, live recording, and MIDI controller input. |
| Cutoff ramp | Not in the app. Per-slot cutoff already filters one pattern and leaves the next open. A linear `cutoffTo` across a pattern is still deferred. |
| CC 75 decay | Not sent. On the built-in synth it collapses an open hat below the neutral value and does not move a kick, tom, saw, or xylophone. Decay is CC 72. |
| Low-cut velocity curve | A filtered drum or note is recorded at velocity 100 and replayed from the sample bank. Other velocities follow the SoundFont curve, which is not the General MIDI drum curve. A GM kick at low cut 20 Hz now matches the unfiltered kick within 0.5 dB at velocities 80, 100, and 127. Other drums and notes are not checked that way. |
| Per-step low cut | Low cut and presence are per row, and can differ between patterns on that row. A step does not have its own corner. |
| Held-note low cut | Shipped for level, loop, and release. A filtered note loops a short crossfaded slice of the steady sustain and keeps the recorded tail after note-off, about as long as the unfiltered note. It is still not the live oscillator. |
| Sample gain migration | Not implemented. Version 2 cannot tell a pre-#23 sample beat apart from a current one: an omitted `gain` used to mean 0 dB and now means +8 dB. A future version 3 would mean omitted gain is +8. Loading a version 2 file that has sample rows and omits `gain` would write explicit `"gain": 0` so those beats keep the quieter level. Do not change beats that already store `gain`, and do not change version 2 files that have no sample rows. Do not change the bytes of a beat that uses none of the newer fields. |
