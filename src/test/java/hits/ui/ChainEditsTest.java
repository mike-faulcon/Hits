package hits.ui;

import hits.Beat;
import hits.Chain;
import hits.Editor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainEditsTest {
    @Test
    void appendSelectsTheNewEntryAndRemoveClamps() {
        Editor editor = new Editor(Beat.drumKit("t"));
        ChainEdits edits = new ChainEdits(editor);
        assertEquals(-1, edits.index());

        assertEquals("Added A to the chain", edits.append('a'));
        assertEquals(0, edits.index());
        assertEquals('a', editor.beat().chain().get(0).slot());
        assertEquals(1, editor.beat().chain().get(0).repeats());

        assertEquals("Added B to the chain", edits.append('b'));
        assertEquals(1, edits.index());
        assertEquals("A", ChainLabels.partText(editor.beat().chain().get(0)));
        assertEquals("B", ChainLabels.partText(editor.beat().chain().get(1)));

        assertTrue(edits.remove());
        assertEquals(0, edits.index());
        assertEquals(1, editor.beat().chain().size());

        assertTrue(edits.remove());
        assertEquals(-1, edits.index());
        assertTrue(editor.beat().chain().isEmpty());
        assertFalse(edits.remove());
    }

    @Test
    void moveReorderAndRepeatsAreOneUndoEach() {
        Editor editor = new Editor(Beat.drumKit("t"));
        ChainEdits edits = new ChainEdits(editor);
        edits.append('a');
        edits.append('b');
        edits.select(0);
        assertTrue(edits.move(1));
        assertEquals(1, edits.index());
        assertEquals('b', editor.beat().chain().get(0).slot());
        assertEquals('a', editor.beat().chain().get(1).slot());
        assertFalse(edits.move(1));
        assertFalse(edits.canMoveDown());
        assertTrue(edits.canMoveUp());

        edits.setRepeats(3);
        assertEquals(3, editor.beat().chain().get(1).repeats());
        assertEquals("A×3", ChainLabels.partText(editor.beat().chain().get(1)));
        assertEquals(
            "A plays 3 times. Click to select.",
            ChainLabels.partTip(editor.beat().chain().get(1))
        );
        edits.setRepeats(3);
        editor.undo();
        assertEquals(1, editor.beat().chain().get(1).repeats());

        assertEquals("Holding pattern B. Click Song to follow the chain.", ChainLabels.hold('b'));
        assertEquals("The chain is empty. Playing pattern A.", ChainLabels.playingEmpty('a'));
        assertEquals("Following the song", ChainLabels.following());
        assertEquals("Add A or B to the chain", ChainLabels.needEntry());
    }

    @Test
    void fullChainRefusesAnotherEntry() {
        Editor editor = new Editor(Beat.drumKit("t"));
        ChainEdits edits = new ChainEdits(editor);
        for (int i = 0; i < Chain.MAX_PARTS; i++) {
            edits.append(i % 2 == 0 ? 'a' : 'b');
        }
        assertEquals(Chain.MAX_PARTS, editor.beat().chain().size());
        assertFalse(edits.canAdd());
        assertEquals("The chain holds " + Chain.MAX_PARTS + " entries", edits.append('a'));
        assertEquals(Chain.MAX_PARTS, editor.beat().chain().size());
        assertEquals(Chain.MAX_PARTS - 1, edits.index());
        editor.undo();
        assertEquals(Chain.MAX_PARTS - 1, editor.beat().chain().size());
    }

    @Test
    void clampFollowsAChainThatShrankOutsideTheSelection() {
        ChainSelection selection = new ChainSelection();
        selection.set(4);
        selection.clamp(3);
        assertEquals(2, selection.index());
        selection.clamp(0);
        assertEquals(-1, selection.index());
        selection.clamp(2);
        assertEquals(-1, selection.index());
    }

    @Test
    void listenerRefreshClampsBeforeRemoveFinishes() {
        Editor editor = new Editor(Beat.drumKit("t"));
        ChainEdits edits = new ChainEdits(editor);
        edits.append('a');
        edits.append('b');
        edits.select(1);
        editor.addListener(musical -> edits.clamp(editor.beat().chain().size()));
        assertTrue(edits.remove());
        assertEquals(0, edits.index());
        assertEquals('a', editor.beat().chain().get(0).slot());
    }
}
