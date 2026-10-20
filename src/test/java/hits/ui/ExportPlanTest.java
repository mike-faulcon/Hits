package hits.ui;

import hits.ExportOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportPlanTest {
    @Test
    void suggestedNameAndSuffix() {
        assertEquals("untitled", ExportPlan.suggestedStem("   "));
        assertEquals("untitled", ExportPlan.suggestedStem("???"));
        assertEquals("AlbumTrackv3", ExportPlan.suggestedStem("Album Track – v3"));

        Path song = Path.of("beats").resolve("song");
        assertEquals(song.resolveSibling("song.mid"), ExportPlan.withExtension(song, "mid"));
        Path already = Path.of("beats").resolve("song.MID");
        assertEquals(already, ExportPlan.withExtension(already, "mid"));
        Path wav = Path.of("/tmp/takes/song");
        assertEquals(Path.of("/tmp/takes/song.wav"), ExportPlan.withExtension(wav, "wav"));
    }

    @Test
    void songWinsOverAThenBAndAnEmptyChainDoesNot() {
        ExportOptions both = ExportPlan.options(false, true, 4, false, true);
        assertFalse(both.asHeard());
        assertTrue(both.bothSlots());
        assertEquals(4, both.repeats());
        assertFalse(both.song());

        ExportOptions song = ExportPlan.options(true, true, 4, true, true);
        assertTrue(song.asHeard());
        assertFalse(song.bothSlots());
        assertTrue(song.song());

        ExportOptions noChain = ExportPlan.options(false, true, 2, true, false);
        assertTrue(noChain.bothSlots());
        assertFalse(noChain.song());
        assertEquals(1, ExportPlan.options(false, false, 0, false, false).repeats());
        assertEquals(32, ExportPlan.options(false, false, 100, false, false).repeats());

        ExportOptions played = ExportPlan.fromDialog(false, false, 1, false, false);
        assertTrue(played.asHeard());
        ExportOptions ignored = ExportPlan.fromDialog(true, true, 2, false, true);
        assertFalse(ignored.asHeard());
        assertTrue(ignored.bothSlots());
        assertEquals(2, ignored.repeats());
    }

    @Test
    void summaryMatchesTheStatusLine() {
        assertEquals(" (all tracks)", ExportPlan.summary(ExportOptions.allTracks()));
        assertEquals(" (as heard)", ExportPlan.summary(ExportOptions.matchingPlayback()));
        assertEquals(" (all tracks, A then B)", ExportPlan.summary(new ExportOptions(false, true, 1, false)));
        assertEquals(" (all tracks, 3 times)", ExportPlan.summary(new ExportOptions(false, false, 3, false)));
        assertEquals(" (as heard, A then B, 3 times)", ExportPlan.summary(new ExportOptions(true, true, 3, false)));
        assertEquals(" (all tracks, song)", ExportPlan.summary(new ExportOptions(false, true, 4, true)));
        assertEquals(" (as heard, song)", ExportPlan.summary(new ExportOptions(true, false, 1, true)));
    }

    @Test
    void dragFollowsSongModeAndKeepsTheOtherChoices() {
        ExportOptions pattern = ExportPlan.dragOptions(false, false, 1, false, true);
        assertFalse(pattern.song());
        assertFalse(pattern.bothSlots());
        assertFalse(pattern.asHeard());
        assertEquals(1, pattern.repeats());

        ExportOptions song = ExportPlan.dragOptions(true, true, 4, true, true);
        assertTrue(song.asHeard());
        assertTrue(song.song());
        assertFalse(song.bothSlots());
        assertEquals(1, song.repeats());

        ExportOptions emptyChain = ExportPlan.dragOptions(false, true, 3, true, false);
        assertFalse(emptyChain.song());
        assertTrue(emptyChain.bothSlots());
        assertEquals(3, emptyChain.repeats());

        ExportOptions remembered = ExportPlan.dragOptions(true, true, 100, false, true);
        assertTrue(remembered.asHeard());
        assertTrue(remembered.bothSlots());
        assertEquals(32, remembered.repeats());
        assertFalse(remembered.song());
    }

    @Test
    void dragFileNameSaysWhatIsInTheFile() {
        ExportOptions pattern = ExportPlan.dragOptions(false, false, 1, false, false);
        assertEquals("AlbumTrackv3-A.mid", ExportPlan.dragFileName("Album Track – v3", pattern, 'a', "MID"));
        assertEquals("AlbumTrackv3-B.wav", ExportPlan.dragFileName("Album Track – v3", pattern, 'b', "wav"));
        assertEquals("untitled-A.mid", ExportPlan.dragFileName("???", pattern, 'a', "mid"));

        ExportOptions song = ExportPlan.dragOptions(true, true, 4, true, true);
        assertEquals("Take-song-as-heard.wav", ExportPlan.dragFileName("Take", song, 'b', "wav"));

        ExportOptions pair = ExportPlan.dragOptions(true, true, 4, false, true);
        assertEquals("Take-A-then-B-x4-as-heard.mid", ExportPlan.dragFileName("Take", pair, 'a', "mid"));
    }
}
