package hits;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TapTempoTest {
    @Test
    void oneTapDoesNotSetATempo() {
        TapTempo tempo = new TapTempo();
        assertTrue(tempo.tap(1_000).isEmpty());
    }

    @Test
    void twoTapsAreThatGap() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        assertEquals(120, tempo.tap(500).orElseThrow());
    }

    @Test
    void recentGapsAreAveraged() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        tempo.tap(400);
        assertEquals(120, tempo.tap(1_000).orElseThrow());
    }

    @Test
    void olderGapsDropOutOfTheAverage() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        tempo.tap(1_000);
        tempo.tap(2_000);
        tempo.tap(3_000);
        tempo.tap(4_000);
        // Five gaps would still include the first 1000 ms. The window keeps four: 1000, 2000, 3000, 4000, 4500.
        assertEquals(69, tempo.tap(4_500).orElseThrow());
    }

    @Test
    void aPauseStartsANewRun() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        assertEquals(120, tempo.tap(500).orElseThrow());
        assertTrue(tempo.tap(500 + TapTempo.RESET_MILLIS + 1).isEmpty());
        assertEquals(120, tempo.tap(500 + TapTempo.RESET_MILLIS + 1 + 500).orElseThrow());
    }

    @Test
    void aGapAtThePauseLimitStillCounts() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        assertEquals(Beat.MIN_BPM, tempo.tap(TapTempo.RESET_MILLIS).orElseThrow());
    }

    @Test
    void tempoStaysInsideTheAppRange() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        assertEquals(Beat.MAX_BPM, tempo.tap(100).orElseThrow());

        TapTempo slow = new TapTempo();
        slow.tap(0);
        assertEquals(Beat.MIN_BPM, slow.tap(1_800).orElseThrow());
    }

    @Test
    void aTapAtTheSameInstantIsIgnored() {
        TapTempo tempo = new TapTempo();
        tempo.tap(1_000);
        assertTrue(tempo.tap(1_000).isEmpty());
        assertEquals(120, tempo.tap(1_500).orElseThrow());
        assertEquals(120, tempo.tap(1_500).orElseThrow());
    }

    @Test
    void roundingUsesTheNearestBpm() {
        TapTempo tempo = new TapTempo();
        tempo.tap(0);
        assertEquals(118, tempo.tap(510).orElseThrow());
    }

    @Test
    void editorUndoRestoresTheTempoBeforeTheTaps() {
        Editor editor = new Editor(Beat.drumKit("t"));
        TapTempo tempo = new TapTempo();
        assertTrue(tempo.tap(0).isEmpty());
        editor.edit(beat -> beat.setBpm(tempo.tap(500).orElseThrow()));
        assertEquals(120, editor.beat().bpm());
        OptionalInt refined = tempo.tap(1_100);
        editor.edit(beat -> beat.setBpm(refined.orElseThrow()));
        assertEquals(109, editor.beat().bpm());
        assertTrue(editor.isDirty());

        editor.undo();
        assertEquals(120, editor.beat().bpm());
        editor.undo();
        assertEquals(96, editor.beat().bpm());
        editor.redo();
        assertEquals(120, editor.beat().bpm());
    }
}
