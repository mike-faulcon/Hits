package hits.ui;

import hits.Beat;
import hits.BuiltinSamples;
import hits.ChannelPlan;
import hits.Editor;
import hits.Gm;
import hits.Notes;
import hits.PitchedPreset;
import hits.Player;
import hits.SamplePaths;
import hits.SampleRef;
import hits.SoundCatalog;
import hits.Step;
import hits.Track;
import hits.TrackMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.Scrollable;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.sound.midi.Soundbank;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Track name, sound, length, hold, nudge, volume, chance, loud hit, mode, step pitch, and pattern length. */
final class TrackInspector {
    private final Editor editor;
    private final Player player;
    private final Sync sync;
    private final JTextField trackName = new JTextField();
    private final JLabel noteValue = new JLabel("C1", SwingConstants.LEFT);
    private final JComboBox<String> drums = new JComboBox<>();
    private final JComboBox<String> kits = new JComboBox<>();
    private final JComboBox<String> programs = new JComboBox<>();
    private final JLabel channelNote = Widgets.label("Out of channels.");
    private final JLabel kitNote = Widgets.label(" ");
    private final List<DrumPick> drumPicks = new ArrayList<>();
    private final List<Integer> kitPrograms = new ArrayList<>();
    private List<SoundCatalog.Voice> voices = List.of();
    private Soundbank programBank;
    private boolean programsReady;
    private final List<JButton> noteButtons = new ArrayList<>();
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
    private final JButton sampleMode = Widgets.button("Sample");
    private final JComboBox<String> samplePicker = new JComboBox<>();
    private final JLabel sampleWarning = Widgets.label(" ");
    private final JSpinner sampleRoot = new JSpinner(new SpinnerNumberModel(SampleRef.DEFAULT_ROOT, 0, 127, 1));
    private final JLabel sampleRootName = Widgets.label(Notes.name(SampleRef.DEFAULT_ROOT));
    private final JSpinner sampleStart = new JSpinner(new SpinnerNumberModel(0, 0, 1_000_000, 1));
    private final JButton oneShot = Widgets.button("One-shot");
    private final JButton choke = Widgets.button("Choke");
    private final JSlider sampleGain = Widgets.slider(SampleRef.GAIN_MIN, SampleRef.GAIN_MAX, SampleRef.GAIN);
    private final JLabel sampleGainValue = Widgets.label(SampleRef.gainText(SampleRef.GAIN));
    private final JCheckBox sampleInvert = new JCheckBox("Invert");
    private final JButton collectSamples = Widgets.button("Collect samples");
    private Runnable collectAction = () -> {};
    private Supplier<Path> beatFile = () -> null;
    private boolean choosingSample;
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
    private final JSpinner tuneSemi = new JSpinner(new SpinnerNumberModel(0, -24, 24, 1));
    private final JSpinner tuneCents = new JSpinner(new SpinnerNumberModel(0, -50, 50, 1));
    private final JSlider decay = Widgets.slider(Track.DECAY_MIN, Track.DECAY_MAX, Track.DECAY);
    private final JLabel decayTitle = Widgets.label("Decay");
    private final JLabel decayValue = Widgets.label(Track.DECAY + "%");
    private final JCheckBox cutoffOn = new JCheckBox("Cutoff");
    private final JSlider cutoff = Widgets.slider(0, 127, Track.FILTER_NEUTRAL);
    private final JLabel cutoffValue = Widgets.label("—");
    private final JCheckBox resonanceOn = new JCheckBox("Resonance");
    private final JSlider resonance = Widgets.slider(0, 127, Track.FILTER_NEUTRAL);
    private final JLabel resonanceValue = Widgets.label("—");
    private final JCheckBox lowCutOn = new JCheckBox("Low cut");
    private final JSlider lowCut = Widgets.slider(0, Track.LOW_CUT_SLIDER, Track.lowCutSlider(120));
    private final JLabel lowCutValue = Widgets.label("—");
    private final JButton slope12 = Widgets.button("12");
    private final JButton slope24 = Widgets.button("24");
    private final JSlider presence = Widgets.slider(0, Track.PRESENCE_MAX, 0);
    private final JLabel presenceValue = Widgets.label("0 dB");
    private final SliderDrag gateDrag;
    private final SliderDrag decayDrag;
    private final SliderDrag cutoffDrag;
    private final SliderDrag resonanceDrag;
    private final SliderDrag lowCutDrag;
    private final SliderDrag presenceDrag;
    /** Slope the buttons show. Kept while Low cut is off so the toggle can be set first. */
    private int shownSlope = Track.LOW_CUT_SLOPE;
    private String slopeContext = "";
    private final SliderDrag velocityDrag;
    private final SliderDrag chanceDrag;
    private final SliderDrag accentDrag;
    private final SliderDrag sampleGainDrag;

    TrackInspector(Editor editor, Player player, Sync sync) {
        this.editor = editor;
        this.player = player;
        this.sync = sync;
        this.gateDrag = new SliderDrag(editor, sync);
        this.decayDrag = new SliderDrag(editor, sync);
        this.cutoffDrag = new SliderDrag(editor, sync);
        this.resonanceDrag = new SliderDrag(editor, sync);
        this.lowCutDrag = new SliderDrag(editor, sync);
        this.presenceDrag = new SliderDrag(editor, sync);
        this.velocityDrag = new SliderDrag(editor, sync);
        this.chanceDrag = new SliderDrag(editor, sync);
        this.accentDrag = new SliderDrag(editor, sync);
        this.sampleGainDrag = new SliderDrag(editor, sync);
    }

    void onShuffle(Runnable action) {
        shuffleTrack = action;
    }

    void onCollect(Runnable action) {
        collectAction = action == null ? () -> {} : action;
    }

    /** Beat file used to resolve a relative sample and to explain a missing file. */
    void setBeatFile(Supplier<Path> beatFile) {
        this.beatFile = beatFile == null ? () -> null : beatFile;
    }

    JPanel component() {
        noteValue.setName("note-heading");
        noteValue.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        noteValue.setForeground(Theme.TEXT);
        drums.setName("drum-sound");
        kits.setName("drum-kit");
        programs.setName("note-program");
        drums.setMaximumRowCount(16);
        kits.setMaximumRowCount(8);
        programs.setMaximumRowCount(14);
        for (int note : Gm.DRUM_NOTES) {
            drums.addItem(Gm.drumChoice(note));
        }
        for (PitchedPreset preset : PitchedPreset.values()) {
            drums.addItem(preset.label());
        }
        kits.addItem("0  Standard");
        for (int program = 0; program < 128; program++) {
            programs.addItem("0:" + program + "  " + Gm.programName(program));
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
        steps16.setToolTipText("This pattern is one bar of 16th notes. Other patterns keep their own length.");
        steps32.setToolTipText("This pattern is two bars. Other patterns keep their own length.");
        addRow(panel, constraints, Widgets.row(steps16, steps32));
        JLabel stepsLabel = Widgets.label("Steps");
        String stepsTip = "How many steps this pattern plays, from 1 to 32. 16 is one bar. Notes past that stay on the grid, dimmed.";
        stepsLabel.setToolTipText(stepsTip);
        patternSteps.setToolTipText(stepsTip);
        Widgets.fix(patternSteps, 72, 32);
        addRow(panel, constraints, Widgets.row(stepsLabel, patternSteps));
        addRow(panel, constraints, noteValue);
        JPanel noteButtons = Widgets.row(
            buttonPair("−", "One semitone down", -1, 44),
            buttonPair("+", "One semitone up", 1, 44),
            buttonPair("Oct −", "One octave down", -12, 72),
            buttonPair("Oct +", "One octave up", 12, 72)
        );
        addRow(panel, constraints, noteButtons);
        stepPitchValue.setName("step-pitch");
        stepPitchValue.setForeground(Theme.TEXT);
        stepPitchValue.setFont(stepPitchValue.getFont().deriveFont(Font.BOLD, 16f));
        stepPitchValue.setPreferredSize(new Dimension(148, 32));
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
        String shiftTip = "Moves every hit on this track by the same percent of a step. Added to each step's nudge, then kept inside half a step. Swing cannot push that onto the next step. 0 is left out of the file.";
        addRow(panel, constraints, spun("Shift", shiftTip, shift, shiftUnit));
        JLabel soundHeading = Widgets.label("Sound");
        soundHeading.setName("sound-heading");
        addRow(panel, constraints, soundHeading);
        drums.setToolTipText("Drum key, or a pitched percussion preset. A preset plays a melodic instrument at its own pitch, on this row's channel.");
        kits.setToolTipText("Drum kit on this row. Standard is the kit in the loaded bank. Another kit uses its own channel. A pitched preset replaces the kit.");
        programs.setToolTipText("Instrument from the loaded soundbank. The numbers are bank and program.");
        channelNote.setForeground(Theme.MUTED);
        channelNote.setVisible(false);
        channelNote.setToolTipText("Every other channel is in use, so this drum shares the drum channel.");
        kitNote.setName("kit-warning");
        kitNote.setForeground(Theme.MUTED);
        kitNote.setVisible(false);
        soundPicker.setOpaque(false);
        soundPicker.add(drums, "drum");
        soundPicker.add(programs, "note");
        soundPicker.add(sampleCard(), "sample");
        addRow(panel, constraints, soundPicker);
        addRow(panel, constraints, kits);
        addRow(panel, constraints, kitNote);
        addRow(panel, constraints, channelNote);
        String tuneTip = "Pitch of this row, from −24 to +24 semitones, plus or minus 50 cents. A tuned drum uses its own channel. Built-in synth only. A DAW may ignore this.";
        tuneSemi.setName("tune-semitone");
        tuneCents.setName("tune-cents");
        addRow(panel, constraints, tuneRow(tuneTip));
        decay.setName("decay");
        decayValue.setName("decay-value");
        addRow(panel, constraints, labeled(decayTitle, DECAY_TIP, decay, decayValue));
        decayValue.setPreferredSize(new Dimension(96, 32));
        cutoff.setName("cutoff");
        addRow(panel, constraints, effectRow(cutoffOn, cutoff, cutoffValue, "Low-pass filter, from dull to bright. 64 is the synth's own tone. Off leaves the controller unset, so the sound stays as it is. Built-in synth only. A DAW may ignore this."));
        resonance.setName("resonance");
        addRow(panel, constraints, effectRow(resonanceOn, resonance, resonanceValue, "Filter emphasis. 64 is the synth's own resonance. Off leaves the controller unset. Built-in synth only. A DAW may ignore this."));
        lowCut.setName("low-cut");
        lowCutOn.setName("low-cut-on");
        lowCutValue.setName("low-cut-value");
        addRow(panel, constraints, lowCutRow());
        addRow(panel, constraints, slopeRow());
        presence.setName("presence");
        presenceValue.setName("presence-value");
        addRow(panel, constraints, presenceRow());
        addRow(panel, constraints, labeled("Length", "How long the last step of a hit holds, as a percent of that step. A one-step hit uses the whole step this way.", gate, gateValue));
        addRow(panel, constraints, labeled("Volume", "How hard a hit is. Drag a pad up or down, or the velocity lane, to edit that step. Right-click or Alt-click selects it.", velocity, velocityValue));
        addRow(panel, constraints, labeled("Chance", "Percent chance the selected step plays. 100% always plays. Playback rolls each pass. Export uses a fixed seed.", chance, chanceValue));
        addRow(panel, constraints, labeled("Loud hit", "Shift-click uses this volume. Pads at least this loud use the bright color.", accent, accentValue));
        addRow(panel, constraints, Widgets.label("Mode"));
        drumMode.setToolTipText("Drum kit sounds, on the drum channel.");
        noteMode.setToolTipText("One pitched instrument for the whole track.");
        sampleMode.setToolTipText("A one-shot WAV, played through the built-in synth. Tune, decay, gain, polarity, and effects still apply.");
        addRow(panel, constraints, Widgets.row(drumMode, noteMode, sampleMode));
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
        constraints.weighty = 1;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        addRow(panel, constraints, spacer);
        return panel;
    }

    void wire() {
        gateDrag.wire(gate, beat -> onScreen(beat).setGate(gate.getValue()));
        decayDrag.wire(decay, beat -> onScreen(beat).setDecay(decay.getValue()));
        cutoffDrag.wire(cutoff, beat -> {
            Track track = onScreen(beat);
            track.setCutoff(cutoff.getValue());
        });
        resonanceDrag.wire(resonance, beat -> onScreen(beat).setResonance(resonance.getValue()));
        lowCutDrag.wire(lowCut, beat -> {
            Track track = onScreen(beat);
            if (track.hasLowCut()) {
                track.setLowCut(Track.lowCutHz(lowCut.getValue()), slopeChoice());
            }
        });
        presenceDrag.wire(presence, beat -> onScreen(beat).setPresence(presence.getValue()));
        tuneSemi.addChangeListener(event -> applyTune());
        tuneCents.addChangeListener(event -> applyTune());
        cutoffOn.addActionListener(event -> applyCutoffToggle());
        resonanceOn.addActionListener(event -> applyResonanceToggle());
        lowCutOn.addActionListener(event -> applyLowCutToggle());
        slope12.addActionListener(event -> applySlope(Track.LOW_CUT_SLOPE));
        slope24.addActionListener(event -> applySlope(Track.LOW_CUT_STEEP));
        velocityDrag.wire(velocity, this::applyVelocity);
        chanceDrag.wire(chance, this::applyChance);
        accentDrag.wire(accent, beat -> onScreen(beat).setAccent(accent.getValue()));
        sampleGainDrag.wire(sampleGain, beat -> {
            Track track = onScreen(beat);
            if (track.sample() != null) {
                track.setSample(track.sample().withGain(sampleGain.getValue()));
            }
        });
        drumMode.addActionListener(event -> setMode(TrackMode.DRUM));
        noteMode.addActionListener(event -> setMode(TrackMode.NOTE));
        sampleMode.addActionListener(event -> setMode(TrackMode.SAMPLE));
        samplePicker.addActionListener(event -> chooseSample());
        sampleRoot.addChangeListener(event -> applySampleRoot());
        sampleStart.addChangeListener(event -> applySampleStart());
        oneShot.addActionListener(event -> applyOneShot(true));
        choke.addActionListener(event -> applyOneShot(false));
        sampleInvert.addActionListener(event -> applyInvert());
        collectSamples.addActionListener(event -> collectAction.run());
        steps16.addActionListener(event -> setPatternLength(16));
        steps32.addActionListener(event -> setPatternLength(32));
        patternSteps.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            setPatternLength((Integer) patternSteps.getValue());
        });
        drums.addActionListener(event -> {
            if (sync.on() || drums.getSelectedIndex() < 0 || drums.getSelectedIndex() >= drumPicks.size()) {
                return;
            }
            DrumPick pick = drumPicks.get(drums.getSelectedIndex());
            Track current = editor.selectedTrack();
            if (pick instanceof DrumKey key) {
                if (current.preset() == null && current.note() == key.note()) {
                    return;
                }
                editor.edit(beat -> {
                    Track track = onScreen(beat);
                    track.setPreset(null);
                    track.setNote(key.note());
                });
            } else if (pick instanceof DrumPreset chosen) {
                if (current.preset() == chosen.preset()) {
                    return;
                }
                editor.edit(beat -> onScreen(beat).setPreset(chosen.preset()));
            }
            auditionRow();
        });
        kits.addActionListener(event -> {
            if (sync.on() || kits.getSelectedIndex() < 0 || kits.getSelectedIndex() >= kitPrograms.size()) {
                return;
            }
            int kit = kitPrograms.get(kits.getSelectedIndex());
            Track current = editor.selectedTrack();
            if (kit == current.kit() && (kit == 0 || current.preset() == null)) {
                return;
            }
            editor.edit(beat -> {
                Track track = onScreen(beat);
                track.setKit(kit);
                if (kit != 0) {
                    track.setPreset(null);
                }
            });
            auditionRow();
        });
        programs.addActionListener(event -> {
            if (sync.on() || programs.getSelectedIndex() < 0) {
                return;
            }
            int index = programs.getSelectedIndex();
            if (index >= voices.size()) {
                return;
            }
            SoundCatalog.Voice voice = voices.get(index);
            Track current = editor.selectedTrack();
            if (voice.bank() == current.bank() && voice.program() == current.program()) {
                return;
            }
            editor.edit(beat -> {
                Track track = onScreen(beat);
                track.setBank(voice.bank());
                track.setProgram(voice.program());
            });
            auditionRow();
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
        boolean pitched = track.mode() == TrackMode.DRUM && track.preset() != null;
        for (JButton button : noteButtons) {
            button.setEnabled(!pitched);
        }
        String heading = TrackReadout.tunedName(track.playedNote(), track.tune());
        noteValue.setText(heading);
        noteValue.setToolTipText(track.tune() == 0
            ? "Pitch of this row."
            : "Pitch of this row, including tune: " + heading + ".");
        ((CardLayout) soundPicker.getLayout()).show(soundPicker, soundCard(track.mode()));
        showSample(track);
        refillDrums(track);
        refillKits(track);
        selectProgram(track);
        boolean drum = track.mode() == TrackMode.DRUM;
        kits.setVisible(drum);
        Soundbank bank = player.soundbank();
        ChannelPlan plan = ChannelPlan.of(beat, everySlot(), bank);
        boolean overflow = drum && !ChannelPlan.sharesDrumChannel(track, bank) && plan.overflow(editor.trackIndex());
        channelNote.setVisible(overflow);
        String fallback = SoundCatalog.kitFallback(bank, track);
        kitNote.setVisible(fallback != null);
        if (fallback != null) {
            kitNote.setText("Kit " + track.kit() + " is not in this bank, so this row plays Standard.");
        }
        if (kits.getParent() != null) {
            kits.getParent().revalidate();
        }
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
        Widgets.paintToggle(sampleMode, track.mode() == TrackMode.SAMPLE);
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
        tuneSemi.setValue(track.tuneSemitones());
        tuneCents.setValue(track.tuneCents());
        if (!decay.getValueIsAdjusting()) {
            decay.setValue(track.decay());
        }
        showDecay(track);
        showCutoff(track);
        showEffect(resonanceOn, resonance, resonanceValue, track.hasResonance(), track.resonance());
        showLowCut(track);
        showPresence(track);
    }

    private void applyTune() {
        if (sync.on()) {
            return;
        }
        int semi = ((Number) tuneSemi.getValue()).intValue();
        int cents = ((Number) tuneCents.getValue()).intValue();
        Track current = editor.selectedTrack();
        if (current.tuneSemitones() == semi && current.tuneCents() == cents) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setTuneParts(semi, cents));
    }

    private void applyCutoffToggle() {
        if (sync.on()) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (cutoffOn.isSelected()) {
                track.setCutoff(cutoffValueInRange(track));
            } else {
                track.clearCutoff();
            }
        });
    }

    private void applyResonanceToggle() {
        if (sync.on()) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (resonanceOn.isSelected()) {
                track.setResonance(resonance.getValue());
            } else {
                track.clearResonance();
            }
        });
    }

    private void applyLowCutToggle() {
        if (sync.on()) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (lowCutOn.isSelected()) {
                track.setLowCut(Track.lowCutHz(lowCut.getValue()), slopeChoice());
            } else {
                track.clearLowCut();
            }
        });
    }

    private void applySlope(int slope) {
        if (sync.on()) {
            return;
        }
        shownSlope = slope;
        Track current = editor.selectedTrack();
        if (!current.hasLowCut() || current.lowCutSlope() == slope) {
            paintSlope();
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (track.hasLowCut()) {
                track.setLowCut(track.lowCutHz(), slope);
            }
        });
    }

    private int slopeChoice() {
        return shownSlope;
    }

    private static final String DECAY_TIP = "How long the built-in synth lets the hit ring, from 10% to 200%. "
        + "100% is the instrument's own release and sends nothing. A decay other than 100% replaces the release a pitched preset's length was sending. "
        + "Kick, snare, closed hat, toms, clap, side stick, Woodblock click, and Snare crack ignore it. Snare crack's tail is reverb. "
        + "A sample row maps the percent onto that sample's volume envelope, for both one-shot and choke. "
        + "A one-shot above 100% matches the file, because the recording is as long as the sound can be. "
        + "A missing file falls back to the drum note, which may ignore decay. "
        + "Built-in synth only. A DAW may ignore this.";

    private void showDecay(Track track) {
        boolean applies = track.decayApplies();
        decay.setEnabled(applies);
        decayTitle.setForeground(applies ? Theme.TEXT : Theme.MUTED);
        decayValue.setForeground(applies ? Theme.TEXT : Theme.MUTED);
        String tip = DECAY_TIP;
        if (!applies) {
            tip = track.decayNote();
            if (track.decay() != Track.DECAY) {
                tip = "Stored at " + track.decay() + "%. " + tip;
            }
            decayValue.setText("ignored");
        } else {
            String limit = track.decayLimit();
            if (limit != null) {
                decayValue.setText(track.decay() + "% file");
                decayValue.setForeground(Theme.MUTED);
                tip = tip + " " + limit;
            } else {
                decayValue.setText(track.decay() + "%");
            }
        }
        decayTitle.setToolTipText(tip);
        decay.setToolTipText(tip);
        decayValue.setToolTipText(tip);
    }

    private void showCutoff(Track track) {
        int low = Track.cutoffSliderMin(track);
        int high = Track.cutoffSliderMax(track);
        cutoff.setMinimum(Math.min(cutoff.getMinimum(), low));
        cutoff.setMaximum(Math.max(cutoff.getMaximum(), high));
        cutoff.setMinimum(low);
        cutoff.setMaximum(high);
        showEffect(cutoffOn, cutoff, cutoffValue, track.hasCutoff(), track.cutoff());
        String tip = "Low-pass filter. This slider covers controller " + low + "–" + high
            + ", which is where this sound changes on the built-in synth. The number is the controller saved in the file. "
            + "Off leaves it unset, so nothing is sent. A saved value outside the slider is kept until you move it. "
            + "Built-in synth only. A DAW may ignore this.";
        cutoffOn.setToolTipText(tip);
        cutoff.setToolTipText(tip);
        cutoffValue.setToolTipText(tip);
    }

    private static final String LOW_CUT_TIP = "High-pass, from 20 Hz to 2 kHz. Off leaves the row as it is. "
        + "12 or 24 dB per octave. The filter is baked into playback and WAV export. "
        + "A MIDI file writes the ordinary note and does not carry it.";
    private static final String LOW_CUT_EXTERNAL = "Low cut and presence play on the built-in synth. "
        + "This MIDI output uses its own sounds, so the setting is kept and comes back when you choose Built-in synth. "
        + "A MIDI file does not carry it.";
    private static final String PRESENCE_TIP = "High shelf above about 3 kHz, from 0 to +12 dB. "
        + "0 leaves the highs as they are. Playback and WAV export use it. A MIDI file does not.";

    private void showLowCut(Track track) {
        boolean live = player.isBuiltIn();
        String context = editor.viewSlot() + ":" + editor.trackIndex();
        if (!context.equals(slopeContext)) {
            slopeContext = context;
            shownSlope = track.hasLowCut() ? track.lowCutSlope() : Track.LOW_CUT_SLOPE;
        } else if (track.hasLowCut()) {
            shownSlope = track.lowCutSlope();
        }
        lowCutOn.setSelected(track.hasLowCut());
        boolean on = track.hasLowCut() && live;
        lowCut.setEnabled(on);
        slope12.setEnabled(on);
        slope24.setEnabled(on);
        lowCutOn.setEnabled(live);
        if (track.hasLowCut() && !lowCut.getValueIsAdjusting()) {
            lowCut.setValue(Track.lowCutSlider(track.lowCutHz()));
        }
        lowCutValue.setText(track.hasLowCut() ? track.lowCutHz() + " Hz" : "—");
        paintSlope();
        String tip = live ? LOW_CUT_TIP : LOW_CUT_EXTERNAL;
        if (!live && track.hasLowCut()) {
            tip = "Stored at " + track.lowCutHz() + " Hz, " + track.lowCutSlope() + " dB/oct. " + tip;
        }
        lowCutOn.setToolTipText(tip);
        lowCut.setToolTipText(tip);
        lowCutValue.setToolTipText(tip);
        slope12.setToolTipText(tip + " 12 dB per octave.");
        slope24.setToolTipText(tip + " 24 dB per octave.");
        lowCutValue.setForeground(live ? Theme.TEXT : Theme.MUTED);
    }

    private void paintSlope() {
        Widgets.paintToggle(slope12, shownSlope != Track.LOW_CUT_STEEP);
        Widgets.paintToggle(slope24, shownSlope == Track.LOW_CUT_STEEP);
    }

    private void showPresence(Track track) {
        boolean live = player.isBuiltIn();
        presence.setEnabled(live);
        if (!presence.getValueIsAdjusting()) {
            presence.setValue(track.presence());
        }
        presenceValue.setText(track.presence() > 0 ? "+" + track.presence() + " dB" : "0 dB");
        String tip = live ? PRESENCE_TIP : LOW_CUT_EXTERNAL;
        presence.setToolTipText(tip);
        presenceValue.setToolTipText(tip);
        presenceValue.setForeground(live ? Theme.TEXT : Theme.MUTED);
    }

    private JPanel lowCutRow() {
        lowCutOn.setOpaque(false);
        lowCutOn.setForeground(Theme.TEXT);
        lowCutOn.setFocusable(false);
        lowCutValue.setPreferredSize(new Dimension(72, 32));
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        lowCutOn.setPreferredSize(new Dimension(108, 32));
        lowCutOn.setMinimumSize(new Dimension(96, 28));
        panel.add(lowCutOn, BorderLayout.WEST);
        panel.add(lowCut, BorderLayout.CENTER);
        panel.add(lowCutValue, BorderLayout.EAST);
        return panel;
    }

    private JPanel slopeRow() {
        slope12.setName("low-cut-12");
        slope24.setName("low-cut-24");
        Widgets.fix(slope12, 48, 32);
        Widgets.fix(slope24, 48, 32);
        JLabel title = Widgets.label("Slope");
        title.setPreferredSize(new Dimension(108, 32));
        title.setName("low-cut-slope");
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.add(title, BorderLayout.WEST);
        panel.add(Widgets.row(slope12, slope24), BorderLayout.CENTER);
        paintSlope();
        return panel;
    }

    private JPanel presenceRow() {
        JLabel title = Widgets.label("Presence");
        title.setName("presence-label");
        title.setPreferredSize(new Dimension(108, 32));
        presenceValue.setPreferredSize(new Dimension(72, 32));
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.add(title, BorderLayout.WEST);
        panel.add(presence, BorderLayout.CENTER);
        panel.add(presenceValue, BorderLayout.EAST);
        title.setToolTipText(PRESENCE_TIP);
        return panel;
    }

    /** Thumb position when cutoff is turned on. Neutral 64 when this sound's slider includes it. */
    private int cutoffValueInRange(Track track) {
        int low = Track.cutoffSliderMin(track);
        int high = Track.cutoffSliderMax(track);
        int value = cutoff.getValue();
        if (value < low || value > high) {
            return Math.max(low, Math.min(high, Track.FILTER_NEUTRAL));
        }
        return value;
    }

    private static void showEffect(JCheckBox toggle, JSlider slider, JLabel value, boolean set, int amount) {
        toggle.setSelected(set);
        slider.setEnabled(set);
        if (!slider.getValueIsAdjusting()) {
            slider.setValue(set ? amount : Track.FILTER_NEUTRAL);
        }
        value.setText(set ? Integer.toString(amount) : "—");
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
            step.setPitch(step.soundingNote(track.playedNote()) + delta);
        });
        Track updated = editor.selectedTrack();
        auditionRow(updated.step(column).soundingNote(updated.playedNote()));
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
        auditionRow();
    }

    private void refillDrums(Track track) {
        drumPicks.clear();
        drums.removeAllItems();
        boolean listed = false;
        for (int note : Gm.DRUM_NOTES) {
            drumPicks.add(new DrumKey(note));
            drums.addItem(Gm.drumChoice(note));
            if (track.preset() == null && track.note() == note) {
                listed = true;
            }
        }
        if (track.preset() == null && !listed) {
            drumPicks.add(new DrumKey(track.note()));
            drums.addItem(Gm.drumChoice(track.note()));
        }
        for (PitchedPreset preset : PitchedPreset.values()) {
            drumPicks.add(new DrumPreset(preset));
            drums.addItem(preset.label());
        }
        int select = 0;
        if (track.preset() != null) {
            for (int i = 0; i < drumPicks.size(); i++) {
                if (drumPicks.get(i) instanceof DrumPreset chosen && chosen.preset() == track.preset()) {
                    select = i;
                    break;
                }
            }
        } else {
            for (int i = 0; i < drumPicks.size(); i++) {
                if (drumPicks.get(i) instanceof DrumKey key && key.note() == track.note()) {
                    select = i;
                    break;
                }
            }
        }
        drums.setSelectedIndex(select);
    }

    private void refillKits(Track track) {
        kitPrograms.clear();
        kits.removeAllItems();
        List<SoundCatalog.Kit> catalog = SoundCatalog.kits(player.soundbank());
        boolean listed = false;
        for (SoundCatalog.Kit kit : catalog) {
            kitPrograms.add(kit.program());
            kits.addItem(SoundCatalog.kitLabel(kit));
            if (kit.program() == track.kit()) {
                listed = true;
            }
        }
        if (!listed) {
            kitPrograms.add(track.kit());
            boolean missing = SoundCatalog.kitFallback(player.soundbank(), track) != null;
            kits.addItem(missing ? track.kit() + "  Not in this bank" : track.kit() + "  Kit " + track.kit());
        }
        int select = 0;
        for (int i = 0; i < kitPrograms.size(); i++) {
            if (kitPrograms.get(i) == track.kit()) {
                select = i;
                break;
            }
        }
        kits.setSelectedIndex(select);
        kits.setEnabled(track.preset() == null);
    }

    private void selectProgram(Track track) {
        Soundbank bank = player.soundbank();
        if (!programsReady || bank != programBank) {
            programBank = bank;
            programsReady = true;
            voices = SoundCatalog.melodic(bank);
            programs.removeAllItems();
            for (SoundCatalog.Voice voice : voices) {
                programs.addItem(SoundCatalog.voiceLabel(voice));
            }
        }
        int match = -1;
        for (int i = 0; i < voices.size(); i++) {
            SoundCatalog.Voice voice = voices.get(i);
            if (voice.bank() == track.bank() && voice.program() == track.program()) {
                match = i;
                break;
            }
        }
        while (programs.getItemCount() > voices.size()) {
            programs.removeItemAt(programs.getItemCount() - 1);
        }
        if (match < 0) {
            programs.addItem(SoundCatalog.voiceLabel(new SoundCatalog.Voice(
                track.bank(), track.program(), Gm.programName(track.program())
            )));
            match = programs.getItemCount() - 1;
        }
        programs.setSelectedIndex(match);
    }

    private static List<Character> everySlot() {
        List<Character> slots = new ArrayList<>();
        for (int i = 0; i < Beat.SLOT_COUNT; i++) {
            slots.add(Beat.slotLetter(i));
        }
        return slots;
    }

    private void auditionRow() {
        player.audition(editor.beat(), editor.viewSlot(), editor.trackIndex());
    }

    private void auditionRow(int note) {
        player.audition(editor.beat(), editor.viewSlot(), editor.trackIndex(), note);
    }

    private interface DrumPick {}

    private record DrumKey(int note) implements DrumPick {}

    private record DrumPreset(PitchedPreset preset) implements DrumPick {}

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

    private JPanel sampleCard() {
        samplePicker.setName("sample-picker");
        samplePicker.setToolTipText("A built-in one-shot, or a WAV file. Drop a WAV on the row to use it.");
        sampleWarning.setName("sample-warning");
        sampleWarning.setForeground(Theme.MUTED);
        sampleRoot.setName("sample-root");
        sampleRoot.setToolTipText("The MIDI note that plays this recording at its own speed.");
        sampleStart.setName("sample-start");
        sampleStart.setToolTipText("How many sample frames to skip before the hit.");
        oneShot.setName("sample-oneshot");
        oneShot.setToolTipText("Play the whole recording. Note-off does not cut it.");
        choke.setName("sample-choke");
        choke.setToolTipText("Note-off and the step length cut the sample.");
        sampleGain.setName("sample-gain");
        sampleGainValue.setName("sample-gain-value");
        sampleInvert.setName("sample-invert");
        sampleInvert.setOpaque(false);
        sampleInvert.setForeground(Theme.TEXT);
        sampleInvert.setFocusable(false);
        sampleInvert.setToolTipText("Flips the phase of this sample. Off is left out of the file. "
            + "Use it when a sample cancels a drum on the same hit, such as a sine 808 under the kick.");
        collectSamples.setName("collect-samples");
        collectSamples.setToolTipText("Copy every sample this beat uses into a folder next to the beat.");
        JPanel card = new JPanel(new GridBagLayout());
        card.setOpaque(false);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, 6, 0);
        card.add(samplePicker, constraints);
        constraints.gridy++;
        card.add(sampleWarning, constraints);
        constraints.gridy++;
        card.add(sampleSpin("Root", sampleRoot, sampleRootName), constraints);
        constraints.gridy++;
        card.add(sampleSpin("Start", sampleStart, Widgets.label("frames")), constraints);
        constraints.gridy++;
        card.add(Widgets.row(oneShot, choke), constraints);
        constraints.gridy++;
        JPanel gain = labeled(
            "Gain",
            "How loud this sample is, in decibels, from −24 to +24. "
                + "+8 dB is the default and is left out of the file. It makes up for the level the built-in synth takes off a note, so a hit sits near the file. "
                + "0 dB is the quieter level from the first sample-track release. Drum and note rows do not use this.",
            sampleGain,
            sampleGainValue
        );
        sampleGainValue.setPreferredSize(new Dimension(72, 32));
        card.add(gain, constraints);
        constraints.gridy++;
        card.add(sampleInvert, constraints);
        constraints.gridy++;
        constraints.insets = new Insets(0, 0, 0, 0);
        card.add(collectSamples, constraints);
        return card;
    }

    private static JPanel sampleSpin(String label, JSpinner spinner, JLabel extra) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.add(Widgets.label(label));
        row.add(spinner);
        row.add(extra);
        return row;
    }

    private static String soundCard(TrackMode mode) {
        if (mode == TrackMode.NOTE) {
            return "note";
        }
        if (mode == TrackMode.SAMPLE) {
            return "sample";
        }
        return "drum";
    }

    private void showSample(Track track) {
        SampleRef sample = track.sample();
        boolean sampleModeOn = track.mode() == TrackMode.SAMPLE && sample != null;
        choosingSample = true;
        samplePicker.removeAllItems();
        samplePicker.addItem("808");
        samplePicker.addItem("Click");
        samplePicker.addItem("Snap");
        int select = 1;
        if (sampleModeOn) {
            String path = sample.path();
            if (BuiltinSamples.EIGHT_OH_EIGHT.equals(path)) {
                select = 0;
            } else if (BuiltinSamples.SNAP.equals(path)) {
                select = 2;
            } else if (BuiltinSamples.CLICK.equals(path) || path.isBlank()) {
                select = 1;
            } else {
                samplePicker.addItem(sample.label());
                select = 3;
            }
        }
        samplePicker.addItem("Choose WAV…");
        samplePicker.setSelectedIndex(select);
        choosingSample = false;
        String problem = sampleModeOn ? SamplePaths.problem(track, beatFile.get()) : null;
        String clamp = sampleModeOn
            ? editor.beat().gainWarning(editor.beat().activeSlot(), track.name())
            : null;
        String warning = clamp == null ? problem : (problem == null ? clamp : clamp + " " + problem);
        sampleWarning.setText(warning == null ? " " : warning);
        sampleWarning.setVisible(warning != null);
        if (sampleModeOn && !sampleRoot.getValue().equals(sample.root())) {
            sampleRoot.setValue(sample.root());
        }
        sampleRootName.setText(Notes.name(((Number) sampleRoot.getValue()).intValue()));
        if (sampleModeOn && !sampleStart.getValue().equals(sample.start())) {
            sampleStart.setValue(sample.start());
        }
        boolean shot = !sampleModeOn || sample.oneShot();
        Widgets.paintToggle(oneShot, shot);
        Widgets.paintToggle(choke, !shot);
        int gain = sampleModeOn ? sample.gain() : SampleRef.GAIN;
        if (!sampleGain.getValueIsAdjusting()) {
            sampleGain.setValue(gain);
        }
        sampleGainValue.setText(SampleRef.gainText(sampleGain.getValueIsAdjusting() ? sampleGain.getValue() : gain));
        boolean clamped = clamp != null;
        sampleGainValue.setForeground(clamped ? Theme.STEP_ACCENT : Theme.TEXT);
        String clampTip = clamped ? "Clamped to " + SampleRef.gainText(gain) : null;
        sampleGainValue.setToolTipText(clampTip);
        sampleGain.setToolTipText(clamped
            ? clampTip
            : "Sample gain, from " + SampleRef.gainText(SampleRef.GAIN_MIN) + " to " + SampleRef.gainText(SampleRef.GAIN_MAX) + ".");
        sampleInvert.setSelected(sampleModeOn && sample.invert());
    }

    private void chooseSample() {
        if (sync.on() || choosingSample || editor.selectedTrack().mode() != TrackMode.SAMPLE) {
            return;
        }
        int index = samplePicker.getSelectedIndex();
        int last = samplePicker.getItemCount() - 1;
        if (index < 0 || index == last) {
            if (index == last) {
                chooseWav();
            }
            return;
        }
        String label = samplePicker.getItemAt(index);
        String path = switch (label) {
            case "808" -> BuiltinSamples.EIGHT_OH_EIGHT;
            case "Click" -> BuiltinSamples.CLICK;
            case "Snap" -> BuiltinSamples.SNAP;
            default -> null;
        };
        if (path == null) {
            return;
        }
        SampleRef current = editor.selectedTrack().sample();
        if (current != null && path.equals(current.path())) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            SampleRef sample = track.sample();
            if (sample == null) {
                track.setSample(SampleRef.builtin(path, track.note()));
            } else {
                track.setSample(sample.withPath(path));
            }
        });
        auditionRow();
    }

    private void chooseWav() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Sample");
        chooser.setFileFilter(new FileNameExtensionFilter("WAV audio", "wav", "wave"));
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(samplePicker);
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            showSample(editor.selectedTrack());
            return;
        }
        String path = chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString();
        editor.edit(beat -> {
            Track track = onScreen(beat);
            SampleRef sample = track.sample();
            if (sample == null) {
                track.setSample(new SampleRef(path, track.note(), 0, true));
            } else {
                track.setSample(sample.withPath(path));
            }
        });
        auditionRow();
    }

    private void applySampleRoot() {
        if (sync.on() || editor.selectedTrack().mode() != TrackMode.SAMPLE) {
            return;
        }
        int root = ((Number) sampleRoot.getValue()).intValue();
        sampleRootName.setText(Notes.name(root));
        SampleRef current = editor.selectedTrack().sample();
        if (current == null || current.root() == root) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (track.sample() != null) {
                track.setSample(track.sample().withRoot(root));
            }
        });
    }

    private void applySampleStart() {
        if (sync.on() || editor.selectedTrack().mode() != TrackMode.SAMPLE) {
            return;
        }
        int start = ((Number) sampleStart.getValue()).intValue();
        SampleRef current = editor.selectedTrack().sample();
        if (current == null || current.start() == start) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (track.sample() != null) {
                track.setSample(track.sample().withStart(start));
            }
        });
    }

    private void applyOneShot(boolean oneShotMode) {
        if (sync.on() || editor.selectedTrack().mode() != TrackMode.SAMPLE) {
            return;
        }
        SampleRef current = editor.selectedTrack().sample();
        if (current == null || current.oneShot() == oneShotMode) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (track.sample() != null) {
                track.setSample(track.sample().withOneShot(oneShotMode));
            }
        });
        auditionRow();
    }

    private void applyInvert() {
        if (sync.on() || editor.selectedTrack().mode() != TrackMode.SAMPLE) {
            return;
        }
        boolean invert = sampleInvert.isSelected();
        SampleRef current = editor.selectedTrack().sample();
        if (current == null || current.invert() == invert) {
            return;
        }
        editor.edit(beat -> {
            Track track = onScreen(beat);
            if (track.sample() != null) {
                track.setSample(track.sample().withInvert(invert));
            }
        });
        auditionRow();
    }

    private void setMode(TrackMode mode) {
        if (editor.selectedTrack().mode() == mode) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setMode(mode));
        auditionRow();
    }

    private JButton buttonPair(String text, String tip, int delta, int width) {
        JButton button = Widgets.button(text);
        button.setToolTipText(tip);
        Widgets.fix(button, width, 32);
        button.addActionListener(event -> changeNote(delta));
        noteButtons.add(button);
        return button;
    }

    private void changeNote(int delta) {
        editor.edit(beat -> {
            Track track = onScreen(beat);
            track.setNote(track.note() + delta);
        });
        auditionRow();
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

    private JPanel tuneRow(String tip) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel title = Widgets.label("Tune");
        title.setToolTipText(tip);
        title.setPreferredSize(new Dimension(72, 32));
        title.setMinimumSize(new Dimension(56, 28));
        panel.add(title, BorderLayout.WEST);
        JPanel spins = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        spins.setOpaque(false);
        tuneSemi.setToolTipText(tip);
        tuneCents.setToolTipText(tip);
        Widgets.fix(tuneSemi, 64, 32);
        Widgets.fix(tuneCents, 64, 32);
        JLabel semis = Widgets.label("st");
        JLabel cents = Widgets.label("¢");
        semis.setToolTipText(tip);
        cents.setToolTipText(tip);
        spins.add(tuneSemi);
        spins.add(semis);
        spins.add(tuneCents);
        spins.add(cents);
        panel.add(spins, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel effectRow(JCheckBox toggle, JSlider slider, JLabel value, String tip) {
        toggle.setOpaque(false);
        toggle.setForeground(Theme.TEXT);
        toggle.setFocusable(false);
        toggle.setToolTipText(tip);
        slider.setToolTipText(tip);
        value.setToolTipText(tip);
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        toggle.setPreferredSize(new Dimension(108, 32));
        toggle.setMinimumSize(new Dimension(96, 28));
        panel.add(toggle, BorderLayout.WEST);
        panel.add(slider, BorderLayout.CENTER);
        value.setPreferredSize(new Dimension(36, 32));
        panel.add(value, BorderLayout.EAST);
        return panel;
    }

    private static JPanel labeled(String name, String tip, JSlider slider, JLabel value) {
        return labeled(Widgets.label(name), tip, slider, value);
    }

    private static JPanel labeled(JLabel title, String tip, JSlider slider, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
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
