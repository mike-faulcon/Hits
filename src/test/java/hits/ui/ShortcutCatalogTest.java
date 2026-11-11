package hits.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Help lists the shortcuts the window actually installs. */
class ShortcutCatalogTest {
    @Test
    void helpTextComesFromTheKeyCodes() {
        String text = KeyboardShortcuts.shortcutText();
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.PLAY) + "  Play or stop"));
        assertTrue(text.contains("Enter or Space  Expand or collapse the focused inspector section"));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.TAP) + "  Tap the tempo"));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.SLIDE) + "  Toggle slide"));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.MUTE) + "  Mute"));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.SOLO) + "  Solo"));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.EARLIER)));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.LATER)));
        assertTrue(text.contains(KeyboardShortcuts.keyName(KeyboardShortcuts.HELP) + "  Show this list"));
        String mod = KeyboardShortcuts.menuShortcutName();
        assertTrue(text.contains(mod + "+Z  Undo"));
        assertTrue(text.contains(mod + "+Shift+Z  Redo"));
        assertTrue(text.contains(mod + "+S  Save"));
        assertTrue(text.contains(mod + "+Shift+S  Save as"));

        int[] rowKeys = {
            KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD,
            KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT,
            KeyEvent.VK_D, KeyEvent.VK_UP, KeyEvent.VK_DOWN
        };
        JPanel source = new JPanel();
        for (int code : rowKeys) {
            assertTrue(KeyboardShortcuts.rowShortcut(key(source, InputEvent.ALT_DOWN_MASK, code)), "alt " + code);
            String label = KeyboardShortcuts.rowLabel(code);
            assertNotNull(label, "label " + code);
            assertTrue(text.contains(label), label);
        }
        for (KeyboardShortcuts.Shortcut mouse : KeyboardShortcuts.mouseShortcuts()) {
            assertTrue(KeyboardShortcuts.gridTip().contains(mouse.keys()), mouse.keys());
            assertTrue(text.contains(mouse.keys() + "  " + mouse.what()));
        }
    }

    @Test
    void theDialogShowsEveryCatalogRow() {
        JPanel panel = ShortcutsDialog.panel();
        String shown = labels(panel);
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.shortcuts()) {
            assertTrue(shown.contains(shortcut.keys()), shortcut.keys());
            assertTrue(shown.contains(shortcut.what()), shortcut.what());
        }
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.mouseShortcuts()) {
            assertTrue(shown.contains(shortcut.keys()), shortcut.keys());
            assertTrue(shown.contains(shortcut.what()), shortcut.what());
        }
        assertTrue(shown.contains("Keys"));
        assertTrue(shown.contains("Mouse"));
    }

    @Test
    void helpMenuUsesTheSameHelpKey() {
        JMenuBar bar = HelpMenu.bar(() -> {});
        JMenuItem item = item(bar);
        assertEquals("Keyboard Shortcuts", item.getText());
        assertEquals("Every key and mouse gesture Hits uses.", item.getToolTipText());
        assertEquals(KeyStroke.getKeyStroke(KeyboardShortcuts.HELP, 0), item.getAccelerator());
    }

    @Test
    void saveAndUndoAreInstalledOnTheWindow() {
        JRootPane root = new JRootPane();
        KeyboardShortcuts.install(root, () -> {}, () -> {}, () -> {}, () -> {});
        int mask = KeyboardShortcuts.menuShortcutMask();
        var input = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        assertEquals("undo", input.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask)));
        assertEquals("redo", input.get(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask | InputEvent.SHIFT_DOWN_MASK)));
        assertEquals("save", input.get(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask)));
        assertEquals("saveAs", input.get(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask | InputEvent.SHIFT_DOWN_MASK)));
    }

    @Test
    void muteAndSoloArePlainKeys() {
        JPanel source = new JPanel();
        assertTrue(KeyboardShortcuts.plainShortcut(key(source, 0, KeyboardShortcuts.MUTE), KeyboardShortcuts.MUTE));
        assertTrue(KeyboardShortcuts.plainShortcut(key(source, 0, KeyboardShortcuts.SOLO), KeyboardShortcuts.SOLO));
        assertTrue(!KeyboardShortcuts.plainShortcut(
            key(source, InputEvent.CTRL_DOWN_MASK, KeyboardShortcuts.SOLO),
            KeyboardShortcuts.SOLO
        ));
    }

    private static JMenuItem item(JMenuBar bar) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu == null) {
                continue;
            }
            for (int j = 0; j < menu.getItemCount(); j++) {
                JMenuItem item = menu.getItem(j);
                if (item != null && "keyboard-shortcuts".equals(item.getName())) {
                    return item;
                }
            }
        }
        throw new AssertionError("Keyboard Shortcuts was not in the Help menu");
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

    private static KeyEvent key(Component source, int modifiers, int code) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }
}
