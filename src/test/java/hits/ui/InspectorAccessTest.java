package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;

import javax.accessibility.AccessibleState;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inspector paints a control as on by filling it and by selecting it.
 * Accessibility reads that selected state, and a control drawn on is enabled.
 */
class InspectorAccessTest {
    @Test
    void drawnControlsMatchAccessibleState() throws Exception {
        // Metal, not Nimbus: Nimbus sliders call getMousePosition and throw when
        // the test JVM is headless. Enabled and checked come from the component.
        Beat beat = Beat.drumKit("access");
        beat.track(0).setName("Kick");
        beat.track(0).setLowCut(120, 24);
        beat.track(0).setEqBand(0, 950, -12, 1.4);
        beat.track(0).setHighCut(6000, 24);
        Editor editor = new Editor(beat);
        Player player = new Player();
        player.open();
        TrackInspector inspector = new TrackInspector(editor, player, new Sync());
        JComponent panel = inspector.component();
        SwingUtilities.invokeAndWait(inspector::refresh);

        JButton drum = findText(panel, "Drum");
        assertNotNull(drum, "Drum");
        JButton slope12 = find(panel, "low-cut-12");
        JButton slope24 = find(panel, "low-cut-24");
        assertNotNull(slope12, "low-cut-12");
        assertNotNull(slope24, "low-cut-24");
        assertTrue(drum.isSelected(), "Drum is the active mode");
        assertTrue(drum.isEnabled());
        assertMatches(drum);
        assertMatches(slope12);
        assertMatches(slope24);

        JButton high6 = find(panel, "high-cut-6");
        JButton high12 = find(panel, "high-cut-12");
        JButton high24 = find(panel, "high-cut-24");
        assertNotNull(high6, "high-cut-6");
        assertNotNull(high12, "high-cut-12");
        assertNotNull(high24, "high-cut-24");
        assertMatches(high6);
        assertMatches(high12);
        assertMatches(high24);
        JTextField frequency = findField(panel, "eq-1-hz-value");
        JTextField highCut = findField(panel, "high-cut-value");
        assertNotNull(frequency, "eq-1-hz-value");
        assertNotNull(highCut, "high-cut-value");
        assertEquals("950 Hz", frequency.getText());
        assertEquals("Band 1 frequency", frequency.getAccessibleContext().getAccessibleName());
        assertEquals("High cut frequency", highCut.getAccessibleContext().getAccessibleName());
        assertEquals("High cut 24 dB per octave", high24.getAccessibleContext().getAccessibleName());

        if (player.isBuiltIn()) {
            assertTrue(slope24.isSelected(), "24 dB slope is drawn selected");
            assertTrue(slope24.isEnabled());
            assertFalse(slope12.isSelected());
            assertTrue(slope24.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.ENABLED));
            assertTrue(slope24.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
            assertTrue(high24.isSelected(), "24 dB high cut is drawn selected");
            assertTrue(high24.isEnabled());
            assertFalse(high6.isSelected());
            assertFalse(high12.isSelected());
            assertTrue(high6.isEnabled());
            assertTrue(high12.isEnabled());
            assertTrue(high24.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
            assertFalse(high12.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.CHECKED));
            assertTrue(frequency.isEnabled());
            assertTrue(highCut.isEnabled());
            assertEquals("6000 Hz", highCut.getText());
        } else {
            assertFalse(slope24.isSelected(), "a greyed slope is not drawn selected");
            assertFalse(slope24.isEnabled());
            assertFalse(slope12.isSelected());
            assertFalse(high24.isSelected(), "a greyed high-cut slope is not drawn selected");
            assertFalse(high24.isEnabled());
            assertFalse(high6.isSelected());
            assertFalse(high12.isSelected());
            assertFalse(frequency.isEnabled());
            assertFalse(highCut.isEnabled());
        }
        player.close();
    }

    private static void assertMatches(JButton button) {
        boolean drawn = Theme.STEP_ON.equals(button.getBackground());
        assertEquals(drawn, button.isSelected(), button.getName() + " selected versus fill");
        if (drawn) {
            assertTrue(button.isEnabled(), button.getName() + " is drawn on and disabled");
        }
        var states = button.getAccessibleContext().getAccessibleStateSet();
        assertEquals(button.isEnabled(), states.contains(AccessibleState.ENABLED), button.getName());
        assertEquals(button.isSelected(), states.contains(AccessibleState.CHECKED), button.getName());
    }

    private static JButton find(Component root, String name) {
        if (root instanceof JButton button && name.equals(button.getName())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = find(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTextField findField(Component root, String name) {
        if (root instanceof JTextField field && name.equals(field.getName())) {
            return field;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTextField found = findField(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton findText(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findText(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
