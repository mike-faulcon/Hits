package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.PitchedPreset;
import hits.Player;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pictures of the preset retest fixes. Not a unit test. */
public final class PresetFixShot {
    private PresetFixShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts/screenshots");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("fixes");
            beat.track(0).setNote(40);
            beat.track(0).setName("Snare");
            beat.track(1).setPreset(PitchedPreset.RIM);
            beat.track(1).setName("Rim");
            beat.track(1).tap(0, false);
            beat.track(2).setKit(8);
            beat.track(2).setName("Room");
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setSize(1180, 800);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.editor.selectTrack(0);
        });
        Thread.sleep(700);
        try {
            shootCombo(holder, "drum-sound", directory.resolve("electric-snare-picker.png"));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectStep(1, 0));
            Thread.sleep(300);
            revealNamed(holder, "step-pitch");
            capture(holder.frame, directory.resolve("preset-step-pitch.png"));
            SwingUtilities.invokeAndWait(() -> holder.editor.selectTrack(2));
            Thread.sleep(300);
            revealNamed(holder, "kit-warning");
            capture(holder.frame, directory.resolve("missing-kit-warning.png"));
            shootCombo(holder, "drum-kit", directory.resolve("missing-kit-picker.png"));
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

    private static void revealNamed(Holder holder, String name) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent component = find(holder.frame.getContentPane(), name, JComponent.class);
            if (component == null) {
                throw new IllegalStateException("Missing " + name);
            }
            component.scrollRectToVisible(new Rectangle(component.getPreferredSize()));
            reveal(component);
            String text = component instanceof javax.swing.JLabel label ? label.getText() : "";
            System.out.println(name + " showing=" + component.isShowing() + " text=[" + text + "]");
        });
        Thread.sleep(200);
    }

    private static void shootCombo(Holder holder, String name, Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> combo = find(holder.frame.getContentPane(), name, JComboBox.class);
            if (combo == null) {
                throw new IllegalStateException("Missing combo " + name);
            }
            reveal(combo);
            combo.setPopupVisible(true);
            holder.combo = combo;
        });
        Thread.sleep(400);
        capture(holder.frame, path);
        SwingUtilities.invokeAndWait(() -> holder.combo.setPopupVisible(false));
        Thread.sleep(150);
    }

    private static void capture(JFrame frame, Path path) throws Exception {
        Rectangle area = new Rectangle(0, 0, 1180, 800);
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                area = area.union(window.getBounds());
            }
        }
        Rectangle screen = frame.getGraphicsConfiguration().getBounds();
        ImageIO.write(new Robot().createScreenCapture(area.intersection(screen)), "png", path.toFile());
    }

    private static void reveal(JComponent component) {
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
        bounds.height = Math.max(bounds.height, scroll.getViewport().getHeight() / 2);
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
        private JFrame frame;
        private JComboBox<?> combo;
    }
}
