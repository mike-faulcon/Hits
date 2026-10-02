package hits;

/** Note names use C1 for MIDI 36, which is the usual kick. */
public final class Notes {
    private static final String[] NAMES = {
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private Notes() {}

    public static int clamp(int note) {
        return Math.max(0, Math.min(127, note));
    }

    public static String name(int note) {
        int midi = clamp(note);
        int octave = midi / 12 - 2;
        return NAMES[midi % 12] + octave;
    }
}
