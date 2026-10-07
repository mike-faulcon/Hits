package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Gm;
import hits.Notes;
import hits.Player;
import hits.Step;
import hits.Track;
import hits.TrackMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/** Track name, sound, length, volume, loud hit, mode, step pitch, and pattern length. */
final class TrackInspector {
    private final Editor editor;
    private final Player player;
    private final Sync sync;
    private final JTextField trackName = new JTextField();
    private final JLabel noteValue = new JLabel("C1", SwingConstants.LEFT);
    private final JComboBox<String> drums = new JComboBox<>();
    private final JComboBox<String> programs = new JComboBox<>();
    private final JPanel soundPicker = new JPanel(new CardLayout());
    private final JSlider gate = Widgets.slider(5, 100, 45);
    private final JLabel gateValue = Widgets.label("45%");
    private final JSlider velocity = Widgets.slider(1, 127, 100);
    private final JLabel velocityValue = Widgets.label("100");
    private final JSlider accent = Widgets.slider(1, 127, 120);
    private final JLabel accentValue = Widgets.label("120");
    private final JButton drumMode = Widgets.button("Drum");
    private final JButton noteMode = Widgets.button("Note");
    private final JButton steps16 = Widgets.button("16 steps");
    private final JButton steps32 = Widgets.button("32 steps");
    private final JLabel stepPitchValue = new JLabel("—", SwingConstants.LEFT);
    private final JButton stepPitchDown = Widgets.button("−");
    private final JButton stepPitchUp = Widgets.button("+");
    private final JButton stepPitchTrack = Widgets.button("Use track");
    private final SliderDrag gateDrag;
    private final SliderDrag velocityDrag;
    private final SliderDrag accentDrag;

    TrackInspector(Editor editor, Player player, Sync sync) {
        this.editor = editor;
        this.player = player;
        this.sync = sync;
        this.gateDrag = new SliderDrag(editor, sync);
        this.velocityDrag = new SliderDrag(editor, sync);
        this.accentDrag = new SliderDrag(editor, sync);
    }

    JPanel component() {
        noteValue.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        noteValue.setForeground(Theme.TEXT);
        for (int note : Gm.DRUM_NOTES) {
            drums.addItem(Gm.drumChoice(note));
        }
        for (int program = 0; program < 128; program++) {
            programs.addItem(Gm.programName(program));
        }
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Theme.PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(4, 0, 4, 0);
        constraints.anchor = GridBagConstraints.WEST;

        trackName.setToolTipText("Name shown on this row.");
        addRow(panel, constraints, trackName);
        addRow(panel, constraints, noteValue);
        JPanel noteButtons = Widgets.row(
            buttonPair("−", "One semitone down", -1, 44),
            buttonPair("+", "One semitone up", 1, 44),
            buttonPair("Oct −", "One octave down", -12, 72),
            buttonPair("Oct +", "One octave up", 12, 72)
        );
        addRow(panel, constraints, noteButtons);
        stepPitchValue.setForeground(Theme.TEXT);
        stepPitchValue.setFont(stepPitchValue.getFont().deriveFont(Font.BOLD, 16f));
        stepPitchValue.setPreferredSize(new Dimension(52, 32));
        stepPitchDown.setToolTipText("Lower this step by one semitone.");
        stepPitchUp.setToolTipText("Raise this step by one semitone.");
        stepPitchTrack.setToolTipText("Clear this step's pitch and use the track pitch.");
        JPanel stepPitch = Widgets.row(stepPitchValue, stepPitchDown, stepPitchUp, stepPitchTrack);
        stepPitch.setToolTipText("Pitch of the selected step. A step without its own pitch uses the track pitch.");
        addRow(panel, constraints, Widgets.label("Step pitch"));
        addRow(panel, constraints, stepPitch);
        addRow(panel, constraints, Widgets.label("Sound"));
        drums.setToolTipText("Drum sound for this track.");
        programs.setToolTipText("Instrument for this track. One pitch for every step.");
        soundPicker.setOpaque(false);
        soundPicker.add(drums, "drum");
        soundPicker.add(programs, "note");
        addRow(panel, constraints, soundPicker);
        addRow(panel, constraints, labeled("Length", "How long each note holds, as a percent of the step.", gate, gateValue));
        addRow(panel, constraints, labeled("Volume", "How hard a hit is. Right-click or Alt-click a step to edit that step.", velocity, velocityValue));
        addRow(panel, constraints, labeled("Loud hit", "Shift-click uses this volume. Pads at least this loud use the bright color.", accent, accentValue));
        addRow(panel, constraints, Widgets.label("Mode"));
        drumMode.setToolTipText("Drum kit sounds, on the drum channel.");
        noteMode.setToolTipText("One pitched instrument for the whole track.");
        addRow(panel, constraints, Widgets.row(drumMode, noteMode));
        JButton copyBar = Widgets.button("Copy bar");
        copyBar.setToolTipText("Copy the first 16 steps into a second bar and switch to 32 steps.");
        copyBar.addActionListener(event -> {
            char slot = editor.viewSlot();
            editor.edit(beat -> beat.copyBar(slot));
        });
        addRow(panel, constraints, copyBar);
        steps16.setToolTipText("One bar of 16th notes.");
        steps32.setToolTipText("Two bars of 16th notes.");
        addRow(panel, constraints, Widgets.row(steps16, steps32));
        constraints.weighty = 1;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        addRow(panel, constraints, spacer);
        return panel;
    }

    void wire() {
        gateDrag.wire(gate, beat -> onScreen(beat).setGate(gate.getValue()));
        velocityDrag.wire(velocity, this::applyVelocity);
        accentDrag.wire(accent, beat -> onScreen(beat).setAccent(accent.getValue()));
        drumMode.addActionListener(event -> setMode(TrackMode.DRUM));
        noteMode.addActionListener(event -> setMode(TrackMode.NOTE));
        steps16.addActionListener(event -> {
            if (editor.beat().stepCount() != 16) {
                editor.edit(beat -> beat.setStepCount(16));
            }
        });
        steps32.addActionListener(event -> {
            if (editor.beat().stepCount() != 32) {
                editor.edit(beat -> beat.setStepCount(32));
            }
        });
        drums.addActionListener(event -> {
            if (sync.on() || drums.getSelectedIndex() < 0) {
                return;
            }
            int note = Gm.DRUM_NOTES[drums.getSelectedIndex()];
            if (note == editor.selectedTrack().note()) {
                return;
            }
            editor.edit(beat -> onScreen(beat).setNote(note));
            player.audition(editor.selectedTrack());
        });
        programs.addActionListener(event -> {
            if (sync.on() || programs.getSelectedIndex() < 0) {
                return;
            }
            int program = programs.getSelectedIndex();
            if (program == editor.selectedTrack().program()) {
                return;
            }
            editor.edit(beat -> onScreen(beat).setProgram(program));
            player.audition(editor.selectedTrack());
        });
        trackName.addActionListener(event -> commitTrackName());
        trackName.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                commitTrackName();
            }
        });
        stepPitchDown.addActionListener(event -> nudgeStepPitch(-1));
        stepPitchUp.addActionListener(event -> nudgeStepPitch(1));
        stepPitchTrack.addActionListener(event -> clearStepPitch());
    }

    void refresh() {
        Beat beat = editor.beat();
        Track track = editor.selectedTrack();
        if (!trackName.isFocusOwner()) {
            trackName.setText(track.name());
        }
        noteValue.setText(Notes.name(track.note()));
        ((CardLayout) soundPicker.getLayout()).show(soundPicker, track.mode() == TrackMode.DRUM ? "drum" : "note");
        selectDrum(track.note());
        programs.setSelectedIndex(track.program());
        if (!gate.getValueIsAdjusting()) {
            gate.setValue(track.gate());
        }
        gateValue.setText(track.gate() + "%");
        int shownVelocity = TrackReadout.shownVelocity(track, editor.stepIndex());
        if (!velocity.getValueIsAdjusting()) {
            velocity.setValue(shownVelocity);
        }
        velocityValue.setText(Integer.toString(shownVelocity));
        velocity.setToolTipText(TrackReadout.velocityTip(track, editor.stepIndex(), beat.stepCount()));
        if (!accent.getValueIsAdjusting()) {
            accent.setValue(track.accent());
        }
        accentValue.setText(Integer.toString(track.accent()));
        Widgets.paintToggle(drumMode, track.mode() == TrackMode.DRUM);
        Widgets.paintToggle(noteMode, track.mode() == TrackMode.NOTE);
        Widgets.paintToggle(steps16, beat.stepCount() == 16);
        Widgets.paintToggle(steps32, beat.stepCount() == 32);
        TrackReadout.StepPitch pitch = TrackReadout.stepPitch(beat, track, editor.stepIndex());
        stepPitchDown.setEnabled(pitch.nudgeEnabled());
        stepPitchUp.setEnabled(pitch.nudgeEnabled());
        stepPitchTrack.setEnabled(pitch.clearEnabled());
        stepPitchValue.setText(pitch.text());
        stepPitchValue.setToolTipText(pitch.tip());
    }

    void commitTrackName() {
        String next = trackName.getText().trim();
        if (next.isEmpty() || next.equals(editor.selectedTrack().name())) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setName(next));
    }

    private void nudgeStepPitch(int delta) {
        int row = editor.trackIndex();
        int column = editor.stepIndex();
        Track current = editor.selectedTrack();
        if (column < 0 || column >= editor.beat().stepCount() || !current.step(column).on()) {
            return;
        }
        char slot = editor.viewSlot();
        editor.edit(beat -> {
            Track track = beat.slot(slot)[row];
            Step step = track.step(column);
            step.setPitch(step.soundingNote(track.note()) + delta);
        });
        Track updated = editor.selectedTrack();
        player.audition(updated, updated.step(column).soundingNote(updated.note()));
    }

    private void clearStepPitch() {
        int row = editor.trackIndex();
        int column = editor.stepIndex();
        Track current = editor.selectedTrack();
        if (column < 0 || !current.step(column).hasPitch()) {
            return;
        }
        char slot = editor.viewSlot();
        editor.edit(beat -> beat.slot(slot)[row].step(column).clearPitch());
        player.audition(editor.selectedTrack());
    }

    private void selectDrum(int note) {
        for (int i = 0; i < Gm.DRUM_NOTES.length; i++) {
            if (Gm.DRUM_NOTES[i] == note) {
                drums.setSelectedIndex(i);
                return;
            }
        }
        drums.setSelectedIndex(-1);
    }

    private void applyVelocity(Beat beat) {
        Track track = onScreen(beat);
        int step = editor.stepIndex();
        int value = velocity.getValue();
        if (step >= 0 && track.step(step).on()) {
            track.step(step).setVelocity(value);
        } else {
            track.setVelocity(value);
        }
    }

    private Track onScreen(Beat beat) {
        return beat.slot(editor.viewSlot())[editor.trackIndex()];
    }

    private void setMode(TrackMode mode) {
        if (editor.selectedTrack().mode() == mode) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setMode(mode));
        player.audition(editor.selectedTrack());
    }

    private JButton buttonPair(String text, String tip, int delta, int width) {
        JButton button = Widgets.button(text);
        button.setToolTipText(tip);
        Widgets.fix(button, width, 32);
        button.addActionListener(event -> changeNote(delta));
        return button;
    }

    private void changeNote(int delta) {
        editor.edit(beat -> {
            Track track = onScreen(beat);
            track.setNote(track.note() + delta);
        });
        player.audition(editor.selectedTrack());
    }

    private static void addRow(JPanel panel, GridBagConstraints constraints, JComponent component) {
        panel.add(component, constraints);
        constraints.gridy++;
        constraints.weighty = 0;
    }

    private static JPanel labeled(String name, String tip, JSlider slider, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel title = Widgets.label(name);
        title.setToolTipText(tip);
        title.setPreferredSize(new Dimension(88, 32));
        panel.add(title, BorderLayout.WEST);
        slider.setToolTipText(tip);
        panel.add(slider, BorderLayout.CENTER);
        value.setToolTipText(tip);
        value.setPreferredSize(new Dimension(44, 32));
        panel.add(value, BorderLayout.EAST);
        return panel;
    }
}
