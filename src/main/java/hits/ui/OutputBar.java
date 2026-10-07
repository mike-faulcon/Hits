package hits.ui;

import hits.MidiOutputs;
import hits.Player;
import hits.SoundFonts;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** MIDI output picker and SoundFont loading along the bottom of the window. */
final class OutputBar {
    private final JFrame frame;
    private final Player player;
    private final Sync sync;
    private final StatusLine status;
    private final BeatsFolder beatsFolder;
    private final Runnable updateTitle;
    private final JComboBox<MidiOutputs.Choice> midiOut = new JComboBox<>();

    OutputBar(JFrame frame, Player player, Sync sync, StatusLine status, BeatsFolder beatsFolder, Runnable updateTitle) {
        this.frame = frame;
        this.player = player;
        this.sync = sync;
        this.status = status;
        this.beatsFolder = beatsFolder;
        this.updateTitle = updateTitle;
    }

    JPanel component() {
        JPanel output = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        output.setOpaque(false);
        JLabel midiLabel = Widgets.label("MIDI out");
        midiLabel.setToolTipText("Where notes are sent. Built-in synth is the Java instrument.");
        output.add(midiLabel);
        midiOut.setToolTipText("Built-in synth, or an output such as Ableton, IAC, or a hardware port.");
        midiOut.setPreferredSize(new Dimension(200, 32));
        output.add(midiOut);
        JButton soundFont = Widgets.button("SoundFont…");
        soundFont.setToolTipText("Load a .sf2 SoundFont on the built-in synth. External outputs use their own sounds.");
        soundFont.addActionListener(event -> chooseSoundFont());
        output.add(soundFont);
        return output;
    }

    void wire() {
        midiOut.addActionListener(event -> midiOutputChosen());
    }

    boolean popupOpen() {
        return midiOut.isPopupVisible();
    }

    void applySaved() {
        String saved = AppPreferences.get(MidiOutputs.PREF_OUTPUT);
        String resolved = MidiOutputs.resolve(saved, MidiOutputs.list().stream().map(MidiOutputs.Choice::id).toList());
        fill(resolved);
        if (!player.outputId().equals(resolved) || !player.isOpen()) {
            player.useOutput(resolved);
        }
        fill(player.outputId());
        if (!saved.isBlank() && !saved.equals(player.outputId())) {
            status.set("Saved MIDI output is not connected. Using the built-in synth.");
        }
        applySoundFontPreference();
    }

    void fill(String selectedId) {
        List<MidiOutputs.Choice> choices = MidiOutputs.list();
        sync.set(true);
        try {
            midiOut.removeAllItems();
            MidiOutputs.Choice selected = null;
            for (MidiOutputs.Choice choice : choices) {
                midiOut.addItem(choice);
                if (choice.id().equals(selectedId)) {
                    selected = choice;
                }
            }
            if (selected == null && midiOut.getItemCount() > 0) {
                selected = midiOut.getItemAt(0);
            }
            midiOut.setSelectedItem(selected);
        } finally {
            sync.set(false);
        }
    }

    private void midiOutputChosen() {
        if (sync.on() || !(midiOut.getSelectedItem() instanceof MidiOutputs.Choice choice)) {
            return;
        }
        if (choice.id().equals(player.outputId()) && player.isOpen()) {
            return;
        }
        if (!player.useOutput(choice.id())) {
            JOptionPane.showMessageDialog(
                frame,
                player.failure() == null ? "That MIDI output is not available." : player.failure(),
                "Hits",
                JOptionPane.WARNING_MESSAGE
            );
            fill(player.outputId());
            updateTitle.run();
            return;
        }
        AppPreferences.put(MidiOutputs.PREF_OUTPUT, choice.id());
        status.set("MIDI out: " + choice.label());
        if (player.soundFontNote() != null) {
            status.set(player.soundFontNote());
        }
        updateTitle.run();
    }

    private void chooseSoundFont() {
        if (!player.isBuiltIn()) {
            if (AppPreferences.get(SoundFonts.PREF_FILE).isBlank()) {
                JOptionPane.showMessageDialog(
                    frame,
                    SoundFonts.NEEDS_BUILTIN + "\nAn external device uses its own sounds.",
                    "Hits",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }
            String[] options = {"Forget saved SoundFont", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                frame,
                SoundFonts.NEEDS_BUILTIN + "\nAn external device uses its own sounds.",
                "SoundFont",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[1]
            );
            if (choice == 0) {
                player.clearSoundFont();
                AppPreferences.remove(SoundFonts.PREF_FILE);
                status.set("Built-in sounds");
            }
            return;
        }
        if (player.soundFont() == null && !AppPreferences.get(SoundFonts.PREF_FILE).isBlank()) {
            String[] options = {"Load a SoundFont", "Forget saved SoundFont", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                frame,
                "A SoundFont is remembered, but it is not loaded.",
                "SoundFont",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]
            );
            if (choice == 1) {
                player.clearSoundFont();
                AppPreferences.remove(SoundFonts.PREF_FILE);
                status.set("Built-in sounds");
                return;
            }
            if (choice != 0) {
                return;
            }
        }
        if (player.soundFont() != null) {
            String[] options = {"Load another", "Built-in sounds", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                frame,
                "Loaded " + player.soundFontName() + ".",
                "SoundFont",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[0]
            );
            if (choice == 1) {
                player.useDefaultSounds();
                AppPreferences.remove(SoundFonts.PREF_FILE);
                status.set("Built-in sounds");
                return;
            }
            if (choice != 0) {
                return;
            }
        }
        Path start = beatsFolder.get();
        String saved = AppPreferences.get(SoundFonts.PREF_FILE);
        if (!saved.isBlank()) {
            Path parent = Path.of(saved).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                start = parent;
            }
        }
        JFileChooser chooser = new JFileChooser(start.toFile());
        chooser.setDialogTitle("SoundFont");
        chooser.setFileFilter(new FileNameExtensionFilter("SoundFont", "sf2"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            String name = player.loadSoundFont(path);
            AppPreferences.put(SoundFonts.PREF_FILE, path.toAbsolutePath().normalize().toString());
            status.set("SoundFont: " + name);
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(frame, Widgets.message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applySoundFontPreference() {
        if (!player.isBuiltIn()) {
            return;
        }
        String saved = AppPreferences.get(SoundFonts.PREF_FILE);
        if (saved.isBlank()) {
            return;
        }
        Path path = Path.of(saved);
        if (!Files.isRegularFile(path)) {
            status.set("SoundFont not found: " + path.getFileName());
            return;
        }
        try {
            status.set("SoundFont: " + player.loadSoundFont(path));
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            status.set(Widgets.message(exception));
        }
    }
}
