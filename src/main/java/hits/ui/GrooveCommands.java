package hits.ui;

import hits.Editor;
import hits.GrooveGenerator;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.concurrent.ThreadLocalRandom;

/** Make me a beat, and Shuffle for the selected track. */
final class GrooveCommands {
    private final JFrame frame;
    private final Editor editor;
    private final StatusLine status;
    private GrooveGenerator.Genre lastGenre;

    GrooveCommands(JFrame frame, Editor editor, StatusLine status) {
        this.frame = frame;
        this.editor = editor;
        this.status = status;
    }

    void makeBeat() {
        char slot = editor.viewSlot();
        boolean occupied = GrooveGenerator.patternHasHits(editor.beat(), slot);
        GrooveGenerator.Genre genre = ask(occupied, slot);
        if (genre == null) {
            return;
        }
        GrooveGenerator.write(editor, genre, ThreadLocalRandom.current().nextLong());
        lastGenre = genre;
        status.set(GroovePrompt.groove(genre, slot));
    }

    void shuffleSelected() {
        GrooveGenerator.Genre genre = lastGenre != null
            ? lastGenre
            : GrooveGenerator.infer(editor.beat().bpm(), editor.beat().swing());
        String name = editor.selectedTrack().name();
        GrooveGenerator.shuffleTrack(editor, genre, ThreadLocalRandom.current().nextLong());
        status.set(GroovePrompt.shuffled(name));
    }

    private GrooveGenerator.Genre ask(boolean occupied, char slot) {
        JDialog dialog = new JDialog(frame, "Make me a beat", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        GrooveGenerator.Genre[] picked = new GrooveGenerator.Genre[1];
        JPanel root = new JPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel text = new JLabel("<html><body style='width:340px'>" + GroovePrompt.makeBeat(slot, occupied) + "</body></html>");
        root.add(text);
        root.add(Box.createVerticalStrut(12));
        JPanel styles = new JPanel(new GridLayout(0, 2, 8, 8));
        styles.setOpaque(false);
        for (GrooveGenerator.Genre genre : GrooveGenerator.Genre.values()) {
            JButton button = Widgets.button(genre.label());
            button.addActionListener(event -> {
                picked[0] = genre;
                dialog.dispose();
            });
            styles.add(button);
        }
        JButton surprise = Widgets.button("Surprise me");
        surprise.addActionListener(event -> {
            GrooveGenerator.Genre[] genres = GrooveGenerator.Genre.values();
            picked[0] = genres[ThreadLocalRandom.current().nextInt(genres.length)];
            dialog.dispose();
        });
        styles.add(surprise);
        root.add(styles);
        root.add(Box.createVerticalStrut(12));
        JButton cancel = Widgets.button("Cancel");
        cancel.addActionListener(event -> dialog.dispose());
        JPanel cancelRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        cancelRow.setOpaque(false);
        cancelRow.add(cancel);
        root.add(cancelRow);
        dialog.setContentPane(root);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);
        return picked[0];
    }
}
