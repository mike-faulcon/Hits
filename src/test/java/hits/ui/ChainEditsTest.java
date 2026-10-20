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
        assertEquals("A ·1", ChainLabels.partText(editor.beat().chain().get(0), 16));
        assertEquals("B ·1", ChainLabels.partText(editor.beat().chain().get(1), 16));

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
        assertEquals("A×3 ·1", ChainLabels.partText(editor.beat().chain().get(1), 16));
        assertEquals(
            "A is 16 steps (1 bar) and plays 3 times. Click to select.",
            ChainLabels.partTip(editor.beat().chain().get(1), 16)
        );
        edits.setRepeats(3);
        editor.undo();
        assertEquals(1, editor.beat().chain().get(1).repeats());

        assertEquals("Holding pattern B. Click Song to follow the chain.", ChainLabels.hold('b'));
        assertEquals("The chain is empty. Playing pattern A.", ChainLabels.playingEmpty('a'));
        assertEquals("Following the song", ChainLabels.following());
        assertEquals("Add a pattern to the chain", ChainLabels.needEntry());
        assertEquals("C", ChainLabels.slotName('c'));
        assertEquals("H×4 ·1", ChainLabels.partText(new Chain.Part('h', 4), 16));
        assertEquals("C ·2", ChainLabels.partText(new Chain.Part('c', 1), 32));
        assertEquals("C ·12", ChainLabels.partText(new Chain.Part('c', 1), 12));
        assertEquals("Added H to the chain", new ChainEdits(editor).append('h'));
        assertEquals('h', editor.beat().chain().get(2).slot());
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
