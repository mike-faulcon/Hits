package hits.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Dimension;
import java.awt.FlowLayout;

/** Shared Swing pieces so every panel uses the same buttons, labels, and sizes. */
final class Widgets {
    private Widgets() {}

    static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT);
        return label;
    }

    static JSlider slider(int min, int max, int value) {
        JSlider slider = new JSlider(min, max, value);
        slider.setBackground(Theme.PANEL);
        slider.setForeground(Theme.STEP_ON);
        return slider;
    }

    static JButton button(String text) {
        JButton button = new JButton(text);
        Dimension preferred = button.getPreferredSize();
        int height = Math.max(32, preferred.height);
        int width = Math.max(28, preferred.width);
        button.setPreferredSize(new Dimension(width, height));
        button.setMinimumSize(new Dimension(28, height));
        button.setMaximumSize(new Dimension(Math.max(width, 28), height));
        button.setFocusable(false);
        button.setRequestFocusEnabled(false);
        return button;
    }

    static void fix(JComponent component, int width, int height) {
        Dimension size = new Dimension(width, height);
        component.setPreferredSize(size);
        component.setMinimumSize(new Dimension(Math.min(width, 28), height));
        component.setMaximumSize(size);
    }

    static JPanel row(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    static void paintToggle(JButton button, boolean on) {
        button.setBackground(on ? Theme.STEP_ON : Theme.PANEL);
        button.setForeground(on ? Theme.INK : Theme.TEXT);
        button.setOpaque(true);
    }

    static String message(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    }
}
