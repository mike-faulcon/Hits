package hits.ui;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * Undo, redo, save, and save as, plus space for play/stop.
 * There is no menu bar. Shortcuts are installed on the window.
 */
final class KeyboardShortcuts {
    private KeyboardShortcuts() {}

    static void install(JRootPane root, Runnable undo, Runnable redo, Runnable save, Runnable saveAs) {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap input = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actions = root.getActionMap();
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "undo");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | InputEvent.SHIFT_DOWN_MASK), "redo");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), "save");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut | InputEvent.SHIFT_DOWN_MASK), "saveAs");
        actions.put("undo", action(event -> undo.run()));
        actions.put("redo", action(event -> redo.run()));
        actions.put("save", action(event -> save.run()));
        actions.put("saveAs", action(event -> saveAs.run()));
    }

    /**
     * Space is play/stop for the whole window. Buttons and the beat list would otherwise take it.
     * A text field still receives the character.
     */
    static KeyEventDispatcher spacePlay(Window window, Runnable togglePlay) {
        return new SpacePlay(window, togglePlay);
    }

    static boolean typing() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent;
    }

    private static AbstractAction action(Consumer<ActionEvent> handler) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                handler.accept(event);
            }
        };
    }

    private static final class SpacePlay implements KeyEventDispatcher {
        private final Window window;
        private final Runnable togglePlay;
        private boolean spaceDown;

        private SpacePlay(Window window, Runnable togglePlay) {
            this.window = window;
            this.togglePlay = togglePlay;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean space = event.getKeyCode() == KeyEvent.VK_SPACE
                || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && event.getKeyChar() == ' ');
            if (!space) {
                return false;
            }
            if (event.getID() == KeyEvent.KEY_RELEASED) {
                spaceDown = false;
            }
            if (event.getModifiersEx() != 0 || typing()) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                spaceDown = false;
                return focused != null && focused.getOwner() == window;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED && !spaceDown) {
                spaceDown = true;
                SwingUtilities.invokeLater(togglePlay);
            }
            return true;
        }
    }
}
