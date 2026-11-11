package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;
import hits.Track;
import hits.TrackMode;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pictures of the keyboard cursor and the shortcut list. Not a unit test. */
public final class KeyboardGridShot {
    private KeyboardGridShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "docs/screenshots/keyboard-grid");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("Keyboard");
            Track kick = beat.track(0);
            kick.tap(0, true);
            kick.tap(4, false);
            kick.tap(8, true);
            kick.tap(12, false);
            kick.step(12).setVelocity(48);
            Track snare = beat.track(1);
            snare.setMute(true);
            snare.tap(4, false);
            snare.tap(12, true);
            Track hat = beat.track(2);
            for (int step = 0; step < 16; step += 2) {
                hat.tap(step, false);
            }
            Track bass = beat.track(6);
            bass.setName("Bass");
            bass.setMode(TrackMode.NOTE);
            bass.setNote(36);
            bass.tap(0, true);
            bass.step(0).setSlide(true);
            bass.tap(8, false);
            bass.step(8).setSlide(true);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
        });
        Thread.sleep(500);
        try {
            show(holder, 1500, 940, 6, 0);
            capture(holder.frame, directory.resolve("focus-1500.png"));
            show(holder, 1180, 860, 1, 4);
            capture(holder.frame, directory.resolve("focus-1180.png"));
            SwingUtilities.invokeAndWait(() -> {
                JDialog dialog = new JDialog(holder.frame, ShortcutsDialog.TITLE, false);
                dialog.setContentPane(ShortcutsDialog.panel());
                dialog.pack();
                dialog.setLocation(40, 24);
                dialog.setVisible(true);
                dialog.toFront();
                holder.dialog = dialog;
            });
            Thread.sleep(400);
            capture(holder.dialog, directory.resolve("shortcuts.png"));
        } catch (Exception exception) {
            exception.printStackTrace();
            code = 1;
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (holder.dialog != null) {
                    holder.dialog.dispose();
                }
                if (holder.frame != null) {
                    holder.frame.dispose();
                }
                if (holder.player != null) {
                    holder.player.close();
                }
            });
        }
        System.exit(code);
    }

    private static void show(Holder holder, int width, int height, int row, int column) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(width, height);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.frame.toFront();
            holder.editor.selectStep(row, column);
            PatternPanel grid = find(holder.frame.getContentPane());
            if (grid != null) {
                grid.requestFocusInWindow();
            }
        });
        Thread.sleep(350);
        SwingUtilities.invokeAndWait(() -> {
            PatternPanel grid = find(holder.frame.getContentPane());
            if (grid != null && !grid.isFocusOwner()) {
                grid.requestFocus();
            }
            holder.frame.toFront();
        });
        Thread.sleep(250);
    }

    private static void capture(java.awt.Window window, Path path) throws Exception {
        Rectangle bounds = new Rectangle();
        SwingUtilities.invokeAndWait(() -> bounds.setBounds(window.getBounds()));
        Rectangle screen = window.getGraphicsConfiguration().getBounds();
        ImageIO.write(new Robot().createScreenCapture(bounds.intersection(screen)), "png", path.toFile());
        System.out.println(path + " " + bounds.width + "x" + bounds.height);
    }

    private static PatternPanel find(Component root) {
        if (root instanceof PatternPanel panel) {
            return panel;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                PatternPanel found = find(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static final class Holder {
        Editor editor;
        Player player;
        JFrame frame;
        JDialog dialog;
    }
}
