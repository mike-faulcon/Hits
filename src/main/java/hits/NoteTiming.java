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
     * Indexes past {@code stepCount} continue into {@code ahead}, each entry being the next
     * section's own step count, and each of those restarts swing. After the known sections,
     * the last length repeats so a note can finish past the file. An empty {@code ahead}
     * repeats this section, which is what one looping pattern already did.
     * The tick of {@code stepCount} itself is {@code sectionTicks}.
     */
    static long gridTick(int index, int stepCount, int swing, long sectionTicks, int[] ahead) {
        if (index < stepCount) {
            return SequenceBuilder.tickForStep(index, swing);
        }
        long cursor = sectionTicks;
        int remaining = index - Math.max(1, stepCount);
        int span = Math.max(1, stepCount);
        if (ahead != null) {
            for (int next : ahead) {
                int count = Math.max(1, next);
                if (remaining < count) {
                    return cursor + SequenceBuilder.tickForStep(remaining, swing);
                }
                cursor += SequenceBuilder.loopTicks(count);
                remaining -= count;
                span = count;
            }
        }
        int later = remaining / span;
        int local = remaining % span;
        return cursor + (long) later * SequenceBuilder.loopTicks(span) + SequenceBuilder.tickForStep(local, swing);
    }

    /**
     * Start tick of one step. {@code wrapStart} is pattern playback: a nudge that falls outside
     * the loop comes back in at the other end. Song and export leave an early start in the
     * previous section, and clamp it at tick 0. The section start already comes from each
     * pattern's own length.
     */
    static long startTick(
        int step,
        int stepCount,
        int swing,
        int nudgePercent,
        long origin,
        long sectionTicks,
        boolean wrapStart,
        int[] ahead
    ) {
        long local = swungStart(step, stepCount, swing, nudgePercent, sectionTicks, ahead).local;
        if (wrapStart && local < 0) {
            return origin + sectionTicks + local;
        }
        if (wrapStart && local >= sectionTicks) {
            return origin + (local - sectionTicks);
        }
        long start = origin + local;
        return start < 0 ? 0 : start;
    }

    /**
     * Places one hit.
     * Length counts steps from {@code step}. The gate shortens only the last of those steps.
     * {@code tieAt} ends the note at that tick instead (legato into the next hit). Pass {@code -1}
     * to use the length. {@code ahead} is each following section's step count.
     * {@code wrapStart} wraps a start that falls outside the section, early or late.
     * {@code clipTick} cuts the note-off so a loop does not leave the note hanging. Pass {@code -1}
     * to let a long note cross a section boundary or run past the file.
     * A wrapped start keeps its unwrapped duration and then stops at the clip, so the note-off
     * is never left on the far side of the loop.
     * The nudge moves the note-off by the same amount as the note-on, so the hit keeps its length.
     * A legato tie is the next hit's start and is not shifted again.
     * Swing plus nudge stays strictly inside the neighbouring steps, so two hits cannot share a tick.
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
        long clipTick,
        int[] ahead
    ) {
        Swung swung = swungStart(step, stepCount, swing, nudgePercent, sectionTicks, ahead);
        long localStart = swung.local;
        long naturalStart = origin + localStart;
        long naturalEnd = tieAt >= 0
            ? tieAt
            : gatedEnd(step, lengthSteps, stepCount, swing, gatePercent, origin, sectionTicks, ahead) + swung.applied;
        long start = naturalStart;
        long end = naturalEnd;
        if (wrapStart && (localStart < 0 || localStart >= sectionTicks)) {
            long duration = Math.max(1, naturalEnd - naturalStart);
            long wrapped = localStart < 0 ? sectionTicks + localStart : localStart - sectionTicks;
            start = origin + wrapped;
            end = start + duration;
        } else if (start < 0) {
            start = 0;
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

    /**
     * Grid tick plus nudge. The nudge is then pulled back by a tick when it would land on the
     * previous or next step inside this section. The last step may still cross the section end:
     * pattern playback wraps that hit, and song playback lets it enter the next section.
     */
    private static Swung swungStart(
        int step,
        int stepCount,
        int swing,
        int nudgePercent,
        long sectionTicks,
        int[] ahead
    ) {
        long applied = offsetTicks(nudgePercent);
        long local = gridTick(step, stepCount, swing, sectionTicks, ahead) + applied;
        if (step + 1 < stepCount) {
            long next = gridTick(step + 1, stepCount, swing, sectionTicks, ahead);
            if (local >= next) {
                applied -= local - (next - 1);
                local = next - 1;
            }
        }
        if (step > 0) {
            long previous = gridTick(step - 1, stepCount, swing, sectionTicks, ahead);
            if (local <= previous) {
                applied += (previous + 1) - local;
                local = previous + 1;
            }
        }
        return new Swung(local, applied);
    }

    /** {@code local} is the start measured from the section. {@code applied} is the tick offset that moved both ends. */
    private record Swung(long local, long applied) {}

    private static long gatedEnd(
        int step,
        int lengthSteps,
        int stepCount,
        int swing,
        int gatePercent,
        long origin,
        long sectionTicks,
        int[] ahead
    ) {
        int length = Step.clampLength(lengthSteps);
        int last = step + length - 1;
        long lastStart = origin + gridTick(last, stepCount, swing, sectionTicks, ahead);
        long boundary = origin + gridTick(last + 1, stepCount, swing, sectionTicks, ahead);
        long piece = Math.max(1, boundary - lastStart);
        int gate = Math.max(5, Math.min(100, gatePercent));
        return lastStart + Math.max(1, Math.round(piece * (gate / 100.0)));
    }
}
