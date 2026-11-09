package hits;

/**
 * Song tempo in hundredths of a beat per minute, from {@link Beat#MIN_BPM} to {@link Beat#MAX_BPM}.
 * A whole number is the same tempo an older file stored as an integer.
 * {@code 92.5} and {@code 98.33} are exact.
 */
public final class Tempo {
    /** Smallest stored step. {@code 92.5} is {@code 9250}. */
    public static final double STEP = 0.01;
    /** Hundredths in one BPM. */
    public static final int SCALE = 100;

    private Tempo() {}

    /**
     * Nearest stored tempo. Values outside {@link Beat#MIN_BPM}–{@link Beat#MAX_BPM}
     * are pulled in. A non-finite value becomes {@link Beat#MIN_BPM}.
     */
    public static int centi(double bpm) {
        if (!Double.isFinite(bpm)) {
            return Beat.MIN_BPM * SCALE;
        }
        long rounded = Math.round(bpm * SCALE);
        long min = (long) Beat.MIN_BPM * SCALE;
        long max = (long) Beat.MAX_BPM * SCALE;
        if (rounded < min) {
            return (int) min;
        }
        if (rounded > max) {
            return (int) max;
        }
        return (int) rounded;
    }

    public static double fromCenti(int centi) {
        return centi / (double) SCALE;
    }

    /** True when {@code bpm} lands on a whole number after it is stored. */
    public static boolean whole(double bpm) {
        return Double.isFinite(bpm) && centi(bpm) % SCALE == 0;
    }

    /** True when the stored tempo needs a decimal. Non-finite values are not fractional. */
    public static boolean fractional(double bpm) {
        return Double.isFinite(bpm) && centi(bpm) % SCALE != 0;
    }

    /**
     * {@code 96}, {@code 92.5}, {@code 98.33}, {@code 40.01}.
     * A whole number has no decimal, so an older file still writes {@code 96}.
     */
    public static String format(double bpm) {
        int value = centi(bpm);
        int whole = value / SCALE;
        int frac = Math.abs(value % SCALE);
        if (frac == 0) {
            return Integer.toString(whole);
        }
        if (frac % 10 == 0) {
            return whole + "." + (frac / 10);
        }
        if (frac < 10) {
            return whole + ".0" + frac;
        }
        return whole + "." + frac;
    }

    /**
     * Microseconds per quarter for a MIDI tempo meta event.
     * A whole-number tempo keeps the older truncating division, so those files
     * stay byte-identical. A fractional tempo rounds to the nearest microsecond.
     */
    public static int microsecondsPerQuarter(double bpm) {
        int value = centi(bpm);
        if (value % SCALE == 0) {
            return 60_000_000 / (value / SCALE);
        }
        return (int) Math.round(60_000_000.0 * SCALE / value);
    }

    /**
     * Microseconds from tick 0 to {@code tick} at a constant tempo.
     * The position is one rounding of the absolute tick, not a sum of rounded steps.
     * A whole-number tempo uses the integer meta-event clock.
     */
    public static long microsecondsAt(long tick, double bpm, int ppq) {
        if (tick <= 0) {
            return 0;
        }
        int resolution = Math.max(1, ppq);
        int value = centi(bpm);
        if (value % SCALE == 0) {
            long usPerQuarter = 60_000_000L / (value / SCALE);
            return tick * usPerQuarter / resolution;
        }
        return Math.round(tick * (60_000_000.0 * SCALE) / (value * (double) resolution));
    }
}
