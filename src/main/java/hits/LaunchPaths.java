package hits;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Where a launch looks for beats.
 * {@code ./gradlew run} uses the working directory, which is the repository when Gradle starts it.
 * A packaged app sets {@code -Dhits.packaged=true} and uses {@code Hits} in the home directory,
 * because a double-clicked process has no useful working directory.
 */
public final class LaunchPaths {
    public static final String PACKAGED_PROPERTY = "hits.packaged";

    private LaunchPaths() {}

    public static boolean packaged() {
        return Boolean.parseBoolean(System.getProperty(PACKAGED_PROPERTY, "false"));
    }

    public static Path baseDirectory() {
        return baseDirectory(packaged(), System.getProperty("user.home"), Path.of("").toAbsolutePath());
    }

    /** Blank {@code userHome} falls back to {@code workingDirectory} so a missing property still resolves. */
    public static Path baseDirectory(boolean packaged, String userHome, Path workingDirectory) {
        Path working = workingDirectory == null ? Path.of("") : workingDirectory;
        if (packaged) {
            Path home = userHome == null || userHome.isBlank()
                ? working
                : Path.of(userHome);
            return home.toAbsolutePath().normalize().resolve("Hits");
        }
        return working.toAbsolutePath().normalize();
    }

    /**
     * True when {@code resolved} is the folder a blank preference would use.
     * An empty saved value is always the default, even before the folder exists.
     */
    public static boolean isDefaultFolder(String saved, Path resolved, Path base) {
        if (saved == null || saved.isBlank()) {
            return true;
        }
        if (resolved == null || base == null) {
            return false;
        }
        Path standard = base.toAbsolutePath().normalize().resolve(BeatFiles.directory()).normalize();
        return resolved.toAbsolutePath().normalize().equals(standard);
    }

    /** Directory of the running classes or jar, or null when the location cannot be read. */
    public static Path codeSource(Class<?> anchor) {
        if (anchor == null) {
            return null;
        }
        try {
            var source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Sample beats shipped next to the application jar.
     * {@code jpackage} copies the input directory, including {@code beats/}, beside that jar.
     */
    public static Path bundledSamples(Path codeSource) {
        if (codeSource == null) {
            return null;
        }
        Path location = codeSource.toAbsolutePath().normalize();
        Path appDir = Files.isDirectory(location) ? location : location.getParent();
        if (appDir == null) {
            return null;
        }
        return appDir.resolve("beats");
    }

    /**
     * Creates {@code destination} and copies {@code .btf} and {@code .json} files into it
     * when the folder has no files yet. An existing file is left in place.
     */
    public static void ensureSamples(Path destination, Path bundled) throws IOException {
        if (destination == null) {
            throw new IOException("Choose a beats folder");
        }
        if (containsFile(destination)) {
            return;
        }
        Files.createDirectories(destination);
        if (bundled == null || !Files.isDirectory(bundled)) {
            return;
        }
        try (var stream = Files.list(bundled)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".btf") || name.endsWith(".json")) {
                    Files.copy(file, destination.resolve(file.getFileName().toString()));
                }
            }
        }
    }

    private static boolean containsFile(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var stream = Files.list(directory)) {
            return stream.anyMatch(Files::isRegularFile);
        }
    }
}
