package hits.ui;

import javax.swing.JLabel;

/** The hint line along the bottom of the window. */
final class StatusLine {
    static final String HINT = "Space play/stop  ·  click step  ·  shift-click loud  ·  right-click select  ·  ⌘/Ctrl S save  ·  ⌘/Ctrl Z undo";

    private final JLabel label = new JLabel(HINT);

    StatusLine() {
        label.setForeground(Theme.MUTED);
    }

    JLabel component() {
        return label;
    }

    void set(String text) {
        label.setText(text);
    }
}
