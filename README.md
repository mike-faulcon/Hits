# Hits

A step sequencer. The desktop window is the studio-desk layout in [docs/mocks/NOTES.md](docs/mocks/NOTES.md).

```shell
./gradlew run
```

Open a pattern from `beats/`. Older `.btf` files still load. Save writes a versioned `.json` file beside them and leaves the original `.btf` in place. Export MIDI writes a standard `.mid` file of the pattern you hear, including mute and solo.

Space plays and stops. Click a step to toggle it. Shift-click sets the accent. Command-Z or Ctrl-Z undoes a change.
