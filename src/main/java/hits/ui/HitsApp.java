package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;

public final class HitsApp {
    private HitsApp() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(HitsApp::start);
    }

    private static void start() {
        installTheme();
        Editor editor = new Editor(Beat.drumKit("untitled"));
        Player player = new Player();
        player.open();
        StudioFrame frame = new StudioFrame(editor, player);
        frame.setVisible(true);
    }

    private static void installTheme() {
        UIManager.put("control", Theme.PANEL);
        UIManager.put("info", Theme.PANEL);
        UIManager.put("nimbusBase", new Color(0x181A1E));
        UIManager.put("nimbusAlertYellow", Theme.STEP_ON);
        UIManager.put("nimbusDisabledText", Theme.MUTED);
        UIManager.put("nimbusFocus", Theme.STEP_ON);
        UIManager.put("nimbusGreen", Theme.PLAY);
        UIManager.put("nimbusInfoBlue", new Color(0x7AA2F7));
        UIManager.put("nimbusLightBackground", Theme.BG);
        UIManager.put("nimbusOrange", Theme.STEP_ON);
        UIManager.put("nimbusSelectedText", Theme.INK);
        UIManager.put("nimbusSelectionBackground", Theme.STEP_ON);
        UIManager.put("text", Theme.TEXT);
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException exception) {
            // The cross-platform look and feel still shows the layout.
        }
    }
}
