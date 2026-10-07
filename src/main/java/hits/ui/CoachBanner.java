package hits.ui;

import hits.Editor;
import hits.Kits;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** First-run hint. Dismissing it is remembered, and later launches start from an empty kit. */
final class CoachBanner {
    private final Editor editor;
    private final JPanel header;
    private JComponent bar;
    private boolean visible;

    CoachBanner(Editor editor, JPanel header) {
        this.editor = editor;
        this.header = header;
    }

    void maybeFirstRun() {
        visible = !AppPreferences.coachDismissed();
        if (visible) {
            editor.edit(Kits::boomBap);
            editor.markClean();
        }
    }

    void attach() {
        if (!visible) {
            return;
        }
        bar = banner();
        header.add(bar, BorderLayout.SOUTH);
    }

    private JComponent banner() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(Theme.SELECT);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel text = new JLabel("Starting on Boom bap.  Space plays.  Click a square to add a hit.  Shift-click makes it loud.");
        text.setForeground(Theme.TEXT);
        panel.add(text, BorderLayout.CENTER);
        JButton dismiss = Widgets.button("Got it");
        dismiss.setToolTipText("Hide this hint. Later launches start from an empty kit.");
        dismiss.addActionListener(event -> dismiss());
        panel.add(dismiss, BorderLayout.EAST);
        return panel;
    }

    private void dismiss() {
        visible = false;
        if (bar != null) {
            header.remove(bar);
            bar = null;
            header.revalidate();
            header.repaint();
        }
        AppPreferences.dismissCoach();
    }
}
