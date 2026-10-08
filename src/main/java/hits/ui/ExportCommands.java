package hits.ui;

import hits.Beat;
import hits.BeatFiles;
import hits.Editor;
import hits.ExportOptions;
import hits.Player;
import hits.SoundFonts;
import hits.WavRenderer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Export MIDI and Export WAV, including the shared options dialog and the WAV progress window. */
final class ExportCommands {
    private final JFrame frame;
    private final Editor editor;
    private final Player player;
    private final BeatsFolder beatsFolder;
    private final StatusLine status;

    ExportCommands(JFrame frame, Editor editor, Player player, BeatsFolder beatsFolder, StatusLine status) {
        this.frame = frame;
        this.editor = editor;
        this.player = player;
        this.beatsFolder = beatsFolder;
        this.status = status;
    }

    void exportMidi() {
        ExportChoice choice = askExport("Export MIDI", "mid", "Standard MIDI");
        if (choice == null) {
            return;
        }
        try {
            BeatFiles.exportMidi(editor.beat(), choice.path(), choice.options());
            status.set("Exported " + choice.path().getFileName() + ExportPlan.summary(choice.options()));
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(frame, Widgets.message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    void exportWav() {
        ExportChoice choice = askExport("Export WAV", "wav", "WAV audio");
        if (choice == null) {
            return;
        }
        Beat beat = editor.beat().copy();
        Path soundFont = player.soundFont();
        JDialog dialog = new JDialog(frame, "Export WAV", Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("Rendering…");
        bar.setPreferredSize(new Dimension(320, 22));
        JButton cancel = Widgets.button("Cancel");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        body.add(new JLabel("Rendering " + choice.path().getFileName()), BorderLayout.NORTH);
        body.add(bar, BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);
        dialog.getContentPane().add(body);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                javax.sound.midi.Soundbank bank = soundFont == null ? null : SoundFonts.read(soundFont);
                BeatFiles.exportWav(
                    beat,
                    choice.path(),
                    choice.options(),
                    bank,
                    this::publishPercent,
                    this::isCancelled
                );
                return null;
            }

            private void publishPercent(int percent) {
                publish(percent);
            }

            @Override
            protected void process(List<Integer> chunks) {
                int percent = chunks.get(chunks.size() - 1);
                bar.setValue(percent);
                bar.setString(percent >= 100 ? "Done" : percent + "%");
            }

            @Override
            protected void done() {
                dialog.dispose();
            }
        };
        cancel.addActionListener(event -> worker.cancel(false));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                worker.cancel(false);
            }
        });
        worker.execute();
        dialog.setVisible(true);
        try {
            worker.get();
            status.set("Exported " + choice.path().getFileName() + ExportPlan.summary(choice.options()));
        } catch (CancellationException exception) {
            status.set("Export cancelled");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status.set("Export cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof WavRenderer.Cancelled) {
                status.set("Export cancelled");
                return;
            }
            JOptionPane.showMessageDialog(frame, Widgets.message(cause), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Save dialog shared by MIDI and WAV. Null when the user cancels. */
    private ExportChoice askExport(String title, String extension, String description) {
        JFileChooser chooser = new JFileChooser(beatsFolder.get().toFile());
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(ExportPlan.suggestedStem(editor.beat().name()) + "." + extension));
        chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
        JCheckBox asHeard = new JCheckBox("As heard (mute and solo)");
        asHeard.setToolTipText("Off writes every track. On leaves muted tracks out and respects solo.");
        JCheckBox both = new JCheckBox("A then B");
        both.setToolTipText("Write pattern A followed by pattern B.");
        JCheckBox song = new JCheckBox("Song");
        boolean hasChain = editor.beat().hasChain();
        song.setEnabled(hasChain);
        song.setToolTipText(hasChain
            ? "Write the chain. Each entry's repeats are included."
            : "Add a chain with +A or +B first.");
        JSpinner repeats = new JSpinner(new SpinnerNumberModel(1, 1, ExportOptions.MAX_REPEATS, 1));
        repeats.setToolTipText("How many times to write that material, from 1 to " + ExportOptions.MAX_REPEATS + ".");
        song.addActionListener(event -> {
            if (song.isSelected()) {
                both.setSelected(false);
            }
            both.setEnabled(!song.isSelected());
            repeats.setEnabled(!song.isSelected());
        });
        both.addActionListener(event -> {
            if (both.isSelected()) {
                song.setSelected(false);
                both.setEnabled(true);
                repeats.setEnabled(true);
            }
        });
        JPanel accessory = new JPanel();
        accessory.setLayout(new BoxLayout(accessory, BoxLayout.Y_AXIS));
        accessory.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JLabel exportLabel = new JLabel("Export");
        exportLabel.setToolTipText("All tracks is the default. Mute and solo are ignored unless As heard is checked.");
        accessory.add(exportLabel);
        accessory.add(Box.createVerticalStrut(6));
        accessory.add(asHeard);
        accessory.add(both);
        accessory.add(song);
        accessory.add(Box.createVerticalStrut(6));
        JPanel repeatRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        repeatRow.add(new JLabel("Repeats"));
        repeatRow.add(repeats);
        accessory.add(repeatRow);
        chooser.setAccessory(accessory);
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path path = ExportPlan.withExtension(chooser.getSelectedFile().toPath(), extension);
        ExportOptions options = ExportPlan.options(
            asHeard.isSelected(),
            both.isSelected(),
            (Integer) repeats.getValue(),
            song.isSelected(),
            hasChain
        );
        return new ExportChoice(path, options);
    }

    private record ExportChoice(Path path, ExportOptions options) {}
}
