package hits;

/** What an exported MIDI file contains. Playback does not use this. */
public record ExportOptions(boolean asHeard, boolean bothSlots, int repeats) {
    public static final int MAX_REPEATS = 32;

    public ExportOptions {
        repeats = Math.max(1, Math.min(MAX_REPEATS, repeats));
    }

    /** Every track, active pattern, once. Mute and solo are ignored. */
    public static ExportOptions allTracks() {
        return new ExportOptions(false, false, 1);
    }

    /** Active pattern as it plays: muted tracks dropped, solo respected. */
    public static ExportOptions matchingPlayback() {
        return new ExportOptions(true, false, 1);
    }

    public int sections() {
        return (bothSlots ? 2 : 1) * repeats;
    }

    /** Section 0 is A when both slots are exported, then B, then A again. */
    public char slotAt(int section, char active) {
        if (!bothSlots) {
            return active == 'b' ? 'b' : 'a';
        }
        return section % 2 == 0 ? 'a' : 'b';
    }
}
