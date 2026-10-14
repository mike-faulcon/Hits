package hits;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrooveGeneratorTest {
    @Test
    void sameSeedRepeatsTheGroove() {
        GrooveGenerator.Groove first = new GrooveGenerator(42).generate(GrooveGenerator.Genre.TRAP, 16);
        GrooveGenerator.Groove second = new GrooveGenerator(42).generate(GrooveGenerator.Genre.TRAP, 16);
        assertSameGroove(first, second);

        int[] hats = new GrooveGenerator(42).shuffle(GrooveGenerator.Genre.TRAP, GrooveGenerator.HAT, 16);
        assertArrayEquals(first.hits(GrooveGenerator.HAT), hats);
    }

    @Test
    void differentSeedsMoveTheHatsAndStayOnTheAnchors() {
        GrooveGenerator.Groove first = new GrooveGenerator(1).generate(GrooveGenerator.Genre.BOOM_BAP, 16);
        boolean moved = false;
        for (long seed = 2; seed <= 30; seed++) {
            GrooveGenerator.Groove next = new GrooveGenerator(seed).generate(GrooveGenerator.Genre.BOOM_BAP, 16);
            assertBoomBapAnchors(next);
            if (mask(first, GrooveGenerator.HAT) != mask(next, GrooveGenerator.HAT) || first.bpm() != next.bpm()) {
                moved = true;
            }
        }
        assertTrue(moved);
    }

    @Test
    void eachStyleKeepsItsAnchorsTempoAndSwing() {
        for (long seed = 1; seed <= 24; seed++) {
            assertBoomBapAnchors(new GrooveGenerator(seed).generate(GrooveGenerator.Genre.BOOM_BAP, 16));
            assertTrapAnchors(new GrooveGenerator(seed).generate(GrooveGenerator.Genre.TRAP, 16));
            assertHouseAnchors(new GrooveGenerator(seed).generate(GrooveGenerator.Genre.HOUSE, 16));
            assertRockAnchors(new GrooveGenerator(seed).generate(GrooveGenerator.Genre.ROCK, 16));
            assertReggaetonAnchors(new GrooveGenerator(seed).generate(GrooveGenerator.Genre.REGGAETON, 16));
        }
    }

    @Test
    void twoBarsKeepTheKickAndSnareAndAddHatMovement() {
        boolean extraHat = false;
        for (long seed = 1; seed <= 20; seed++) {
            GrooveGenerator.Groove groove = new GrooveGenerator(seed).generate(GrooveGenerator.Genre.HOUSE, 32);
            assertEquals(32, groove.steps());
            assertHouseAnchors(groove);
            for (int step = 0; step < 16; step++) {
                assertEquals(groove.on(GrooveGenerator.KICK, step), groove.on(GrooveGenerator.KICK, step + 16));
                assertEquals(groove.on(GrooveGenerator.SNARE, step), groove.on(GrooveGenerator.SNARE, step + 16));
                assertEquals(groove.on(GrooveGenerator.CLAP, step), groove.on(GrooveGenerator.CLAP, step + 16));
                if (groove.on(GrooveGenerator.HAT, step)) {
                    assertTrue(groove.on(GrooveGenerator.HAT, step + 16));
                }
            }
            for (int step = 16; step < 32; step++) {
                if (groove.on(GrooveGenerator.HAT, step) && !groove.on(GrooveGenerator.HAT, step - 16)) {
                    extraHat = true;
                }
            }
            assertTrue(inRange(groove.velocity(GrooveGenerator.KICK, 16), 1, 127));
        }
        assertTrue(extraHat);
    }

    @Test
    void velocitiesStayInsideMidiAndAStyleIsRequired() {
        for (GrooveGenerator.Genre genre : GrooveGenerator.Genre.values()) {
            for (long seed = 1; seed <= 12; seed++) {
                for (int length : new int[] {16, 32}) {
                    GrooveGenerator.Groove groove = new GrooveGenerator(seed).generate(genre, length);
                    assertEquals(length, groove.steps());
                    for (int track = 0; track < Beat.TRACKS; track++) {
                        for (int step = 0; step < length; step++) {
                            int velocity = groove.velocity(track, step);
                            assertTrue(velocity == 0 || inRange(velocity, 1, 127));
                        }
                    }
                    int[] shuffled = new GrooveGenerator(seed).shuffle(genre, trackRole(genre, seed), length);
                    assertTrue(count(shuffled) > 0);
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new GrooveGenerator(1).generate(null, 16));
        assertEquals(16, new GrooveGenerator(1).shuffle(GrooveGenerator.Genre.ROCK, -1, 16).length);
        assertEquals(0, count(new GrooveGenerator(1).shuffle(GrooveGenerator.Genre.ROCK, 9, 16)));
    }

    @Test
    void roleFollowsTheDrumSound() {
        Beat beat = Beat.drumKit("t");
        assertEquals(GrooveGenerator.KICK, GrooveGenerator.role(beat.track(0), 0));
        assertEquals(GrooveGenerator.SNARE, GrooveGenerator.role(beat.track(1), 1));
        assertEquals(GrooveGenerator.HAT, GrooveGenerator.role(beat.track(2), 2));
        beat.track(5).setNote(38);
        assertEquals(GrooveGenerator.SNARE, GrooveGenerator.role(beat.track(5), 5));
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setNote(36);
        assertEquals(6, GrooveGenerator.role(beat.track(6), 6));
        assertEquals(GrooveGenerator.KICK, GrooveGenerator.role(null, -1));
    }

    @Test
    void writeIsOneUndoAndKeepsTheOtherPattern() {
        Beat beat = Beat.drumKit("Take");
        beat.setBpm(90);
        beat.setSwing(50);
        beat.track(0).tap(1, false);
        beat.track(2).setName("Hats");
        beat.track(2).setNote(44);
        beat.track(2).setMute(true);
        beat.track(0).step(3).setPitch(40);
        beat.setActiveSlot('b');
        beat.track(3).tap(2, false);
        beat.track(0).step(0).setPitch(47);
        beat.setChain(List.of(new Chain.Part('a', 2)));
        beat.setActiveSlot('a');

        Editor editor = new Editor(beat);
        editor.setSongMode(true);
        editor.holdSlot('b');
        GrooveGenerator.write(editor, GrooveGenerator.Genre.HOUSE, 5);
        assertEquals('b', editor.beat().activeSlot());
        assertTrue(inRange(editor.beat().bpm(), 120, 128));
        assertTrue(editor.beat().slot('b')[0].step(0).on());
        assertTrue(editor.beat().slot('b')[0].step(4).on());
        assertTrue(editor.beat().slot('b')[0].step(8).on());
        assertTrue(editor.beat().slot('b')[0].step(12).on());
        assertFalse(editor.beat().slot('b')[0].step(0).hasPitch());
        assertEquals(40, editor.beat().slot('a')[0].step(3).pitch());
        assertTrue(editor.beat().slot('a')[0].step(1).on());
        assertFalse(editor.beat().slot('a')[0].step(0).on());
        assertEquals("Hats", editor.beat().slot('a')[2].name());
        assertEquals(44, editor.beat().slot('a')[2].note());
        assertTrue(editor.beat().slot('a')[2].mute());
        assertEquals("Closed Hat", editor.beat().slot('b')[2].name());
        assertEquals(42, editor.beat().track(2).note());
        assertFalse(editor.beat().track(2).mute());
        assertEquals(TrackMode.DRUM, editor.beat().track(2).mode());
        assertEquals(1, editor.beat().chain().size());
        assertEquals(16, editor.beat().stepCount());
        assertTrue(editor.isDirty());

        editor.undo();
        assertEquals('a', editor.beat().activeSlot());
        assertEquals(90, editor.beat().bpm());
        assertEquals(50, editor.beat().swing());
        assertTrue(editor.beat().slot('a')[0].step(1).on());
        assertFalse(editor.beat().slot('a')[0].step(0).on());
        assertEquals(40, editor.beat().slot('a')[0].step(3).pitch());
        assertTrue(editor.beat().slot('b')[3].step(2).on());
        assertFalse(editor.beat().slot('b')[0].step(0).on());
        assertEquals(47, editor.beat().slot('b')[0].step(0).pitch());

        editor.redo();
        assertEquals('b', editor.beat().activeSlot());
        assertTrue(editor.beat().slot('b')[0].step(0).on());
    }

    @Test
    void shuffleRewritesOneTrackAndIsOneUndo() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.beat().track(0).tap(0, false);
        editor.beat().track(1).tap(4, false);
        editor.selectTrack(2);
        int bpm = editor.beat().bpm();
        int swing = editor.beat().swing();
        GrooveGenerator.shuffleTrack(editor, GrooveGenerator.Genre.TRAP, 9);
        assertEquals(bpm, editor.beat().bpm());
        assertEquals(swing, editor.beat().swing());
        assertTrue(editor.beat().track(0).step(0).on());
        assertTrue(editor.beat().track(1).step(4).on());
        assertTrue(editor.beat().track(2).step(0).on());
        assertTrue(editor.beat().track(2).step(2).on());
        assertFalse(editor.beat().slot('b')[2].step(0).on());
        assertEquals(TrackMode.DRUM, editor.beat().track(2).mode());
        assertEquals(42, editor.beat().track(2).note());

        editor.undo();
        assertFalse(editor.beat().track(2).step(0).on());
        assertTrue(editor.beat().track(0).step(0).on());
        assertEquals(bpm, editor.beat().bpm());
    }

    @Test
    void shuffleStillMovesAFourOnTheFloorKick() {
        Editor editor = new Editor(Beat.drumKit("t"));
        GrooveGenerator.write(editor, GrooveGenerator.Genre.HOUSE, 11);
        assertTrue(editor.beat().track(2).step(2).on());
        boolean hatGhost = editor.beat().track(2).step(1).on();
        editor.selectTrack(0);
        GrooveGenerator.shuffleTrack(editor, GrooveGenerator.Genre.HOUSE, 11);
        assertTrue(editor.beat().track(0).step(0).on());
        assertTrue(editor.beat().track(0).step(4).on());
        assertTrue(editor.beat().track(0).step(8).on());
        assertTrue(editor.beat().track(0).step(12).on());
        int added = 0;
        for (int step = 0; step < 16; step++) {
            if (step % 4 != 0 && editor.beat().track(0).step(step).on()) {
                added++;
            }
        }
        assertEquals(1, added);
        assertEquals(hatGhost, editor.beat().track(2).step(1).on());
        assertTrue(editor.beat().track(2).step(2).on());
        assertFalse(editor.beat().slot('b')[0].step(0).on());
        editor.undo();
        for (int step = 0; step < 16; step++) {
            if (step % 4 == 0) {
                assertTrue(editor.beat().track(0).step(step).on());
            } else {
                assertFalse(editor.beat().track(0).step(step).on());
            }
        }
    }

    @Test
    void shuffleUsesTheSoundWhenTheRowMoved() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.beat().track(5).setNote(38);
        editor.selectTrack(5);
        GrooveGenerator.shuffleTrack(editor, GrooveGenerator.Genre.REGGAETON, 3);
        assertTrue(editor.beat().track(5).step(4).on());
        assertTrue(editor.beat().track(5).step(12).on());
        assertFalse(editor.beat().track(1).step(4).on());
        assertEquals(38, editor.beat().track(5).note());
        editor.undo();
        assertFalse(editor.beat().track(5).step(4).on());
    }

    @Test
    void writeFillsBothBarsWhenThePatternIsLong() {
        Editor editor = new Editor(Beat.drumKit("t"));
        editor.edit(beat -> beat.setStepCount(32));
        GrooveGenerator.write(editor, GrooveGenerator.Genre.ROCK, 2);
        assertEquals(32, editor.beat().stepCount());
        assertTrue(editor.beat().track(0).step(0).on());
        assertTrue(editor.beat().track(0).step(16).on());
        assertTrue(editor.beat().track(1).step(4).on());
        assertTrue(editor.beat().track(1).step(20).on());
        editor.undo();
        assertEquals(32, editor.beat().stepCount());
        assertFalse(editor.beat().track(0).step(0).on());
        assertFalse(editor.beat().track(0).step(16).on());
        editor.undo();
        assertEquals(16, editor.beat().stepCount());
    }

    @Test
    void emptyPatternNeedsNoWarningAndAHitOnTheOtherSlotStays() {
        Beat beat = Beat.drumKit("t");
        assertFalse(GrooveGenerator.patternHasHits(beat, 'a'));
        beat.track(0).step(20).setOn(true);
        assertFalse(GrooveGenerator.patternHasHits(beat, 'a'));
        beat.setStepCount(32);
        assertTrue(GrooveGenerator.patternHasHits(beat, 'a'));

        Beat other = Beat.drumKit("t");
        other.setActiveSlot('b');
        other.track(0).tap(0, false);
        other.setActiveSlot('a');
        assertFalse(GrooveGenerator.patternHasHits(other, 'a'));
        assertTrue(GrooveGenerator.patternHasHits(other, 'b'));
    }

    @Test
    void aGeneratedBeatStaysVersionOne() {
        Beat beat = Beat.drumKit("t");
        GrooveGenerator.apply(beat, new GrooveGenerator(8).generate(GrooveGenerator.Genre.TRAP, 16));
        assertEquals(Beat.VERSION, BeatJson.documentVersion(beat));
        assertFalse(beat.usesFormatV2());
        assertEquals("t", beat.name());
    }

    @Test
    void inferFollowsTempoAndSwing() {
        assertEquals(GrooveGenerator.Genre.BOOM_BAP, GrooveGenerator.infer(96, 54));
        assertEquals(GrooveGenerator.Genre.BOOM_BAP, GrooveGenerator.infer(96, 50));
        assertEquals(GrooveGenerator.Genre.BOOM_BAP, GrooveGenerator.infer(90, 52));
        assertEquals(GrooveGenerator.Genre.TRAP, GrooveGenerator.infer(140, 50));
        assertEquals(GrooveGenerator.Genre.HOUSE, GrooveGenerator.infer(124, 50));
        assertEquals(GrooveGenerator.Genre.ROCK, GrooveGenerator.infer(110, 50));
    }

    private static void assertBoomBapAnchors(GrooveGenerator.Groove groove) {
        assertTrue(groove.on(GrooveGenerator.KICK, 0));
        assertTrue(groove.on(GrooveGenerator.KICK, 8));
        assertTrue(groove.on(GrooveGenerator.SNARE, 4));
        assertTrue(groove.on(GrooveGenerator.SNARE, 12));
        assertTrue(groove.hitCount(GrooveGenerator.HAT) >= 4);
        assertTrue(groove.hitCount(GrooveGenerator.OPEN) >= 1);
        assertTrue(inRange(groove.bpm(), 86, 100));
        assertTrue(inRange(groove.swing(), 52, 58));
    }

    private static void assertTrapAnchors(GrooveGenerator.Groove groove) {
        assertTrue(groove.on(GrooveGenerator.KICK, 0));
        assertFalse(groove.on(GrooveGenerator.KICK, 4));
        assertFalse(groove.on(GrooveGenerator.KICK, 12));
        assertTrue(groove.on(GrooveGenerator.SNARE, 4));
        assertTrue(groove.on(GrooveGenerator.SNARE, 12));
        assertTrue(groove.on(GrooveGenerator.CLAP, 4));
        assertTrue(groove.on(GrooveGenerator.CLAP, 12));
        assertTrue(groove.on(GrooveGenerator.HAT, 14));
        assertTrue(groove.on(GrooveGenerator.HAT, 15));
        assertTrue(groove.hitCount(GrooveGenerator.HAT) >= 8);
        assertTrue(inRange(groove.bpm(), 134, 150));
        assertEquals(50, groove.swing());
    }

    private static void assertHouseAnchors(GrooveGenerator.Groove groove) {
        for (int step = 0; step < 16; step += 4) {
            assertTrue(groove.on(GrooveGenerator.KICK, step % 16));
        }
        assertTrue(groove.on(GrooveGenerator.KICK, 0));
        assertTrue(groove.on(GrooveGenerator.KICK, 4));
        assertTrue(groove.on(GrooveGenerator.KICK, 8));
        assertTrue(groove.on(GrooveGenerator.KICK, 12));
        assertEquals(4, countInBar(groove, GrooveGenerator.KICK, 0));
        assertTrue(groove.on(GrooveGenerator.CLAP, 4));
        assertTrue(groove.on(GrooveGenerator.CLAP, 12));
        assertTrue(groove.on(GrooveGenerator.HAT, 2));
        assertTrue(groove.on(GrooveGenerator.HAT, 10));
        assertTrue(groove.on(GrooveGenerator.OPEN, 14));
        assertFalse(groove.on(GrooveGenerator.HAT, 14));
        assertTrue(inRange(groove.bpm(), 120, 128));
        assertTrue(inRange(groove.swing(), 50, 51));
    }

    private static void assertRockAnchors(GrooveGenerator.Groove groove) {
        assertTrue(groove.on(GrooveGenerator.KICK, 0));
        assertTrue(groove.on(GrooveGenerator.KICK, 8));
        assertTrue(groove.on(GrooveGenerator.SNARE, 4));
        assertTrue(groove.on(GrooveGenerator.SNARE, 12));
        assertTrue(groove.on(GrooveGenerator.CRASH, 0));
        assertTrue(groove.hitCount(GrooveGenerator.HAT) >= 6 || groove.hitCount(GrooveGenerator.RIDE) >= 6);
        assertEquals(50, groove.swing());
        assertTrue(inRange(groove.bpm(), 104, 128));
    }

    private static void assertReggaetonAnchors(GrooveGenerator.Groove groove) {
        for (int step : new int[] {0, 3, 6, 8, 11, 14}) {
            assertTrue(groove.on(GrooveGenerator.KICK, step));
        }
        for (int step : new int[] {2, 4, 5, 7, 10, 12, 13, 15}) {
            assertTrue(groove.on(GrooveGenerator.SNARE, step));
        }
        assertTrue(groove.on(GrooveGenerator.HAT, 2));
        assertTrue(groove.on(GrooveGenerator.HAT, 14));
        assertEquals(50, groove.swing());
        assertTrue(inRange(groove.bpm(), 90, 100));
    }

    private static int countInBar(GrooveGenerator.Groove groove, int track, int start) {
        int count = 0;
        for (int step = start; step < start + 16 && step < groove.steps(); step++) {
            if (groove.on(track, step)) {
                count++;
            }
        }
        return count;
    }

    private static int trackRole(GrooveGenerator.Genre genre, long seed) {
        return (int) ((seed + genre.ordinal()) % Beat.TRACKS);
    }

    private static void assertSameGroove(GrooveGenerator.Groove first, GrooveGenerator.Groove second) {
        assertEquals(first.genre(), second.genre());
        assertEquals(first.bpm(), second.bpm());
        assertEquals(first.swing(), second.swing());
        assertEquals(first.steps(), second.steps());
        for (int track = 0; track < Beat.TRACKS; track++) {
            assertArrayEquals(first.hits(track), second.hits(track));
        }
    }

    private static int mask(GrooveGenerator.Groove groove, int track) {
        int mask = 0;
        for (int step = 0; step < Math.min(16, groove.steps()); step++) {
            if (groove.on(track, step)) {
                mask |= 1 << step;
            }
        }
        return mask;
    }

    private static int count(int[] hits) {
        int count = 0;
        for (int velocity : hits) {
            if (velocity > 0) {
                count++;
            }
        }
        return count;
    }

    private static boolean inRange(int value, int min, int max) {
        return value >= min && value <= max;
    }
}
