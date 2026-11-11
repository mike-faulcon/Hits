package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.EqBand;
import hits.Player;
import hits.Track;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pictures of the row inspector before and after the compact sections. Not a unit test. */
public final class CompactInspectorShot {
    private CompactInspectorShot() {}

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "after";
        Path directory = Path.of(args.length > 1 ? args[1] : "docs/screenshots/compact-inspector");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            holder.editor = new Editor(example());
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setLocation(0, 0);
            holder.editor.selectTrack(0);
        });
        Thread.sleep(700);
        try {
            if ("before".equals(mode)) {
                SwingUtilities.invokeAndWait(() -> {
                    if (find(holder.frame.getContentPane(), "section-filter-header", JComponent.class) != null) {
                        throw new IllegalStateException(
                            "before pictures are the inspector without sections; this window already has them"
                        );
                    }
                });
            }
            String prefix = "before".equals(mode) ? "before" : "after";
            shoot(holder, 1500, 1000, directory.resolve(prefix + "_1500.png"), false);
            shoot(holder, 1180, 900, directory.resolve(prefix + "_1180.png"), false);
            if ("collapsed".equals(mode) || "after".equals(mode)) {
                shoot(holder, 1180, 900, directory.resolve("collapsed_active_1180.png"), true);
            }
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

    /** Kick with the processing the compact headers summarize. */
    static Beat example() {
        Beat beat = Beat.drumKit("Compact");
        Track kick = beat.track(0);
        kick.setName("Kick");
        kick.setLowCut(80, 12);
        kick.setEqBand(0, 120, -3, 1.0);
        kick.setEqBand(1, 2500, 2.5, 0.7, EqBand.Type.HIGH_SHELF, true);
        kick.setHighCut(5000, 12);
        return beat;
    }

    private static void shoot(Holder holder, int width, int height, Path path, boolean collapse) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(width, height);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.frame.toFront();
            if (collapse) {
                JComponent header = find(holder.frame.getContentPane(), "section-filter-header", JComponent.class);
                if (header == null) {
                    throw new IllegalStateException("Missing section-filter-header");
                }
                for (java.awt.event.MouseListener listener : header.getMouseListeners()) {
                    listener.mouseClicked(new java.awt.event.MouseEvent(
                        header,
                        java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(),
                        0,
                        8,
                        8,
                        1,
                        false
                    ));
                }
            }
            String anchor = collapse ? "section-filter-header" : "sound-heading";
            reveal(holder.frame, anchor);
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> reveal(holder.frame, collapse ? "section-filter-header" : "sound-heading"));
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
        java.awt.Rectangle bounds = SwingUtilities.convertRectangle(
            component.getParent(),
            component.getBounds(),
            scroll.getViewport().getView()
        );
        bounds.y = Math.max(0, bounds.y - 8);
        scroll.getViewport().setViewPosition(new java.awt.Point(0, bounds.y));
        scroll.getHorizontalScrollBar().setValue(0);
        scroll.revalidate();
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
