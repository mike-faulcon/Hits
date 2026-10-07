package hits;

import java.nio.file.Path;

/** Where Load and Save look. The stored value is an absolute path, not a cwd-relative {@code beats/}. */
public final class BeatFolders {
    public static final String PREF_DIRECTORY = "beatsDirectory";

    private BeatFolders() {}

    /**
     * Blank means {@code beats} inside {@code workingDirectory}.
     * A relative saved value is resolved from that directory. An absolute value is kept as-is.
     */
    public static Path resolve(String saved, Path workingDirectory) {
        Path base = workingDirectory == null
            ? Path.of("").toAbsolutePath().normalize()
            : workingDirectory.toAbsolutePath().normalize();
        if (saved == null || saved.isBlank()) {
            return base.resolve(BeatFiles.directory()).normalize();
        }
        Path chosen = Path.of(saved.trim());
        if (!chosen.isAbsolute()) {
            chosen = base.resolve(chosen);
        }
        return chosen.toAbsolutePath().normalize();
    }

    public static String remember(Path folder) {
        if (folder == null) {
            throw new IllegalArgumentException("Choose a beats folder");
        }
        return folder.toAbsolutePath().normalize().toString();
    }
}
