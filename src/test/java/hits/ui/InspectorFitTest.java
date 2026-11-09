package hits.ui;

import hits.Beat;
import hits.BuiltinSamples;
import hits.Editor;
import hits.Player;
import hits.SampleRef;
import hits.TrackMode;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The drum sound picker stays one row tall, and the step count names its unit. */
class InspectorFitTest {
    @Test
    void drumSoundPickerDoesNotKeepTheSampleFormHeight() throws Exception {
        Editor editor = new Editor(Beat.drumKit("fit"));
        Player player = new Player();
        player.open();
        TrackInspector inspector = new TrackInspector(editor, player, new Sync());
        JComponent panel = inspector.component();
        SwingUtilities.invokeAndWait(inspector::refresh);
        JComponent picker = find(panel, "sound-picker");
        assertNotNull(picker);
        int drumHeight = picker.getPreferredSize().height;
        assertTrue(drumHeight < 80, "drum picker height " + drumHeight);
        assertNotNull(label(panel, "steps"));

        editor.edit(beat -> {
            beat.track(0).setMode(TrackMode.SAMPLE);
            beat.track(0).setSample(new SampleRef(BuiltinSamples.CLICK, 60, 0, true));
        });
        SwingUtilities.invokeAndWait(inspector::refresh);
        int sampleHeight = picker.getPreferredSize().height;
        assertTrue(sampleHeight > drumHeight + 80, "sample picker height " + sampleHeight);
        player.close();
    }

    private static JComponent find(Container container, String name) {
        for (Component child : container.getComponents()) {
            if (name.equals(child.getName()) && child instanceof JComponent component) {
                return component;
            }
            if (child instanceof Container nested) {
                JComponent found = find(nested, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel label(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof Container nested) {
                JLabel found = label(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
