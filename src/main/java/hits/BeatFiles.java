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

    public static Path save(Beat beat, Path directory) throws IOException {
        Files.createDirectories(directory);
        String safe = safeName(beat.name());
        if (safe.isEmpty()) {
            throw new IllegalArgumentException("Name the beat with letters, numbers, _ or -");
        }
        beat.setName(safe);
        Path path = directory.resolve(safe + ".json");
        Files.writeString(path, BeatJson.write(beat), StandardCharsets.UTF_8);
        return path;
    }

    public static void exportMidi(Beat beat, Path path) throws IOException, InvalidMidiDataException {
        MidiSystem.write(SequenceBuilder.build(beat), 1, path.toFile());
    }

    public static String safeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
    }

    private static boolean isBeat(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".json") || filename.endsWith(".btf");
    }
}
