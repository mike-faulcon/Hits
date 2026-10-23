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
 * {@code gain} is decibels added in the SoundFont, in 0.1 dB steps. {@link #GAIN} is the default and is left out of the file.
 * A whole number is written without a decimal.
 * {@code invert} flips the phase. False is left out of the file.
 */
public final class SampleRef {
    public static final int DEFAULT_ROOT = 60;
    /**
     * Default sample gain, in decibels. Omitted from the file.
     * The built-in synth attenuates a note (velocity, channel volume, expression, and center pan),
     * so 0 dB renders well below the file. +8 dB puts a default hit near the file peak and
     * brings the built-in Click up to the woodblock. Sample rows are new, so this default
     * is louder than the first sample-track release. A beat that stores {@code "gain": 0} keeps that older level.
     * Drum and note rows do not use this.
     */
    public static final int GAIN = 8;
    public static final int GAIN_MIN = -24;
    public static final int GAIN_MAX = 24;

    private final String path;
    private final int root;
    private final int start;
    private final boolean oneShot;
    /** Gain in tenths of a dB, inside {@link #GAIN_MIN}..{@link #GAIN_MAX}. */
    private final int gainTenths;
    private final boolean invert;

    public SampleRef(String path, int root, int start, boolean oneShot) {
        this(path, root, start, oneShot, GAIN, false);
    }

    public SampleRef(String path, int root, int start, boolean oneShot, int gain, boolean invert) {
        this(path, root, start, oneShot, (double) gain, invert);
    }

    public SampleRef(String path, int root, int start, boolean oneShot, double gain, boolean invert) {
        this.path = path == null ? "" : path;
        this.root = Math.max(0, Math.min(127, root));
        this.start = Math.max(0, start);
        this.oneShot = oneShot;
        this.gainTenths = Db.tenths(Db.clamp(gain, GAIN_MIN, GAIN_MAX));
        this.invert = invert;
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

    /** Decibels added in the SoundFont, in 0.1 dB steps. {@link #GAIN} is the default. */
    public double gain() {
        return Db.fromTenths(gainTenths);
    }

    /** True when the sample's phase is flipped. */
    public boolean invert() {
        return invert;
    }

    public static String gainText(double gain) {
        return Db.labeled(Db.clamp(gain, GAIN_MIN, GAIN_MAX));
    }

    /** Slider position for a sample gain. Each step is 0.1 dB. */
    public static int gainSlider(double gain) {
        return Db.tenths(Db.clamp(gain, GAIN_MIN, GAIN_MAX));
    }

    /** Sample gain for a {@link #gainSlider(double)} position. */
    public static double gainFromSlider(int slider) {
        int tenths = Math.max(Db.tenths(GAIN_MIN), Math.min(Db.tenths(GAIN_MAX), slider));
        return Db.fromTenths(tenths);
    }

    public SampleRef withPath(String path) {
        return new SampleRef(path, root, start, oneShot, gain(), invert);
    }

    public SampleRef withRoot(int root) {
        return new SampleRef(path, root, start, oneShot, gain(), invert);
    }

    public SampleRef withStart(int start) {
        return new SampleRef(path, root, start, oneShot, gain(), invert);
    }

    public SampleRef withOneShot(boolean oneShot) {
        return new SampleRef(path, root, start, oneShot, gain(), invert);
    }

    public SampleRef withGain(double gain) {
        return new SampleRef(path, root, start, oneShot, gain, invert);
    }

    public SampleRef withInvert(boolean invert) {
        return new SampleRef(path, root, start, oneShot, gain(), invert);
    }

    public boolean same(SampleRef other) {
        return other != null
            && path.equals(other.path)
            && root == other.root
            && start == other.start
            && oneShot == other.oneShot
            && gainTenths == other.gainTenths
            && invert == other.invert;
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
        token.append(path).append('@').append(root).append(',').append(start).append(',').append(oneShot)
            .append(',').append(Db.number(gain())).append(',').append(invert);
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
