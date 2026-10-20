package hits;

/**
 * Where one hit starts and ends on the existing 96 PPQ grid.
 * A straight 16th stays {@link SequenceBuilder#STEP_TICKS} ticks. Nudge is a percent of that
 * straight step, added after swing, so the golden dumps for un-nudged beats stay put.
 */
final class NoteTiming {
    private NoteTiming() {}

    /** A note-on and the note-off that ends it. {@code end} is exclusive. */
    record Sound(long start, long end) {}

    /** Ticks added to a step for {@code percent} of one straight step. */
    static long offsetTicks(int percent) {
        return Math.round(SequenceBuilder.STEP_TICKS * (Step.clampNudge(percent) / 100.0));
    }

    /**
     * Tick of step {@code index} measured from the start of a pattern.
     * Indexes past {@code stepCount} continue into the following patterns, and each of those
     * restarts swing the way the song chain does. The tick of {@code stepCount} itself is
     * {@code sectionTicks}, which is the boundary the last one-step note already uses.
     */
    static long gridTick(int index, int stepCount, int swing, long sectionTicks) {
        if (index < stepCount) {
            return SequenceBuilder.tickForStep(index, swing);
        }
        int span = Math.max(1, stepCount);
        int beyond = index - span;
        int later = beyond / span;
        int local = beyond % span;
        return sectionTicks * (later + 1L) + SequenceBuilder.tickForStep(local, swing);
    }

    /**
     * Start tick of one step. {@code wrapStart} is pattern playback: a negative nudge on step 0
     * moves to the end of this loop. Song and export leave the start in the previous section,
     * and clamp it at tick 0.
     */
    static long startTick(
        int step,
        int stepCount,
        int swing,
        int nudgePercent,
        long origin,
        long sectionTicks,
        boolean wrapStart
    ) {
        long local = gridTick(step, stepCount, swing, sectionTicks) + offsetTicks(nudgePercent);
        if (wrapStart && local < 0) {
            return origin + sectionTicks + local;
        }
        long start = origin + local;
        return start < 0 ? 0 : start;
    }

    /**
     * Places one hit.
     * Length counts steps from {@code step}. The gate shortens only the last of those steps.
     * {@code tieAt} ends the note at that tick instead (legato into the next hit). Pass {@code -1}
     * to use the length. {@code wrapStart} wraps a start that falls before the section.
     * {@code clipTick} cuts the note-off so a loop does not leave the note hanging. Pass {@code -1}
     * to let a long note cross a section boundary or run past the file.
     * A wrapped start keeps its unwrapped duration and then stops at the clip, so the note-off
     * is never left on the far side of the loop.
     */
    static Sound place(
        int step,
        int stepCount,
        int swing,
        int nudgePercent,
        int lengthSteps,
        int gatePercent,
        long tieAt,
        long origin,
        long sectionTicks,
        boolean wrapStart,
        long clipTick
    ) {
        long localStart = gridTick(step, stepCount, swing, sectionTicks) + offsetTicks(nudgePercent);
        long naturalStart = origin + localStart;
        long naturalEnd = tieAt >= 0
            ? tieAt
            : gatedEnd(step, lengthSteps, stepCount, swing, gatePercent, origin, sectionTicks);
        long start = naturalStart;
        long end = naturalEnd;
        if (wrapStart && localStart < 0) {
            long duration = Math.max(1, naturalEnd - naturalStart);
            start = origin + sectionTicks + localStart;
            end = start + duration;
        } else if (start < 0) {
            start = 0;
        }
        if (wrapStart && start >= origin + sectionTicks) {
            start = origin + sectionTicks - 1;
        }
        if (clipTick >= 0 && end > clipTick) {
            end = clipTick;
        }
        if (end <= start) {
            if (clipTick >= 0 && start + 1 > clipTick) {
                return null;
            }
            end = start + 1;
        }
        return new Sound(start, end);
    }

    private static long gatedEnd(
        int step,
        int lengthSteps,
        int stepCount,
        int swing,
        int gatePercent,
        long origin,
        long sectionTicks
    ) {
        int length = Step.clampLength(lengthSteps);
        int last = step + length - 1;
        long lastStart = origin + gridTick(last, stepCount, swing, sectionTicks);
        long boundary = origin + gridTick(last + 1, stepCount, swing, sectionTicks);
        long piece = Math.max(1, boundary - lastStart);
        int gate = Math.max(5, Math.min(100, gatePercent));
        return lastStart + Math.max(1, Math.round(piece * (gate / 100.0)));
    }
}
