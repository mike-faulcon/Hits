package hits;

import java.util.ArrayList;
import java.util.List;

/**
 * A song is an ordered list of A/B entries. Each entry plays one pattern slot
 * {@code repeats} times. The chain is not a timeline: every entry uses the beat's
 * length, tempo, and swing.
 */
public final class Chain {
    public static final int MAX_PARTS = 32;

    private Chain() {}

    /** One slot in the chain. Repeats are clamped to {@link ExportOptions#MAX_REPEATS}. */
    public record Part(char slot, int repeats) {
        public Part {
            slot = slot == 'b' ? 'b' : 'a';
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

    public static boolean usesBoth(List<Part> chain) {
        boolean a = false;
        boolean b = false;
        for (Part part : chain) {
            if (part.slot() == 'b') {
                b = true;
            } else {
                a = true;
            }
        }
        return a && b;
    }

    /** Tick length of one pass through the chain. Zero when the chain is empty. */
    public static long ticks(List<Part> chain, int stepCount) {
        return (long) sections(chain) * SequenceBuilder.loopTicks(stepCount);
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
}
