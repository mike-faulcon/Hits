# Live input

**On hold until Michael is ready.** This is a plan only. Do not add the classes, shortcuts, or file fields below until he asks for this feature.

Two ways to put hits on the grid besides the mouse:

1. The computer keyboard, for step entry and for playing along.
2. A MIDI controller, through `javax.sound.midi`, into the same record path.

Playback, export, the song chain, and the mouse grid stay as they are. A beat that never uses a custom input note still saves as version 1, byte for byte with today's writer.

## Goals

- Program steps from the keyboard while the transport is stopped: pick a row, toggle a cell, put a pitch on a note track.
- Arm record and play. Keyboard pads and MIDI notes land on the step grid, using the beat's swing.
- Choose a MIDI input. Remember it. If it is unplugged next launch, keep the saved id and stay disconnected.
- Route a note to a track. Drum tracks match their sounding note, or a learned input note. A pitched track can store a step pitch.
- Compensate for a late controller with a per-machine latency value.
- One record pass is one undo step. Undo, redo, save, and Space behave as they do now.
- Headless tests cover quantize, routing, the undo group, and the JSON. Tests pass with no display and no MIDI device.

## Out of scope

- MIDI clock, start/stop, song position, or sync to another program.
- Velocity curves, aftertouch, knobs, and the mod wheel.
- Chords. A step holds one pitch. A second note on that step replaces the pitch.
- Changing a note's length from how long the key was held. Length stays the track's Length (gate) percent.
- Recording mute, solo, tempo, swing, the chain, or the A/B slot change.
- A new document version. The extra field is optional version 2, same ceiling as step pitch and the chain.
- Partial quantize, triplet grids, or a step size other than the pattern's 16ths.

## What the code does today

Edits go through `Editor`. `edit` pushes one undo snapshot (50 deep) and marks the beat dirty. `beginAdjust` / `adjust` / `endAdjust` group a slider drag into one undo. `Editor.tap` calls `Track.tap` on `viewSlot()`, which is the slot on screen, including a song slot the grid is following or holding.

`Track.tap` matches the mouse. A plain tap toggles the cell and uses the track velocity when turning it on. Shift uses the loud-hit velocity. The grid already treats those the same way: click, Shift-click, and `PatternPanel`.

`SequenceBuilder` is the clock. `PPQ` is 96, so a 16th is `STEP_TICKS` (24) ticks. `tickForStep` places even steps on the pair boundary and delays odd steps by the swing amount. Swing 50 is straight (step 1 at tick 24). Swing 75 puts step 1 at tick 36 and step 3 at tick 84. `stepForTick` is the floor: the latest step whose start is at or before the tick. `Chain.place` folds a song tick into a section, then uses that floor. Each chain section is `loopTicks(stepCount)` long.

`StudioFrame.changed` rebuilds the playing sequence on every musical edit via `Player.update`. That restart is fine for a click. It is too coarse for a stream of recorded hits. `tickPlayhead` runs every 40 ms, and it skips `showPlayingSlot` while `isAdjusting()` is true. A record take must not reuse that flag, or the song grid freezes for the whole pass.

`Player` already takes a `Transmitter`, from the sequencer, and points it at the output `Receiver`. That wire is playback. Input is a different device and a different transmitter. `MidiOutputs` lists devices that can receive. Nothing lists devices that can send. `Gm.DRUM_CHANNEL` is 9 (MIDI channel 10, counted from 1).

`Beat.usesFormatV2()` is true when any step has its own pitch or the chain is not empty. `BeatJson` then writes `"version": 2` and the optional `pitch` and `chain` fields. Any other beat is written as version 1. The reader accepts versions 1 and 2 and ignores keys it does not know. `KeyboardShortcuts` owns Space (play/stop, including when a button has focus) and the shortcut-key undo, redo, save, and save as. A focused text field still receives characters. `AppPreferences` stores the MIDI output id under `midiOutput`.

## UX

Record is a transport mode. It is off every launch, it is not saved, and it is not an undo step. The device, the latency, and the quantize choice are machine preferences. The only thing that enters the beat file is a learned input note that differs from the track's sounding note.

### Transport

`TransportBar`, immediately to the right of Play:

| Control | Behavior |
| --- | --- |
| **Record** | Toggles record. Armed: background `Theme.RECORD` (`0xE15A4A`), ink text, same painting as Play uses `Theme.PLAY`. Disarmed: panel background, normal text. Tooltip: "Record keyboard and MIDI into the grid. Stopped: each hit advances one step. Playing: hits snap to the grid." |
| **Nearest** / **Step** | One button. **Nearest** snaps to the closest swung step. **Step** uses the playhead's floor, the step `stepForTick` already returns. Click swaps. Tooltip: "Nearest snaps to the closest step. Step uses the step the playhead is already on." |

Status line when record turns on: `Record on. Stopped steps advance. Playing snaps to the grid.`

Space still starts and stops. Stopping the transport ends an open take. Disarming record ends it too. Play does not clear the arm, so a second pass can start with Space.

### Computer keyboard

Handled by a `KeyEventDispatcher` next to `SpacePlay`, removed on dispose. The dispatcher leaves the event alone when:

- `KeyboardShortcuts.typing()` is true (beat name, track name, BPM text).
- The focus owner is a `JList`, `JSlider`, `JComboBox`, or `JSpinner`, or a combo popup is open.
- Any modifier other than Shift is down, so shortcut-Z and shortcut-S still reach the existing input map.
- The key is Space. Play/stop stays in `SpacePlay`.

`Widgets.button` components are not focusable, so the transport buttons do not eat these keys. After a click the grid already calls `requestFocusInWindow`. Pressing Record does that too, so arming record leaves the grid ready for keys.

**Pads.** `1`–`8` are tracks 0–7.

**Piano row**, for the selected track. One octave whose C is the C of the track note (`note / 12 * 12`), plus a session octave offset:

| Keys | Semitones from that C |
| --- | --- |
| `A S D F G H J` | 0, 2, 4, 5, 7, 9, 11 (C D E F G A B) |
| `W E T Y U` | 1, 3, 6, 8, 10 (C# D# F# G# A#) |

`[` and `]` move the octave offset by one octave and clamp the sounding note into 0–127. The offset is session state. It is not a preference and not part of the file. `S` alone is D; shortcut-S is still Save because the dispatcher ignores shortcut modifiers.

Shift means a loud hit: `track.accent()`, the same velocity Shift-click writes. A plain key uses `track.velocity()`. The keyboard has no velocity of its own.

**Arrows and Enter** apply only while `PatternPanel` is focused, so the beat list and the sliders keep their arrow keys.

- Left and Right move the selected step inside `stepCount` (16 or 32, not the unused cells past the length). With no step selected, Right selects column 0 and Left selects the last column.
- Up and Down move the track and keep the column. This is a new `Editor.moveSelection`. `selectTrack` from a click in the name gutter still clears the step, as it does now.
- Enter toggles the selected cell through `editor.tap` (plain). Shift+Enter taps with the loud hit. Each press is its own undo.

**Record off, stopped — step entry.** A pad selects that track and toggles the selected column (column 0 if none) through `editor.tap`. A piano key selects nothing extra. On a note track it turns the selected step on and sets that step's pitch when the played note differs from the track note; when it matches, it clears a step pitch so the cell uses the track note. On a drum track a piano key only auditions, unless the played note equals that track's sounding note, in which case it toggles the selected cell. The column does not advance. Arrows move it.

**Record off, playing — audition.** Pads and piano keys call `Player.audition` and do not write. A pad also selects its track. Enter still toggles the selected cell, one undo, the same as a click during playback.

**Record on, stopped — step record.** Writes turn a cell on. They do not turn one off. A pad writes that track at the selected column, then moves the selection one column to the right, wrapping inside `stepCount`. A piano gesture on a note track writes the selected track: every key that goes down before the gesture ends updates that same cell (the last pitch wins), and the column advances when the last key of the gesture comes up. Drum-note piano keys that match the selected drum track advance immediately, like a pad. One take, one undo.

**Record on, playing — live record.** The column is the quantized step, not the selection. Pads write tracks 0–7. Piano keys write the selected note track, with a step pitch when the note differs. The selection moves to the cell that was written so the outline shows it. The column does not advance on its own; the clock does.

### MIDI in

The footer already has **MIDI out** and **SoundFont…** in `OutputBar`. Add, to the left of MIDI out:

| Control | Behavior |
| --- | --- |
| **MIDI in** | A combo. First row is **None** (`MidiInputs.NONE_ID`). Then every external input, labeled like MIDI out (`MidiOutputs.labelFor`). Tooltip: "A keyboard or pad controller. None leaves the grid to the mouse and the computer keys." |
| **Late** | A spinner, 0–250 ms, step 1. Tooltip: "Shift recorded MIDI earlier by this many milliseconds. 0 uses the time the note arrived. Keyboard recording stays on the clock." |

Choosing a port calls the same shape as `OutputBar.midiOutputChosen`: on failure, show the message, leave the previous input connected, and put the combo back. Status on success: `MIDI in: <label>`. An empty choice stores nothing and closes the port.

On launch, resolve the saved id the way `MidiOutputs.resolve` resolves an output, with **None** as the fallback. If a saved id is missing, stay on None, keep the preference, and set the status to `Saved MIDI input is not connected.` Playback does not wait on this.

Window focus refreshes the MIDI out list today (`StudioFrame` skips the refresh while the popup is open). Do the same for MIDI in, and skip the refresh while either popup is open.

**Record off.** A note-on auditions the routed track and selects it. A note-off ends that preview when the last matching note is up; the existing 160 ms cap in `Player.audition` still applies. Nothing is written.

**Record on.** The same routing writes, with the message velocity clamped to 1–127. Velocity 0 is a note-off (`ShortMessage` note-on with velocity 0), never a hit. Stopped transport uses the step-record rules above, so a chord gesture shares one column and advances when the last note-off arrives. Playing transport quantizes.

**Learn.** Phase 4, on `TrackInspector`, under the track note. A readout shows the input note (`Notes.name`), or `Track` when `inputNote` is `-1`. **Learn** waits for one note-on. Status: `Play a note to set the input for <track>.` That note is stored with `editor.edit`, is auditioned, and is not recorded even if Record is armed. A learned note equal to the sounding note clears the field, so the file stays version 1. **Learn** again, or Esc while the grid is focused, cancels the wait. **Use track note** clears the field through `editor.edit`.

### Where a hit is written

`recordHit` writes `editor.viewSlot()`, the slot on screen. That is the rule `tap` already uses.

While a song plays and the grid is following, the slot is the one `Chain.place` is sounding. While the grid is held on A or B, the hit still uses the song clock's step index, and the cell is the one on screen. The playhead stays hidden when the held slot is not the sounding slot, which is already how `tickPlayhead` works. The written cell gets the selection outline so the punch is visible.

### Quantize

Pure function, no device and no sequencer:

`Quantize.choose(tick, stepCount, swing, latencyTicks, nearest) -> int`

1. Subtract `latencyTicks` from `tick` and wrap with `floorMod` into `loopTicks(stepCount)`. A late hit can land on the previous step, including wrapping from step 0 back to the last step.
2. Floor is `SequenceBuilder.stepForTick` of that adjusted tick.
3. **Step** mode returns the floor.
4. **Nearest** returns the next step when the adjusted tick is strictly closer to the next boundary than to this step's start. The next boundary is `tickForStep(floor + 1)` or `loopTicks` on the last step. The next index wraps to 0. A tick exactly halfway stays on the earlier step.

Use the swung boundaries. Do not split the bar into equal 24-tick buckets when swing is above 50.

Worked values, 16 steps, for the tests:

| Swing | Tick | Nearest | Floor |
| --- | --- | --- | --- |
| 50 | 11 | 0 | 0 |
| 50 | 12 | 0 | 0 |
| 50 | 13 | 1 | 0 |
| 50 | 23 | 1 | 0 |
| 75 | 18 | 0 | 0 |
| 75 | 19 | 1 | 0 |
| 75 | 42 | 1 | 1 |
| 75 | 43 | 2 | 1 |
| 50 | 372 | 15 | 15 |
| 50 | 373 | 0 | 15 |

Step starts at swing 50 are 0, 24, 48, … and the loop is 384. At swing 75 the first three starts are 0, 36, 48. Halfway from 360 to 384 is 372.

Song mode quantizes the tick inside the current section, not the raw song tick. Section length is `SequenceBuilder.loopTicks(stepCount)`. `Chain.place` already divides the song the same way. A song tick of 400 at 16 steps is section offset 16, which is still step 0 at swing 50.

Latency in milliseconds converts with `Math.round(millis * bpm * SequenceBuilder.PPQ / 60_000.0)`. At 96 BPM, 20 ms is 3 ticks. At 120 BPM, 20 ms is 4 ticks. The keyboard passes 0. The spinner value is the MIDI figure.

MIDI time: `Receiver.send` gives a microsecond timestamp, or `-1` when the port does not stamp. Anchor `sequencer.getMicrosecondPosition()` and `System.nanoTime()` when playback starts, and again after each `Player.update`. A stamped event uses its timestamp. An unstamped event uses the nanoTime delta from that anchor. `Quantize.tickAt(startMicros, startTick, eventMicros, bpm)` is the pure conversion: `startTick + round((eventMicros - startMicros) * bpm * PPQ / 60_000_000)`. Then `choose` applies latency and swing. While stopped, there is no tick: step record uses the selected column.

`Player` grows two readers, `tickPosition()` and `microsecondPosition()`, both `-1` when the sequencer is closed or stopped. Live input must not call `getTransmitter` on the sequencer or replace the playback receiver.

### Note routing

`NoteRouter.route(tracks, selectedIndex, note, channel) -> Route`, where `Route` is a track index plus an optional pitch. `note` is clamped with `Notes.clamp`. Channel 9 is `Gm.DRUM_CHANNEL`.

1. Candidates are tracks whose stored input note is 0–127 and equals the played note.
2. If none, candidates are drum tracks whose sounding `note()` equals the played note.
3. If the selected track is a candidate, choose it. Otherwise choose the lowest index.
4. If there is still no candidate, the channel is not 9, and the selected track is `TrackMode.NOTE`, route to that track. The route carries a pitch when the played note differs from the track note, and `-1` when it matches (the writer clears any step pitch). An unmapped note on channel 9 stays off the pitched track.
5. Otherwise drop the event. No new track is created. Status, at most a few times a second: `No track for <note name>.`

Computer pads skip the router and name a row directly. They pass an empty pitch, so a pad never sets or clears step pitch. The piano row uses the router only for its pitch decision on the selected track: it does not jump to another row.

`recordHit(row, step, velocity, pitch)`:

- `pitch == -1` leaves the cell's pitch alone (pads and matched drums).
- `pitch >= 0` equal to the track note calls `Step.clearPitch()`.
- Any other pitch calls `Step.setPitch`.
- The cell is set on, and its velocity is replaced, even if it was already on.
- The written row and column become the selection, the same way `tap` moves the outline. Step record then advances one column from there.

### Hearing the take

`StudioFrame.changed` calls `Player.update` on every musical change while playing. A take must not do that per hit: `setSequence` restarts the sequencer.

While `editor.takeOpen()` is true, skip `player.update`. Still refresh the grid. Each new hit calls `Player.audition` on the EDT so the attack is heard before the loop knows about it. The 160 ms preview stays. A hit on a step the old sequence already plays can attack twice; the loop-wrap rebuild below is what removes the stale copy.

`endTake`, after it clears the flag, fires one musical change if the take wrote anything. The existing `player.update` path then runs once.

The 40 ms playhead timer also rebuilds at a loop wrap during an open take. Remember `takeRevision()` at the last rebuild. When the section tick goes backwards (loop or next chain section) and the revision moved, call `player.update` once. The restart sits on the bar line. Mid-bar hits stay in the model and in the audition, and they join the sequence on the next pass.

`takeRevision()` increments on each `recordHit`. Load, undo, and redo leave it alone.

### Undo

A take is its own flag, not `isAdjusting()`.

- The first `recordHit` of a pass calls `beginTake`, which pushes one `Beat.copy()` and clears redo, the same snapshot `edit` pushes. Arming record does not push a snapshot.
- `recordHit` then mutates the current beat, marks dirty, increments the revision, and fires a musical change.
- `endTake` clears the flag. A pass that wrote fires one musical change after the flag is clear, so playback can rebuild. `endTake` with no open take does nothing.
- `setRecording(false)`, `Player.stop` from the transport, `undo`, `redo`, and `replace` all end an open take first.
- `edit` and `beginAdjust` end an open take first, so a click or a slider during a pass becomes the next undo entry rather than part of the performance. `beginAdjust` still freezes song follow for the drag only.
- `undo` while a take is open ends the take without firing, then pops. The snapshot from `beginTake` is the pre-pass beat, so one undo removes the whole pass. Redo puts it back.
- Record on/off, the quantize mode, the octave offset, and Learn-waiting are not undo and do not mark the beat dirty.

Step entry with record off keeps using `editor.tap` / `editor.edit`: one key, one undo.

## Classes to add

| Class | Role |
| --- | --- |
| `hits.Quantize` | `choose`, `ticksForMillis`, `tickAt`, `sectionTick`. No MIDI classes imported. |
| `hits.NoteRouter` | `route` and a small `Route` record (`track`, `pitch` with `-1` for "leave the step pitch"). |
| `hits.MidiInputs` | Mirror of `MidiOutputs` for ports that send. `NONE_ID` `"none"`, `NONE_LABEL` `"None"`, `PREF_INPUT` `"midiInput"`. `isExternalInput`: false for a `Synthesizer` or a `Sequencer`, true when `getMaxTransmitters() != 0`. Ids and labels go through `MidiOutputs.idFor` and `labelFor`, so the encoding stays in one place. `list`, `find`, `resolve` (missing id resolves to `NONE_ID`). |
| `hits.ui.KeyPads` | `trackIndex(keyCode)` for `1`–`8`, or `-1`. `pitch(keyCode, trackNote, octaveOffset)` for the piano row, or `-1`. `octaveDelta(keyCode)` for `[` and `]`. |
| `hits.ui.LiveInput` | Owns the open input device, its `Transmitter`, and the `Receiver`. Combo and preference wiring can live here and be called from `OutputBar`, so `OutputBar` does not grow a second device lifecycle. Queue plus a single `SwingUtilities.invokeLater` drain. |

Package shape matches the tree: public final types in `hits`, package-private final types in `hits.ui`.

## Classes to change

| Class | Change |
| --- | --- |
| `Track` | `inputNote` defaults to `-1` (use the sounding note). `setInputNote` / `clearInputNote`. The raw value is what routing and `copy()` use, including when it equals `note()`, so a later edit of the sounding note can keep a learned pad. `hasInputNote()` is the file rule: raw value `>= 0` and different from `note()`. `clearSteps` leaves the field. |
| `Beat` | `hasInputMap()` scans both slots. `usesFormatV2()` becomes step pitch, or a chain, or an input map. |
| `BeatJson` | Read and write `inputNote` on a track, after `note`, only when `hasInputNote()`. Update the class comment. Version ceiling stays `Beat.FORMAT_V2`. |
| `Editor` | `recording`, `setRecording`, `takeOpen`, `takeRevision`, `beginTake`, `recordHit`, `endTake`, `moveSelection`. `edit`, `beginAdjust`, `undo`, `redo`, and `replace` end a take as described above. |
| `Player` | `tickPosition()` and `microsecondPosition()`, both `-1` when stopped or closed. `endAudition()` cancels the 160 ms preview and sends that preview's note-off, so a controller note-off can release the note it started. The preview stays monophonic, as `audition` is today. No change to the playback transmitter. |
| `KeyboardShortcuts` | A second dispatcher, installed beside `spacePlay`, using `KeyPads`. It calls back into a small handler owned by `StudioFrame` or `LiveInput` so this class does not grow a recorder. |
| `TransportBar` | Record and the Nearest/Step button. `refresh` paints them from editor and preference state. `togglePlay` ending in stop ends the take (the frame can do that in the existing stop branch). |
| `OutputBar` | MIDI in combo and Late spinner, delegated to `LiveInput`. `popupOpen` covers both popups. |
| `StudioFrame` | Build `LiveInput`, install the key dispatcher, remove it in `dispose`, and close the input before `player.close()`. Skip `player.update` while `takeOpen()`. Rebuild on take end and on loop wrap. |
| `TrackInspector` | Input-note readout, Learn, Use track note. Phase 4. |
| `Theme` | `RECORD = new Color(0xE15A4A)`. |
| `README.md` | When this ships: Playing (keys, Record, quantize), MIDI in next to MIDI out, the Files paragraph for `inputNote`, and the Controls table. |
| `CONTRIBUTING.md` | When this ships: the new core types and `LiveInput` / `KeyPads` in the layout list. |

`PatternPanel` mouse handling stays put. Tooltips can mention the keys in the same sentence as click and Shift-click when the feature ships.

Do not restructure `StudioFrame`, the chain, export, or `SequenceBuilder` beyond calling `tickForStep` / `stepForTick` from `Quantize`. Other branches are in those files.

## Data and format

Version 2 stays the version written when the document uses any optional feature. `documentVersion` keeps using `usesFormatV2()`.

New optional track field:

```json
"note": 36,
"inputNote": 60,
```

Omitted when the field is `-1`, and omitted when it equals the sounding note. A save at that moment drops a learned value that currently matches, same as **Use track note**. Until that save, `copy()` still carries the raw value, so changing the sounding note again in the same session keeps the pad. Insert the field only in that spot so every other line of a version 1 file is unchanged. A fresh `Beat.drumKit` must still satisfy `documentVersion == 1` and `write` must not contain `inputNote`.

| Document | Written version |
| --- | --- |
| Kit, empty chain, no step pitch, no input override | 1 |
| Any step `pitch` | 2 |
| Non-empty `chain` | 2 |
| Any `inputNote` that differs from that track's `note` | 2 |
| A version 2 file whose only unknown extra is something else, such as `pan` | Reader accepts it. Resave writes 1 if pitch, chain, and input note are all absent. |

The reader already allows versions 1 and 2 and rejects 0 and 3. Keep that. A version 1 file that happens to contain `inputNote` loads the override, and the next save writes version 2, the same way a version 1 file with `pitch` does today.

A build from before this field loads the file (unknown keys are ignored) and drops the map on the next save. If the file was version 2 only because of `inputNote`, that older build writes version 1. That is the additive rule. Do not add a version 3.

`Track.copy` and `Beat.copy` carry `inputNote`, or undo will strip it. `copySlotToOther` already copies whole tracks. `copyBar` copies steps only; the input note stays on the track.

These stay out of the file, in `AppPreferences` on the `HitsApp` node:

| Key | Default | Values |
| --- | --- | --- |
| `midiInput` | empty, treated as None | `MidiInputs` id |
| `midiInputLatencyMs` | `0` | `0`–`250` |
| `recordQuantize` | `nearest` | `nearest` or `floor` |

Record arm, Learn-waiting, and the piano octave offset are memory only.

## Threading and the open port

The `Receiver` runs on the device thread.

- Accept `ShortMessage` note-on and note-off only. Ignore clocks, control changes, sysex, and active sensing.
- Push an immutable event (command, channel, note, velocity, timestamp) onto a queue. If a drain is not already posted, `SwingUtilities.invokeLater` one drain that empties the queue.
- The drain runs on the EDT: route, `recordHit` or `audition`, status text.
- `send` does not open, close, or call `MidiSystem`, and it does not take `Player`'s lock or touch a `Receiver` on the synth. Audition stays on the EDT, which is the thread that already sends preview notes.

Opening and closing happen on the EDT from the combo, matching `useOutput`. Remember `boolean openedByUs = !device.isOpen()` before `open()`. On release, close the transmitter always, and close the device only when `openedByUs`. A port that is already the MIDI output may be the same `MidiDevice` instance; closing it would cut playback.

`LiveInput.close` runs from `StudioFrame.dispose` and from the window-closing path, before `player.close()`.

## Testing

`./gradlew test build` with no display and no MIDI device. Do not open a `JFrame`, a `Sequencer`, or a `MidiDevice` from a test. Reading ids from `MidiSystem.getMidiDeviceInfo()` is allowed only inside `MidiInputs.list` at runtime; the tests call the pure helpers and, if they call `list`, only assert that None is present and that every extra row has a non-empty id.

`QuantizeTest` (`src/test/java/hits/QuantizeTest.java`):

- The table above. A latency of 13 ticks pulls tick 24 at swing 50 from step 1 back onto step 0. A latency of 12 ticks wraps tick 0 at swing 50 onto step 15 (adjusted tick 372, the exact halfway, which stays on the earlier step).
- `ticksForMillis(96, 20) == 3` and `ticksForMillis(120, 20) == 4`.
- `tickAt` for a 0 start, a later timestamp, and a timestamp before the start (the result may be negative; `choose` wraps it).
- `sectionTick` of song tick 400 at 16 steps is 16.

`NoteRouterTest`:

- A default kit: note 36 → Kick, 38 → Snare, 42 → Closed Hat. Channel 0 and channel 9 agree when only one drum matches.
- Two drum tracks on note 36: the selected one wins; with another row selected, index 0 wins.
- A stored input note of 60 on the snare wins over a kick whose sounding note is 60.
- A note track, selected, with no drum match, on channel 0: pitch is set when the note differs, and `-1` when it matches. The same unmapped note on channel 9 is empty.
- A note with no candidate and a selected drum track is empty.
- Notes outside 0–127 clamp. Velocity is not the router's job.

`RecordTakeTest` (editor, no window):

- Three `recordHit` calls, then `endTake`, then one `undo` restores all three cells. Redo puts them back.
- A `tap` during an open take ends it; the tap is a second undo entry.
- `setRecording` does not mark dirty and does not push undo.
- `recordHit` writes `viewSlot()` while the editor is holding the other slot.
- A hit with a pitch different from the track note sets `Step.pitch`. A hit whose pitch equals the track note clears it. A hit with pitch `-1` leaves an existing step pitch in place.
- `moveSelection` changes the column and does not mark dirty.

`BeatJson` cases, added beside `version1DocumentMigratesAndStepPitchWritesVersion2` in `BeatTest`:

- `Beat.drumKit("plain")` still writes version 1 and the text does not contain `inputNote`.
- A track with `inputNote` 60 and sounding note 36 round-trips, writes version 2, and writes the field next to `note`.
- Clearing the override, or setting it equal to the sounding note, returns the document to version 1 when pitch and chain are absent.
- A file that already has a step pitch and an `inputNote` keeps both.
- A version 1 document that includes `inputNote` loads it.
- A version 2 document with an unknown key and no real v2 feature still resaves as version 1.

`KeyPadsTest` in `hits.ui`: `1`–`8`, the piano semitones, `[` / `]`, and `-1` for Space, `Z`, and shortcut-looking keys the map does not own. `KeyEvent` constants do not open a window.

`MidiInputsTest`: `isExternalInput` is false for a sequencer and a synthesizer (a tiny fake, or a package-visible predicate on max transmitters plus a type flag if a fake `MidiDevice` is awkward). Id round-trip can reuse a name that contains `|` and assert the same encoding `MidiOutputs.idFor` already uses. `resolve` of an unknown id is `none`.

## Manual check when this ships

Skip this for the plan review. When the code exists, on a machine with a display:

- Space, shortcut-Z, shortcut-Shift-Z, shortcut-S, and shortcut-Shift-S still work with the grid focused and with a button focused.
- Typing a beat name that contains `A`, `S`, or `1` does not change the grid. Record can be armed during that typing.
- Stopped, record off: `1` toggles kick at the selected step; Shift+`1` uses the loud hit; arrows move; Enter toggles.
- Stopped, record on: pads fill a row and wrap at the last step; one undo clears the pass.
- Playing, record on, swing 50 and swing 75: a pad on the beat lands on that step. A pad just after the swung halfway point lands on the next step in Nearest, and stays on the playhead step in Step mode. A pad exactly halfway stays on the earlier step.
- Song playing, grid following: a hit lands in the slot that is sounding. Hold B and record: the hit lands on B at the clock's step.
- MIDI in **None** leaves playback on the built-in synth. Pick a controller, play a kick, hear it. Arm record, play, see steps. Unplug, relaunch, confirm the status and that playback still starts.
- Learn on a hat from a pad that does not send note 42. Save, reload, confirm the field. Clear it, save, confirm the file is version 1 again if nothing else uses version 2.
- Export MIDI of a recorded pattern still matches the grid, including a step pitch and swing.

This environment has no synth check in the plan. WAV export and the existing playback tests stay the coverage for "the sequence contains the notes."

## Risks

- **Sequence restart.** Rebuilding on every hit will glitch the clock and drop notes. The skip-while-take-open rule is part of the feature, not a follow-up. `Player.update` at the wrap still restarts; doing it only when the section tick wraps keeps the glitch on the bar line.
- **Shared `MidiDevice`.** Closing an input that is the same instance as the open output silences playback. Close the device only when this feature opened it.
- **Callback thread versus Gervill.** Sending to the synth, or taking `midiLock`, inside `Receiver.send` can deadlock. The queue and the EDT drain are the whole fix. Do not audition from the device thread.
- **Unstamped events.** Many JDK ports pass `-1`. The nanoTime anchor is the fallback, and it is only as steady as "when the message was delivered," so latency compensation on those ports is approximate. The spinner is still worth having for ports that do stamp.
- **Audition versus the looping sequence.** A preview note-off on channel 9 can cut a drum the sequencer is still holding, and a fresh hit on an already-on step can double. Both already exist for inspector audition. Live input makes them common. The bar-line rebuild is the mitigation for the double; do not add a second note engine in this feature.
- **`isAdjusting` and the song.** Reusing the slider flag for a take freezes `showPlayingSlot` for the whole pass. The take flag is separate on purpose.
- **One pitch per step.** A chord collapses to the last note. Say so in the Record tooltip's longer help if someone asks; the status line does not need to warn on every chord.
- **CI and this VM.** No input device, and often no synth. `list()` must return None and nothing else without throwing. Tests must not require a port.
- **Merge surface.** `StudioFrame`, `TransportBar`, `OutputBar`, `Editor`, `Beat`, and `BeatJson` move in parallel branches. An implementation PR should touch the rows in the tables above and leave neighboring refactors alone.
- **Older apps.** They keep opening the file and they drop `inputNote` when they save. That is the version 2 contract already used for `pitch` and `chain`.

## Milestones

Each milestone leaves `./gradlew test build` green and leaves a plain kit's JSON unchanged. Stop between them if the branch needs to land early.

1. **Keyboard step entry, record off.** `KeyPads`, the dispatcher, `moveSelection`, tap and step-pitch writes while stopped, audition while playing. No Record button and no file change. Tests: `KeyPadsTest`, and an editor test that a piano write sets and clears pitch through `edit`.
2. **Record and quantize.** Record button, `Quantize`, the take API, keyboard step-record and live record, the playback skip and the bar-line rebuild. Preferences for nearest versus floor. Tests: `QuantizeTest`, `RecordTakeTest`.
3. **MIDI input.** `MidiInputs`, `LiveInput`, the footer combo, the Late spinner, `NoteRouter`, audition and record from note-on / note-off. Tests: `NoteRouterTest`, `MidiInputsTest`. Manual check with a controller happens here. Default mapping only; no new JSON.
4. **Learned input note.** `Track.inputNote`, `BeatJson`, the inspector Learn row, README and CONTRIBUTING. Tests: the JSON cases. This is the only milestone that changes a file on disk, and only when a track's input note differs from its sounding note.

Until Michael says to start, milestone 1 does not start either.
