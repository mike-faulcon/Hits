package hits.ui;

import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;

/** The Help menu. F1 uses the same key code as {@link KeyboardShortcuts#HELP}. */
final class HelpMenu {
    private HelpMenu() {}

    static JMenuBar bar(Runnable showShortcuts) {
        JMenuBar bar = new JMenuBar();
        bar.setBackground(Theme.PANEL);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BG));
        JMenu help = new JMenu("Help");
        help.setMnemonic(KeyEvent.VK_H);
        help.setToolTipText("Shortcuts, and how to start a beat.");
        JMenuItem item = new JMenuItem("Keyboard Shortcuts");
        item.setName("keyboard-shortcuts");
        item.setAccelerator(KeyStroke.getKeyStroke(KeyboardShortcuts.HELP, 0));
        item.setToolTipText("Every key and mouse gesture Hits uses.");
        item.addActionListener(event -> showShortcuts.run());
        help.add(item);
        bar.add(help);
        return bar;
    }
}
