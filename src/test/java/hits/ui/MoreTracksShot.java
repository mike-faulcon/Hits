package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Player;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.awt.Robot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Main window with sixteen rows, at 1500 px and 1180 px wide. Not a unit test. */
public final class MoreTracksShot {
    private MoreTracksShot() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length > 0 ? args[0] : "/opt/cursor/artifacts/screenshots");
        Files.createDirectories(directory);
        AppPreferences.dismissCoach();
        HitsApp.installTheme();
        Holder holder = new Holder();
        int code = 0;
        SwingUtilities.invokeAndWait(() -> {
            Beat beat = Beat.drumKit("Sixteen");
            beat.setBpm(96);
            beat.setTrackCount(16);
            beat.track(0).tap(0, false);
            beat.track(0).tap(8, false);
            beat.track(2).tap(0, false);
            beat.track(2).tap(4, false);
            beat.track(2).tap(8, false);
            beat.track(2).tap(12, false);
            beat.track(8).setName("Sub");
            beat.track(8).tap(0, false);
            beat.track(8).tap(8, false);
            beat.track(11).setName("Perc");
            beat.track(11).tap(6, false);
            beat.track(15).setName("Shaker");
            beat.track(15).tap(2, false);
            beat.track(15).tap(6, false);
            beat.track(15).tap(10, false);
            beat.track(15).tap(14, false);
            holder.editor = new Editor(beat);
            holder.player = new Player();
            holder.player.open();
            holder.frame = new StudioFrame(holder.editor, holder.player);
            holder.editor.selectTrack(8);
        });
        Thread.sleep(800);
        try {
            capture(holder, 1500, 900, directory.resolve("more-tracks-1500.png"));
            capture(holder, 1180, 860, directory.resolve("more-tracks-1180.png"));
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

    private static void capture(Holder holder, int width, int height, Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            holder.frame.setSize(width, height);
            holder.frame.setLocation(0, 0);
            holder.frame.setVisible(true);
            holder.frame.toFront();
            holder.frame.validate();
        });
        Thread.sleep(400);
        Rectangle window = holder.frame.getBounds();
        Rectangle screen = holder.frame.getGraphicsConfiguration().getBounds();
        ImageIO.write(new Robot().createScreenCapture(window.intersection(screen)), "png", path.toFile());
        System.out.println(path + " " + window.width + "x" + window.height);
    }

    private static final class Holder {
        private Editor editor;
        private Player player;
        private StudioFrame frame;
    }
}
