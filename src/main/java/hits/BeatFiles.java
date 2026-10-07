package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class BeatFiles {
    private BeatFiles() {}

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
        exportMidi(beat, path, ExportOptions.allTracks());
    }

    public static void exportMidi(Beat beat, Path path, ExportOptions options) throws IOException, InvalidMidiDataException {
        MidiSystem.write(SequenceBuilder.export(beat, options), 1, path.toFile());
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
