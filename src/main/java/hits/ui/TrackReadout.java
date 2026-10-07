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

    record StepPitch(String text, String tip, boolean nudgeEnabled, boolean clearEnabled) {}

    static StepPitch stepPitch(Beat beat, Track track, int column) {
        boolean editable = column >= 0 && column < beat.stepCount() && track.step(column).on();
        if (!editable) {
            return new StepPitch("—", "Right-click or Alt-click a step that is on.", false, false);
        }
        Step step = track.step(column);
        if (step.hasPitch()) {
            return new StepPitch(
                Notes.name(step.pitch()),
                "This step plays " + Notes.name(step.pitch()) + ". The track pitch is " + Notes.name(track.note()) + ".",
                true,
                true
            );
        }
        return new StepPitch(
            Notes.name(track.note()),
            "Uses the track pitch, " + Notes.name(track.note()) + ".",
            true,
            false
        );
    }
}
