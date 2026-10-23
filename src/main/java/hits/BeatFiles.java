package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

public final class BeatFiles {
    private BeatFiles() {}

    /** Relative default. The window stores an absolute folder with {@link BeatFolders}. */
    public static Path directory() {
        return Path.of("beats");
    }

    public record Entry(String label, Path path) {}

    public static List<Entry> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(BeatFiles::isBeat).sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        List<Entry> entries = new ArrayList<>();
        for (Path path : files) {
            String filename = path.getFileName().toString();
            boolean json = filename.toLowerCase(Locale.ROOT).endsWith(".json");
            String stem = filename.substring(0, filename.length() - (json ? 5 : 4));
            boolean both = files.stream().anyMatch(other -> {
                String otherName = other.getFileName().toString();
                return otherName.equals(stem + ".json") || otherName.equals(stem + ".btf");
            }) && files.stream().anyMatch(other -> other.getFileName().toString().equals(stem + ".json"))
                && files.stream().anyMatch(other -> other.getFileName().toString().equals(stem + ".btf"));
            String label = both && !json ? stem + " .btf" : stem;
            entries.add(new Entry(label, path));
        }
        return entries;
    }

    public static Beat load(Path path) throws IOException {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".json")) {
            return BeatJson.read(Files.readString(path, StandardCharsets.UTF_8));
        }
        return BtfFormat.read(path);
    }

    /**
     * How Save should ask before writing. The display name stays in the JSON;
     * only the file name is reduced to {@link #safeName(String)}.
     */
    public enum SaveChoice {
        /** Same file as last time, and the name needs no explanation. */
        WRITE,
        /** Show the file name. It drops characters the display name still has. */
        CONFIRM_NAME,
        /** The destination already exists and is not the open file. */
        CONFIRM_REPLACE
    }

    public static Path save(Beat beat, Path directory) throws IOException {
        return write(beat, jsonFile(directory, beat.name()));
    }

    public static Path jsonFile(Path directory, String displayName) {
        String safe = safeName(displayName);
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("Add a letter or number to the name before saving");
        }
        return directory.resolve(safe + ".json");
    }

    /** Writes the beat as-is. The display name in the file is not rewritten to the file stem. */
    public static Path write(Beat beat, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, BeatJson.write(beat), StandardCharsets.UTF_8);
        return path;
    }

    public static SaveChoice plan(String displayName, Path target, Path currentFile, boolean forcePrompt, boolean targetExists) {
        String shown = displayName == null ? "" : displayName.trim();
        boolean nameAdjusted = !safeName(shown).equals(shown);
        boolean same = sameFile(target, currentFile);
        if (targetExists && (forcePrompt || !same)) {
            return SaveChoice.CONFIRM_REPLACE;
        }
        if (forcePrompt || (nameAdjusted && !same)) {
            return SaveChoice.CONFIRM_NAME;
        }
        return SaveChoice.WRITE;
    }

    public static void exportMidi(Beat beat, Path path) throws IOException, InvalidMidiDataException {
        exportMidi(beat, path, ExportOptions.matchingPlayback());
    }

    public static void exportMidi(Beat beat, Path path, ExportOptions options) throws IOException, InvalidMidiDataException {
        exportMidi(beat, path, options, null, false);
    }

    /**
     * Writes the MIDI file. Sample rows whose files can be read select the sample bank.
     * {@code beatFile} resolves relative sample paths. When {@code writeSamples} is set and a
     * sample bank was built, a companion {@code <name>-samples.sf2} is written beside the MIDI file.
     */
    public static void exportMidi(
        Beat beat,
        Path path,
        ExportOptions options,
        Path beatFile,
        boolean writeSamples
    ) throws IOException, InvalidMidiDataException {
        // Low cut is not a MIDI controller. The companion SoundFont keeps gain, invert,
        // and the envelope, and leaves the PCM unfiltered. A drum stays on its ordinary note.
        SampleLibrary.Ready ready = SampleLibrary.prepare(
            beat, beatFile, SequenceBuilder.exportSlots(beat, options), null, false
        );
        MidiSystem.write(SequenceBuilder.export(beat, options, null, ready.use()), 1, path.toFile());
        if (writeSamples && ready.sf2() != null && ready.sf2().length > 0) {
            Files.write(samplesFont(path), ready.sf2());
        }
    }

    /** Companion SoundFont written next to a MIDI export, {@code Album-A-as-heard.mid} to {@code Album-A-as-heard-samples.sf2}. */
    public static Path samplesFont(Path midiPath) {
        String name = midiPath.getFileName() == null ? "samples" : midiPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return midiPath.resolveSibling(stem + "-samples.sf2");
    }

    /**
     * Writes the same sequence as {@link #exportMidi} as 44.1 kHz 16-bit stereo PCM.
     * {@code soundbank} is the SoundFont loaded on the built-in synth, or null for General MIDI.
     * {@code progress} receives 0–100 from the render thread. A cancelled render leaves {@code path} unchanged.
     * <p>
     * There is no beat file here, so a relative sample is resolved against the working directory.
     * A relative sample that is not in that directory throws {@link IOException} instead of being dropped.
     * Absolute paths and built-ins are unchanged. Pass the beat file to
     * {@link #exportWav(Beat, Path, ExportOptions, Soundbank, IntConsumer, BooleanSupplier, Path)}
     * when the path is relative to that file.
     */
    public static String exportWav(
        Beat beat,
        Path path,
        ExportOptions options,
        Soundbank soundbank,
        IntConsumer progress
    ) throws IOException, InvalidMidiDataException {
        return exportWav(beat, path, options, soundbank, progress, () -> false, null);
    }

    public static String exportWav(
        Beat beat,
        Path path,
        ExportOptions options,
        Soundbank soundbank,
        IntConsumer progress,
        BooleanSupplier cancelled
    ) throws IOException, InvalidMidiDataException {
        return exportWav(beat, path, options, soundbank, progress, cancelled, null);
    }

    /**
     * Same WAV as {@link #exportWav(Beat, Path, ExportOptions, Soundbank, IntConsumer, BooleanSupplier)}.
     * {@code beatFile} resolves relative samples. The sample bank is loaded beside {@code soundbank}.
     * Returns the master clip or limiter warning, or null when the render stays under 1 dB of reduction.
     */
    public static String exportWav(
        Beat beat,
        Path path,
        ExportOptions options,
        Soundbank soundbank,
        IntConsumer progress,
        BooleanSupplier cancelled,
        Path beatFile
    ) throws IOException, InvalidMidiDataException {
        Soundbank playing = soundbank != null ? soundbank : SoundCatalog.builtin();
        Path samplesFrom = beatFile;
        if (samplesFrom == null) {
            samplesFrom = Path.of("").toAbsolutePath().resolve("export.json");
            String missing = SamplePaths.unresolvedRelative(beat, samplesFrom);
            if (missing != null) {
                throw new IOException(missing);
            }
        }
        SampleLibrary.Ready ready = SampleLibrary.prepare(
            beat, samplesFrom, SequenceBuilder.exportSlots(beat, options), soundbank, true
        );
        return WavRenderer.render(
            SequenceBuilder.export(beat, options, playing, ready.use()),
            path,
            soundbank,
            ready.bank(),
            progress,
            cancelled
        );
    }

    public static String safeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private static boolean sameFile(Path left, Path right) {
        if (left == null || right == null) {
            return false;
        }
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    private static boolean isBeat(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".json") || filename.endsWith(".btf");
    }
}
