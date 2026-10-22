package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.SampleRef;
import hits.TrackMode;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Path;

/** Pictures of the sample inspector and the MIDI export dialog. Not a unit test. */
public final class SampleShot {
    private SampleShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts");
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("samples");
            beat.track(0).setMode(TrackMode.SAMPLE);
            beat.track(0).setSample(new SampleRef("/tmp/hits-missing-click.wav", 36, 80, true));
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setSize(1500, 900);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
        });
        Thread.sleep(700);
        showSampleCard(holder.frame);
        capture(holder.frame, directory.resolve("sample-inspector-1500.png"));
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(1180, 860);
            holder.frame.setLocation(0, 0);
            holder.frame.validate();
        });
        Thread.sleep(400);
        showSampleCard(holder.frame);
        capture(holder.frame, directory.resolve("sample-inspector-1180.png"));
        SwingUtilities.invokeLater(() -> {
            JButton export = findButton(holder.frame, "Export MIDI");
            if (export == null) {
                throw new IllegalStateException("Missing Export MIDI");
            }
            export.doClick();
        });
        Thread.sleep(900);
        capture(holder.frame, directory.resolve("sample-export-dialog.png"));
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog) {
                    window.dispose();
                }
            }
            holder.frame.dispose();
            holder.player.close();
        });
    }

    private static void capture(JFrame frame, Path path) throws Exception {
        Rectangle area = frame.getBounds();
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                area = area.union(window.getBounds());
            }
        }
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        area = area.intersection(screen);
        ImageIO.write(new Robot().createScreenCapture(area), "png", path.toFile());
    }

    private static void showSampleCard(JFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.validate();
            reveal(find(frame, "collect-samples"));
        });
        Thread.sleep(250);
    }

    private static void reveal(JComponent component) {
        if (component == null) {
            throw new IllegalStateException("Missing sample picker");
        }
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
        if (scroll == null) {
            return;
        }
        Rectangle bounds = SwingUtilities.convertRectangle(
            component.getParent(),
            component.getBounds(),
            scroll.getViewport().getView()
        );
        bounds.y = Math.max(0, bounds.y - 220);
        scroll.getViewport().setViewPosition(new java.awt.Point(0, bounds.y));
        scroll.getViewport().revalidate();
    }

    private static JComponent find(Component root, String name) {
        if (root instanceof JComponent component && name.equals(component.getName())) {
            return component;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JComponent found = find(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Component root, String text) {
        if (root instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButton(child, text);
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
        private JFrame frame;
    }
}
