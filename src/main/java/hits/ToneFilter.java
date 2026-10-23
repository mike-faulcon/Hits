package hits;

/**
 * Per-row high-pass and an optional high shelf, applied to PCM before it goes into the
 * sample bank. Gervill has no MIDI high-pass. Butterworth sections are −3 dB at the corner:
 * one section is 12 dB/oct, two are 24 dB/oct.
 */
public final class ToneFilter {
    private ToneFilter() {}

    /**
     * PCM scaled into 16-bit, and the decibels to add back so playback stays at the
     * unscaled level. {@code makeupDb} is 0 when nothing exceeded the range.
     */
    public record Fitted(short[] samples, double makeupDb) {}

    /**
     * High-pass, then a high shelf when {@code presenceDb} is above 0.
     * Returns {@code samples} itself when both are off, so a plain row is not copied.
     * Samples outside 16-bit are clamped. {@link #fit(double[])} keeps that overshoot instead.
     */
    public static short[] apply(short[] samples, int rate, int hz, int slope, int presenceDb) {
        if (samples == null || samples.length == 0) {
            return samples == null ? new short[0] : samples;
        }
        if (hz <= 0 && presenceDb <= 0) {
            return samples;
        }
        double[] audio = process(samples, rate, hz, slope, presenceDb);
        short[] out = new short[audio.length];
        for (int i = 0; i < audio.length; i++) {
            out[i] = clamp(audio[i]);
        }
        return out;
    }

    /**
     * The same filter as {@link #apply}, left in floating point so a later stage can
     * scale the peak into 16-bit instead of flattening it.
     */
    public static double[] process(short[] samples, int rate, int hz, int slope, int presenceDb) {
        int length = samples == null ? 0 : samples.length;
        double[] audio = new double[length];
        for (int i = 0; i < length; i++) {
            audio[i] = samples[i];
        }
        if (hz > 0) {
            for (Section section : highPass(rate, hz, slope)) {
                section.process(audio);
            }
        }
        if (presenceDb > 0) {
            highShelf(rate, Track.PRESENCE_HZ, presenceDb).process(audio);
        }
        return audio;
    }

    /**
     * Scales {@code audio} so the peak fits in 16-bit. The returned makeup is the
     * decibel boost that puts playback back at the unscaled level.
     */
    public static Fitted fit(double[] audio) {
        if (audio == null || audio.length == 0) {
            return new Fitted(new short[0], 0);
        }
        double peak = 0;
        for (double sample : audio) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double scale = 1;
        double makeup = 0;
        if (peak > 32767) {
            scale = 32767.0 / peak;
            makeup = 20.0 * Math.log10(peak / 32767.0);
        }
        short[] out = new short[audio.length];
        for (int i = 0; i < audio.length; i++) {
            out[i] = clamp(audio[i] * scale);
        }
        return new Fitted(out, makeup);
    }

    /** High-pass magnitude at {@code freqHz}, in dB. 0 dB is the passband. */
    public static double highPassDb(int rate, int cornerHz, int slope, double freqHz) {
        double db = 0;
        for (Section section : highPass(rate, cornerHz, slope)) {
            db += section.magnitudeDb(freqHz);
        }
        return db;
    }

    /** High-shelf magnitude at {@code freqHz}, in dB. The shelf is centered near {@code shelfHz}. */
    public static double shelfDb(int rate, int shelfHz, double gainDb, double freqHz) {
        return highShelf(rate, shelfHz, gainDb).magnitudeDb(freqHz);
    }

    private static Section[] highPass(int rate, int cornerHz, int slope) {
        double hz = clampHz(rate, cornerHz);
        if (slope >= Track.LOW_CUT_STEEP) {
            return new Section[] {
                Section.highPass(rate, hz, 1.3065629648763766),
                Section.highPass(rate, hz, 0.541196100146197)
            };
        }
        return new Section[] {Section.highPass(rate, hz, 1 / Math.sqrt(2))};
    }

    private static Section highShelf(int rate, int shelfHz, double gainDb) {
        return Section.highShelf(rate, clampHz(rate, shelfHz), gainDb);
    }

    private static double clampHz(int rate, int hz) {
        double nyquist = Math.max(2, rate) * 0.45;
        return Math.max(1, Math.min(hz, nyquist));
    }

    private static short clamp(double sample) {
        if (sample > 32767) {
            return 32767;
        }
        if (sample < -32768) {
            return -32768;
        }
        return (short) Math.round(sample);
    }

    /** Direct form II transposed. Coefficients are normalized so a0 is 1. */
    private static final class Section {
        private final int rate;
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double z1;
        private double z2;

        private Section(int rate, double b0, double b1, double b2, double a1, double a2) {
            this.rate = rate;
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        static Section highPass(int rate, double hz, double q) {
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double sin = Math.sin(w);
            double alpha = sin / (2 * q);
            double b0 = (1 + cos) / 2;
            double b1 = -(1 + cos);
            double b2 = (1 + cos) / 2;
            double a0 = 1 + alpha;
            return new Section(rate, b0 / a0, b1 / a0, b2 / a0, (-2 * cos) / a0, (1 - alpha) / a0);
        }

        static Section highShelf(int rate, double hz, double gainDb) {
            double a = Math.pow(10, gainDb / 40.0);
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double sin = Math.sin(w);
            // Shelf slope S = 1. The (A + 1/A) term drops out, leaving √2.
            double alpha = sin / 2 * Math.sqrt(2);
            double sqrtA = Math.sqrt(a);
            double twoSqrtAAlpha = 2 * sqrtA * alpha;
            double b0 = a * ((a + 1) + (a - 1) * cos + twoSqrtAAlpha);
            double b1 = -2 * a * ((a - 1) + (a + 1) * cos);
            double b2 = a * ((a + 1) + (a - 1) * cos - twoSqrtAAlpha);
            double a0 = (a + 1) - (a - 1) * cos + twoSqrtAAlpha;
            double a1 = 2 * ((a - 1) - (a + 1) * cos);
            double a2 = (a + 1) - (a - 1) * cos - twoSqrtAAlpha;
            return new Section(rate, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
        }

        void process(double[] audio) {
            for (int i = 0; i < audio.length; i++) {
                double x = audio[i];
                double y = b0 * x + z1;
                z1 = b1 * x - a1 * y + z2;
                z2 = b2 * x - a2 * y;
                audio[i] = y;
            }
        }

        double magnitudeDb(double freqHz) {
            double w = 2 * Math.PI * freqHz / rate;
            double cos1 = Math.cos(w);
            double sin1 = Math.sin(w);
            double cos2 = Math.cos(2 * w);
            double sin2 = Math.sin(2 * w);
            double numRe = b0 + b1 * cos1 + b2 * cos2;
            double numIm = -b1 * sin1 - b2 * sin2;
            double denRe = 1 + a1 * cos1 + a2 * cos2;
            double denIm = -a1 * sin1 - a2 * sin2;
            double mag = Math.hypot(numRe, numIm) / Math.hypot(denRe, denIm);
            if (mag <= 1e-12) {
                return -120;
            }
            return 20 * Math.log10(mag);
        }
    }
}
