package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.PitchedPreset;
import hits.Player;
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

/** Pictures of the decay label, the cutoff slider, and the tuned step-pitch readout. Not a unit test. */
public final class ControlShot {
    private ControlShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts/screenshots");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("controls");
            Track kick = beat.track(0);
            kick.setName("Break kick");
            kick.setNote(36);
            kick.setTune(1500);
            kick.setDecay(150);
            kick.tap(0, false);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setLocation(0, 0);
            holder.editor.selectStep(0, 0);
        });
        Thread.sleep(700);
        try {
            capture(holder, "step-pitch", directory.resolve("q6-step-pitch-tune.png"));
            capture(holder, "decay", directory.resolve("q4-kick-decay-ignored.png"));

            SwingUtilities.invokeAndWait(() -> holder.editor.edit(beat -> {
                Track crack = beat.track(1);
                crack.setName("Snare crack");
                crack.setPreset(PitchedPreset.CRACK);
                crack.setDecay(80);
                crack.tap(0, false);
                Track snare = beat.track(2);
                snare.setName("Kit snare");
                snare.setNote(38);
                snare.setDecay(40);
                snare.tap(0, false);
            }));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectStep(1, 0));
            Thread.sleep(300);
            capture(holder, "decay", directory.resolve("q4-crack-decay-ignored.png"));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectStep(2, 0));
            Thread.sleep(300);
            capture(holder, "decay", directory.resolve("q4-snare-decay-ignored.png"));

            SwingUtilities.invokeAndWait(() -> holder.editor.edit(beat -> {
                Track bass = beat.track(3);
                bass.setName("Syn Bass");
                bass.setMode(TrackMode.NOTE);
                bass.setProgram(38);
                bass.setNote(35);
                bass.setDecay(40);
                bass.setCutoff(74);
                bass.tap(0, false);
                Track hat = beat.track(4);
                hat.setName("Open hat");
                hat.setNote(46);
                hat.setDecay(25);
                hat.setCutoff(40);
                hat.tap(0, false);
            }));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectStep(3, 0));
            Thread.sleep(300);
            capture(holder, "decay", directory.resolve("q1-q2-bass-sliders.png"));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectStep(4, 0));
            Thread.sleep(300);
            capture(holder, "decay", directory.resolve("q1-q2-hat-sliders.png"));
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

    private static void capture(Holder holder, String reveal, Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(1500, 900);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.frame.toFront();
            reveal(holder.frame, reveal);
        });
        Thread.sleep(250);
        Rectangle area = new Rectangle(0, 0, 1500, 900);
        Rectangle screen = holder.frame.getGraphicsConfiguration().getBounds();
        ImageIO.write(new Robot().createScreenCapture(area.intersection(screen)), "png", path.toFile());
        System.out.println(path);
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
        bounds.y = Math.max(0, bounds.y - 80);
        scroll.getViewport().setViewPosition(new Point(0, bounds.y));
        scroll.getViewport().revalidate();
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
