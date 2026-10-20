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
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Export MIDI and Export WAV, plus the drag chips that reuse the same options and renderer. */
final class ExportCommands {
    private final JFrame frame;
    private final Editor editor;
    private final Player player;
    private final BeatsFolder beatsFolder;
    private final StatusLine status;
    private final DragFiles dragFiles;
    private final FileDrag midiDrag = new FileDrag();
    private final FileDrag wavDrag = new FileDrag();
    private final ExportMemory memory = new ExportMemory();
    private boolean songMode;

    ExportCommands(JFrame frame, Editor editor, Player player, BeatsFolder beatsFolder, StatusLine status) {
        this.frame = frame;
        this.editor = editor;
        this.player = player;
        this.beatsFolder = beatsFolder;
        this.status = status;
        this.songMode = editor.songMode();
        try {
            this.dragFiles = DragFiles.open();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create a folder for dragged files", exception);
        }
    }

    JComponent midiChip() {
        JComponent chip = FileDrag.chip("Drag MIDI");
        chip.setName("dragMidi");
        chip.setToolTipText("Drag the current pattern into a DAW or a folder. Song mode drags the chain. Uses the current export choices.");
        midiDrag.install(chip, () -> prepare("mid"), () ->
            status.set("MIDI is ready. Drag the chip into a DAW or a folder."));
        return chip;
    }

    JComponent wavChip() {
        JComponent chip = FileDrag.chip("Drag WAV");
        chip.setName("dragWav");
        chip.setToolTipText("Drag a WAV of the same notes. A progress window shows while it renders. Uses the built-in synth, and a loaded SoundFont when one is active.");
        wavDrag.install(chip, () -> prepare("wav"), () ->
            status.set("WAV is ready. Drag the chip into a DAW or a folder."));
        return chip;
    }

    /** Forgets a WAV that was rendered for an older pattern, song, or export choice. */
    void noteChange(boolean musical) {
        boolean song = editor.songMode();
        if (musical || song != songMode) {
            disarmDrag();
        }
        songMode = song;
    }

    void disarmDrag() {
        midiDrag.disarm();
        wavDrag.disarm();
    }

    void close() {
        dragFiles.close();
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
        if (renderWav(editor.beat().copy(), choice.path(), choice.options(), "Export WAV")) {
            status.set("Exported " + choice.path().getFileName() + ExportPlan.summary(choice.options()));
        }
    }

    /**
     * Writes the temp file for a drag. Null when the user cancels the WAV render or the write fails.
     * Called on the event thread, before the native drag starts.
     */
    private Path prepare(String extension) {
        Beat beat = editor.beat().copy();
        ExportOptions options = memory.forDrag(editor.songMode(), beat.hasChain());
        String name = ExportPlan.dragFileName(beat.name(), options, beat.activeSlot(), extension);
        try {
            Path path = dragFiles.place(name);
            if ("wav".equals(extension)) {
                if (!renderWav(beat, path, options, "Drag WAV")) {
                    return null;
                }
            } else {
                BeatFiles.exportMidi(beat, path, options);
            }
            if (!Files.isRegularFile(path)) {
                return null;
            }
            status.set("Dragging " + path.getFileName() + ExportPlan.summary(options));
            return path;
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(frame, Widgets.message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    /** Renders a WAV while a progress dialog is up. False when the user cancels or rendering fails. */
    private boolean renderWav(Beat beat, Path path, ExportOptions options, String title) {
        Path soundFont = player.soundFont();
        JDialog dialog = new JDialog(frame, title, Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("Rendering…");
        bar.setPreferredSize(new Dimension(320, 22));
        JButton cancel = Widgets.button("Cancel");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        body.add(new JLabel("Rendering " + path.getFileName()), BorderLayout.NORTH);
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
                    path,
                    options,
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
        // Start the render only after the dialog is on screen. A fast file otherwise
        // finishes, and done() closes the dialog, before setVisible paints it.
        SwingUtilities.invokeLater(worker::execute);
        dialog.setVisible(true);
        try {
            worker.get();
            return true;
        } catch (CancellationException exception) {
            status.set("Export cancelled");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status.set("Export cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof WavRenderer.Cancelled) {
                status.set("Export cancelled");
                return false;
            }
            JOptionPane.showMessageDialog(frame, Widgets.message(cause), "Hits", JOptionPane.ERROR_MESSAGE);
        }
        return false;
    }

    /** Save dialog shared by MIDI and WAV. Null when the user cancels. */
    private ExportChoice askExport(String title, String extension, String description) {
        JFileChooser chooser = new JFileChooser(beatsFolder.get().toFile());
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(new java.io.File(ExportPlan.suggestedStem(editor.beat().name()) + "." + extension));
        chooser.setFileFilter(new FileNameExtensionFilter(description, extension));
        JCheckBox ignoreMuteSolo = new JCheckBox("Ignore mute and solo");
        ignoreMuteSolo.setToolTipText("Off matches playback. On writes every track, including muted ones, and ignores solo.");
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
        ignoreMuteSolo.setSelected(!memory.asHeard());
        repeats.setValue(memory.repeats());
        boolean songOn = editor.songMode() && hasChain;
        if (songOn) {
            song.setSelected(true);
            both.setEnabled(false);
            repeats.setEnabled(false);
        } else {
            both.setSelected(memory.bothSlots());
        }
        JPanel accessory = new JPanel();
        accessory.setLayout(new BoxLayout(accessory, BoxLayout.Y_AXIS));
        accessory.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JLabel exportLabel = new JLabel("Export");
        exportLabel.setToolTipText("Mute and solo match playback unless Ignore mute and solo is checked. Pan and levels stay either way.");
        accessory.add(exportLabel);
        accessory.add(Box.createVerticalStrut(6));
        accessory.add(ignoreMuteSolo);
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
        boolean pair = song.isSelected() ? memory.bothSlots() : both.isSelected();
        int times = (Integer) repeats.getValue();
        memory.remember(ExportMemory.asHeardFromIgnore(ignoreMuteSolo.isSelected()), pair, times);
        disarmDrag();
        Path path = ExportPlan.withExtension(chooser.getSelectedFile().toPath(), extension);
        ExportOptions options = ExportPlan.fromDialog(
            ignoreMuteSolo.isSelected(),
            both.isSelected(),
            times,
            song.isSelected(),
            hasChain
        );
        return new ExportChoice(path, options);
    }

    private record ExportChoice(Path path, ExportOptions options) {}

    /**
     * The export dialog's choices, kept for the next export and for a drag.
     * Song mode decides pattern versus song at the moment of the drag.
     */
    static final class ExportMemory {
        /** A new drag matches playback until an export says otherwise. */
        private boolean asHeard = true;
        private boolean bothSlots;
        private int repeats = 1;

        /** The dialog's "Ignore mute and solo" box is the opposite of exporting as heard. */
        static boolean asHeardFromIgnore(boolean ignoreMuteSolo) {
            return !ignoreMuteSolo;
        }

        void remember(boolean asHeard, boolean bothSlots, int repeats) {
            this.asHeard = asHeard;
            this.bothSlots = bothSlots;
            this.repeats = Math.max(1, Math.min(ExportOptions.MAX_REPEATS, repeats));
        }

        boolean asHeard() {
            return asHeard;
        }

        boolean bothSlots() {
            return bothSlots;
        }

        int repeats() {
            return repeats;
        }

        ExportOptions forDrag(boolean songMode, boolean hasChain) {
            return ExportPlan.dragOptions(asHeard, bothSlots, repeats, songMode, hasChain);
        }
    }
}
