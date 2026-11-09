package hits.ui;

import hits.Beat;
import hits.Kits;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An empty pattern is the one that should tell a new user what to do. */
class PatternHintsTest {
    @Test
    void aKitWithNoHitsIsQuietAndBoomBapIsNot() {
        Beat empty = Beat.drumKit("untitled");
        assertTrue(PatternHints.quiet(empty, 'a'));
        Kits.boomBap(empty);
        assertFalse(PatternHints.quiet(empty, 'a'));
        assertTrue(PatternHints.EMPTY.contains("Space"));
        assertTrue(PatternHints.STATUS.contains(PatternHints.EMPTY));
        assertTrue(StatusLine.HINT.contains("Help"));
    }
}
