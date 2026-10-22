# Plans

Notes for work that is not in the app yet. Michael Faulcon decides when a plan starts. Until then it stays here and the code stays as it is.

| Plan | Status |
| --- | --- |
| [Sample tracks](sample-tracks.md) | One-shots shipped. Still deferred: one sample per row across patterns, stereo, loops, embedding the SoundFont in the MIDI file, and external-output playback of the WAV. |
| [Live input](live-input.md) | On hold until Michael is ready. Computer-keyboard step entry, live recording, and MIDI controller input. |
| Cutoff ramp | Not in the app. Per-slot cutoff already filters one pattern and leaves the next open. A linear `cutoffTo` across a pattern is still deferred. |
| CC 75 decay | Not sent. On the built-in synth it collapses an open hat below the neutral value and does not move a kick, tom, saw, or xylophone. Decay is CC 72. |
| Low-cut velocity curve | A filtered drum or note is recorded at velocity 100 and replayed from the sample bank. Other velocities follow the SoundFont curve, which is not the General MIDI drum curve. Velocity 100 matches. |
| Per-step low cut | Low cut and presence are per row, and can differ between patterns on that row. A step does not have its own corner. |
| Held-note low cut | A filtered note that sustains loops a recording of the built-in sound, with a short crossfade and about a 300 ms release. It is not the live oscillator. |
