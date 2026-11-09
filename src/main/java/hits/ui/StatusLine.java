package hits.ui;

import javax.swing.JLabel;

/** The hint line along the bottom of the window. */
final class StatusLine {
    static final String HINT = PatternHints.STATUS;

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
