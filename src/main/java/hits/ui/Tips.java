package hits.ui;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

/** Tooltips shared onto spinner fields, and a check that a control explains itself. */
final class Tips {
    private Tips() {}

    /** Copies a spinner's tooltip onto its text field, which is what the pointer actually hits. */
    static void share(Component component) {
        if (component instanceof JSpinner spinner) {
            JComponent editor = spinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor number) {
                JTextField field = number.getTextField();
                String tip = spinner.getToolTipText();
                if (tip != null && !tip.isBlank() && (field.getToolTipText() == null || field.getToolTipText().isBlank())) {
                    field.setToolTipText(tip);
                }
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                share(child);
            }
        }
    }

    /** Buttons, sliders, spinners, lists, and fields with no tooltip. Scrollbar and spinner arrows are skipped. */
    static List<String> missing(Component component) {
        List<String> missing = new ArrayList<>();
        walk(component, missing);
        return missing;
    }

    private static void walk(Component component, List<String> missing) {
        if (wantsTip(component)) {
            String tip = ((JComponent) component).getToolTipText();
            if (tip == null || tip.isBlank()) {
                missing.add(describe(component));
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                walk(child, missing);
            }
        }
    }

    private static boolean wantsTip(Component component) {
        if (!(component instanceof JButton
            || component instanceof JSlider
            || component instanceof JSpinner
            || component instanceof JComboBox
            || component instanceof JCheckBox
            || component instanceof JTextField
            || component instanceof JList)) {
            return false;
        }
        if (ancestor(component, JScrollBar.class) != null) {
            return false;
        }
        if (component instanceof JButton && ancestor(component, JSpinner.class) != null) {
            return false;
        }
        if (component instanceof JButton && ancestor(component, JComboBox.class) != null) {
            return false;
        }
        return true;
    }

    private static Component ancestor(Component component, Class<?> type) {
        Component current = component.getParent();
        while (current != null) {
            if (type.isInstance(current)) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String describe(Component component) {
        String name = component.getName();
        String text = "";
        if (component instanceof JButton button) {
            text = button.getText();
        } else if (component instanceof JCheckBox box) {
            text = box.getText();
        } else if (component instanceof JTextField field) {
            text = field.getText();
        }
        return component.getClass().getSimpleName() + " name=" + name + " text=" + text;
    }
}
