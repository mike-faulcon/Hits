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

## Song

**Pattern** loops the A or B pattern on screen. **Song** loops the chain. The chain is a short list, not a timeline. **+A** and **+B** add an entry, **Remove** deletes the selected one, and **Up** / **Down** reorder it. **×** is how many times that entry plays, from 1 to 32. The chain holds 32 entries. When they no longer fit, the entries scroll sideways and the chain buttons stay on the row. Build something like A, A, B, A, and give any entry its own repeat count.

While a song plays, the grid shows the pattern you hear and the playhead sits on that grid. Click a step to edit the pattern on screen. The song keeps its place and picks up the edit. A slider stays on the pattern you started dragging, even if the song moves on, and follows again when you let go.

Click **A** or **B** while a song plays to select that pattern and hold the grid on it. Click a chain entry to hold the grid on that entry's pattern. The playhead shows when the held pattern is the one sounding. Click **Song** again to follow the chain. Stop returns the grid to the selected pattern.

Every entry uses the beat's tempo, swing, and length. Song mode itself is not saved. The chain is. An empty chain still plays the pattern on screen.

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
- **Song** writes the chain, including each entry's repeats. A then B and Repeats are not used for that export. All tracks or As heard still applies, judged separately for each entry. A row that plays under both A and B keeps one MIDI track, with the same `Kick / Rim` label and program change as A then B.

The suggested `.mid` name uses the same file-safe spelling as Save. A step with its own pitch is written at that note.

## Export WAV

**Export WAV** sits next to Export MIDI and writes a 44.1 kHz, 16-bit stereo file. It uses the same choices: all tracks or as heard, A then B, repeats from 1 to 32, and Song. The notes are the ones Export MIDI would write for those choices, including swing, a step's own pitch, and the chain when Song is selected.

Hits renders the file through the built-in Java synth, without playing it out loud, and usually faster than real time. The file keeps two seconds after the loop ends so a note and its reverb can ring instead of stopping dead. A longer export shows a progress bar. **Cancel**, or closing that window, leaves any file already at that name in place. If rendering fails, Hits shows the reason.

A SoundFont loaded on the built-in synth is the sound in the file. Otherwise the file uses the Java General MIDI set. WAV does not record an external MIDI output. If MIDI out is a hardware port or another application, switch to **Built-in synth** and load the SoundFont before exporting when you want that bank in the file.

The suggested `.wav` name uses the same file-safe spelling as Save.

## MIDI out

**MIDI out** is at the bottom of the window. **Built-in synth** is the Java instrument and the fallback. Choose another output to send notes to Ableton, an IAC bus, or a hardware port. Hits remembers the choice. If that device is not connected the next time you open Hits, playback uses the built-in synth and the saved choice is kept until the device is back.

## SoundFont

**SoundFont…** loads a `.sf2` file onto the built-in synth. The JDK reads the file; Hits does not add a SoundFont library. The path is remembered. **Built-in sounds** restores the Java General MIDI set.

A SoundFont does not change an external output. Ableton, IAC, and hardware play their own instruments. `.sf3` and `.sfz` are not read. The built-in synth needs a working audio device; if it cannot open, pick an external MIDI output instead.

## Files

Saved beats are still JSON. A file with `"version": 1` opens as before. Version 2 is that document plus optional fields. A step can store its own pitch (`pitch`). A beat can store a song chain (`chain`): a list of entries such as `{ "slot": "a", "repeats": 2 }`. The file is version 2 when any step has its own pitch or the chain is not empty. A step without `pitch` uses the track pitch. Saving a beat that uses neither still writes version 1, so a previous version of Hits can open it. Extra fields Hits does not know are ignored. Older `.btf` files still load.

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
| A / B | Two patterns in one beat. Copy replaces the other pattern. During a song, click one to hold the grid there. |
| Pattern / Song | Pattern loops the pattern on screen. Song loops the chain. Click Song again to follow the pattern you hear. |
| +A / +B | Add that pattern to the chain. |
| Remove / Up / Down | Edit the selected chain entry. |
| × | How many times the selected chain entry plays, from 1 to 32. |
| 16 steps / 32 steps | One bar, or two. Copy bar duplicates the first bar into the second. |
| Boom bap / Reggae | Replace the pattern on screen, the tempo, and the swing, after a confirmation. The other pattern is kept. |
