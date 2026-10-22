package hits;

import java.util.Random;

/**
 * License-free one-shots generated in code. Nothing here is downloaded or copied
 * from a recording. The ids are stored in the beat as {@code hits:808}, {@code hits:click},
 * and {@code hits:snap}.
 */
public final class BuiltinSamples {
    public static final String EIGHT_OH_EIGHT = "hits:808";
    public static final String CLICK = "hits:click";
    public static final String SNAP = "hits:snap";
    public static final int RATE = 44_100;

    private BuiltinSamples() {}

    public static boolean isBuiltin(String path) {
        return EIGHT_OH_EIGHT.equals(path) || CLICK.equals(path) || SNAP.equals(path);
    }

    public static String label(String path) {
        if (EIGHT_OH_EIGHT.equals(path)) {
            return "808";
        }
        if (CLICK.equals(path)) {
            return "Click";
        }
        if (SNAP.equals(path)) {
            return "Snap";
        }
        return "Sample";
    }

    /** File name used when the one-shot is copied next to a beat. */
    public static String fileName(String path) {
        if (EIGHT_OH_EIGHT.equals(path)) {
            return "808.wav";
        }
        if (CLICK.equals(path)) {
            return "click.wav";
        }
        if (SNAP.equals(path)) {
            return "snap.wav";
        }
        return "sample.wav";
    }

    public record Pcm(int rate, short[] samples) {}

    public static Pcm pcm(String path) {
        if (EIGHT_OH_EIGHT.equals(path)) {
            return new Pcm(RATE, eightOhEight());
        }
        if (CLICK.equals(path)) {
            return new Pcm(RATE, click());
        }
        if (SNAP.equals(path)) {
            return new Pcm(RATE, snap());
        }
        return null;
    }

    /**
     * What the built-in is, measured the way the retest measured it.
     * 808: time from the start until the wave stays under −20 dB of its peak, about 400 ms.
     * The old “406 ms ring” was the −40 dB point, and the −20 dB point was about 205 ms.
     * Click: a render's power-weighted centroid over the onset is about 1.4 kHz, not 1.7 kHz.
     * Snap: 300 Hz high-pass, power-weighted centroid from 5 ms before the onset to 70 ms after, about 2.2 kHz.
     * The unfiltered full-file centroid sits lower because of the quiet low body.
     */
    public static String description(String path) {
        if (EIGHT_OH_EIGHT.equals(path)) {
            return "Sine from 80 Hz down to 68 Hz over the first 200 ms. "
                + "The ring is the time from the start until the wave stays under −20 dB of its peak, about 400 ms.";
        }
        if (CLICK.equals(path)) {
            return "A short knock plus noise near 480 Hz and 3.4 kHz. "
                + "Rendered through the built-in synth, the power-weighted centroid over the onset is about 1.4 kHz.";
        }
        if (SNAP.equals(path)) {
            return "Noise with a quiet low body. "
                + "With a 300 Hz high-pass, the power-weighted centroid from 5 ms before the onset to 70 ms after is about 2.2 kHz.";
        }
        return "";
    }

    /**
     * Sine with a pitch drop from 80 Hz to 68 Hz over the first 200 ms.
     * The envelope time constant is 172 ms, so the wave crosses −20 dB of its peak near 400 ms
     * (last sample at or above that threshold, measured from the start).
     */
    static short[] eightOhEight() {
        int count = (int) Math.round(RATE * 0.85);
        double[] raw = new double[count];
        double phase = 0;
        double attack = 0.004;
        double tau = 0.172;
        for (int i = 0; i < count; i++) {
            double t = i / (double) RATE;
            double freq = t < 0.200 ? 80 + (68 - 80) * (t / 0.200) : 68;
            phase += 2 * Math.PI * freq / RATE;
            double env = t < attack ? t / attack : Math.exp(-(t - attack) / tau);
            raw[i] = Math.sin(phase) * env;
        }
        return normalize(raw);
    }

    /**
     * A short wood knock plus two noise bands, around 480 Hz and around 3.4 kHz.
     * Each band is two filters deep so the click is not a pure tone and not a hiss.
     * A render's power-weighted centroid over the onset is about 1.4 kHz.
     */
    static short[] click() {
        int count = (int) Math.round(RATE * 0.07);
        double[] raw = new double[count];
        Biquad lowA = bandpass(RATE, 480, 2.0);
        Biquad lowB = bandpass(RATE, 480, 2.0);
        Biquad highA = bandpass(RATE, 3400, 2.2);
        Biquad highB = bandpass(RATE, 3400, 2.2);
        Random noise = new Random(0xC11C);
        double wood = 0;
        for (int i = 0; i < count; i++) {
            double t = i / (double) RATE;
            double white = noise.nextDouble() * 2 - 1;
            double burst = Math.exp(-t / 0.012);
            double lowNoise = lowB.process(lowA.process(white)) * burst * 1.4;
            double highNoise = highB.process(highA.process(white)) * burst * 0.85;
            wood += 2 * Math.PI * 940 / RATE;
            double knock = t < 0.0008 ? t / 0.0008 : Math.exp(-(t - 0.0008) / 0.006);
            raw[i] = Math.sin(wood) * knock * 0.7 + lowNoise + highNoise;
        }
        return normalize(raw);
    }

    /**
     * Noise centred near 2.1 kHz, three filters deep, plus a quiet low body.
     * With a 300 Hz high-pass, the power-weighted centroid from 5 ms before the onset
     * to 70 ms after is about 2.2 kHz. The unfiltered full-file centroid is lower.
     */
    static short[] snap() {
        int count = (int) Math.round(RATE * 0.20);
        double[] raw = new double[count];
        Biquad bandA = bandpass(RATE, 2100, 1.2);
        Biquad bandB = bandpass(RATE, 2100, 1.2);
        Biquad bandC = bandpass(RATE, 2100, 1.2);
        Random noise = new Random(0x5AA9);
        double body = 0;
        for (int i = 0; i < count; i++) {
            double t = i / (double) RATE;
            double white = noise.nextDouble() * 2 - 1;
            double air = bandC.process(bandB.process(bandA.process(white))) * Math.exp(-t / 0.040);
            body += 2 * Math.PI * 180 / RATE;
            double tone = Math.sin(body) * Math.exp(-t / 0.055) * 0.06;
            raw[i] = air + tone;
        }
        return normalize(raw);
    }

    private static short[] normalize(double[] raw) {
        double peak = 0;
        for (double sample : raw) {
            peak = Math.max(peak, Math.abs(sample));
        }
        double gain = peak < 1e-9 ? 0 : 0.92 / peak;
        short[] out = new short[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double sample = Math.max(-1, Math.min(1, raw[i] * gain));
            out[i] = (short) Math.round(sample * 32767);
        }
        return out;
    }

    /** Constant-peak bandpass from the Audio EQ Cookbook. */
    private static Biquad bandpass(int rate, double freq, double q) {
        double w0 = 2 * Math.PI * freq / rate;
        double alpha = Math.sin(w0) / (2 * q);
        double a0 = 1 + alpha;
        double b0 = alpha / a0;
        double b1 = 0;
        double b2 = -alpha / a0;
        double a1 = (-2 * Math.cos(w0)) / a0;
        double a2 = (1 - alpha) / a0;
        return new Biquad(b0, b1, b2, a1, a2);
    }

    private static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double z1;
        private double z2;

        private Biquad(double b0, double b1, double b2, double a1, double a2) {
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        private double process(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return output;
        }
    }
}
