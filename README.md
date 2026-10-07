# Hits

[![CI](https://github.com/mike-faulcon/Hits/actions/workflows/ci.yml/badge.svg)](https://github.com/mike-faulcon/Hits/actions/workflows/ci.yml)

A step sequencer for sketching drum and note patterns. The desktop window follows the studio-desk layout in [docs/mocks/NOTES.md](docs/mocks/NOTES.md).

```shell
./gradlew run
```

Open a pattern from the beats folder. Until you choose another one, that folder is `beats` next to the directory you started in. **Folder…** stores the absolute path, so it stays put when Hits is started from somewhere else. Older `.btf` files still load. Save writes a versioned `.json` file beside them and leaves the original `.btf` in place.

## Playing

- **Space** plays and stops. That stays true when a button, slider, or the beat list has focus. Space still types a character while the cursor is in a text field (the beat name, a track name, or the BPM box).
- Click a step to toggle it. **Shift-click** sets a loud hit, using the **Loud hit** slider. **Right-click** or **Alt-click** selects a step so **Volume** edits that hit without turning it off. A bright pad is at least as loud as the track's Loud hit value. With a step selected, **Step pitch** sets that hit's note. **Use track** clears it. A white mark on a pad means that step has its own pitch.
- **⌘Z / Ctrl-Z** undoes. **⌘⇧Z / Ctrl-Shift-Z** redoes.
- **⌘S / Ctrl-S** saves. **⌘⇧S / Ctrl-Shift-S** is Save As.

Until you dismiss the hint, Hits opens on the Boom bap pattern, not playing. Undo once to clear it back to an empty kit. After you dismiss the hint, later launches start from an empty kit.

## Saving

The name in the title is the name stored in the JSON file, including spaces and punctuation. The file on disk uses only letters, numbers, `_`, and `-`. Hover the name to see that file name (`Album Track – v3` saves as `AlbumTrackv3.json`). Hits also shows the file name before writing when it differs from the display name and you have not already saved to that file.

Save asks before replacing an existing file that is not the one you have open. A dot in the title (`untitled •`) means there are unsaved changes. Closing the window, loading another beat, or choosing New offers **Save**, **Don't Save**, and **Cancel**.

`.btf` files are never overwritten. Loading one and saving writes a `.json` file.

## Export MIDI

Export writes a Standard MIDI file of 16th notes. Swing is part of the note times. **50% swing is straight**; higher values delay every other 16th. Playback still follows mute and solo. The file does not, unless you ask it to.

- **All tracks** is the default. Mute and solo are ignored, so a part you muted while listening is still in the file.
- **As heard** leaves muted tracks out and respects solo, judged separately for each pattern.
- **A then B** writes pattern A followed by pattern B. Each row keeps one MIDI track. A row whose name differs is labeled `Kick / Rim`. If the instrument changes between A and B, the file carries a program change where B starts.
- **Repeats** writes that material again, from 1 to 32 times. With A then B, the pair repeats (A B A B).

The suggested `.mid` name uses the same file-safe spelling as Save. A step with its own pitch is written at that note.

## MIDI out

**MIDI out** is at the bottom of the window. **Built-in synth** is the Java instrument and the fallback. Choose another output to send notes to Ableton, an IAC bus, or a hardware port. Hits remembers the choice. If that device is not connected the next time you open Hits, playback uses the built-in synth and the saved choice is kept until the device is back.

## SoundFont

**SoundFont…** loads a `.sf2` file onto the built-in synth. The JDK reads the file; Hits does not add a SoundFont library. The path is remembered. **Built-in sounds** restores the Java General MIDI set.

A SoundFont does not change an external output. Ableton, IAC, and hardware play their own instruments. `.sf3` and `.sfz` are not read. The built-in synth needs a working audio device; if it cannot open, pick an external MIDI output instead.

## Files

Saved beats are still JSON. A file with `"version": 1` opens as before. A step can store its own pitch. The file becomes `"version": 2` when any step has one, and the new field is `pitch` on that step. A step without `pitch` uses the track pitch. Saving a beat that does not use step pitch still writes version 1, so a previous version of Hits can open it. Version 2 is the same document plus that optional field. Extra fields Hits does not know are ignored. Older `.btf` files still load.

## Controls

| Control | What it does |
| --- | --- |
| Length | How long each note holds, as a percent of the step. This used to be labeled Gate. |
| Volume | How hard a hit is. Edits the selected step when that step is on. Otherwise it is the volume of new hits. |
| Loud hit | Volume used by shift-click. Bright pads are at least this loud. This used to be labeled Accent. |
| Swing | 50% is straight. The readout says `50% straight`. |
| − / + | Move the track note by one semitone. |
| Oct − / Oct + | Move the track note by one octave. These used to say −8 / +8. |
| Step pitch | Pitch of the selected step, when that step is on. Use track clears it. A step without its own pitch uses the track pitch. |
| Drum / Note | Drum kit sounds, or one pitched instrument for the whole track. |
| A / B | Two patterns in one beat. Copy replaces the other pattern. |
| 16 steps / 32 steps | One bar, or two. Copy bar duplicates the first bar into the second. |
| Boom bap / Reggae | Replace the pattern on screen, the tempo, and the swing, after a confirmation. The other pattern is kept. |
