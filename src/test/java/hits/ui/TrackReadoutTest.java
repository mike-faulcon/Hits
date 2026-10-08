package hits.ui;

import hits.Beat;
import hits.Track;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackReadoutTest {
    @Test
    void swingAndVelocityFollowTheStepOnScreen() {
        assertEquals("50% straight", TrackReadout.swing(50));
        assertEquals("62%", TrackReadout.swing(62));

        Beat beat = Beat.drumKit("t");
        Track kick = beat.track(0);
        kick.tap(0, false);
        kick.step(0).setVelocity(90);
        assertEquals(90, TrackReadout.shownVelocity(kick, 0));
        assertEquals(kick.velocity(), TrackReadout.shownVelocity(kick, 1));
        assertEquals(kick.velocity(), TrackReadout.shownVelocity(kick, -1));
        assertEquals("Volume of step 1 on " + kick.name() + ".", TrackReadout.velocityTip(kick, 0, beat.stepCount()));
        assertEquals(
            "Volume of new hits on this track. Right-click or Alt-click a pad to edit that hit.",
            TrackReadout.velocityTip(kick, 1, beat.stepCount())
        );
    }

    @Test
    void stepPitchUsesTheTrackUntilTheStepHasItsOwn() {
        Beat beat = Beat.drumKit("t");
        Track kick = beat.track(0);
        TrackReadout.StepPitch none = TrackReadout.stepPitch(beat, kick, -1);
        assertEquals("—", none.text());
        assertEquals("Right-click or Alt-click a step that is on.", none.tip());
        assertFalse(none.nudgeEnabled());
        assertFalse(none.clearEnabled());

        kick.tap(0, false);
        TrackReadout.StepPitch trackPitch = TrackReadout.stepPitch(beat, kick, 0);
        assertEquals("C1", trackPitch.text());
        assertEquals("Uses the track pitch, C1.", trackPitch.tip());
        assertTrue(trackPitch.nudgeEnabled());
        assertFalse(trackPitch.clearEnabled());

        kick.step(0).setPitch(38);
        TrackReadout.StepPitch own = TrackReadout.stepPitch(beat, kick, 0);
        assertEquals("D1", own.text());
        assertEquals("This step plays D1. The track pitch is C1.", own.tip());
        assertTrue(own.nudgeEnabled());
        assertTrue(own.clearEnabled());
    }
}
