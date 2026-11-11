package hits.ui;

import hits.Beat;
import hits.BeatJson;
import hits.Editor;
import hits.ExportOptions;
import hits.SequenceBuilder;
import hits.Track;
import hits.TrackMode;

import org.junit.jupiter.api.Test;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.sound.midi.MidiSystem;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyboard cursor, the click path for a key toggle, accessible names, and shortcut conflicts.
 * The cursor does not wrap. Space stays play/stop, including while the grid has focus.
 */
class KeyboardGridTest {
    @Test
    void cursorMovesAndStopsAtTheEdges() {
        Editor editor = new Editor(Beat.drumKit("cursor"));
        String before = BeatJson.write(editor.beat());
        JPanel source = new JPanel();
        assertEquals(-1, editor.stepIndex());

        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_LEFT)));
        assertEquals(0, editor.trackIndex());
        assertEquals(0, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_UP)));
        assertTrue(GridKeys.apply(editor, key(source, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_UP)));
        assertEquals(0, editor.trackIndex());
        assertEquals(0, editor.stepIndex());

        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_RIGHT)));
        assertEquals(1, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_KP_RIGHT)));
        assertEquals(2, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_DOWN)));
        assertEquals(1, editor.trackIndex());
        assertEquals(2, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_DOWN)));
        assertEquals(2, editor.trackIndex());

        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_END)));
        assertEquals(2, editor.trackIndex());
        assertEquals(15, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_RIGHT)));
        assertEquals(15, editor.stepIndex());
        assertEquals(2, editor.trackIndex());
        for (int i = 0; i < 20; i++) {
            assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_DOWN)));
        }
        assertEquals(7, editor.trackIndex());
        assertEquals(15, editor.stepIndex());
        assertTrue(GridKeys.apply(editor, key(source, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_DOWN)));
        assertEquals(7, editor.trackIndex());

        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_HOME)));
        assertEquals(7, editor.trackIndex());
        assertEquals(0, editor.stepIndex());
        assertEquals(before, BeatJson.write(editor.beat()));

        editor.beat().setStepCount(32);
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_END)));
        assertEquals(31, editor.stepIndex());

        Editor shorter = new Editor(Beat.drumKit("short"));
        shorter.beat().setSlotSteps('a', 8);
        shorter.selectStep(1, 3);
        assertTrue(GridKeys.apply(shorter, key(source, 0, KeyEvent.VK_END)));
        assertEquals(1, shorter.trackIndex());
        assertEquals(15, shorter.stepIndex(), "end uses the drawn columns, including steps past the pattern length");
        assertFalse(GridKeys.apply(editor, key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_DOWN)));
        assertEquals(7, editor.trackIndex());
    }

    @Test
    void enterTogglesThroughTheClickAndUndoes() throws Exception {
        Editor editor = new Editor(Beat.drumKit("keys"));
        String before = BeatJson.write(editor.beat());
        byte[] midiBefore = midi(editor.beat());
        PatternPanel panel = new PatternPanel(editor);
        assertFalse(panel.handleKey(key(panel, 0, KeyEvent.VK_SPACE)));
        assertFalse(editor.beat().track(0).step(0).on());

        KeyEvent enter = key(panel, 0, KeyEvent.VK_ENTER);
        assertTrue(panel.handleKey(enter));
        assertTrue(panel.handleKey(enter));
        assertTrue(editor.beat().track(0).step(0).on());
        assertEquals(100, editor.beat().track(0).step(0).velocity());

        Editor mouse = new Editor(Beat.drumKit("keys"));
        mouse.tap(0, 0, false);
        assertEquals(BeatJson.write(mouse.beat()), BeatJson.write(editor.beat()));
        assertArrayEquals(midi(mouse.beat()), midi(editor.beat()));

        editor.undo();
        assertFalse(editor.beat().track(0).step(0).on());
        assertEquals(before, BeatJson.write(editor.beat()));
        assertArrayEquals(midiBefore, midi(editor.beat()));
        editor.undo();
        assertEquals(before, BeatJson.write(editor.beat()));
        editor.redo();
        assertTrue(editor.beat().track(0).step(0).on());

        panel.handleKey(released(panel, KeyEvent.VK_ENTER));
        assertTrue(panel.handleKey(key(panel, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_ENTER)));
        Editor accent = new Editor(Beat.drumKit("keys"));
        accent.tap(0, 0, false);
        accent.tap(0, 0, true);
        assertEquals(BeatJson.write(accent.beat()), BeatJson.write(editor.beat()));
        assertEquals(editor.beat().track(0).accent(), editor.beat().track(0).step(0).velocity());
    }

    @Test
    void slideAndVelocityFollowTheCursor() {
        Editor editor = new Editor(Beat.drumKit("glide"));
        JPanel source = new JPanel();
        Track bass = editor.beat().track(3);
        bass.setMode(TrackMode.NOTE);
        bass.setName("Bass");
        editor.selectStep(0, 2);
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_DOWN)));
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_DOWN)));
        assertTrue(GridKeys.apply(editor, key(source, 0, KeyEvent.VK_DOWN)));
        assertEquals(3, editor.trackIndex());
        assertEquals(2, editor.stepIndex());
        assertFalse(GridKeys.gridClaim(key(source, 0, KeyEvent.VK_G)));

        editor.toggleSlide(editor.trackIndex(), editor.stepIndex());
        assertTrue(bass.step(2).on());
        assertTrue(bass.step(2).slide());
        assertFalse(editor.beat().track(0).step(2).slide());
        int columns = editor.beat().gridColumns(editor.viewSlot());
        assertEquals("Bass, step 3 of 16, on, slide", GridAccess.cellName(bass, 2, columns));

        int row = editor.trackIndex();
        int column = editor.stepIndex();
        editor.edit(beat -> {
            Track track = beat.slot(editor.viewSlot())[row];
            if (track.step(column).on()) {
                track.step(column).setVelocity(40);
            }
        });
        assertEquals(40, bass.step(2).velocity());
        assertEquals("Bass, step 3 of 16, on, quiet, slide", GridAccess.cellName(editor.viewTrack(row), column, columns));
        assertEquals(40, TrackReadout.shownVelocity(editor.selectedTrack(), editor.stepIndex()));

        editor.selectStep(0, 1);
        editor.toggleSlide(editor.trackIndex(), editor.stepIndex());
        assertFalse(editor.beat().track(0).step(1).slide());
        assertFalse(editor.beat().track(0).step(1).on());
    }

    @Test
    void namesTheGridTheRowAndTheFocusedCell() {
        Editor editor = new Editor(Beat.drumKit("names"));
        PatternPanel panel = new PatternPanel(editor);
        editor.selectStep(0, 4);
        AccessibleContext grid = panel.getAccessibleContext();
        assertEquals(GridAccess.GRID_NAME, grid.getAccessibleName());
        assertEquals(AccessibleRole.TABLE, grid.getAccessibleRole());
        assertEquals(panel.getToolTipText(), grid.getAccessibleDescription());
        assertTrue(panel.getToolTipText().contains("Click a step"));
        assertTrue(panel.getToolTipText().contains("G"));
        assertTrue(panel.isFocusable());
        assertTrue(panel.getFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS)
            .contains(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));

        assertEquals(8, grid.getAccessibleChildrenCount());
        assertEquals("Kick", grid.getAccessibleChild(0).getAccessibleContext().getAccessibleName());
        assertEquals("Snare", grid.getAccessibleChild(1).getAccessibleContext().getAccessibleName());
        AccessibleContext cell = grid.getAccessibleChild(0).getAccessibleContext().getAccessibleChild(4).getAccessibleContext();
        assertEquals("Kick, step 5 of 16, off", cell.getAccessibleName());
        assertTrue(cell.getAccessibleStateSet().contains(AccessibleState.SELECTED));

        editor.tap(0, 4, true);
        assertEquals("Kick, step 5 of 16, on, accent", cell.getAccessibleName());
        editor.beat().track(0).setMute(true);
        assertEquals("Kick, step 5 of 16, on, accent, muted", cell.getAccessibleName());

        List<String> announced = new ArrayList<>();
        grid.addPropertyChangeListener(event -> {
            if (AccessibleContext.ACCESSIBLE_TEXT_PROPERTY.equals(event.getPropertyName())) {
                announced.add(String.valueOf(event.getNewValue()));
            }
        });
        List<PropertyChangeEvent> renamed = new ArrayList<>();
        cell.addPropertyChangeListener(event -> {
            if (AccessibleContext.ACCESSIBLE_NAME_PROPERTY.equals(event.getPropertyName())) {
                renamed.add(event);
            }
        });
        editor.beat().track(0).setMute(false);
        assertTrue(panel.handleKey(key(panel, 0, KeyEvent.VK_ENTER)));
        assertEquals("Kick, step 5 of 16, off", announced.get(announced.size() - 1));
        assertEquals("Kick, step 5 of 16, on, accent", renamed.get(renamed.size() - 1).getOldValue());
        assertEquals("Kick, step 5 of 16, off", renamed.get(renamed.size() - 1).getNewValue());
        assertEquals("Kick, step 5 of 16, off", cell.getAccessibleName());
    }

    @Test
    void gridKeysDoNotReuseAWindowShortcut() {
        JPanel source = new JPanel();
        int[] codes = {
            KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
            KeyEvent.VK_KP_LEFT, KeyEvent.VK_KP_RIGHT, KeyEvent.VK_KP_UP, KeyEvent.VK_KP_DOWN,
            KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE,
            KeyEvent.VK_TAB, KeyEvent.VK_M, KeyEvent.VK_S, KeyEvent.VK_G, KeyEvent.VK_T,
            KeyEvent.VK_Z, KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_F1,
            KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD,
            KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT, KeyEvent.VK_D
        };
        int[] mods = {
            0,
            InputEvent.SHIFT_DOWN_MASK,
            InputEvent.CTRL_DOWN_MASK,
            InputEvent.ALT_DOWN_MASK,
            InputEvent.META_DOWN_MASK,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
            InputEvent.ALT_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK,
            InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
        };
        for (int code : codes) {
            for (int mod : mods) {
                KeyEvent event = key(source, mod, code);
                assertFalse(
                    GridKeys.gridClaim(event) && KeyboardShortcuts.globalClaim(event),
                    KeyEvent.getKeyText(code) + " mods " + mod
                );
            }
        }
        assertTrue(GridKeys.gridClaim(key(source, 0, KeyEvent.VK_ENTER)));
        assertFalse(KeyboardShortcuts.globalClaim(key(source, 0, KeyEvent.VK_ENTER)));
        assertTrue(GridKeys.gridClaim(key(source, InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_ENTER)));
        assertFalse(GridKeys.gridClaim(key(source, 0, KeyEvent.VK_SPACE)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, 0, KeyEvent.VK_SPACE)));
        assertTrue(GridKeys.gridClaim(key(source, 0, KeyEvent.VK_UP)));
        assertTrue(GridKeys.gridClaim(key(source, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_UP)));
        assertFalse(KeyboardShortcuts.globalClaim(key(source, InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_DOWN)));
        assertFalse(GridKeys.gridClaim(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UP)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UP)));
        assertFalse(GridKeys.gridClaim(key(source, 0, KeyEvent.VK_G)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, 0, KeyEvent.VK_G)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, 0, KeyEvent.VK_M)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, 0, KeyEvent.VK_S)));
        int menu = KeyboardShortcuts.menuShortcutMask();
        assertTrue(KeyboardShortcuts.globalClaim(key(source, menu, KeyEvent.VK_Z)));
        assertFalse(GridKeys.gridClaim(key(source, menu, KeyEvent.VK_Z)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, menu | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_Z)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, menu, KeyEvent.VK_S)));
        assertFalse(GridKeys.gridClaim(key(source, menu, KeyEvent.VK_S)));
        assertTrue(KeyboardShortcuts.globalClaim(key(source, menu | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_S)));

        String text = KeyboardShortcuts.shortcutText();
        assertTrue(text.contains("Left  Right  Move the cursor"));
        assertTrue(text.contains("does not wrap"));
        assertTrue(text.contains("Ctrl+Up  Ctrl+Down"));
        assertTrue(text.contains("Alt+Up and Alt+Down still reorder"));
        assertTrue(text.contains("Home  End"));
        assertTrue(text.contains("Shift+Enter makes a loud hit"));
        assertTrue(text.contains("including while the step grid has focus"));
        String shown = labels(ShortcutsDialog.panel());
        assertTrue(shown.contains("does not wrap"));
        assertTrue(shown.contains("Ctrl+Up  Ctrl+Down"));
        assertTrue(shown.contains("Shift+Enter makes a loud hit"));
    }

    private static byte[] midi(Beat beat) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(SequenceBuilder.export(beat, ExportOptions.allTracks()), 1, out);
        return out.toByteArray();
    }

    private static KeyEvent key(Component source, int modifiers, int code) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent released(Component source, int code) {
        return new KeyEvent(source, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static String labels(Component component) {
        StringBuilder text = new StringBuilder();
        collect(component, text);
        return text.toString();
    }

    private static void collect(Component component, StringBuilder text) {
        if (component instanceof JLabel label && label.getText() != null) {
            text.append(label.getText()).append('\n');
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, text);
            }
        }
    }
}
