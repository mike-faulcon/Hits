package hits.ui;

import hits.Editor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Cursor keys for the step grid, used only while that grid has focus.
 * The cursor does not wrap: Left on the first step stays there, and Down on the
 * last row stays there. Home and End use the drawn columns, including steps past
 * the pattern length. Enter and Shift+Enter call {@link Editor#tap}, the same
 * edit as a click and a Shift-click. Space is not handled here, so play/stop stays
 * with the window even while the grid is focused.
 */
final class GridKeys {
    private enum Action {
        LEFT, RIGHT, UP, DOWN, HOME, END, TOGGLE, ACCENT
    }

    private GridKeys() {}

    /** True when the grid owns this key while it is focused. */
    static boolean gridClaim(KeyEvent event) {
        return action(event) != null;
    }

    /** Moves the cursor or toggles its step. False when the grid ignores the key. */
    static boolean apply(Editor editor, KeyEvent event) {
        Action action = action(event);
        if (action == null || editor == null) {
            return false;
        }
        switch (action) {
            case LEFT -> move(editor, 0, -1);
            case RIGHT -> move(editor, 0, 1);
            case UP -> move(editor, -1, 0);
            case DOWN -> move(editor, 1, 0);
            case HOME -> jump(editor, 0);
            case END -> jump(editor, columns(editor) - 1);
            case TOGGLE -> editor.tap(row(editor), column(editor), false);
            case ACCENT -> editor.tap(row(editor), column(editor), true);
        }
        return true;
    }

    private static Action action(KeyEvent event) {
        if (event == null || event.getID() != KeyEvent.KEY_PRESSED) {
            return null;
        }
        int mods = KeyboardShortcuts.chordModifiers(event);
        int code = arrows(event.getKeyCode());
        if (mods == 0) {
            return switch (code) {
                case KeyEvent.VK_LEFT -> Action.LEFT;
                case KeyEvent.VK_RIGHT -> Action.RIGHT;
                case KeyEvent.VK_UP -> Action.UP;
                case KeyEvent.VK_DOWN -> Action.DOWN;
                case KeyEvent.VK_HOME -> Action.HOME;
                case KeyEvent.VK_END -> Action.END;
                case KeyEvent.VK_ENTER -> Action.TOGGLE;
                default -> null;
            };
        }
        if (mods == InputEvent.SHIFT_DOWN_MASK && code == KeyEvent.VK_ENTER) {
            return Action.ACCENT;
        }
        if (mods == InputEvent.CTRL_DOWN_MASK && code == KeyEvent.VK_UP) {
            return Action.UP;
        }
        if (mods == InputEvent.CTRL_DOWN_MASK && code == KeyEvent.VK_DOWN) {
            return Action.DOWN;
        }
        return null;
    }

    private static int arrows(int code) {
        return switch (code) {
            case KeyEvent.VK_KP_LEFT -> KeyEvent.VK_LEFT;
            case KeyEvent.VK_KP_RIGHT -> KeyEvent.VK_RIGHT;
            case KeyEvent.VK_KP_UP -> KeyEvent.VK_UP;
            case KeyEvent.VK_KP_DOWN -> KeyEvent.VK_DOWN;
            default -> code;
        };
    }

    /** No step selected is treated as step 1, which is where the cursor starts. */
    private static int column(Editor editor) {
        int columns = columns(editor);
        int column = editor.stepIndex();
        if (column < 0 || column >= columns) {
            return 0;
        }
        return column;
    }

    private static int row(Editor editor) {
        int rows = rows(editor);
        int row = editor.trackIndex();
        if (row < 0 || row >= rows) {
            return 0;
        }
        return row;
    }

    private static int columns(Editor editor) {
        return Math.max(1, editor.beat().gridColumns(editor.viewSlot()));
    }

    private static int rows(Editor editor) {
        return Math.max(1, editor.beat().trackCount());
    }

    private static void move(Editor editor, int dRow, int dColumn) {
        int row = clamp(row(editor) + dRow, 0, rows(editor) - 1);
        int column = clamp(column(editor) + dColumn, 0, columns(editor) - 1);
        editor.selectStep(row, column);
    }

    private static void jump(Editor editor, int column) {
        editor.selectStep(row(editor), clamp(column, 0, columns(editor) - 1));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
