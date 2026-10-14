package hits.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Temporary MIDI and WAV files created for a drag. A drop target may still be reading
 * after the gesture ends, so the files stay until Hits exits.
 */
final class DragFiles implements AutoCloseable {
    private final Path directory;
    private final Set<String> names = new LinkedHashSet<>();
    private final List<Path> paths = new ArrayList<>();
    private boolean closed;

    private DragFiles(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** A private folder under the system temp directory, removed on exit. */
    static DragFiles open() throws IOException {
        DragFiles files = new DragFiles(Files.createTempDirectory("hits-drag-"));
        Runtime.getRuntime().addShutdownHook(new Thread(files::close, "hits-drag-cleanup"));
        return files;
    }

    /** A folder the caller owns. Tests use this so they do not register a shutdown hook. */
    static DragFiles in(Path directory) throws IOException {
        Files.createDirectories(directory);
        return new DragFiles(directory);
    }

    Path directory() {
        return directory;
    }

    /**
     * Reserves a unique file name in the temp folder. The file itself is written by the export.
     * Asking for the same name again yields {@code name-2}, then {@code name-3}, before the extension.
     */
    synchronized Path place(String fileName) {
        if (closed) {
            throw new IllegalStateException("Drag files have already been deleted");
        }
        if (!singleSegment(fileName)) {
            throw new IllegalArgumentException("Drag file name must be a single file name");
        }
        String unique = uniqueName(names, fileName);
        Path path = directory.resolve(unique).normalize();
        if (path.getParent() == null || !path.getParent().equals(directory)) {
            throw new IllegalArgumentException("Drag file name must stay in the temp folder");
        }
        paths.add(path);
        return path;
    }

    static String uniqueName(Set<String> used, String fileName) {
        if (used.add(fileName)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        int index = 2;
        String candidate = stem + "-" + index + extension;
        while (!used.add(candidate)) {
            index++;
            candidate = stem + "-" + index + extension;
        }
        return candidate;
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Path path : paths) {
            deleteQuietly(path);
        }
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var stream = Files.list(directory)) {
            stream.forEach(DragFiles::deleteQuietly);
        } catch (IOException ignored) {
            // A later close, including the shutdown hook, tries again.
        }
        deleteQuietly(directory);
    }

    private static boolean singleSegment(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        return fileName.indexOf('/') < 0
            && fileName.indexOf('\\') < 0
            && fileName.indexOf('\0') < 0
            && !fileName.equals(".")
            && !fileName.equals("..");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A DAW may still have the file open. The shutdown hook retries at exit.
        }
    }
}
