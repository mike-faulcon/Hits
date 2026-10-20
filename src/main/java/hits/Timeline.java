package hits;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each pattern sits in a loop or a song. Sections can have different lengths.
 * A note's swing is resolved inside its own section, so a short pattern does not
 * shift the pattern that follows it.
 */
public final class Timeline {
    /** One play of one pattern. {@code start} and {@code ticks} are in MIDI ticks. */
    public record Section(char slot, int steps, long start, long ticks) {}

    private Timeline() {}

    /** The pattern on screen, once. */
    public static List<Section> pattern(Beat beat) {
        char slot = beat.activeSlot();
        return List.of(section(slot, beat.stepCount(slot), 0));
    }

    /**
     * One pass of the chain. Each repeat is its own section.
     * An empty chain is the active pattern once, which is what playback does.
     */
    public static List<Section> song(Beat beat) {
        if (beat == null || !beat.hasChain()) {
            return beat == null ? List.of() : pattern(beat);
        }
        List<Section> sections = new ArrayList<>();
        long cursor = 0;
        for (Chain.Part part : beat.chain()) {
            int steps = beat.stepCount(part.slot());
            long length = SequenceBuilder.loopTicks(steps);
            for (int i = 0; i < part.repeats(); i++) {
                sections.add(new Section(part.slot(), steps, cursor, length));
                cursor += length;
            }
        }
        return List.copyOf(sections);
    }

    /**
     * Export order. Song uses the chain. Otherwise A then B, or the active pattern,
     * including repeats. Each section uses that pattern's own length.
     */
    public static List<Section> export(Beat beat, ExportOptions options) {
        ExportOptions chosen = options == null ? ExportOptions.matchingPlayback() : options;
        if (chosen.song() && beat.hasChain()) {
            return song(beat);
        }
        List<Section> sections = new ArrayList<>();
        long cursor = 0;
        int count = chosen.sections();
        for (int i = 0; i < count; i++) {
            char slot = chosen.slotAt(i, beat.activeSlot());
            int steps = beat.stepCount(slot);
            long length = SequenceBuilder.loopTicks(steps);
            sections.add(new Section(slot, steps, cursor, length));
            cursor += length;
        }
        return List.copyOf(sections);
    }

    public static long ticks(List<Section> sections) {
        if (sections == null || sections.isEmpty()) {
            return 0;
        }
        Section last = sections.get(sections.size() - 1);
        return last.start() + last.ticks();
    }

    /**
     * Slot, step, and section for {@code tick}, wrapping so the song loops.
     * The step is measured from the start of its own section.
     */
    public static Chain.Place place(List<Section> sections, long tick, int swing) {
        long total = ticks(sections);
        if (sections == null || sections.isEmpty() || total <= 0) {
            return new Chain.Place('a', 0, 0);
        }
        long wrapped = Math.floorMod(tick, total);
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            long end = section.start() + section.ticks();
            if (wrapped < end || i == sections.size() - 1) {
                long offset = wrapped - section.start();
                int step = SequenceBuilder.stepForTick(offset, section.steps(), swing);
                return new Chain.Place(section.slot(), step, i);
            }
        }
        return new Chain.Place('a', 0, 0);
    }

    private static Section section(char slot, int steps, long start) {
        return new Section(slot, steps, start, SequenceBuilder.loopTicks(steps));
    }
}
