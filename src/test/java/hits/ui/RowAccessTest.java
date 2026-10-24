package hits.ui;

import hits.Beat;
import hits.Editor;

import org.junit.jupiter.api.Test;

import javax.accessibility.AccessibleState;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Row buttons, mixer strips, and the Alt shortcuts. No window, so CI can run it headless. */
class RowAccessTest {
    @Test
    void rowButtonsMatchTheCountAndTheAccessibleName() {
        JButton add = StudioFrame.rowButton("Add row", "add-row", "Alt and equals.");
        JButton duplicate = StudioFrame.rowButton("Duplicate", "duplicate-row", "Alt+D.");
        JButton remove = StudioFrame.rowButton("Remove", "remove-row", "Alt and minus.");
        JButton up = StudioFrame.rowButton("Up", "move-row-up", "Alt+Up.");
        JButton down = StudioFrame.rowButton("Down", "move-row-down", "Alt+Down.");

        assertEquals("Add row", add.getAccessibleContext().getAccessibleName());
        assertEquals("Duplicate row", duplicate.getAccessibleContext().getAccessibleName());
        assertEquals("Remove row", remove.getAccessibleContext().getAccessibleName());
        assertEquals("Move row up", up.getAccessibleContext().getAccessibleName());
        assertEquals("Move row down", down.getAccessibleContext().getAccessibleName());
        assertFalse(add.isFocusable());
        assertFalse(remove.isFocusable());

        StudioFrame.showRowActions(add, duplicate, remove, up, down, Beat.MAX_TRACKS, 0);
        assertFalse(add.isEnabled());
        assertFalse(duplicate.isEnabled());
        assertTrue(remove.isEnabled());
        assertFalse(up.isEnabled());
        assertTrue(down.isEnabled());
        assertFalse(add.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.ENABLED));
        assertTrue(remove.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.ENABLED));
        assertTrue(down.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.ENABLED));

        StudioFrame.showRowActions(add, duplicate, remove, up, down, Beat.MIN_TRACKS, 0);
        assertTrue(add.isEnabled());
        assertFalse(remove.isEnabled());
        assertFalse(down.isEnabled());
        assertFalse(remove.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.ENABLED));
    }

    @Test
    void mixerNamesEveryRowAndHidesTheOnesPastTheBeat() {
        Beat beat = Beat.drumKit("mixer");
        beat.setTrackCount(Beat.MAX_TRACKS);
        beat.track(15).setMute(true);
        beat.track(15).setSolo(true);
        Editor editor = new Editor(beat);
        MixerPanel mixer = new MixerPanel(editor, new Sync());
        mixer.wire();
        mixer.refresh();

        JButton mute = findButton(mixer.component(), "mute-15");
        JButton solo = findButton(mixer.component(), "solo-15");
        assertNotNull(mute, "mute-15");
        assertNotNull(solo, "solo-15");
        assertEquals("Mute row 16", mute.getAccessibleContext().getAccessibleName());
        assertEquals("Solo row 16", solo.getAccessibleContext().getAccessibleName());
        assertTrue(mute.isSelected());
        assertTrue(solo.isSelected());
        assertTrue(mute.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
        assertTrue(solo.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
        assertTrue(mute.isVisible());

        editor.beat().setTrackCount(9);
        mixer.refresh();
        assertFalse(shown(findButton(mixer.component(), "mute-9")));
        assertTrue(shown(findButton(mixer.component(), "mute-8")));
        JLabel name = findLabel(mixer.component(), "9  Sub");
        assertNotNull(name, "row 9 is the sine 808");
    }

    @Test
    void sixteenRowsUseAShorterCellAndStillScroll() {
        Editor editor = new Editor(Beat.drumKit("grid"));
        PatternPanel panel = new PatternPanel(editor);
        int eight = panel.getPreferredSize().height;
        editor.beat().setTrackCount(11);
        int eleven = panel.getPreferredSize().height;
        editor.beat().setTrackCount(Beat.MAX_TRACKS);
        int sixteen = panel.getPreferredSize().height;
        assertEquals(24 + 8 * 26 + 56, eight);
        assertEquals(24 + 11 * 22 + 56, eleven);
        assertEquals(24 + 16 * 20 + 56, sixteen);
        assertTrue(sixteen > eight);
        assertTrue(sixteen < eight * 2);
        assertFalse(panel.getScrollableTracksViewportHeight());
    }

    @Test
    void altKeysAreTheRowShortcuts() {
        JPanel source = new JPanel();
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_EQUALS)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_PLUS)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ADD)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_MINUS)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_SUBTRACT)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_D)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_UP)));
        assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_DOWN)));
        assertFalse(KeyboardShortcuts.rowShortcut(key(source, 0, KeyEvent.VK_EQUALS)));
        assertFalse(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_D)));
        assertFalse(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_SPACE)));
        KeyEvent released = key(source, InputEvent.ALT_DOWN_MASK, KeyEvent.VK_EQUALS);
        released.setKeyCode(KeyEvent.VK_EQUALS);
        assertFalse(KeyboardShortcuts.rowShortcut(new KeyEvent(
            source,
            KeyEvent.KEY_RELEASED,
            0,
            InputEvent.ALT_DOWN_MASK,
            KeyEvent.VK_EQUALS,
            '='
        )));
    }

    private static KeyEvent key(JPanel source, int modifiers, int code) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static boolean shown(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return component != null;
    }

    private static JButton findButton(Component root, String name) {
        if (root instanceof JButton button && name.equals(button.getName())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel findLabel(Component root, String text) {
        if (root instanceof JLabel label && text.equals(label.getText())) {
            return label;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = findLabel(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
