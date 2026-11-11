package hits.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Window;

/** Help window. The rows are {@link KeyboardShortcuts#shortcuts()} and the mouse list. */
final class ShortcutsDialog {
    static final String TITLE = "Keyboard Shortcuts";

    private ShortcutsDialog() {}

    static void show(Window owner) {
        JDialog dialog = new JDialog(owner, TITLE, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(panel());
        dialog.pack();
        dialog.setMinimumSize(new Dimension(560, 420));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    static JPanel panel() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Theme.BG);
        page.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JLabel intro = Widgets.label("These are the keys and clicks Hits uses. Typing in a text field still inserts that character.");
        intro.setForeground(Theme.MUTED);
        intro.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        page.add(intro, BorderLayout.NORTH);

        int keyWidth = keyColumnWidth();
        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBackground(Theme.BG);
        rows.setName("shortcut-list");
        addHeading(rows, "Keys");
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.shortcuts()) {
            addRow(rows, shortcut, keyWidth);
        }
        rows.add(Box.createVerticalStrut(10));
        addHeading(rows, "Mouse");
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.mouseShortcuts()) {
            addRow(rows, shortcut, keyWidth);
        }
        JScrollPane scroll = new JScrollPane(rows);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(Theme.BG);
        int listHeight = rows.getPreferredSize().height;
        scroll.setPreferredSize(new Dimension(Math.max(680, keyWidth + 520), listHeight));
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    private static void addHeading(JPanel rows, String title) {
        JLabel heading = Widgets.label(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        heading.setForeground(Theme.STEP_ON);
        heading.setAlignmentX(0f);
        heading.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        rows.add(heading);
    }

    private static int keyColumnWidth() {
        JLabel probe = Widgets.label("Ag");
        Font font = probe.getFont().deriveFont(Font.BOLD, 13f);
        FontMetrics metrics = probe.getFontMetrics(font);
        int width = 168;
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.shortcuts()) {
            width = Math.max(width, metrics.stringWidth(shortcut.keys()) + 16);
        }
        for (KeyboardShortcuts.Shortcut shortcut : KeyboardShortcuts.mouseShortcuts()) {
            width = Math.max(width, metrics.stringWidth(shortcut.keys()) + 16);
        }
        return width;
    }

    private static void addRow(JPanel rows, KeyboardShortcuts.Shortcut shortcut, int keyWidth) {
        JPanel row = new JPanel(new BorderLayout(16, 0));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        JLabel keys = Widgets.label(shortcut.keys());
        keys.setName("shortcut-keys");
        keys.setFont(keys.getFont().deriveFont(Font.BOLD, 13f));
        keys.setForeground(Theme.TEXT);
        keys.setPreferredSize(new Dimension(keyWidth, 24));
        keys.setMinimumSize(new Dimension(keyWidth, 24));
        JLabel what = Widgets.label("<html><body style='width: 430px'>" + escape(shortcut.what()) + "</body></html>");
        what.setName("shortcut-what");
        what.setForeground(new Color(0xC8C6C2));
        row.add(keys, BorderLayout.WEST);
        row.add(what, BorderLayout.CENTER);
        Dimension rowSize = row.getPreferredSize();
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowSize.height));
        rows.add(row);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
