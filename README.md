# Hits

[![CI](https://github.com/mike-faulcon/Hits/actions/workflows/ci.yml/badge.svg)](https://github.com/mike-faulcon/Hits/actions/workflows/ci.yml)

A step sequencer for sketching drum and note patterns. The desktop window follows the studio-desk layout in [docs/mocks/NOTES.md](docs/mocks/NOTES.md).

```shell
./gradlew run
```

You can also install a double-clickable app that includes a Java runtime. See [Install](#install).

## Command line

`render` and `info` run with no display. They do not open the window. The same `HitsApp` entry point still opens the window when it is given no arguments, including the packaged app. With no display, no arguments prints the usage text and exits with code 2.

```shell
./gradlew installDist
build/install/hits/bin/hits info beats/somefile.json
build/install/hits/bin/hits render beats/somefile.json --out out.wav
build/install/hits/bin/hits render beats/somefile.json --song --out out.mid
build/install/hits/bin/hits render beats/somefile.json --out out.mid --samples-sf2
```

`./gradlew run --args="info beats/somefile.json"` does the same. Gradle would treat `--out` as its own flag, so pass the whole command in `--args`. On Windows the start script is `build\install\hits\bin\hits.bat`.

`render` writes the active pattern once. `--song` writes the chain, including every pattern A–H that the chain names. The extension picks the file: `.wav`, `.mid`, or `.midi`. Mute and solo match what playback would do. WAV is the built-in General MIDI synth plus that beat's sample bank, offline, the same renderer as Export WAV with no user SoundFont loaded. A row's low cut, presence, and EQ are in that WAV. A MIDI file keeps the ordinary note and does not carry them. `hits render` to `.mid` or `.midi`, and `hits render --samples-sf2`, say so on stderr and name the rows. A WAV render does not print that warning. If the master clips, or the limiter pulls it down by more than 1 dB, the WAV render says so on stderr and still exits 0. `--samples-sf2` writes `<name>-samples.sf2` next to a MIDI file. A beat with no samples still succeeds and says so. A relative sample path is resolved against the beat file's directory, including when the beat path itself is a bare name such as `beat.json` run from that directory. A sample gain outside −24 to +24 dB is clamped, and a gain that is not a 0.1 dB step is rounded. Presence above +12 dB, and an EQ gain outside −18 to +12 dB, are clamped the same way. `hits render` and `hits info` say which row and what was stored. The start script and `./gradlew run` pass `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` and `--add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED`. The export is what the renderer needs. The open is what lets a render read the limiter.

`info` prints the tempo, swing, the patterns in use, each pattern's length in steps, the chain, the track list, the length of one pattern and of the song in bars and seconds, and a reminder that a sample row with no `gain` plays at +8 dB. When the beat uses low cut, presence, or EQ, stderr names those rows and says a MIDI file and a companion SoundFont will not carry them. For the test fixture:

```text
Sparse groove
tempo: 120 bpm
swing: 58%
patterns: A, D
lengths: A 16, D 16
chain: A x1, D x1
tracks:
  A  Kick             drum   C1  Kick
  A  Snare            drum   D1  Snare
  A  Closed Hat       drum  F#1  Hat C
  A  Open Hat         drum  A#1  Hat O
  A  Clap             drum  D#1  Clap, muted
  A  Tom              drum   A1  Tom
  A  Bass             note   E1  Syn Bass 1
  A  Ride             drum  D#2  Ride
  D  Kick             drum   C1  Kick
  D  Snare            drum   D1  Snare
  D  Closed Hat       drum  F#1  Hat C
  D  Open Hat         drum  A#1  Hat O
  D  Clap             drum  D#1  Clap
  D  Tom              drum   A1  Tom
  D  Bass             note   G1  Syn Bass 1
  D  Ride             drum  D#2  Ride
length: 1 bar, 2.000 s
song: 2 bars, 4.000 s
sample gain: +8 dB is the default when a sample row does not set gain
```

Exit codes are `0` when the command worked, `1` when the beat path is missing, the JSON is invalid, or the file could not be written, and `2` when the command, the `--out` path, or the extension is wrong. `hits shot` is not a command: a picture of the grid needs the window.

A neutral one-bar fixture used by the tests and by CI lives at `src/test/resources/fixtures/sparse-808-groove.json`.

Open a pattern from the beats folder. Until you choose another one, `./gradlew run` uses `beats` next to the directory you started in. A packaged app uses `Hits/beats` in your home directory (`~/Hits/beats` on macOS and Linux, `%USERPROFILE%\Hits\beats` on Windows) and copies the sample patterns there when that folder is empty. **Folder…** stores the absolute path, so it stays put when Hits is started from somewhere else. The first launch stores that path. Older `.btf` files still load. Save writes a versioned `.json` file beside them and leaves the original `.btf` in place.

## Playing

- **Space** plays and stops. That stays true when a button, slider, or the beat list has focus. Space still types a character while the cursor is in a text field (the beat name, a track name, or the BPM box).
- **T** sets the tempo from your taps, unless you are typing. Hits averages the last few gaps, starts over when you pause for more than two seconds, and keeps the tempo between 40 and 240. **Tap** does the same. Each change can be undone.
- **Click** is a metronome while the pattern or song plays. It clicks on each beat and is louder on beat 1 of each bar. The click is a short square-wave note on its own channel (higher on beat 1), not a drum-kit hit. On the JDK's built-in bank the claves and wood-block keys are the same sample, so a drum click could not be told apart from a wood-block track. The clicks are not written into a MIDI or WAV export. Hits remembers whether Click is on. When a step can miss, playback rolls chance across several passes, and the click is on every pass.
- Click a step to toggle it. **Shift-click** sets a loud hit, using the **Loud hit** slider. **Right-click** or **Alt-click** selects a step so **Volume** edits that hit without turning it off. A bright pad is at least as loud as the track's Loud hit value. Drag a pad up or down to set that step's velocity. The pad fills from the bottom to show it, and the **Velocity** lane under the grid does the same for the selected track. A new hit uses the track's normal or loud-hit velocity, so an older beat sounds the same. **Chance** is the percent chance the selected step plays. 100% always plays, which is the default. Each pass through the pattern, or through the song, rolls chance again. Hits prepares a long run of those rolls when playback starts; when the run ends, the same series repeats. With a step selected, **Step pitch** sets that hit's note. **Use track** clears it. A white mark on a pad means that step has its own pitch. A number on a pad is its chance, when that chance is not 100%.
- **M** mutes that track. **S** solos it. While any solo is on, only soloed tracks play. A muted track stays silent, including when it is also soloed. The same buttons are on the **Mixer**.
- **Mixer** shows every track's level and pan, plus **Master**. Level **100** leaves each hit as programmed. **0** is silent. Pan **C** is center. Playback uses them. Drums at center, on the standard kit and with no bank or pitched preset, share the General MIDI drum channel. A drum that is panned, a drum on another kit, a bank, a pitched preset, or a Note row gets its own MIDI channel, and that channel stays the same in every pattern of a song or export. **All patterns** starts on. A level or pan change is written to every pattern whose row has the same name and sound. A row that is a different instrument, such as a lead in one pattern and a hat in another, keeps its own level and pan. Turn All patterns off to edit only the pattern on screen. Mute and solo always stay on the pattern on screen. The choice is not stored in the file. Each pattern still keeps its own level and pan.
- **⌘Z / Ctrl-Z** undoes. **⌘⇧Z / Ctrl-Shift-Z** redoes.
- **⌘S / Ctrl-S** saves. **⌘⇧S / Ctrl-Shift-S** is Save As.

Until you dismiss the hint, Hits opens on the Boom bap pattern, not playing. Undo once to clear it back to an empty kit. After you dismiss the hint, later launches start from an empty kit.

## Make me a beat

**Make me a beat** writes a starter groove on the pattern on screen. Pick boom bap, trap, house, rock, or reggaeton, or **Surprise me**. The kick and snare stay on the beats that make that style, and the hats change a little each time. Tempo and swing follow the style and change for the whole beat, not only the pattern on screen. The style picker says so. A 32-step pattern keeps those beats in the second bar. Sounds, names, chance, and the other patterns' hits stay. The new hits play every time.

If that pattern already has hits, the style picker says so. Cancel leaves it alone. **Shuffle**, on the track inspector, writes a new rhythm on the selected track only. A snare that you moved to another row still shuffles as a snare. Both buttons are one undo.

## Song

**Pattern** loops the pattern on screen. A beat has eight patterns, A through H. **Song** loops the chain. The chain is a short list, not a timeline. Pick a letter and **+** adds that pattern, **Remove** deletes the selected entry, and **Up** / **Down** reorder it. **×** is how many times that entry plays, from 1 to 32. The chain holds 32 entries. When they no longer fit, the entries scroll sideways and the chain buttons stay on the row. Build something like A, A, C, A, and give any entry its own repeat count.

While a song plays, the grid shows the pattern you hear and the playhead sits on that grid. Click a step to edit the pattern on screen. The song keeps its place and picks up the edit. A slider stays on the pattern you started dragging, even if the song moves on, and follows again when you let go.

Choose a pattern while a song plays to select it and hold the grid on it. Click a chain entry to hold the grid on that entry's pattern. The playhead shows when the held pattern is the one sounding. Click **Song** again to follow the chain. Stop returns the grid to the selected pattern.

**Copy** replaces the pattern chosen next to it with the pattern on screen. The other patterns stay.

Every entry uses the beat's tempo and swing. Each pattern has its own length, from 1 to 32 steps, so a 16-step stop can sit between 32-step grooves. The chain button shows that length in bars (`C ·1` is one bar, `C ·2` is two). A length that is not a whole number of bars shows the step count (`C ·12`). A held note runs into the next pattern using that pattern's length, and a nudge early on the first step lands in the pattern before it, using that step's own instrument and pan. Looping a single pattern stops a held note at that pattern's end. A nudge that falls past the end of that loop starts again at the beginning. Song mode itself is not saved. The chain is. An empty chain still plays the pattern on screen.

## Saving

The name in the title is the name stored in the JSON file, including spaces and punctuation. The file on disk uses only letters, numbers, `_`, and `-`. Hover the name to see that file name (`Album Track – v3` saves as `AlbumTrackv3.json`). Hits also shows the file name before writing when it differs from the display name and you have not already saved to that file.

Save asks before replacing an existing file that is not the one you have open. A dot in the title (`untitled •`) means there are unsaved changes. Closing the window, loading another beat, or choosing New offers **Save**, **Don't Save**, and **Cancel**.

`.btf` files are never overwritten. Loading one and saving writes a `.json` file.

## Export MIDI

Export writes a Standard MIDI file of 16th notes. Swing is part of the note times. **50% swing is straight**; higher values delay every other 16th. Playback follows mute, solo, pan, and the mixer levels. The file does too, unless you ask it to ignore mute and solo. Pan and levels stay in the file either way.

- The default matches playback. Muted tracks are left out, and solo is respected, judged separately for each pattern.
- **Ignore mute and solo** writes every track, so a part you muted while listening is still in the file.
- **A then B** writes pattern A followed by pattern B. Each pattern keeps its own length. Each row keeps one MIDI track. A row whose name differs is labeled `Kick / Rim`. If the instrument changes between A and B, the file carries a program change where B starts.
- **Repeats** writes that material again, from 1 to 32 times. With A then B, the pair repeats (A B A B).
- **Song** writes the chain, including each entry's repeats. An entry can name any pattern from A through H, and each pattern keeps its own length, so the file is the sum of those lengths. A held note can cross from one pattern into the next. A then B and Repeats are not used for that export. Mute and solo still apply unless you ignore them, judged separately for each entry. A row that plays in more than one pattern keeps one MIDI track. Differing names are joined in pattern order, such as `Kick / Rim`. If the instrument changes, the file carries a program change where the new pattern starts. Pan and levels stay in the file.

The suggested `.mid` name uses the same file-safe spelling as Save. A step with its own pitch is written at that note. The metronome is playback only and is not in the file.

**Companion samples (.sf2)** writes a SoundFont of the beat's sample tracks next to the MIDI file, named `<file>-samples.sf2`. The MIDI file selects that bank (CC 0 = 64, CC 32 = 0) and the row's program. A sample that cannot be read is written as the row's General MIDI note instead. Drag MIDI includes the companion until that box is unchecked. The companion is the unfiltered sample. Low cut, presence, and EQ are not in the MIDI file: the file writes the ordinary note. The export dialog says so and names the rows, such as `A Kick, A Bass`. Playback and WAV export are where those controls are heard.

A step's chance is rolled with a fixed seed. The seed is not stored in the beat. Exporting the same beat with the same options — all tracks or as heard, A then B, repeats, or Song — writes the same notes every time. Each repeat and each chain entry is rolled on its own. 100% always plays and 0% never does. Playback does not use that seed, so a performance can differ from the file and from the next time you press play.

## Export WAV

**Export WAV** sits next to Export MIDI and writes a 44.1 kHz, 16-bit stereo file. It uses the same choices: mute and solo unless you ignore them, A then B, repeats from 1 to 32, and Song. Song can include any pattern from A through H. The notes are the ones Export MIDI would write for those choices, including swing, a step's own pitch, pan, mixer levels, the chain when Song is selected, and the same fixed chance seed. A WAV export of a beat is reproducible in the same way as the MIDI file.

Hits renders the file through the built-in Java synth, without playing it out loud, and usually faster than real time. The file keeps two seconds after the loop ends so a note and its reverb can ring instead of stopping dead. A longer export shows a progress bar. **Cancel**, or closing that window, leaves any file already at that name in place. If rendering fails, Hits shows the reason. If the master clips, or the limiter pulls it down by more than 1 dB, the status line says so and a warning dialog names the reduction. The file is still written.

A SoundFont loaded on the built-in synth is the sound in the file, and sample tracks are rendered from the same in-memory bank playback uses. Otherwise the file uses the Java General MIDI set plus those samples. Low cut, presence, and EQ are baked into that bank, so the WAV matches playback. WAV does not record an external MIDI output. If MIDI out is a hardware port or another application, switch to **Built-in synth** and load the SoundFont before exporting when you want that bank in the file. Those controls are greyed out on an external output, because that port never hears the bank.

The suggested `.wav` name uses the same file-safe spelling as Save.

## Drag MIDI and Drag WAV

**Drag MIDI** and **Drag WAV** sit above the load and save buttons. Drag a chip into a DAW or a file manager to drop a copy of the current pattern. With **Song** on and a chain in the beat, the drop is the song instead. An empty chain drags the active pattern, which is also what playback does.

The file is written to a temporary folder first, using the same notes as Export. Until you change them, a drag matches playback: muted tracks are left out, solo is respected, and the active pattern is written once. **Ignore mute and solo**, **A then B**, and **Repeats** follow the last export. The export dialog opens with those choices, and **Song** starts checked when Song mode is on and the chain is not empty. A drag in Song mode writes the chain and does not use A then B or Repeats. Mute and solo apply unless the last export ignored them.

The file name uses the same spelling as Save, plus a short qualifier: `AlbumTrackv3-A-as-heard.mid`, `AlbumTrackv3-B-as-heard.wav`, `AlbumTrackv3-song-as-heard.mid`, `AlbumTrackv3-A-then-B-x4-as-heard.mid`, `AlbumTrackv3-song.mid` when mute and solo are ignored. Dragging the same pattern again reuses that file. After the beat or the export choices change, the next file is `AlbumTrackv3-A-as-heard-2.mid`, so a DAW that is still reading the first one is left alone.

MIDI is written when the drag starts. WAV uses the same progress window as Export WAV, because the file has to exist before the drag can offer it. **Cancel** leaves nothing on the pointer. The progress window takes the mouse, so a move made while it is up is not part of the drag. When it closes, drag the chip again if the file did not land. Hits keeps that rendered file until the beat or the export choices change, and the second drag does not render again.

Hits deletes the temporary folder when the window closes. Drop the file where you want to keep it. A program that only remembers the path will not find it after Hits exits. The beat you have open is not moved or changed.

The drag is a copy. Swing offers the temp file as a file list (`DataFlavor.javaFileListFlavor`). It is not a promise of a file that appears later, which is why WAV finishes rendering first.

- **macOS.** Finder accepts the drop. Some DAWs accept a WAV or MIDI file dropped on the arrangement, and some only accept a drag that started in Finder. If the drop does nothing, drag the chip onto the Desktop and then into the DAW.
- **Windows.** File Explorer accepts the drop. Most DAWs accept a WAV dropped on the arrangement. MIDI depends on the DAW. If another program still has the file open when Hits exits, Windows may leave it in the temp folder until that program lets go.
- **Linux.** On X11, file managers that understand a URI list usually accept the drop, because the JDK presents the file list that way. A Wayland window often does not see a drag that started in Java, since Swing still speaks X11. Drop the chip on a folder that does see it, or use Export MIDI / Export WAV.

A dragged WAV is the built-in synth, including a loaded SoundFont and the beat's samples. It is not the external MIDI output. Same as Export WAV. A dragged MIDI file can include the companion sample SoundFont, until Companion samples is unchecked.

## MIDI out

**MIDI out** is at the bottom of the window. **Built-in synth** is the Java instrument and the fallback. Choose another output to send notes to Ableton, an IAC bus, or a hardware port. Hits remembers the choice. If that device is not connected the next time you open Hits, playback uses the built-in synth and the saved choice is kept until the device is back.

## SoundFont

**SoundFont…** loads a `.sf2` file onto the built-in synth. The JDK reads the file; Hits does not add a SoundFont library. The path is remembered. **Built-in sounds** restores the Java General MIDI set.

A SoundFont does not change an external output. Ableton, IAC, and hardware play their own instruments. `.sf3` and `.sfz` are not read. The built-in synth needs a working audio device; if it cannot open, pick an external MIDI output instead.

A sample track can sit on the synth at the same time as that SoundFont. The sample bank is separate. **Built-in sounds** unloads the SoundFont and leaves the samples.

## Samples

**Sample** is a third track mode, next to Drum and Note. Choose a WAV from the picker, or drop a `.wav` on the row. **808**, **Click**, and **Snap** are one-shots generated in Hits, not downloaded recordings. Root is the MIDI note that plays the file at its own speed. Start skips that many sample frames. **One-shot** plays the whole file. **Choke** lets the step length cut it. Tune, decay, cutoff, resonance, reverb, and chorus still apply, because the sample plays through the built-in synth. The tone on a sample runs in this order: invert, low cut, EQ bands, high cut, presence, then gain compensation. The preset's gain still applies when it plays. A drum or note row with any of those controls is recorded dry from the built-in sound, with the synth's leading silence trimmed, and played back from the same bank, so live playback and WAV export match. The row's own reverb and chorus are applied then, not baked into the recording. A filtered pattern and a plain pattern on the same row do not share a channel, so the plain drum stays a plain drum. The bank rebuilds when the slider settles, not on every tick.

Each pattern can name its own sample on the same row. Every distinct sample, including the same file at a different decay, gain, polarity, low cut, or EQ, is its own preset in bank 64 (CC 0 = 64, CC 32 = 0). Playback and WAV send that program. MIDI sends the preset too, but the companion SoundFont leaves low cut, presence, and EQ out of the PCM. The first sample on a row keeps program = row.

**Gain** is decibels in the SoundFont, from −24 to +24, in 0.1 dB steps. The slider stops at those ends. The default is +8 dB, and that value is left out of the file. A whole number is written without a decimal, so an older beat that stores `8` still saves `8`. A stored gain outside that range is clamped, a value that is not on a 0.1 dB step is rounded, the inspector readout turns amber and says so, and `hits info` names the row. The built-in synth turns a note down (velocity, channel volume, expression, and center pan), so a sample with no boost renders well below the file. +8 dB puts a default hit near the file peak and brings the built-in Click up to the woodblock click. Sample rows are new, so this default is louder than the first sample-track release. A beat that stores `"gain": 0` keeps that older level. Drum and note rows do not use this gain. A low cut or an EQ boost that would push the PCM past 16-bit is scaled to fit, and the SoundFont gain puts that level back, so the file does not flat-top and playback stays at the same level.

**Invert** flips the sample's phase. Off is left out of the file. It is there so a sine 808 layered on the kit kick does not cancel the low end.

**Decay** on a sample row is that preset's volume envelope, not CC 72. CC 72 cannot move a one-shot, and on a choke it cannot shorten the short release. Below 100% the hit fades to silence in that fraction of the file. A choke at 100% still stops when the step ends, and above 100% lengthens that release the way CC 72 used to. A one-shot at 100% already plays the whole file, so 101–200% sounds the same and the readout says `file`.

**Reverb** on a sample row is dry unless the row sets a send. The built-in synth would otherwise leave the channel at 40, and a one-shot rings on after the file. A set send is sent as usual.

A missing file, or a sample that does not fit in the bank, shows a warning and that pattern plays its drum note on channel 9. The melodic channel cannot be switched from the sample bank to the drum bank, so the fallback note is the drum channel itself and it does sound. `hits render` and `hits info` print that warning on stderr and still write the file. `hits info` lists each sample row's path, gain or invert when those differ from the default, and `ready` or `missing`. On an external MIDI output every sample row plays its drum note on channel 9, because the other program does not have the file.

**Collect samples** copies the files the beat uses into `<beat>.samples/` next to the beat. Built-ins are written as WAV files there. Paths in the beat stay relative to the beat file, and Save As rewrites them. A beat that never uses Sample still saves exactly as it did before. A relative sample is resolved against the beat file's directory, including a bare `beat.json` on the command line. WAV export without a beat file resolves a relative sample against the working directory and throws if that file is not there, instead of dropping it.

## Files

Saved beats are still JSON. A file with `"version": 1` opens as before. Version 2 is that document plus optional fields. A step can store its own pitch (`pitch`), a play probability (`probability`, 0–100), a note length in steps (`len`, 2–32), and a timing nudge (`nudge`, −50 to 50, a percent of one step). A beat can store a song chain (`chain`): a list of entries such as `{ "slot": "c", "repeats": 2 }`. Patterns C through H are optional keys in `slots`, written only when that pattern differs from the empty kit, including a difference in pan, level, step velocity, probability, length, nudge, bank, kit, preset, tune, decay, reverb, chorus, cutoff, resonance, or that pattern's own step count. A track can store a mixer `pan` (0–127, 64 is center), a mixer `level` (0–127, 100 leaves the hits as programmed), `legato` (note tracks hold each hit until the next one), `shift` (the same nudge on every step, −50 to 50), `bank` (MSB×128+LSB, 0 is General MIDI), `kit` (a drum kit program, 0 is the standard kit), and `preset` (`click`, `crack`, or `rim`, a pitched percussion sound on a drum row). A track can also store `tune` (cents, −2400 to 2400, omitted at 0), `decay` (percent, 10 to 200, omitted at 100), and `reverb`, `chorus`, `cutoff`, and `resonance` (each 0–127, omitted when unset, so no controller is sent and the sound stays as it is). A track can store `lowCut` (`hz` from 20 to 2000, and `slope` 12 or 24) when the high-pass is on, and `presence` (0 to 12 dB, in 0.1 dB steps, omitted at 0). It can store `eq`: `bands` is a list of up to two peaking bands (`hz` from 30 to 12000, `gain` from −18 to +12 dB, `q` from 0.3 to 8), and `highCut` is a 12 dB/oct low-pass from 1000 to 20000 Hz. A flat band is left out. `eq` is omitted when every band is flat and high cut is off. A whole-number gain is written without a decimal. A value that is not on a 0.1 step is rounded on load, and `hits info` says so. Low cut, presence, and EQ are omitted when off, so a beat that does not use them saves the same bytes as before. A sample track stores `mode` `sample` and a `sample` object: `path` (relative to the beat file, or a built-in id such as `hits:808`), `root` (MIDI note, 60 when omitted), `start` (frames, 0 when omitted), `oneShot` (true plays the whole file), optional `gain` (decibels, −24 to +24 in 0.1 dB steps, omitted at +8, whole numbers written without a decimal), and optional `invert` (omitted when false). The object is written only while the row is in sample mode. A sample saved before `gain` existed has no `gain` field, so it now plays at +8 dB. `"gain": 0` keeps the older level. An older Hits reads an unknown mode as a drum and drops the `sample` object the next time it saves. On a pitched preset, a decay other than 100% replaces the release the drum length was sending, and a reverb send replaces that length's reverb. One controller is sent, so the two do not fight. A beat can store `master` (0–127, 127 is full). Mute and solo are stored on every track, as they always have been. A pattern can store its own `steps` (1–32) on that slot. The key is written only when the length differs from the beat's `steps`, which stays 16 or 32 so an older Hits still sees one length. Notes past a pattern's length stay in the file. The file is version 2 when any of those optional fields is in use. A step without `pitch` uses the track pitch. A step without `probability` plays every time, and 100 is omitted when saving. `len` is omitted at 1, `nudge` and `shift` are omitted at 0, `legato` is omitted when it is off, and `bank`, `kit`, and `preset` are omitted at their defaults. A beat that only uses A and B, and none of the other version 2 fields, is written the same way as before, so a previous version of Hits can still open a version 1 file. Extra fields Hits does not know are ignored. An older Hits drops `len`, `nudge`, `legato`, `shift`, `bank`, `kit`, `preset`, `tune`, `decay`, `reverb`, `chorus`, `cutoff`, `resonance`, `lowCut`, `presence`, `eq`, `sample`, and a pattern's own `steps` the next time it saves. It also reads `mode` `sample` as a drum. Older `.btf` files still load.

## Controls

| Control | What it does |
| --- | --- |
| Length | How long the last step of a hit holds, as a percent of that step. This used to be labeled Gate. A longer Hold uses this only on its last step. |
| Hold | How many steps the selected hit lasts. 1 is a single step. Drag a lit pad sideways to stretch it. Covered steps draw as a bar. |
| Legato | On a note track, each hit holds until the next hit. Drum tracks keep Hold instead. |
| Nudge | Moves the selected hit off the grid, from −50% to +50% of a step, and keeps the note the same length. Comma is earlier, period is later. The pad shows a small tick. Swing cannot push the hit onto the next step. |
| Shift | Moves every hit on the track by the same percent. Added to Nudge, then kept inside half a step. Swing cannot push that total onto the next step. |
| Volume | How hard a hit is, from 1 to 127. Edits the selected step when that step is on. Otherwise it is the volume of new hits. Drag a pad or the velocity lane to set a step directly. |
| Chance | Percent chance the selected step plays. 100% always plays. 0% never plays. Playback rolls each pass. MIDI and WAV export use a fixed seed. |
| Loud hit | Volume used by shift-click. Bright pads are at least this loud. This used to be labeled Accent. |
| Swing | 50% is straight. The readout says `50% straight`. |
| Tap | Set the tempo by tapping. T does this unless you are typing. A pause of more than two seconds starts over. |
| Click | Metronome during playback. Louder and higher on beat 1. A square-wave click, not a drum hit, so it stays distinct from wood blocks. Left out of exports. The choice is remembered. |
| − / + | Move the track note by one semitone. |
| Oct − / Oct + | Move the track note by one octave. These used to say −8 / +8. |
| Step pitch | Pitch of the selected step, when that step is on. Use track clears it. A step without its own pitch uses the track pitch. Tune is part of the readout, so a kick at +15 semitones reads `C1 +15 st`, the same way the note heading does. |
| M / S | Mute this track, or solo it. While any solo is on, only soloed tracks play. A muted track stays silent. |
| Mixer | Level, pan, reverb, and chorus for every track, plus Master. Level 100 is unity. Pan C is center. Reverb and chorus stay off until you turn them on, so the controller is not sent and today's sound is unchanged. All patterns applies level, pan, reverb, and chorus to the same instrument in every pattern. |
| Level | How loud the track is in the mix. This is separate from Volume, which is how hard a hit is. |
| Reverb | Reverb send on the built-in synth. Off leaves the controller unset. On a pitched preset, a send replaces the reverb the length was sending. A sample row sends 0 unless a send is set, because the synth would otherwise leave the channel at 40 and the sample rings on after the file. A DAW may ignore it. |
| Chorus | Chorus send on the built-in synth. Off leaves the controller unset. A DAW may ignore it. |
| Pan | Left, center, or right. A drum off center, another kit, a bank, a pitched preset, or any tune, decay, or effect uses its own MIDI channel. |
| Tune | Pitch of the row, −24 to +24 semitones, with a ±50 cent fine control. The spinners round to the nearest semitone so the cents stay within ±50, and one semitone step adds 100 cents. A tuned drum is bent on its own channel, after the bend range is set to 24 semitones. Built-in synth only. A DAW may ignore it. |
| Decay | How long the built-in synth lets a hit ring, 10% to 200%. 100% is the instrument's own release and sends nothing. A decay other than 100% replaces the release a pitched preset's length was sending. The percent maps onto CC 72, and CC 72 is 7-bit, so one step is as fine as the controller gets. 10% sends 56, 90% sends 63, 100% sends nothing (64 is neutral), 120% sends 77, and 200% sends 127. That mapping is what shipped with the decay retest: a rim at 90% used to send 56 and now sends 63, and a boom at 120% used to send 76 and now sends 77. Near 120% on Syn Bass one of those steps is about 95 ms. There is no migration. The stored percent is unchanged, and older beats sound like this mapping. Kick, snare, closed hat, toms, clap, side stick, Woodblock click, and Snare crack ignore it, and the control is marked ignored. Snare crack's tail is reverb, so Reverb is what shortens that one. Open hat, crash, ride, and rimshot do respond. A sample row maps the percent onto that sample's volume envelope instead of CC 72. Below 100% the hit is shorter. A one-shot above 100% matches the file, and the readout says `file`. A choke above 100% rings past the step. A missing file falls back to a drum note, which may ignore decay, and the control stays available. Built-in synth only. A DAW may ignore it. |
| Cutoff | Low-pass filter. Off leaves it unset, so no controller is sent. The slider covers the range this sound actually uses on the built-in synth: 0–56 for kit drums, 58–86 for the bass programs (32–39), 32–80 for other notes, and 0–64 for a sample row. A sample starts with the filter open, and controllers above 64 do not brighten it. The number stored in the file is still that controller, 0–127. A saved value outside the slider is kept and sent until you move the slider. 64 is the synth's own tone. Built-in synth only. A DAW may ignore it. |
| Resonance | Filter emphasis. Off leaves it unset. Built-in synth only. A DAW may ignore it. |
| Low cut | High-pass on this row. Off by default, and then it is left out of the file. The slider runs from 20 Hz to 2 kHz on a log scale and shows the corner in Hz. Slope is 12 or 24 dB/oct, and 12 is the default. The slope buttons stay available while Low cut is off, so the slope can be chosen first. Playback and WAV bake it into the sound. The recording is dry, and the row's own reverb and chorus are applied when it plays. A filtered pattern and a plain pattern on the same row use different channels, so the plain drum stays a plain drum. MIDI export keeps the ordinary note. The export dialog, `hits info`, and a MIDI `hits render` name the rows. Greyed out, with the stored value in the tooltip, on an external MIDI output. The highlighted slope is the selected accessible state, and a control drawn on is enabled. |
| Presence | High shelf, 0 to +12 dB above about 3 kHz, in 0.1 dB steps. 0 is off and is left out of the file. A whole number is written without a decimal. A stored value above +12 dB is clamped, the readout turns amber, and `hits info` names the row. Same playback, WAV, and MIDI rules as Low cut. |
| EQ | Two peaking bands and a high cut, under Low cut and Presence. Each band is 30 Hz to 12 kHz on a log scale, gain −18 to +12 dB, Q 0.3 to 8. Gain and Q move in 0.1 steps. A flat gain is off and is left out of the file. High cut is a 12 dB/oct low-pass from 1 kHz to 20 kHz, off until the checkbox is on. A small curve draws the combined response. The stages run in this order: invert (samples only), low cut, EQ bands, high cut, presence, then gain compensation. Playback and WAV bake it into the sound, the same way as Low cut, and the bank rebuilds when the slider settles. MIDI export keeps the ordinary note. Greyed out, with the stored shape in the tooltip, on an external MIDI output. |
| Master | Turns every track down together. 127 is full. |
| Sound | Drum keys that are different sounds on the built-in bank, plus Woodblock click, Snare crack, and Rimshot. Those three stay on the drum row and play a pitched instrument, so they do not need a Note track. Note mode lists the loaded soundbank as bank and program. |
| Kit | Drum kit from the loaded soundbank. The built-in bank has Standard only. Another kit uses its own channel. Omitted from the file at 0. |
| Drum / Note / Sample | Drum kit sounds, one pitched instrument, or a one-shot WAV. Sample plays through the built-in synth. A missing file falls back to the drum note. |
| Root / Start | Sample only. Root is the note that plays the file at its own speed. Start skips that many frames. |
| One-shot / Choke | Sample only. One-shot plays the whole file. Choke stops it when the step ends. Decay shortens either one, and lengthens a choke past the step. |
| Gain | Sample only. Decibels added to this sample, −24 to +24, in 0.1 dB steps. +8 dB is the default and is left out of the file. 0 dB is the quieter first sample-track level. A whole number is saved without a decimal. A value off that step is rounded, and `hits info` says so. |
| Invert | Sample only. Flips the phase. Off is left out of the file. |
| Collect samples | Copies every sample this beat uses into a folder next to the beat. |
| Companion samples (.sf2) | MIDI export option. Writes the sample SoundFont beside the `.mid` file. |
| Ignore mute and solo | Export option. Off matches playback. On writes every track. Pan and levels stay either way. |
| Pattern | Pattern on screen, A through H. During a song, choose one to hold the grid there. |
| to / Copy | Copy replaces the chosen pattern with the one on screen. |
| Pattern / Song | Pattern loops the pattern on screen. Song loops the chain. Click Song again to follow the pattern you hear. |
| + | Add the pattern chosen beside it to the chain. Any pattern A through H. |
| Remove / Up / Down | Edit the selected chain entry. |
| × | How many times the selected chain entry plays, from 1 to 32. |
| 16 steps / 32 steps | Sets the pattern on screen to one bar or two. The other patterns keep their lengths. |
| Steps | Length of the pattern on screen, from 1 to 32. Notes past it stay on the grid, dimmed, and do not play. Copy bar duplicates the first bar and sets this pattern to 32 steps. |
| Make me a beat | Starter groove from a style. Asks first when the pattern on screen has hits. Tempo and swing change for the whole beat. The other patterns' hits stay. |
| Shuffle | New rhythm on the selected track. The sound, tempo, and other tracks stay. |
| Boom bap / Reggae | Replace the pattern on screen, the tempo, and the swing, after a confirmation. The other patterns are kept. |
| Drag MIDI | Drag the current pattern, or the song, into a DAW or a folder. |
| Drag WAV | The same notes as a WAV file. A progress window shows while it renders. |

## Install

Prebuilt apps are attached to [GitHub Releases](https://github.com/mike-faulcon/Hits/releases) when a `v*` tag is published. Each one includes a Java runtime, so you do not need a JDK or a terminal. They are not code-signed or notarized. Signing certificates cost money, and this project does not use them.

| System | File | How to open |
| --- | --- | --- |
| macOS | `Hits-<version>.dmg` | Open the disk image and drag Hits to Applications. |
| Windows | `Hits-<version>.msi` | Run the installer. It installs for your user and adds a Start menu shortcut. |
| Linux | `hits_<version>-1_amd64.deb` | Install the package, then launch Hits from the app menu. |

On macOS, Gatekeeper blocks an unsigned app the first time. Right-click Hits (or Control-click it) and choose **Open**, then **Open** in the dialog. After that, a normal double-click works. The dialog is Gatekeeper reacting to an unsigned build.

On Windows, SmartScreen may say "Windows protected your PC". Choose **More info**, then **Run anyway**.

On Linux, a file manager may ask for your password when you open the `.deb`. From a terminal, `sudo apt install ./hits_<version>-1_amd64.deb` does the same thing. The menu entry is named Hits. The `.deb` is built on GitHub's `ubuntu-latest` image, and its dependencies are the libraries that image provides. On another distribution, `./gradlew packageApp -PpackageType=app-image` writes `build/jpackage/dist/Hits/bin/Hits`.

The first launch of the packaged app creates `Hits/beats` in your home directory and copies the sample patterns into it when the folder is empty. **Folder…** can point somewhere else. `./gradlew run` keeps using the working directory until a launch has stored a folder.

## Build a package

JDK 21 is required. `jpackage` and `jlink` come with the JDK.

```shell
./gradlew packageApp
```

The installer is written to `build/jpackage/dist/`. The task builds an app image, checks that WAV export's `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED` flag is in the launcher config, and checks that the sample beats and a `java.desktop` runtime are inside. It then wraps that image for the current operating system.

`jlink` builds the runtime from `java.desktop` and `java.prefs` (and the modules they require). Debug symbols, man pages, header files, and the extra JDK commands are left out, and resources are compressed. The full JDK is not bundled.

| System | Default output | Extra tool |
| --- | --- | --- |
| Linux | `.deb` | `fakeroot` and `dpkg-deb` (`sudo apt install fakeroot`; `dpkg-deb` is in `dpkg`) |
| macOS | `.dmg` | none |
| Windows | `.msi` | [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) (`candle` and `light` on `PATH`). WiX is free. |

If the extra tool is missing, the task writes an app image instead: a `Hits` directory (or `Hits.app` on macOS) with a launcher you can double-click. Pass `-PpackageType=` to choose `app-image`, `deb`, `dmg`, `msi`, or `exe`.

The icon is a step grid drawn by `pack/HitsIcon.java` while the package is built. Linux uses the PNG, Windows the ICO, and macOS the ICNS.

## Changelog

- **EQ.** Each row has two peaking bands (30 Hz–12 kHz, −18 to +12 dB, Q 0.3–8) and a high cut (12 dB/oct, 1–20 kHz, off by default). They sit under Low cut and Presence. Playback and WAV export, including `hits render`, bake them in. MIDI export does not. Flat settings are left out of the file, and a whole-number gain still saves as an integer. Sample gain, presence, and EQ gain move in 0.1 dB steps. A value that has to be clamped or rounded is named by `hits info` and `hits render`. See the changelog.

[`.github/workflows/package.yml`](.github/workflows/package.yml) builds the Linux `.deb`, macOS `.dmg`, and Windows `.msi` on GitHub-hosted `ubuntu-latest`, `macos-latest`, and `windows-latest` runners. It runs when a `v*` tag is pushed and when someone starts it by hand (`workflow_dispatch`). It does not run on pull requests. The packages are workflow artifacts kept for 7 days. A tag also attaches them to a GitHub Release. Nothing in that workflow is signed.
