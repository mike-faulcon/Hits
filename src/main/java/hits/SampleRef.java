package hits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One one-shot on a sample track.
 * {@code path} is relative to the beat file, absolute until the beat is saved,
 * or a built-in id such as {@code hits:808}.
 * {@code root} is the MIDI note that plays the recording at its own speed.
 * {@code start} is how many sample frames to skip.
 * {@code oneShot} plays the whole recording; choke lets note-off and the step length cut it.
 */
public final class SampleRef {
    public static final int DEFAULT_ROOT = 60;

    private final String path;
    private final int root;
    private final int start;
    private final boolean oneShot;

    public SampleRef(String path, int root, int start, boolean oneShot) {
        this.path = path == null ? "" : path;
        this.root = Math.max(0, Math.min(127, root));
        this.start = Math.max(0, start);
        this.oneShot = oneShot;
    }

    public static SampleRef builtin(String id, int root) {
        return new SampleRef(id, root, 0, true);
    }

    public String path() {
        return path;
    }

    public int root() {
        return root;
    }

    public int start() {
        return start;
    }

    public boolean oneShot() {
        return oneShot;
    }

    public SampleRef withPath(String path) {
        return new SampleRef(path, root, start, oneShot);
    }

    public SampleRef withRoot(int root) {
        return new SampleRef(path, root, start, oneShot);
    }

    public SampleRef withStart(int start) {
        return new SampleRef(path, root, start, oneShot);
    }

    public SampleRef withOneShot(boolean oneShot) {
        return new SampleRef(path, root, start, oneShot);
    }

    public boolean same(SampleRef other) {
        return other != null
            && path.equals(other.path)
            && root == other.root
            && start == other.start
            && oneShot == other.oneShot;
    }

    /** File name or built-in label, for the row and the info listing. */
    public String label() {
        if (path.isBlank()) {
            return "Sample";
        }
        if (BuiltinSamples.isBuiltin(path)) {
            return BuiltinSamples.label(path);
        }
        Path file = Path.of(path);
        Path name = file.getFileName();
        return name == null ? path : name.toString();
    }

    /**
     * Identity for the playback cache: the path, the settings, and the file's
     * modification time and size when the path is a real file.
     */
    public String cacheToken(Path beatFile) {
        StringBuilder token = new StringBuilder();
        token.append(path).append('@').append(root).append(',').append(start).append(',').append(oneShot);
        if (BuiltinSamples.isBuiltin(path) || path.isBlank()) {
            return token.toString();
        }
        Path resolved = SamplePaths.resolve(path, beatFile);
        long modified = -1;
        long size = -1;
        if (resolved != null && Files.isRegularFile(resolved)) {
            try {
                modified = Files.getLastModifiedTime(resolved).toMillis();
                size = Files.size(resolved);
            } catch (IOException ignored) {
                modified = -1;
                size = -1;
            }
        }
        token.append('#').append(modified).append('#').append(size);
        return token.toString();
    }
}
