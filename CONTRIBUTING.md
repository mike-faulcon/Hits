# Contributing

## Build, run, and test

JDK 21 is required. Use the Gradle wrapper from the repository root; a separate Gradle install is not needed.

```shell
./gradlew test    # unit tests
./gradlew build   # tests, jar, and distributions
./gradlew run     # desktop window
```

`./gradlew run` needs a display. Playback uses the built-in Java synth, or an external MIDI output if you pick one. Tests do not.

WAV export renders through Gervill's `AudioSynthesizer.openStream`, which the JDK does not export. The `run` and `test` tasks pass `--add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED`. A render test skips itself when that call is blocked. It does not open a window or an audio device.

## Branches and pull requests

Branch from `master` and open pull requests into `master`. Michael Faulcon reviews each pull request. Nothing is merged without his approval.

## Code layout

- `src/main/java/hits/` is the core: the beat model, JSON and legacy `.btf` files, MIDI sequence building, WAV rendering, MIDI output selection, SoundFont loading, and playback (`Player`).
- `src/main/java/hits/ui/` is the Swing window. `HitsApp` is the entry point. `StudioFrame` builds the window and wires the pieces below. `PatternPanel` is the step grid. There is no menu bar.
  - `TransportBar` — play/stop, tempo, swing, A/B slots, copy, and the beat name.
  - `SongChainBar` — Pattern/Song and the chain row. `ChainEdits` and `ChainSelection` are the selection and edit rules. `ChainLabels` is the button and status text.
  - `TrackInspector` — track name, sound, length, volume, loud hit, drum/note, step pitch, and 16/32 steps. `TrackReadout` is the slider and step-pitch text.
  - `BeatBrowser` — the beats list, Folder…, and the action buttons.
  - `FileCommands` — open, save, save as, new, kit replace, the unsaved-changes prompt, and the beats folder. `SaveSession` remembers the open file and the save-prompt wording.
  - `ExportCommands` — Export MIDI and Export WAV. `ExportPlan` maps the dialog onto `ExportOptions`, the file suffix, and the status summary.
  - `OutputBar` — MIDI out and SoundFont.
  - `KeyboardShortcuts` — undo, redo, save, save as, and space for play/stop.
  - `CoachBanner` — the first-run hint.
- `src/test/java/hits/` holds the unit tests, including `hits.ui` for save state, export options, chain editing, and track readouts. They read sample patterns from `beats/` and the SoundFont fixture in `src/test/resources/`. Those tests do not open a window.
- `docs/mocks/` describes the studio-desk layout.

## Tests

Tests must not need a display or a MIDI device. Keep them on the model, file formats, and MIDI data written to files. Reading a `.mid` or `.sf2` through the JDK does not count as needing a device. Do not open a window from a test. CI runs `./gradlew test` on a headless `ubuntu-latest` runner.
