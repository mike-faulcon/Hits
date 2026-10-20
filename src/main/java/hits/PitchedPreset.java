package hits;

/**
 * A drum-row sound the built-in bank cannot play as a kit key.
 * The row stays a drum. Playback sends a melodic program and this pitch,
 * so a woodblock click, a snare crack, or a rimshot does not need its own Note track.
 * A step pitch still replaces {@link #note()}.
 */
public enum PitchedPreset {
    /** Woodblock, G5. A short click around 800 Hz. */
    CLICK("click", "Woodblock click", 115, 91),
    /** Timpani, E5. The crack the kit snare does not have, around 1.3 kHz. */
    CRACK("crack", "Snare crack", 47, 88),
    /** Xylophone, C7. A bright tick above 3 kHz. The short drum gate cuts the ring. */
    RIM("rim", "Rimshot", 13, 108);

    private final String json;
    private final String label;
    private final int program;
    private final int note;

    PitchedPreset(String json, String label, int program, int note) {
        this.json = json;
        this.label = label;
        this.program = program;
        this.note = note;
    }

    public String json() {
        return json;
    }

    public String label() {
        return label;
    }

    public int program() {
        return program;
    }

    public int note() {
        return note;
    }

    /** Known {@code click}, {@code crack}, or {@code rim}. Anything else is ignored. */
    public static PitchedPreset parse(String json) {
        if (json == null) {
            return null;
        }
        for (PitchedPreset preset : values()) {
            if (preset.json.equals(json)) {
                return preset;
            }
        }
        return null;
    }
}
