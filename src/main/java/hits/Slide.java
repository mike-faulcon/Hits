package hits;

import java.util.List;

/**
 * Pitch glide for a note row or a sample row, including Sub.
 * A step marked slide holds the previous note and bends to its own pitch.
 * There is no new attack. Drum rows do not glide.
 * <p>
 * The bend is MIDI pitch bend. The range is RPN 0, wide enough for the largest
 * interval on the row and never wider than ±{@link ChannelPlan#BEND_RANGE} semitones.
 * Linear moves in equal semitone steps. Exponential eases through the same
 * interval, fast at first and then settling, still measured in pitch.
 */
public final class Slide {
    /** Shortest glide, in milliseconds. */
    public static final int MIN_MS = 10;
    /** Longest glide, in milliseconds. */
    public static final int MAX_MS = 500;
    /** Glide used when the file leaves the time out. */
    public static final int DEFAULT_MS = 80;

    private Slide() {}

    /** How the pitch moves across {@link #DEFAULT_MS} or the row's own time. */
    public enum Curve {
        LINEAR,
        EXPONENTIAL;

        public String json() {
            return this == EXPONENTIAL ? "exponential" : "linear";
        }

        /** Word used by {@code hits info}. */
        public String label() {
            return json();
        }

        /**
         * {@code linear} and {@code exponential}. A blank value is linear.
         * Anything else is unknown and returns null.
         */
        public static Curve parse(String text) {
            if (text == null || text.isBlank() || text.equalsIgnoreCase("linear")) {
                return LINEAR;
            }
            if (text.equalsIgnoreCase("exponential") || text.equalsIgnoreCase("exp")) {
                return EXPONENTIAL;
            }
            return null;
        }

        /**
         * Position along the glide, 0 at the previous pitch and 1 at this step's pitch.
         * Both curves are in pitch (semitones), not in Hertz.
         */
        public double shape(double unit) {
            double u = unit < 0 ? 0 : Math.min(1, unit);
            if (this == LINEAR || u == 0 || u == 1) {
                return u;
            }
            double k = 4.0;
            return (1 - Math.exp(-k * u)) / (1 - Math.exp(-k));
        }
    }

    /** Note rows and sample rows can glide. A drum row cannot. */
    public static boolean applies(Track track) {
        return track != null && (track.mode() == TrackMode.NOTE || track.mode() == TrackMode.SAMPLE);
    }

    /**
     * This pattern will bend. The step is on, it asks to slide, and the row can glide.
     * A sample that did not load is excluded: it plays on the drum channel, and a bend
     * there would move every drum.
     */
    public static boolean sounds(Track track, int row, SampleUse samples, int steps) {
        if (!applies(track)) {
            return false;
        }
        if (track.mode() == TrackMode.SAMPLE && (samples == null || !samples.plays(track, row))) {
            return false;
        }
        int limit = Math.min(Math.max(0, steps), Track.CAPACITY);
        for (int i = 0; i < limit; i++) {
            Step step = track.step(i);
            if (step.on() && step.slide()) {
                return true;
            }
        }
        return false;
    }

    public static int clampMs(int milliseconds) {
        return Math.max(MIN_MS, Math.min(MAX_MS, milliseconds));
    }

    /**
     * Ticks a glide of {@code milliseconds} lasts at {@code bpm} on the 96 PPQ grid.
     * At least one tick, so the bend still reaches the target.
     */
    public static long ticks(int bpm, int milliseconds) {
        int ms = clampMs(milliseconds);
        int tempo = Math.max(1, bpm);
        long ticks = Math.round(ms * (tempo / 60_000.0) * SequenceBuilder.PPQ);
        return Math.max(1, ticks);
    }

    /**
     * Pitch-bend range, in semitones, that covers every slide on this row
     * plus the row's tune. 1 when the row only needs a center bend.
     * Never above {@link ChannelPlan#BEND_RANGE}.
     */
    public static int range(Beat beat, List<Character> slots, int row) {
        int maxCents = 0;
        if (beat != null && slots != null && row >= 0) {
            for (char slot : slots) {
                Track[] tracks = beat.slot(slot);
                if (row >= tracks.length) {
                    continue;
                }
                Track track = tracks[row];
                maxCents = Math.max(maxCents, Math.abs(track.tune()));
                int anchor = -1;
                int limit = Math.min(beat.stepCount(slot), Track.CAPACITY);
                for (int step = 0; step < limit; step++) {
                    Step cell = track.step(step);
                    if (!cell.on()) {
                        continue;
                    }
                    int note = cell.soundingNote(track.playedNote());
                    if (cell.slide() && anchor >= 0 && applies(track)) {
                        maxCents = Math.max(maxCents, Math.abs(track.tune() + (note - anchor) * 100));
                    } else {
                        anchor = note;
                    }
                }
            }
        }
        int semis = (maxCents + 99) / 100;
        if (semis < 1) {
            semis = 1;
        }
        return Math.min(ChannelPlan.BEND_RANGE, semis);
    }

    /**
     * Cents above the note that attacked, at one point on the glide.
     * {@code shaped} is 0 at {@code fromNote} and 1 at {@code toNote}.
     */
    public static int cents(int tuneCents, int anchor, int fromNote, int toNote, double shaped) {
        double unit = shaped < 0 ? 0 : Math.min(1, shaped);
        double sounding = fromNote + (toNote - fromNote) * unit;
        return tuneCents + (int) Math.round((sounding - anchor) * 100.0);
    }
}
