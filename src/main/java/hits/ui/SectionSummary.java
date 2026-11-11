package hits.ui;

import hits.BuiltinSamples;
import hits.Db;
import hits.Gm;
import hits.Notes;
import hits.SampleRef;
import hits.Slide;
import hits.Step;
import hits.Track;
import hits.TrackMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Which inspector sections a row shows, and the words in an active header.
 * A section is active when its settings differ from a new row. The text is the
 * summary that stays visible after the section is collapsed.
 */
final class SectionSummary {
    enum Id {
        SOUND("Sound"),
        TONE("Tone/Envelope"),
        FILTER("Filter & EQ"),
        SLIDE("Slide"),
        SAMPLE("Sample");

        private final String title;

        Id(String title) {
            this.title = title;
        }

        String title() {
            return title;
        }
    }

    private SectionSummary() {}

    /** Sound, tone, and filter apply to every row. Slide and sample follow the row type. */
    static boolean applies(Id id, Track track) {
        if (track == null) {
            return false;
        }
        return switch (id) {
            case SLIDE -> Slide.applies(track);
            case SAMPLE -> track.mode() == TrackMode.SAMPLE;
            default -> true;
        };
    }

    /** Empty when the section is at its defaults. */
    static String summary(Id id, Track track) {
        if (track == null || !applies(id, track)) {
            return "";
        }
        return switch (id) {
            case SOUND -> sound(track);
            case TONE -> tone(track);
            case FILTER -> filter(track);
            case SLIDE -> slide(track);
            case SAMPLE -> sample(track);
        };
    }

    /** Header name for a screen reader, including the summary and whether it is open. */
    static String accessibleName(Id id, String summary, boolean expanded) {
        StringBuilder name = new StringBuilder(id.title());
        if (summary != null && !summary.isEmpty()) {
            name.append(", active, ").append(summary);
        }
        name.append(expanded ? ", expanded" : ", collapsed");
        return name.toString();
    }

    private static String sound(Track track) {
        List<String> parts = new ArrayList<>();
        if (track.preset() != null) {
            parts.add(track.preset().label());
        }
        if (track.kit() != 0) {
            parts.add("Kit " + track.kit());
        }
        if (track.bank() != 0) {
            parts.add("Bank " + track.bank());
        }
        if (track.mode() == TrackMode.NOTE && track.program() != 0) {
            parts.add(Gm.programName(track.program()));
        }
        SampleRef sample = track.sample();
        if (track.mode() == TrackMode.SAMPLE && sample != null && !BuiltinSamples.isBuiltin(sample.path())) {
            parts.add(sample.label());
        }
        return join(parts);
    }

    private static String tone(Track track) {
        List<String> parts = new ArrayList<>();
        if (track.tune() != 0) {
            parts.add(tuneText(track));
        }
        if (track.decay() != Track.DECAY) {
            parts.add("Decay " + track.decay() + "%");
        }
        if (track.hasCutoff()) {
            parts.add("Cutoff " + track.cutoff());
        }
        if (track.hasResonance()) {
            parts.add("Resonance " + track.resonance());
        }
        if (track.legato()) {
            parts.add("Legato");
        }
        return join(parts);
    }

    private static String tuneText(Track track) {
        int semi = track.tuneSemitones();
        int cents = track.tuneCents();
        StringBuilder text = new StringBuilder("Tune ");
        if (semi != 0) {
            text.append(semi > 0 ? "+" : "").append(semi).append(" st");
            if (cents != 0) {
                text.append(' ');
            }
        }
        if (cents != 0 || semi == 0) {
            text.append(cents > 0 ? "+" : "").append(cents).append(" ¢");
        }
        return text.toString();
    }

    private static String filter(Track track) {
        List<String> parts = new ArrayList<>();
        if (track.hasLowCut()) {
            String low = "Low cut " + hertz(track.lowCutHz());
            if (track.lowCutSlope() != Track.LOW_CUT_SLOPE) {
                low += ", " + track.lowCutSlope() + " dB/oct";
            }
            parts.add(low);
        }
        int bands = track.eqBands().size();
        if (bands == 1) {
            parts.add("1 band");
        } else if (bands > 1) {
            parts.add(bands + " bands");
        }
        if (track.eqBypassed()) {
            parts.add("EQ bypassed");
        }
        if (track.hasHighCut()) {
            String high = "High cut " + hertz(track.highCutHz());
            if (track.highCutSlope() != Track.HIGH_CUT_SLOPE) {
                high += ", " + track.highCutSlope() + " dB/oct";
            }
            parts.add(high);
        }
        if (track.hasPresence()) {
            parts.add("Presence " + Db.labeled(track.presence()));
        }
        return join(parts);
    }

    private static String slide(Track track) {
        List<String> parts = new ArrayList<>();
        int glides = 0;
        for (int i = 0; i < Track.CAPACITY; i++) {
            Step step = track.step(i);
            if (step.slide()) {
                glides++;
            }
        }
        if (glides == 1) {
            parts.add("1 glide step");
        } else if (glides > 1) {
            parts.add(glides + " glide steps");
        }
        if (track.slideMs() != Slide.DEFAULT_MS) {
            parts.add(track.slideMs() + " ms");
        }
        if (track.slideCurve() != Slide.Curve.LINEAR) {
            parts.add("Exponential");
        }
        return join(parts);
    }

    private static String sample(Track track) {
        SampleRef sample = track.sample();
        if (sample == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (sample.root() != SampleRef.DEFAULT_ROOT) {
            parts.add("Root " + Notes.name(sample.root()));
        }
        if (sample.start() != 0) {
            parts.add("Start " + sample.start());
        }
        if (!sample.oneShot()) {
            parts.add("Choke");
        }
        if (sample.gain() != SampleRef.GAIN) {
            parts.add("Gain " + SampleRef.gainText(sample.gain()));
        }
        if (sample.invert()) {
            parts.add("Invert");
        }
        return join(parts);
    }

    /** Round thousands as kilohertz. 5000 is {@code 5 kHz} and 80 stays {@code 80 Hz}. */
    static String hertz(int frequency) {
        int hz = Math.max(0, frequency);
        if (hz >= 1000 && hz % 100 == 0) {
            int tenths = hz / 100;
            if (tenths % 10 == 0) {
                return (tenths / 10) + " kHz";
            }
            return (tenths / 10) + "." + (tenths % 10) + " kHz";
        }
        return hz + " Hz";
    }

    private static String join(List<String> parts) {
        return String.join(", ", parts);
    }
}
