package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.TrackMode;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.nio.file.Path;

/** Pictures of the drum, kit, and program pickers. Not a unit test. */
public final class SoundPickerShot {
    private SoundPickerShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts");
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("sounds");
            beat.track(6).setMode(TrackMode.NOTE);
            beat.track(6).setProgram(0);
            beat.track(6).setNote(60);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.frame.setSize(1180, 800);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
        });
        Thread.sleep(600);
        shoot(holder, "drum-sound", directory.resolve("drum-sound-picker.png"));
        shoot(holder, "drum-kit", directory.resolve("drum-kit-picker.png"));
        SwingUtilities.invokeAndWait(() -> holder.editor.selectTrack(6));
        Thread.sleep(300);
        shoot(holder, "note-program", directory.resolve("note-program-picker.png"));
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.dispose();
            holder.player.close();
        });
    }

    private static void shoot(Holder holder, String name, Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<?> combo = find(holder.frame.getContentPane(), name);
            if (combo == null) {
                throw new IllegalStateException("Missing combo " + name);
            }
            reveal(combo);
            combo.setPopupVisible(true);
            holder.combo = combo;
        });
        Thread.sleep(400);
        Rectangle area = new Rectangle(0, 0, 1180, 800);
        for (Window window : Window.getWindows()) {
            if (window.isShowing()) {
                area = area.union(window.getBounds());
            }
        }
        Rectangle screen = holder.frame.getGraphicsConfiguration().getBounds();
        area = area.intersection(screen);
        ImageIO.write(new Robot().createScreenCapture(area), "png", path.toFile());
        SwingUtilities.invokeAndWait(() -> holder.combo.setPopupVisible(false));
        Thread.sleep(150);
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
        scroll.getViewport().setViewPosition(new java.awt.Point(0, bounds.y));
        scroll.getViewport().revalidate();
    }

    private static JComboBox<?> find(Component root, String name) {
        if (root instanceof JComboBox<?> combo && name.equals(combo.getName())) {
            return combo;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JComboBox<?> found = find(child, name);
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
