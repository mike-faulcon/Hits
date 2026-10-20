package hits;

import java.util.Random;

/**
 * Whether a step sounds.
 * Playback rolls again on every pass through the pattern, or through the song.
 * MIDI and WAV export share {@link #EXPORT_SEED}, so the same beat and the same
 * export options write the same notes. The seed is not stored in the beat file.
 */
public final class Chance {
    /** Fixed seed for MIDI and WAV export. */
    public static final long EXPORT_SEED = 0x481753L;
    /** Passes pre-rolled when playback starts and a sounding step can miss. */
    public static final int PLAYBACK_PASSES = 128;
    /** Upper bound on pattern sections packed into one playback sequence. */
    public static final int MAX_PLAYBACK_SECTIONS = 4096;

    private Chance() {}

    public static Random exportRandom() {
        return new Random(EXPORT_SEED);
    }

    /**
     * 100 always plays. 0 never plays. Values in between draw once from {@code random}.
     * A null random plays every step that is not 0, which is how a one-pass preview skips the roll.
     */
    public static boolean plays(int probability, Random random) {
        if (probability <= 0) {
            return false;
        }
        if (probability >= 100 || random == null) {
            return true;
        }
        return random.nextInt(100) < probability;
    }
}
