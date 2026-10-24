package hits;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-row tone, applied to PCM before it goes into the sample bank.
 * Gervill has no MIDI EQ. The stages, each skipped when it is off, run in this order:
 * <ol>
 * <li>invert, for a sample row only, before this class sees the PCM</li>
 * <li>low cut, a Butterworth high-pass, 12 or 24 dB/oct, −3 dB at the corner</li>
 * <li>EQ bands, peaking biquads, in band order</li>
 * <li>high cut, a Butterworth low-pass at 6, 12, or 24 dB/oct, −3 dB at the corner.
 * 12 is the default</li>
 * <li>presence, a high shelf above about 3 kHz</li>
 * <li>gain compensation: {@link #fit} scales a peak into 16-bit, and the SoundFont
 * attenuation puts that level back. A recorded drum or note is also scaled by the
 * capture playback gain before the fit, so the passband matches the built-in sound.
 * A sample row adds its own gain in that same attenuation.</li>
 * </ol>
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
    public static short[] apply(short[] samples, int rate, int hz, int slope, double presenceDb) {
        return apply(samples, rate, hz, slope, List.of(), 0, presenceDb);
    }

    /**
     * {@link #process} written back to 16-bit. Returns {@code samples} itself when
     * every stage is off.
     */
    public static short[] apply(
        short[] samples,
        int rate,
        int hz,
        int slope,
        List<EqBand> bands,
        int highCutHz,
        double presenceDb
    ) {
        if (samples == null || samples.length == 0) {
            return samples == null ? new short[0] : samples;
        }
        if (flat(hz, bands, highCutHz, presenceDb)) {
            return samples;
        }
        double[] audio = process(samples, rate, hz, slope, bands, highCutHz, Track.HIGH_CUT_SLOPE, presenceDb);
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
    public static double[] process(short[] samples, int rate, int hz, int slope, double presenceDb) {
        return process(samples, rate, hz, slope, List.of(), 0, presenceDb);
    }

    /** Low cut, EQ bands, high cut, then presence. See the class note for the full order. */
    public static double[] process(short[] samples, int rate, Track track) {
        int hz = track == null ? 0 : track.lowCutHz();
        int slope = track == null ? Track.LOW_CUT_SLOPE : track.lowCutSlope();
        List<EqBand> bands = track == null ? List.of() : track.eqBands();
        int highCut = track == null ? 0 : track.highCutHz();
        int highSlope = track == null ? Track.HIGH_CUT_SLOPE : track.highCutSlope();
        double presence = track == null ? 0 : track.presence();
        return process(samples, rate, hz, slope, bands, highCut, highSlope, presence);
    }

    public static double[] process(
        short[] samples,
        int rate,
        int hz,
        int slope,
        List<EqBand> bands,
        int highCutHz,
        double presenceDb
    ) {
        return process(samples, rate, hz, slope, bands, highCutHz, Track.HIGH_CUT_SLOPE, presenceDb);
    }

    /** Low cut, EQ, high cut at {@code highCutSlope} dB/oct, then presence. */
    public static double[] process(
        short[] samples,
        int rate,
        int hz,
        int slope,
        List<EqBand> bands,
        int highCutHz,
        int highCutSlope,
        double presenceDb
    ) {
        int length = samples == null ? 0 : samples.length;
        double[] audio = new double[length];
        for (int i = 0; i < length; i++) {
            audio[i] = samples[i];
        }
        for (Section section : sections(rate, hz, slope, bands, highCutHz, highCutSlope, presenceDb)) {
            section.process(audio);
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

    /** Peaking magnitude at {@code freqHz}, in dB. The centre is {@code gainDb}. */
    public static double peakingDb(int rate, double centerHz, double gainDb, double q, double freqHz) {
        return Section.peaking(rate, clampHz(rate, centerHz), gainDb, Math.max(0.05, q)).magnitudeDb(freqHz);
    }

    /** 12 dB/oct low-pass magnitude at {@code freqHz}, in dB. The corner is about −3 dB. */
    public static double lowPassDb(int rate, double cornerHz, double freqHz) {
        return lowPassDb(rate, cornerHz, Track.HIGH_CUT_SLOPE, freqHz);
    }

    /**
     * Low-pass magnitude at {@code freqHz}, in dB.
     * {@code slope} is 6, 12, or 24 dB/oct. The corner is about −3 dB.
     */
    public static double lowPassDb(int rate, double cornerHz, int slope, double freqHz) {
        double db = 0;
        for (Section section : lowPass(rate, cornerHz, slope)) {
            db += section.magnitudeDb(freqHz);
        }
        return db;
    }

    /**
     * Bandwidth in octaves of a peaking band with this Q, measured between the
     * half-gain points. {@code Q = 1 / (2 sinh(ln(2)/2 · BW))}.
     */
    public static double bandwidthOctaves(double q) {
        double safe = Math.max(0.05, q);
        double x = 1.0 / (2.0 * safe);
        return Math.log(x + Math.sqrt(x * x + 1.0)) * 2.0 / Math.log(2.0);
    }

    /**
     * Combined low cut, EQ, high cut, and presence at {@code freqHz}, in dB.
     * 0 dB when the row is flat.
     */
    public static double responseDb(int rate, Track track, double freqHz) {
        if (track == null) {
            return 0;
        }
        double db = 0;
        for (Section section : sections(
            rate,
            track.lowCutHz(),
            track.lowCutSlope(),
            track.eqBands(),
            track.highCutHz(),
            track.highCutSlope(),
            track.presence()
        )) {
            db += section.magnitudeDb(freqHz);
        }
        return db;
    }

    private static boolean flat(int hz, List<EqBand> bands, int highCutHz, double presenceDb) {
        if (hz > 0 || highCutHz > 0 || presenceDb > 0) {
            return false;
        }
        if (bands == null) {
            return true;
        }
        for (EqBand band : bands) {
            if (band != null && band.active()) {
                return false;
            }
        }
        return true;
    }

    private static List<Section> sections(
        int rate,
        int lowCutHz,
        int slope,
        List<EqBand> bands,
        int highCutHz,
        int highCutSlope,
        double presenceDb
    ) {
        List<Section> chain = new ArrayList<>();
        if (lowCutHz > 0) {
            for (Section section : highPass(rate, lowCutHz, slope)) {
                chain.add(section);
            }
        }
        if (bands != null) {
            for (EqBand band : bands) {
                if (band != null && band.active()) {
                    chain.add(Section.peaking(
                        rate,
                        clampHz(rate, band.hz()),
                        band.gain(),
                        Math.max(0.05, band.q())
                    ));
                }
            }
        }
        if (highCutHz > 0) {
            for (Section section : lowPass(rate, highCutHz, highCutSlope)) {
                chain.add(section);
            }
        }
        if (presenceDb > 0) {
            chain.add(highShelf(rate, Track.PRESENCE_HZ, presenceDb));
        }
        return chain;
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

    /** 6 dB/oct is one real pole. 24 dB/oct is two Butterworth sections. Anything else is 12. */
    private static Section[] lowPass(int rate, double cornerHz, int slope) {
        double hz = clampHz(rate, cornerHz);
        if (slope <= Track.HIGH_CUT_GENTLE) {
            return new Section[] {Section.lowPassFirst(rate, hz)};
        }
        if (slope >= Track.HIGH_CUT_STEEP) {
            return new Section[] {
                Section.lowPass(rate, hz, 1.3065629648763766),
                Section.lowPass(rate, hz, 0.541196100146197)
            };
        }
        return new Section[] {Section.lowPass(rate, hz, 1 / Math.sqrt(2))};
    }

    private static Section highShelf(int rate, int shelfHz, double gainDb) {
        return Section.highShelf(rate, clampHz(rate, shelfHz), gainDb);
    }

    private static double clampHz(int rate, double hz) {
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

        /** First-order Butterworth low-pass. −3 dB at {@code hz}, about 6 dB down an octave later. */
        static Section lowPassFirst(int rate, double hz) {
            double k = Math.tan(Math.PI * hz / rate);
            double norm = 1.0 / (1.0 + k);
            double b0 = k * norm;
            return new Section(rate, b0, b0, 0, (k - 1.0) * norm, 0);
        }

        static Section lowPass(int rate, double hz, double q) {
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double sin = Math.sin(w);
            double alpha = sin / (2 * q);
            double b0 = (1 - cos) / 2;
            double b1 = 1 - cos;
            double b2 = (1 - cos) / 2;
            double a0 = 1 + alpha;
            return new Section(rate, b0 / a0, b1 / a0, b2 / a0, (-2 * cos) / a0, (1 - alpha) / a0);
        }

        /** Audio EQ Cookbook peaking EQ. {@code gainDb} is the centre gain. */
        static Section peaking(int rate, double hz, double gainDb, double q) {
            double a = Math.pow(10, gainDb / 40.0);
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double sin = Math.sin(w);
            double alpha = sin / (2 * q);
            double b0 = 1 + alpha * a;
            double b1 = -2 * cos;
            double b2 = 1 - alpha * a;
            double a0 = 1 + alpha / a;
            double a1 = -2 * cos;
            double a2 = 1 - alpha / a;
            return new Section(rate, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
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
