package hits.ui;

import hits.Beat;
import hits.Notes;
import hits.Step;
import hits.Track;

/** Slider and step-pitch text derived from the track on screen. */
final class TrackReadout {
    private TrackReadout() {}

    static String swing(int swing) {
        return swing == 50 ? "50% straight" : swing + "%";
    }

    static int shownVelocity(Track track, int step) {
        if (step >= 0 && step < Track.CAPACITY && track.step(step).on()) {
            return track.step(step).velocity();
        }
        return track.velocity();
    }

    static String velocityTip(Track track, int step, int stepCount) {
        if (step >= 0 && step < stepCount && track.step(step).on()) {
            return "Volume of step " + (step + 1) + " on " + track.name() + ".";
        }
        return "Volume of new hits on this track. Right-click or Alt-click a pad to edit that hit.";
    }

    record Chance(int value, String text, String tip, boolean enabled) {}

    /** Probability of the selected step. Disabled until a step is selected. */
    static Chance chance(Track track, int step, int stepCount) {
        if (step < 0 || step >= stepCount) {
            return new Chance(100, "—", "Right-click or Alt-click a step to set its chance.", false);
        }
        int value = track.step(step).probability();
        return new Chance(
            value,
            value + "%",
            "Chance step " + (step + 1) + " on " + track.name() + " plays. 100% always plays. 0% never plays.",
            true
        );
    }

    record StepPitch(String text, String tip, boolean nudgeEnabled, boolean clearEnabled) {}

    static StepPitch stepPitch(Beat beat, char slot, Track track, int column) {
        boolean editable = column >= 0 && column < beat.gridColumns(slot) && track.step(column).on();
        if (!editable) {
            return new StepPitch("—", "Right-click or Alt-click a step that is on.", false, false);
        }
        Step step = track.step(column);
        String trackPitch = tunedName(track.playedNote(), track.tune());
        if (step.hasPitch()) {
            String played = tunedName(step.pitch(), track.tune());
            return new StepPitch(
                played,
                "This step plays " + played + ". The track pitch is " + trackPitch + ".",
                true,
                true
            );
        }
        return new StepPitch(
            trackPitch,
            "Uses the track pitch, " + trackPitch + ".",
            true,
            false
        );
    }

    /**
     * Note name plus the row's tune. {@code +1500} cents is {@code C1 +15 st}.
     * Cents that are not a whole semitone stay in the name, inside ±50 after rounding.
     */
    static String tunedName(int note, int tuneCents) {
        String name = Notes.name(note);
        if (tuneCents == 0) {
            return name;
        }
        int semis = (int) Math.round(tuneCents / 100.0);
        int cents = tuneCents - semis * 100;
        StringBuilder text = new StringBuilder(name);
        if (semis != 0) {
            text.append(semis > 0 ? " +" : " −").append(Math.abs(semis)).append(" st");
        }
        if (cents != 0) {
            text.append(cents > 0 ? " +" : " −").append(Math.abs(cents)).append(" c");
        }
        return text.toString();
    }
}
