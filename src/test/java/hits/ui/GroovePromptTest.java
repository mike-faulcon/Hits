package hits.ui;

import hits.GrooveGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovePromptTest {
    @Test
    void occupiedPatternWarnsAndAnEmptyOneDoesNot() {
        String empty = GroovePrompt.makeBeat('a', false);
        assertTrue(empty.contains("pattern A"));
        assertFalse(empty.contains("already has hits"));

        String occupied = GroovePrompt.makeBeat('b', true);
        assertTrue(occupied.contains("Pattern B already has hits"));
        assertTrue(occupied.contains("Undo"));
        assertTrue(occupied.contains("other pattern"));
    }

    @Test
    void statusNamesTheStyleAndTheTrack() {
        assertEquals("House groove on pattern A", GroovePrompt.groove(GrooveGenerator.Genre.HOUSE, 'a'));
        assertEquals("Shuffled Kick", GroovePrompt.shuffled("Kick"));
        assertEquals("Shuffled Snare", GroovePrompt.shuffled("  Snare  "));
        assertEquals("Shuffled track", GroovePrompt.shuffled(" "));
    }
}
