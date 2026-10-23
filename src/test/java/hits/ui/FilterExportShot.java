package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.Track;
import hits.TrackMode;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Picture of the MIDI export dialog naming the rows whose low cut and presence
 * will not be in the file. Not a unit test.
 */
public final class FilterExportShot {
    private FilterExportShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("filter warning");
            Track kick = beat.track(0);
            kick.setName("Kick");
            kick.setLowCut(80, 24);
            Track bass = beat.track(1);
            bass.setName("Bass");
            bass.setMode(TrackMode.NOTE);
            bass.setProgram(38);
            bass.setNote(36);
            bass.setPresence(8);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setSize(1500, 900);
            holder.frame.setLocation(40, 40);
            holder.frame.setVisible(true);
        });
        Thread.sleep(800);
        int code = 0;
        try {
            SwingUtilities.invokeLater(() -> {
                JButton export = find(holder.frame.getContentPane(), "export-midi", JButton.class);
                if (export == null) {
                    throw new IllegalStateException("Missing export-midi");
                }
                export.doClick();
            });
            Window dialog = waitForLabel();
            SwingUtilities.invokeAndWait(() -> {
                dialog.setSize(1500, 820);
                dialog.setLocation(0, 0);
                dialog.toFront();
            });
            Thread.sleep(500);
            Rectangle area = new Rectangle(0, 0, 1500, 820);
            Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
            Path path = directory.resolve("export_filter_warning.png");
            ImageIO.write(new Robot().createScreenCapture(area.intersection(screen)), "png", path.toFile());
            System.out.println(path);
            SwingUtilities.invokeAndWait(() -> dialog.dispose());
        } catch (Exception exception) {
            exception.printStackTrace();
            code = 1;
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                holder.frame.dispose();
                holder.player.close();
            });
        }
        System.exit(code);
    }

    private static Window waitForLabel() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            for (Window window : Window.getWindows()) {
                if (window.isShowing() && find(window, "export-low-cut", JLabel.class) != null) {
                    return window;
                }
            }
        }
        throw new IllegalStateException("Export dialog did not show the filter warning");
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
        if (root == null) {
            return null;
        }
        if (type.isInstance(root) && name.equals(root.getName())) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static final class Holder {
        private Editor editor;
        private Player player;
        private StudioFrame frame;
    }
}
