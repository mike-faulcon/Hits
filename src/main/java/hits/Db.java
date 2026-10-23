package hits;

import java.util.Locale;

/**
 * Decibel and other 0.1-step values. Whole numbers format without a decimal,
 * so a beat that stores {@code 6} still saves {@code 6}.
 */
public final class Db {
    public static final double STEP = 0.1;

    private Db() {}

    /** Nearest tenth. {@code 3.64} is {@code 36}, {@code -12} is {@code -120}. */
    public static int tenths(double value) {
        return (int) Math.round(value / STEP);
    }

    public static double fromTenths(int tenths) {
        return tenths * STEP;
    }

    /** Nearest tenth inside {@code min}..{@code max}. */
    public static double clamp(double value, double min, double max) {
        double limited = Math.max(min, Math.min(max, value));
        return fromTenths(tenths(limited));
    }

    /** True when {@code value} is already a tenth, within a rounding tolerance. */
    public static boolean onStep(double value) {
        double scaled = value / STEP;
        return Math.abs(scaled - Math.rint(scaled)) < 1e-6;
    }

    /**
     * {@code 6}, {@code -12}, {@code 3.6}. Whole tenths drop the decimal
     * so an integer stored by an older Hits is written the same way.
     */
    public static String number(double value) {
        int tenths = tenths(value);
        if (tenths % 10 == 0) {
            return Integer.toString(tenths / 10);
        }
        int abs = Math.abs(tenths);
        String text = (abs / 10) + "." + (abs % 10);
        return tenths < 0 ? "-" + text : text;
    }

    /** {@code +6 dB}, {@code -3.5 dB}, {@code 0 dB}. */
    public static String labeled(double value) {
        String text = number(value);
        if (tenths(value) > 0) {
            return "+" + text + " dB";
        }
        return text + " dB";
    }

    /** The number as it was written, without a trailing {@code .0}. */
    public static String exact(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        double nearest = Math.rint(value);
        if (Math.abs(value - nearest) < 1e-9) {
            return Long.toString((long) nearest);
        }
        String text = String.format(Locale.ROOT, "%.4f", value);
        int end = text.length();
        while (end > 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }
}
