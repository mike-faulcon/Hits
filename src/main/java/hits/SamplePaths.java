package hits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Paths stored in a beat are relative to the beat file, and rewritten when that file moves. */
public final class SamplePaths {
    private SamplePaths() {}

    public static boolean builtin(String path) {
        return BuiltinSamples.isBuiltin(path);
    }

    /** Absolute file for a stored path, or null for a built-in or a blank path. */
    public static Path resolve(String stored, Path beatFile) {
        if (stored == null || stored.isBlank() || builtin(stored)) {
            return null;
        }
        Path path = Path.of(stored);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        if (beatFile == null || beatFile.getParent() == null) {
            return null;
        }
        return beatFile.getParent().resolve(path).normalize();
    }

    /** Path relative to {@code beatFile}, with {@code /} separators. */
    public static String relativize(Path file, Path beatFile) {
        if (file == null || beatFile == null || beatFile.getParent() == null) {
            return file == null ? "" : file.toString().replace('\\', '/');
        }
        Path base = beatFile.getParent().toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        return base.relativize(target).toString().replace('\\', '/');
    }

    /**
     * Rewrites sample paths so they still point at the same files after the beat moves
     * from {@code fromFile} to {@code toFile}. Built-ins stay as they are.
     * A null {@code fromFile} still turns absolute paths into paths relative to {@code toFile}.
     * Returns true when a stored path changed.
     */
    public static boolean retarget(Beat beat, Path fromFile, Path toFile) {
        if (beat == null || toFile == null) {
            return false;
        }
        boolean changed = false;
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            Track[] tracks = beat.slot(Beat.slotLetter(slot));
            for (Track track : tracks) {
                if (track.mode() != TrackMode.SAMPLE || track.sample() == null) {
                    continue;
                }
                SampleRef sample = track.sample();
                if (builtin(sample.path()) || sample.path().isBlank()) {
                    continue;
                }
                Path absolute = resolve(sample.path(), fromFile);
                if (absolute == null || !absolute.isAbsolute()) {
                    continue;
                }
                String next = relativize(absolute, toFile);
                if (!next.equals(sample.path())) {
                    track.setSample(sample.withPath(next));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** {@code <stem>.samples} beside the beat file. */
    public static Path directory(Path beatFile) {
        String name = beatFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return beatFile.resolveSibling(stem + ".samples");
    }

    /**
     * Copies every sample the beat can read into {@link #directory}.
     * Built-ins are written as WAV files. Paths in the beat become relative.
     * Returns how many tracks now point into that folder.
     */
    public static int collect(Beat beat, Path beatFile) throws IOException {
        if (beat == null || beatFile == null) {
            throw new IOException("Save the beat before collecting samples");
        }
        Path folder = directory(beatFile);
        Files.createDirectories(folder);
        Map<String, String> copied = new LinkedHashMap<>();
        int updated = 0;
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            Track[] tracks = beat.slot(Beat.slotLetter(slot));
            for (Track track : tracks) {
                if (track.mode() != TrackMode.SAMPLE || track.sample() == null) {
                    continue;
                }
                SampleRef sample = track.sample();
                if (sample.path().isBlank()) {
                    continue;
                }
                String relative = copied.get(sample.path());
                if (relative == null) {
                    relative = copyOne(sample, beatFile, folder);
                    if (relative == null) {
                        continue;
                    }
                    copied.put(sample.path(), relative);
                }
                if (!relative.equals(sample.path())) {
                    track.setSample(sample.withPath(relative));
                }
                updated++;
            }
        }
        return updated;
    }

    private static String copyOne(SampleRef sample, Path beatFile, Path folder) throws IOException {
        if (builtin(sample.path())) {
            BuiltinSamples.Pcm pcm = BuiltinSamples.pcm(sample.path());
            if (pcm == null) {
                return null;
            }
            Path dest = folder.resolve(BuiltinSamples.fileName(sample.path()));
            if (!Files.isRegularFile(dest)) {
                SampleAudio.writeWav(dest, pcm.rate(), pcm.samples());
            }
            return relativize(dest, beatFile);
        }
        Path source = resolve(sample.path(), beatFile);
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        if (source.getParent() != null && source.getParent().equals(folder.toAbsolutePath().normalize())) {
            return relativize(source, beatFile);
        }
        String name = source.getFileName() == null ? "sample.wav" : source.getFileName().toString();
        Path dest = unique(folder, name);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        return relativize(dest, beatFile);
    }

    private static Path unique(Path folder, String name) throws IOException {
        Path direct = folder.resolve(name);
        if (!Files.exists(direct)) {
            return direct;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 1000; index++) {
            Path candidate = folder.resolve(stem + "-" + index + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not name a collected sample");
    }

    /**
     * A relative sample that {@code beatFile} cannot see, or null when every relative sample resolves.
     * Built-ins and absolute paths are not this method's problem.
     * The six-argument WAV export uses the working directory as {@code beatFile} and throws this text
     * instead of dropping the file.
     */
    public static String unresolvedRelative(Beat beat, Path beatFile) {
        if (beat == null) {
            return null;
        }
        for (int slot = 0; slot < Beat.SLOT_COUNT; slot++) {
            Track[] tracks = beat.slot(Beat.slotLetter(slot));
            for (Track track : tracks) {
                if (track.mode() != TrackMode.SAMPLE || track.sample() == null) {
                    continue;
                }
                String stored = track.sample().path();
                if (stored.isBlank() || builtin(stored) || Path.of(stored).isAbsolute()) {
                    continue;
                }
                Path resolved = resolve(stored, beatFile);
                if (resolved == null || !Files.isRegularFile(resolved)) {
                    return "Sample \"" + stored + "\" was not found. "
                        + "A relative path needs the beat file, or the file has to sit in the working directory.";
                }
            }
        }
        return null;
    }

    /** Why this sample cannot play, or null when it can. */
    public static String problem(Track track, Path beatFile) {
        if (track == null || track.mode() != TrackMode.SAMPLE) {
            return null;
        }
        String fallback = "This row plays " + Notes.name(track.note()) + ".";
        SampleRef sample = track.sample();
        if (sample == null || sample.path().isBlank()) {
            return "Choose a sample. " + fallback;
        }
        if (builtin(sample.path())) {
            return null;
        }
        Path resolved = resolve(sample.path(), beatFile);
        if (resolved == null || !Files.isRegularFile(resolved)) {
            return "Sample file was not found. " + fallback;
        }
        return null;
    }
}
