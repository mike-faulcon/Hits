package hits;

import java.util.ArrayList;
import java.util.List;

/**
 * A song is an ordered list of pattern entries. Each entry plays one pattern,
 * A through H, {@code repeats} times. Every entry uses the beat's tempo and swing.
 * The length is that pattern's own step count.
 */
public final class Chain {
    public static final int MAX_PARTS = 32;

    private Chain() {}

    /** One slot in the chain. Repeats are clamped to {@link ExportOptions#MAX_REPEATS}. */
    public record Part(char slot, int repeats) {
        public Part {
            slot = Beat.normalizeSlot(slot);
            repeats = Math.max(1, Math.min(ExportOptions.MAX_REPEATS, repeats));
        }
    }

    /** Where a tick falls while the chain loops. */
    public record Place(char slot, int step, int section) {}

    public static List<Part> normalize(List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return List.of();
        }
        List<Part> copy = new ArrayList<>();
        for (Part part : parts) {
            if (part == null || copy.size() == MAX_PARTS) {
                break;
            }
            copy.add(new Part(part.slot(), part.repeats()));
        }
        return List.copyOf(copy);
    }

    public static int sections(List<Part> chain) {
        int total = 0;
        for (Part part : chain) {
            total += part.repeats();
        }
        return total;
    }

    public static char slotAt(List<Part> chain, int section) {
        int cursor = 0;
        for (Part part : chain) {
            if (section < cursor + part.repeats()) {
                return part.slot();
            }
            cursor += part.repeats();
        }
        return chain.isEmpty() ? 'a' : chain.get(chain.size() - 1).slot();
    }

    /** Patterns named by the chain, in letter order, with duplicates removed. */
    public static List<Character> distinctSlots(List<Part> chain) {
        boolean[] seen = new boolean[Beat.SLOT_COUNT];
        if (chain != null) {
            for (Part part : chain) {
                if (part != null) {
                    seen[Beat.slotIndex(part.slot())] = true;
                }
            }
        }
        List<Character> slots = new ArrayList<>();
        for (int i = 0; i < Beat.SLOT_COUNT; i++) {
            if (seen[i]) {
                slots.add(Beat.slotLetter(i));
            }
        }
        return List.copyOf(slots);
    }

    /** True when the chain names more than one pattern. */
    public static boolean usesBoth(List<Part> chain) {
        return distinctSlots(chain).size() > 1;
    }

    /** Tick length of one pass when every pattern has the same {@code stepCount}. Zero when the chain is empty. */
    public static long ticks(List<Part> chain, int stepCount) {
        return (long) sections(chain) * SequenceBuilder.loopTicks(stepCount);
    }

    /** Tick length of one pass. Each entry uses that pattern's own length. An empty chain is the active pattern. */
    public static long ticks(Beat beat) {
        return Timeline.ticks(Timeline.song(beat));
    }

    /** How many sections one pass contains. An empty chain counts as the active pattern once. */
    public static int sections(Beat beat) {
        return Timeline.song(beat).size();
    }

    /**
     * Slot, step, and section for {@code tick}, wrapping past the end so the song loops.
     * An empty chain reports slot A and the step inside one pattern.
     */
    public static Place place(List<Part> chain, long tick, int stepCount, int swing) {
        long sectionTicks = SequenceBuilder.loopTicks(stepCount);
        int count = sections(chain);
        if (count == 0 || sectionTicks <= 0) {
            return new Place('a', SequenceBuilder.stepForTick(tick, stepCount, swing), 0);
        }
        long wrapped = Math.floorMod(tick, sectionTicks * count);
        int section = (int) (wrapped / sectionTicks);
        long offset = wrapped - (long) section * sectionTicks;
        return new Place(slotAt(chain, section), SequenceBuilder.stepForTick(offset, stepCount, swing), section);
    }

    /**
     * Slot, step, and section for {@code tick} in {@code beat}'s chain.
     * An empty chain is the active pattern. The tick wraps so the song loops.
     */
    public static Place place(Beat beat, long tick) {
        if (beat == null) {
            return new Place('a', 0, 0);
        }
        return Timeline.place(Timeline.song(beat), tick, beat.swing());
    }
}
