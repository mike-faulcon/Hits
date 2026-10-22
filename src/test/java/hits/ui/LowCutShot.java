package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.Track;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pictures of the Low cut control. Not a unit test. */
public final class LowCutShot {
    private LowCutShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("low cut");
            Track kick = beat.track(0);
            kick.setName("Kick");
            kick.setCutoff(40);
            kick.setResonance(70);
            kick.setLowCut(120, 24);
            kick.setPresence(6);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.editor.selectTrack(0);
        });
        Thread.sleep(600);
        try {
            shoot(holder, 1500, 900, directory.resolve("low_cut_inspector_1500.png"));
            shoot(holder, 1180, 800, directory.resolve("low_cut_inspector_1180.png"));
        } catch (Exception exception) {
            exception.printStackTrace();
            code = 1;
        } finally {
            if (holder.frame != null) {
                SwingUtilities.invokeAndWait(() -> {
                    holder.frame.dispose();
                    holder.player.close();
                });
            }
        }
        System.exit(code);
    }

    private static void shoot(Holder holder, int width, int height, Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(width, height);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.frame.toFront();
            reveal(holder.frame, "low-cut");
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> reveal(holder.frame, "low-cut"));
        Thread.sleep(200);
        capture(holder.frame, width, height, path);
    }

    private static void reveal(JFrame frame, String name) {
        JComponent component = find(frame.getContentPane(), name, JComponent.class);
        if (component == null) {
            throw new IllegalStateException("Missing " + name);
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
        bounds.y = Math.max(0, bounds.y - 48);
        scroll.getViewport().setViewPosition(new Point(0, bounds.y));
        scroll.getViewport().revalidate();
        frame.revalidate();
        frame.repaint();
    }

    private static void capture(JFrame frame, int width, int height, Path path) throws Exception {
        Rectangle area = new Rectangle(0, 0, width, height);
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        ImageIO.write(new Robot().createScreenCapture(area.intersection(screen)), "png", path.toFile());
        System.out.println(path + " " + width + "x" + height);
    }

    private static <T extends Component> T find(Component root, String name, Class<T> type) {
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
