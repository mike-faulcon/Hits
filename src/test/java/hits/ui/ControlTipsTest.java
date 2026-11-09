package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every control on the desk names itself, including the tempo unit. */
class ControlTipsTest {
    @Test
    void theDeskControlsHaveTooltips() throws Exception {
        Editor editor = new Editor(Beat.drumKit("tips"));
        Player player = new Player();
        player.open();
        Sync sync = new Sync();
        StatusLine status = new StatusLine();
        List<String> missing = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            TransportBar transport = new TransportBar(editor, player, sync, status);
            JPanel transportPanel = transport.component();
            assertNotNull(label(transportPanel, "BPM"));
            TrackInspector inspector = new TrackInspector(editor, player, sync);
            JComponent inspectorPanel = inspector.component();
            inspector.refresh();
            MixerPanel mixer = new MixerPanel(editor, sync);
            mixer.wire();
            SongChainBar chain = new SongChainBar(editor, player, sync, status, () -> {});
            BeatBrowser browser = new BeatBrowser(new BeatsFolder(), status);
            browser.component();
            OutputBar output = new OutputBar(null, player, sync, status, new BeatsFolder(), () -> {});
            missing.addAll(Tips.missing(transportPanel));
            missing.addAll(Tips.missing(inspectorPanel));
            missing.addAll(Tips.missing(mixer.component()));
            missing.addAll(Tips.missing(chain.component()));
            missing.addAll(Tips.missing(browser.component()));
            missing.addAll(Tips.missing(output.component()));
            PatternPanel grid = new PatternPanel(editor);
            assertTrue(grid.getToolTipText().contains("Ctrl-click"), grid.getToolTipText());
            assertTrue(grid.getToolTipText().contains("G"));
        });
        assertTrue(missing.isEmpty(), String.join("\n", missing));
        player.close();
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
