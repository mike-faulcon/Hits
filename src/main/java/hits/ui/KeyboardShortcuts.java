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
 * Keys the window handles. {@link #shortcuts()} and {@link #mouseShortcuts()} are the list
 * Help shows. The dispatchers below use the same key codes. Grid cursor keys live in
 * {@link GridKeys} and are listed here too, so the dialog and the conflict check share one list.
 */
final class KeyboardShortcuts {
    static final int PLAY = KeyEvent.VK_SPACE;
    static final int TAP = KeyEvent.VK_T;
    static final int SLIDE = KeyEvent.VK_G;
    static final int MUTE = KeyEvent.VK_M;
    static final int SOLO = KeyEvent.VK_S;
    static final int EARLIER = KeyEvent.VK_COMMA;
    static final int LATER = KeyEvent.VK_PERIOD;
    static final int HELP = KeyEvent.VK_F1;

    record Shortcut(String keys, String what) {}

    private KeyboardShortcuts() {}

    static void install(JRootPane root, Runnable undo, Runnable redo, Runnable save, Runnable saveAs) {
        int shortcut = menuShortcutMask();
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

    /**
     * T taps the tempo. A held key does not count as more taps.
     * A text field still receives the character.
     */
    static KeyEventDispatcher tapTempo(Window window, Runnable tap) {
        return new TapKey(window, tap);
    }

    /**
     * G toggles slide on the selected step. A held key counts once.
     * A text field still receives the character. Drum rows ignore it.
     */
    static KeyEventDispatcher glide(Window window, Runnable toggle) {
        return new GlideKey(window, toggle);
    }

    /**
     * Comma nudges the selected step earlier. Period nudges it later.
     * Repeats while the key is held. A text field still receives the character.
     */
    static KeyEventDispatcher nudge(Window window, Runnable earlier, Runnable later) {
        return new NudgeKeys(window, earlier, later);
    }

    /**
     * Alt+Up and Alt+Down move the selected row. Alt+= adds one, Alt+- removes it,
     * and Alt+D duplicates it. A text field still receives the character.
     */
    static KeyEventDispatcher rows(Window window, Runnable add, Runnable remove, Runnable duplicate, Runnable up, Runnable down) {
        return new RowKeys(window, add, remove, duplicate, up, down);
    }

    /** M mutes the selected row. S solos it. A text field still receives the character. */
    static KeyEventDispatcher muteSolo(Window window, Runnable mute, Runnable solo) {
        return new MuteSolo(window, mute, solo);
    }

    /**
     * Ctrl on Windows and Linux, ⌘ on macOS. The same mask {@link #install} uses.
     * A headless test run has no menu mask, so it stays Ctrl.
     */
    static int menuShortcutMask() {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException exception) {
            return InputEvent.CTRL_DOWN_MASK;
        }
    }

    static String menuShortcutName() {
        if ((menuShortcutMask() & InputEvent.META_DOWN_MASK) != 0) {
            return "⌘";
        }
        return "Ctrl";
    }

    static String keyName(int code) {
        return switch (code) {
            case PLAY -> "Space";
            case EARLIER -> ",";
            case LATER -> ".";
            case HELP -> "F1";
            default -> KeyEvent.getKeyText(code);
        };
    }

    /** Label for a row shortcut key, or null when that key is not one. */
    static String rowLabel(int code) {
        return switch (code) {
            case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> "Alt+=";
            case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> "Alt+-";
            case KeyEvent.VK_D -> "Alt+D";
            case KeyEvent.VK_UP -> "Alt+Up";
            case KeyEvent.VK_DOWN -> "Alt+Down";
            default -> null;
        };
    }

    /** Keyboard shortcuts, in the order Help shows them. Names come from the key codes above. */
    static java.util.List<Shortcut> shortcuts() {
        String mod = menuShortcutName();
        return java.util.List.of(
            new Shortcut(keyName(PLAY), "Play or stop, including while the step grid has focus. A text field still types a space."),
            new Shortcut("Tab", "Move focus. The step grid is in the Tab order and shows the cursor while it has focus."),
            new Shortcut("Left  Right", "Move the cursor one step. It stops at the first and last step and does not wrap."),
            new Shortcut("Up  Down", "Move the cursor to the row above or below. It stops at the first and last row."),
            new Shortcut("Ctrl+Up  Ctrl+Down", "Move the cursor between rows. Alt+Up and Alt+Down still reorder the row."),
            new Shortcut("Home  End", "Jump to the first or last step on this row."),
            new Shortcut("Enter", "Toggle the step under the cursor. Shift+Enter makes a loud hit."),
            new Shortcut(keyName(TAP), "Tap the tempo. A text field still types T."),
            new Shortcut(keyName(SLIDE), "Toggle slide on the selected step. Note and sample rows, including Sub."),
            new Shortcut(keyName(EARLIER) + "  " + keyName(LATER), "Nudge the selected step earlier or later."),
            new Shortcut(keyName(MUTE), "Mute the selected row."),
            new Shortcut(keyName(SOLO), "Solo the selected row. A muted row stays quiet."),
            new Shortcut(rowLabel(KeyEvent.VK_EQUALS), "Add a row. Alt and plus, or Alt and the numpad plus, do this too."),
            new Shortcut(rowLabel(KeyEvent.VK_MINUS), "Remove the selected row. Alt and the numpad minus do this too."),
            new Shortcut(rowLabel(KeyEvent.VK_D), "Duplicate the selected row."),
            new Shortcut(rowLabel(KeyEvent.VK_UP), "Move the selected row up."),
            new Shortcut(rowLabel(KeyEvent.VK_DOWN), "Move the selected row down."),
            new Shortcut(mod + "+Z", "Undo a grid edit, a row change, or another edit."),
            new Shortcut(mod + "+Shift+Z", "Redo."),
            new Shortcut(mod + "+S", "Save."),
            new Shortcut(mod + "+Shift+S", "Save as."),
            new Shortcut(keyName(HELP), "Show this list.")
        );
    }

    /**
     * Mouse gestures the grid handles. {@link #gridTip()} is the sentence on the step grid,
     * and it contains each of these labels.
     */
    static java.util.List<Shortcut> mouseShortcuts() {
        return java.util.List.of(
            new Shortcut("Click a step", "Turn that step on or off."),
            new Shortcut("Shift-click", "Turn a step on as a loud hit."),
            new Shortcut("Ctrl-click", "Toggle slide on a note or sample row. Command-click does this too."),
            new Shortcut("Right-click", "Select a step without turning it off. Alt-click does this too."),
            new Shortcut("Drag up or down", "Set how hard that hit is."),
            new Shortcut("Drag sideways", "Set how many steps a lit hit holds.")
        );
    }

    static String gridTip() {
        return "Click a step to toggle it. Shift-click makes a loud hit. "
            + "Ctrl-click or G toggles slide on a note or sample row. "
            + "Drag up or down to set velocity. Drag sideways on a lit pad to set how many steps it holds. "
            + "Right-click or Alt-click selects it so you can change its volume, chance, or step pitch. "
            + "Tab to this grid, then the arrow keys move the cursor and Enter toggles that step.";
    }

    static String shortcutText() {
        StringBuilder text = new StringBuilder();
        for (Shortcut shortcut : shortcuts()) {
            text.append(shortcut.keys()).append("  ").append(shortcut.what()).append('\n');
        }
        for (Shortcut shortcut : mouseShortcuts()) {
            text.append(shortcut.keys()).append("  ").append(shortcut.what()).append('\n');
        }
        return text.toString();
    }

    static boolean typing() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent;
    }

    /** Shift, Ctrl, Alt, and ⌘. Button and AltGr bits are ignored so a chord stays stable. */
    static int chordModifiers(KeyEvent event) {
        return event.getModifiersEx() & (
            InputEvent.SHIFT_DOWN_MASK
                | InputEvent.CTRL_DOWN_MASK
                | InputEvent.META_DOWN_MASK
                | InputEvent.ALT_DOWN_MASK
        );
    }

    /**
     * True when the window already handles this key, outside a text field.
     * {@link GridKeys} must not claim the same chord. F1 still opens Help from a text field.
     */
    static boolean globalClaim(KeyEvent event) {
        if (event == null || event.getID() != KeyEvent.KEY_PRESSED) {
            return false;
        }
        int mods = chordModifiers(event);
        int code = event.getKeyCode();
        if (code == HELP && mods == 0) {
            return true;
        }
        if (typing()) {
            return false;
        }
        if (mods == 0 && (code == PLAY || code == TAP || code == SLIDE || code == MUTE || code == SOLO
            || code == EARLIER || code == LATER)) {
            return true;
        }
        if (rowShortcut(event)) {
            return true;
        }
        int menu = menuShortcutMask();
        if (code == KeyEvent.VK_Z && (mods == menu || mods == (menu | InputEvent.SHIFT_DOWN_MASK))) {
            return true;
        }
        return code == KeyEvent.VK_S && (mods == menu || mods == (menu | InputEvent.SHIFT_DOWN_MASK));
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
            boolean space = event.getKeyCode() == PLAY
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

    private static final class TapKey implements KeyEventDispatcher {
        private final Window window;
        private final Runnable tap;
        private boolean down;

        private TapKey(Window window, Runnable tap) {
            this.window = window;
            this.tap = tap;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean tee = event.getKeyCode() == TAP
                || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && (event.getKeyChar() == 't' || event.getKeyChar() == 'T'));
            if (!tee) {
                return false;
            }
            if (event.getID() == KeyEvent.KEY_RELEASED) {
                down = false;
            }
            if (event.getModifiersEx() != 0 || typing()) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                down = false;
                return focused != null && focused.getOwner() == window;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED && !down) {
                down = true;
                SwingUtilities.invokeLater(tap);
            }
            return true;
        }
    }

    private static final class GlideKey implements KeyEventDispatcher {
        private final Window window;
        private final Runnable toggle;
        private boolean down;

        private GlideKey(Window window, Runnable toggle) {
            this.window = window;
            this.toggle = toggle;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean gee = event.getKeyCode() == SLIDE
                || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && (event.getKeyChar() == 'g' || event.getKeyChar() == 'G'));
            if (!gee) {
                return false;
            }
            if (event.getID() == KeyEvent.KEY_RELEASED) {
                down = false;
            }
            if (event.getModifiersEx() != 0 || typing()) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                down = false;
                return focused != null && focused.getOwner() == window;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED && !down) {
                down = true;
                SwingUtilities.invokeLater(toggle);
            }
            return true;
        }
    }

    private static final class NudgeKeys implements KeyEventDispatcher {
        private final Window window;
        private final Runnable earlier;
        private final Runnable later;

        private NudgeKeys(Window window, Runnable earlier, Runnable later) {
            this.window = window;
            this.earlier = earlier;
            this.later = later;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            Boolean laterKey = nudgeKey(event);
            if (laterKey == null) {
                return false;
            }
            if (event.getID() != KeyEvent.KEY_PRESSED || event.getModifiersEx() != 0 || typing()) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                return focused != null && focused.getOwner() == window;
            }
            SwingUtilities.invokeLater(laterKey ? later : earlier);
            return true;
        }

        private static Boolean nudgeKey(KeyEvent event) {
            int code = event.getKeyCode();
            char typed = event.getKeyChar();
            if (code == EARLIER || typed == ',') {
                return Boolean.FALSE;
            }
            if (code == LATER || typed == '.') {
                return Boolean.TRUE;
            }
            return null;
        }
    }

    private static final class RowKeys implements KeyEventDispatcher {
        private final Window window;
        private final Runnable add;
        private final Runnable remove;
        private final Runnable duplicate;
        private final Runnable up;
        private final Runnable down;

        private RowKeys(
            Window window,
            Runnable add,
            Runnable remove,
            Runnable duplicate,
            Runnable up,
            Runnable down
        ) {
            this.window = window;
            this.add = add;
            this.remove = remove;
            this.duplicate = duplicate;
            this.up = up;
            this.down = down;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (!rowShortcut(event)) {
                return false;
            }
            Runnable action = action(event.getKeyCode());
            if (action == null) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                return focused != null && focused.getOwner() == window;
            }
            SwingUtilities.invokeLater(action);
            return true;
        }

        private Runnable action(int keyCode) {
            return switch (keyCode) {
                case KeyEvent.VK_UP -> up;
                case KeyEvent.VK_DOWN -> down;
                case KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD -> add;
                case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> remove;
                case KeyEvent.VK_D -> duplicate;
                default -> null;
            };
        }
    }

    /**
     * Alt alone, plus a row key, and the cursor is not in a text field.
     * Equals, plus, and the numpad add key add a row. Minus removes it.
     * D duplicates it. Up and Down move it.
     */
    static boolean rowShortcut(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED || typing()) {
            return false;
        }
        int mods = event.getModifiersEx();
        if ((mods & InputEvent.ALT_DOWN_MASK) == 0 || (mods & ~InputEvent.ALT_DOWN_MASK) != 0) {
            return false;
        }
        return switch (event.getKeyCode()) {
            case KeyEvent.VK_UP, KeyEvent.VK_DOWN,
                KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD,
                KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT,
                KeyEvent.VK_D -> true;
            default -> false;
        };
    }

    /**
     * A letter with no modifiers, and the cursor is not in a text field.
     * M and S use this. Space, T, and G keep their own repeat rules.
     */
    static boolean plainShortcut(KeyEvent event, int code) {
        if (event.getID() != KeyEvent.KEY_PRESSED || typing()) {
            return false;
        }
        if (event.getModifiersEx() != 0) {
            return false;
        }
        if (event.getKeyCode() == code) {
            return true;
        }
        if (event.getKeyCode() != KeyEvent.VK_UNDEFINED) {
            return false;
        }
        char typed = event.getKeyChar();
        String name = keyName(code);
        return name.length() == 1 && (typed == name.charAt(0) || typed == Character.toLowerCase(name.charAt(0)));
    }

    private static final class MuteSolo implements KeyEventDispatcher {
        private final Window window;
        private final Runnable mute;
        private final Runnable solo;
        private boolean muteDown;
        private boolean soloDown;

        private MuteSolo(Window window, Runnable mute, Runnable solo) {
            this.window = window;
            this.mute = mute;
            this.solo = solo;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean muteKey = event.getKeyCode() == MUTE
                || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && (event.getKeyChar() == 'm' || event.getKeyChar() == 'M'));
            boolean soloKey = event.getKeyCode() == SOLO
                || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && (event.getKeyChar() == 's' || event.getKeyChar() == 'S'));
            if (!muteKey && !soloKey) {
                return false;
            }
            if (event.getID() == KeyEvent.KEY_RELEASED) {
                if (muteKey) {
                    muteDown = false;
                }
                if (soloKey) {
                    soloDown = false;
                }
            }
            if (event.getModifiersEx() != 0 || typing()) {
                return false;
            }
            Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
            if (focused != window) {
                muteDown = false;
                soloDown = false;
                return focused != null && focused.getOwner() == window;
            }
            if (event.getID() == KeyEvent.KEY_PRESSED && muteKey && !muteDown) {
                muteDown = true;
                SwingUtilities.invokeLater(mute);
            }
            if (event.getID() == KeyEvent.KEY_PRESSED && soloKey && !soloDown) {
                soloDown = true;
                SwingUtilities.invokeLater(solo);
            }
            return true;
        }
    }
}
