package hits;

import java.util.ArrayDeque;
import java.util.OptionalInt;

/**
 * Tempo from a run of taps. The BPM is the average of the recent gaps.
 * A pause longer than {@link #RESET_MILLIS} starts a new run.
 * The result stays inside {@link Beat#MIN_BPM}–{@link Beat#MAX_BPM}.
 */
public final class TapTempo {
    /** A gap longer than this is a pause, not a slow tempo. Two seconds is slower than 40 BPM. */
    public static final long RESET_MILLIS = 2_000;
    /** How many gaps stay in the average. The oldest drops off once this fills. */
    public static final int RECENT_GAPS = 4;

    private final ArrayDeque<Long> times = new ArrayDeque<>();

    /**
     * Records a tap at {@code nowMillis}. Empty until two taps in the same run
     * have a gap. A tap at the same instant as the last one is ignored.
     */
    public OptionalInt tap(long nowMillis) {
        if (!times.isEmpty()) {
            long gap = nowMillis - times.peekLast();
            if (gap > RESET_MILLIS) {
                times.clear();
            } else if (gap <= 0) {
                return average();
            }
        }
        times.addLast(nowMillis);
        while (times.size() > RECENT_GAPS + 1) {
            times.removeFirst();
        }
        return average();
    }

    private OptionalInt average() {
        if (times.size() < 2) {
            return OptionalInt.empty();
        }
        long span = times.peekLast() - times.peekFirst();
        int gaps = times.size() - 1;
        if (span <= 0) {
            return OptionalInt.empty();
        }
        int bpm = (int) Math.round(60_000.0 * gaps / span);
        return OptionalInt.of(Beat.clampBpm(bpm));
    }
}
