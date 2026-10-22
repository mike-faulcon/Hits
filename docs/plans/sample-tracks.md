# Sample tracks

**Shipped in this branch.** One-shot WAV tracks, three generated built-ins, and a companion SoundFont on MIDI export. The notes below are what that pass left out.

## Still deferred

- A different sample on the same row in two patterns. The bank has one preset per row. The first pattern in the slot list that can be read wins, and the other pattern plays its General MIDI note.
- Stereo samples. Files are downmixed to mono 16-bit before they go into the bank.
- Looping samples, velocity layers, and round-robin.
- A start offset in milliseconds. Start is a frame count.
- Embedding the SoundFont inside the MIDI file. Export writes a companion `<name>-samples.sf2` instead.
- Playing the WAV on an external MIDI output. That output hears the row's drum note.
- Deleting files in `<beat>.samples/` that the beat no longer names. Collect only copies.
- CC 75. Decay stays on CC 72. See the plans index.

## What the code does

`TrackMode.SAMPLE` and a `sample` object are version 2. A beat that never uses them is still written as version 1. `SampleBank` writes an in-memory SoundFont 2 file and `MidiSystem.getSoundbank` reads it. The bank number is 64 in the file, which the built-in synth maps to MIDI bank 8192 (CC 0 = 64, CC 32 = 0). The program is the row. One-shot is a sample that does not loop, with a release long enough that note-off does not cut it. Choke uses a short release.

Decay stays enabled on a sample row. The voice sets a volume-envelope release and Gervill's CC 72 adds to it, so the control is not marked ignored the way a kick or a closed hat is. A missing file still falls back to the drum note, which may ignore that controller, and the stored percent is kept. Cutoff for a sample row is 0–64. The voice has no filter of its own, so 64 is already open and a higher controller does not brighten it. Kit drums, bass, and other notes keep the ranges measured for those instruments.
