package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.Slide;
import hits.Track;
import hits.TrackMode;

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

/** Pictures of a slide step in the grid and the slide controls in the inspector. Not a unit test. */
public final class SlideShot {
    private SlideShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("Slides");
            Track bass = beat.track(0);
            bass.setName("Bass");
            bass.setMode(TrackMode.NOTE);
            bass.setProgram(38);
            bass.setNote(36);
            bass.clearSteps();
            bass.tap(0, false);
            bass.tap(4, false);
            bass.tap(8, false);
            bass.tap(12, true);
            bass.step(0).setPitch(36);
            bass.step(4).setPitch(43);
            bass.step(8).setPitch(48);
            bass.step(12).setPitch(36);
            bass.step(4).setSlide(true);
            bass.step(8).setSlide(true);
            bass.step(12).setSlide(true);
            bass.setSlideMs(80);
            bass.setSlideCurve(Slide.Curve.EXPONENTIAL);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.editor.selectTrack(0);
            holder.editor.selectStep(4);
        });
        Thread.sleep(700);
        try {
            shoot(holder, 1500, 940, directory.resolve("slides_1500.png"));
            shoot(holder, 1180, 940, directory.resolve("slides_1180.png"));
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
            reveal(holder.frame, "slide-row");
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> reveal(holder.frame, "slide-row"));
        Thread.sleep(250);
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
        bounds.y = Math.max(0, bounds.y - 40);
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
