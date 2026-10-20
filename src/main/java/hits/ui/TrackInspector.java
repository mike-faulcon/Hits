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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/** Track name, sound, length, hold, nudge, volume, chance, loud hit, mode, step pitch, and pattern length. */
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
    private final JSlider chance = Widgets.slider(0, 100, 100);
    private final JLabel chanceValue = Widgets.label("100%");
    private final JSlider accent = Widgets.slider(1, 127, 120);
    private final JLabel accentValue = Widgets.label("120");
    private final JButton drumMode = Widgets.button("Drum");
    private final JButton noteMode = Widgets.button("Note");
    private final JButton steps16 = Widgets.button("16 steps");
    private final JButton steps32 = Widgets.button("32 steps");
    private final JSpinner patternSteps = new JSpinner(new SpinnerNumberModel(16, Beat.MIN_STEPS, Beat.MAX_STEPS, 1));
    private final JButton shuffle = Widgets.button("Shuffle");
    private Runnable shuffleTrack = () -> {};
    private final JLabel stepPitchValue = new JLabel("—", SwingConstants.LEFT);
    private final JButton stepPitchDown = Widgets.button("−");
    private final JButton stepPitchUp = Widgets.button("+");
    private final JButton stepPitchTrack = Widgets.button("Use track");
    private final JSpinner hold = new JSpinner(new SpinnerNumberModel(Step.MIN_LENGTH, Step.MIN_LENGTH, Step.MAX_LENGTH, 1));
    private final JLabel holdUnit = Widgets.label("steps");
    private final JSpinner nudge = new JSpinner(new SpinnerNumberModel(0, Step.MIN_NUDGE, Step.MAX_NUDGE, 1));
    private final JLabel nudgeUnit = Widgets.label("%");
    private final JCheckBox legato = new JCheckBox("Legato");
    private final JSpinner shift = new JSpinner(new SpinnerNumberModel(0, Step.MIN_NUDGE, Step.MAX_NUDGE, 1));
    private final JLabel shiftUnit = Widgets.label("%");
    private final SliderDrag gateDrag;
    private final SliderDrag velocityDrag;
    private final SliderDrag chanceDrag;
    private final SliderDrag accentDrag;

    TrackInspector(Editor editor, Player player, Sync sync) {
        this.editor = editor;
        this.player = player;
        this.sync = sync;
        this.gateDrag = new SliderDrag(editor, sync);
        this.velocityDrag = new SliderDrag(editor, sync);
        this.chanceDrag = new SliderDrag(editor, sync);
        this.accentDrag = new SliderDrag(editor, sync);
    }

    void onShuffle(Runnable action) {
        shuffleTrack = action;
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
        JPanel panel = new InspectorBody();
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
        String holdTip = "How many steps the selected hit lasts. 1 is a single step and is left out of the file. Drag a lit pad sideways to stretch it. Length, below, shortens only the last step.";
        addRow(panel, constraints, spun("Hold", holdTip, hold, holdUnit));
        String nudgeTip = "Moves the selected hit off the grid, as a percent of one step. −50 is half a step early, +50 is half a step late. Comma and period nudge it. 0 is left out of the file.";
        addRow(panel, constraints, spun("Nudge", nudgeTip, nudge, nudgeUnit));
        legato.setOpaque(false);
        legato.setForeground(Theme.TEXT);
        legato.setFocusable(false);
        legato.setToolTipText("Note tracks only. Each hit holds until the next hit on this row. Drum tracks keep Hold instead.");
        addRow(panel, constraints, legato);
        String shiftTip = "Moves every hit on this track by the same percent of a step. Added to each step's nudge, then kept inside half a step. 0 is left out of the file.";
        addRow(panel, constraints, spun("Shift", shiftTip, shift, shiftUnit));
        addRow(panel, constraints, Widgets.label("Sound"));
        drums.setToolTipText("Drum sound for this track.");
        programs.setToolTipText("Instrument for this track. One pitch for every step.");
        soundPicker.setOpaque(false);
        soundPicker.add(drums, "drum");
        soundPicker.add(programs, "note");
        addRow(panel, constraints, soundPicker);
        addRow(panel, constraints, labeled("Length", "How long the last step of a hit holds, as a percent of that step. A one-step hit uses the whole step this way.", gate, gateValue));
        addRow(panel, constraints, labeled("Volume", "How hard a hit is. Drag a pad up or down, or the velocity lane, to edit that step. Right-click or Alt-click selects it.", velocity, velocityValue));
        addRow(panel, constraints, labeled("Chance", "Percent chance the selected step plays. 100% always plays. Playback rolls each pass. Export uses a fixed seed.", chance, chanceValue));
        addRow(panel, constraints, labeled("Loud hit", "Shift-click uses this volume. Pads at least this loud use the bright color.", accent, accentValue));
        addRow(panel, constraints, Widgets.label("Mode"));
        drumMode.setToolTipText("Drum kit sounds, on the drum channel.");
        noteMode.setToolTipText("One pitched instrument for the whole track.");
        addRow(panel, constraints, Widgets.row(drumMode, noteMode));
        shuffle.setToolTipText("New hits on this track. The sound stays. Undo puts the old hits back.");
        shuffle.addActionListener(event -> shuffleTrack.run());
        addRow(panel, constraints, shuffle);
        JButton copyBar = Widgets.button("Copy bar");
        copyBar.setToolTipText("Copy the first 16 steps into a second bar and set this pattern to 32 steps.");
        copyBar.addActionListener(event -> {
            char slot = editor.viewSlot();
            editor.edit(beat -> beat.copyBar(slot));
        });
        addRow(panel, constraints, copyBar);
        steps16.setToolTipText("This pattern is one bar of 16th notes. Other patterns keep their own length.");
        steps32.setToolTipText("This pattern is two bars. Other patterns keep their own length.");
        addRow(panel, constraints, Widgets.row(steps16, steps32));
        JLabel stepsLabel = Widgets.label("Steps");
        String stepsTip = "How many steps this pattern plays, from 1 to 32. 16 is one bar. Notes past that stay on the grid, dimmed.";
        stepsLabel.setToolTipText(stepsTip);
        patternSteps.setToolTipText(stepsTip);
        Widgets.fix(patternSteps, 72, 32);
        addRow(panel, constraints, Widgets.row(stepsLabel, patternSteps));
        constraints.weighty = 1;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        addRow(panel, constraints, spacer);
        return panel;
    }

    void wire() {
        gateDrag.wire(gate, beat -> onScreen(beat).setGate(gate.getValue()));
        velocityDrag.wire(velocity, this::applyVelocity);
        chanceDrag.wire(chance, this::applyChance);
        accentDrag.wire(accent, beat -> onScreen(beat).setAccent(accent.getValue()));
        drumMode.addActionListener(event -> setMode(TrackMode.DRUM));
        noteMode.addActionListener(event -> setMode(TrackMode.NOTE));
        steps16.addActionListener(event -> setPatternLength(16));
        steps32.addActionListener(event -> setPatternLength(32));
        patternSteps.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            setPatternLength((Integer) patternSteps.getValue());
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
        hold.addChangeListener(event -> {
            if (sync.on() || !hold.isEnabled()) {
                return;
            }
            int length = ((Number) hold.getValue()).intValue();
            if (selectedStep() == null || selectedStep().length() == length) {
                return;
            }
            editor.setStepLength(length);
        });
        nudge.addChangeListener(event -> {
            if (sync.on() || !nudge.isEnabled()) {
                return;
            }
            int percent = ((Number) nudge.getValue()).intValue();
            if (selectedStep() == null || selectedStep().nudge() == percent) {
                return;
            }
            editor.setStepNudge(percent);
        });
        legato.addActionListener(event -> {
            if (sync.on() || editor.selectedTrack().legato() == legato.isSelected()) {
                return;
            }
            editor.setLegato(legato.isSelected());
        });
        shift.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            int percent = ((Number) shift.getValue()).intValue();
            if (editor.selectedTrack().shift() == percent) {
                return;
            }
            editor.setShift(percent);
        });
    }

    private Step selectedStep() {
        int column = editor.stepIndex();
        Track track = editor.selectedTrack();
        if (column < 0 || column >= editor.beat().gridColumns(editor.viewSlot())) {
            return null;
        }
        return track.step(column);
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
        int columns = beat.gridColumns(editor.viewSlot());
        velocity.setToolTipText(TrackReadout.velocityTip(track, editor.stepIndex(), columns));
        TrackReadout.Chance shownChance = TrackReadout.chance(track, editor.stepIndex(), columns);
        chance.setEnabled(shownChance.enabled());
        if (!chance.getValueIsAdjusting() || !shownChance.enabled()) {
            chance.setValue(shownChance.value());
        }
        chanceValue.setText(shownChance.text());
        chance.setToolTipText(shownChance.tip());
        if (!accent.getValueIsAdjusting()) {
            accent.setValue(track.accent());
        }
        accentValue.setText(Integer.toString(track.accent()));
        Widgets.paintToggle(drumMode, track.mode() == TrackMode.DRUM);
        Widgets.paintToggle(noteMode, track.mode() == TrackMode.NOTE);
        int length = beat.stepCount(editor.viewSlot());
        Widgets.paintToggle(steps16, length == 16);
        Widgets.paintToggle(steps32, length == 32);
        if (!patternSteps.getValue().equals(length)) {
            patternSteps.setValue(length);
        }
        TrackReadout.StepPitch pitch = TrackReadout.stepPitch(beat, editor.viewSlot(), track, editor.stepIndex());
        stepPitchDown.setEnabled(pitch.nudgeEnabled());
        stepPitchUp.setEnabled(pitch.nudgeEnabled());
        stepPitchTrack.setEnabled(pitch.clearEnabled());
        stepPitchValue.setText(pitch.text());
        stepPitchValue.setToolTipText(pitch.tip());
        Step selected = selectedStep();
        boolean stepChosen = selected != null;
        hold.setEnabled(stepChosen);
        nudge.setEnabled(stepChosen);
        if (stepChosen) {
            hold.setValue(selected.length());
            nudge.setValue(selected.nudge());
            holdUnit.setText(selected.length() == 1 ? "step" : "steps");
            nudgeUnit.setText(signedPercent(selected.nudge()));
        } else {
            hold.setValue(Step.MIN_LENGTH);
            nudge.setValue(0);
            holdUnit.setText("steps");
            nudgeUnit.setText("—");
        }
        boolean noteTrack = track.mode() == TrackMode.NOTE;
        legato.setEnabled(noteTrack);
        legato.setSelected(track.legato());
        shift.setValue(track.shift());
        shiftUnit.setText(signedPercent(track.shift()));
    }

    private static String signedPercent(int value) {
        if (value > 0) {
            return "+" + value + "%";
        }
        if (value < 0) {
            return "−" + Math.abs(value) + "%";
        }
        return "0%";
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
        if (column < 0 || column >= editor.beat().gridColumns(editor.viewSlot()) || !current.step(column).on()) {
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

    private void applyChance(Beat beat) {
        int step = editor.stepIndex();
        if (step < 0 || step >= beat.gridColumns(editor.viewSlot())) {
            return;
        }
        onScreen(beat).step(step).setProbability(chance.getValue());
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

    private void setPatternLength(int steps) {
        char slot = editor.viewSlot();
        if (editor.beat().stepCount(slot) == steps) {
            return;
        }
        editor.edit(beat -> beat.setSlotSteps(slot, steps));
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

    /**
     * Stays as wide as the inspector column. The step grid scrolls sideways instead of
     * squeezing Chance, Volume, and the sound picker down to a sliver.
     */
    private static final class InspectorBody extends JPanel implements Scrollable {
        private static final int PREFERRED_WIDTH = 320;

        private InspectorBody() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(PREFERRED_WIDTH, getPreferredSize().height);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void addRow(JPanel panel, GridBagConstraints constraints, JComponent component) {
        panel.add(component, constraints);
        constraints.gridy++;
        constraints.weighty = 0;
    }

    /**
     * A spinner row that stays inside the 300px inspector. The label and unit have floors
     * so a narrow window does not collapse the number field.
     */
    private static JPanel spun(String name, String tip, JSpinner spinner, JLabel unit) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel title = Widgets.label(name);
        title.setToolTipText(tip);
        title.setPreferredSize(new Dimension(72, 32));
        title.setMinimumSize(new Dimension(56, 28));
        panel.add(title, BorderLayout.WEST);
        spinner.setToolTipText(tip);
        spinner.setPreferredSize(new Dimension(88, 32));
        spinner.setMinimumSize(new Dimension(64, 28));
        panel.add(spinner, BorderLayout.CENTER);
        unit.setToolTipText(tip);
        unit.setPreferredSize(new Dimension(52, 32));
        unit.setMinimumSize(new Dimension(36, 28));
        panel.add(unit, BorderLayout.EAST);
        return panel;
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
