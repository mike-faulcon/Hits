package hits.ui;

import hits.Beat;
import hits.BeatFiles;
import hits.BeatFolders;
import hits.Chain;
import hits.Editor;
import hits.ExportOptions;
import hits.Gm;
import hits.Kits;
import hits.MidiOutputs;
import hits.Notes;
import hits.Player;
import hits.SequenceBuilder;
import hits.SoundFonts;
import hits.Step;
import hits.Track;
import hits.TrackMode;
import hits.WavRenderer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Studio-desk window: transport, eight tracks, step grid, inspector, and the beat list. */
final class StudioFrame extends JFrame {
    private final Editor editor;
    private final Player player;
    private final PatternPanel pattern;
    private final Timer playhead;
    private final DefaultListModel<BeatFiles.Entry> beats = new DefaultListModel<>();

    private final JButton play = button("Play");
    private final JSpinner bpm = new JSpinner(new SpinnerNumberModel(96, 40, 240, 1));
    private final JSlider swing = slider(50, 75, 50);
    private final JLabel swingValue = label("50%");
    private final JButton slotA = button("A");
    private final JButton slotB = button("B");
    private final JButton copySlot = button("Copy");
    private final JButton patternMode = button("Pattern");
    private final JButton songMode = button("Song");
    private final JPanel chainSlots = new JPanel();
    private final JButton addChainA = button("+A");
    private final JButton addChainB = button("+B");
    private final JButton removeChain = button("Remove");
    private final JButton chainUp = button("Up");
    private final JButton chainDown = button("Down");
    private final JSpinner chainRepeats = new JSpinner(new SpinnerNumberModel(1, 1, ExportOptions.MAX_REPEATS, 1));
    private int chainIndex = -1;
    private String chainButtons = "";
    private final JTextField documentName = new JTextField("untitled", 12);

    private final JTextField trackName = new JTextField();
    private final JLabel noteValue = new JLabel("C1", SwingConstants.LEFT);
    private final JComboBox<String> drums = new JComboBox<>();
    private final JComboBox<String> programs = new JComboBox<>();
    private final JPanel soundPicker = new JPanel(new CardLayout());
    private final JSlider gate = slider(5, 100, 45);
    private final JLabel gateValue = label("45%");
    private final JSlider velocity = slider(1, 127, 100);
    private final JLabel velocityValue = label("100");
    private final JSlider accent = slider(1, 127, 120);
    private final JLabel accentValue = label("120");
    private final JButton drumMode = button("Drum");
    private final JButton noteMode = button("Note");
    private final JButton steps16 = button("16 steps");
    private final JButton steps32 = button("32 steps");
    private final JLabel stepPitchValue = new JLabel("—", SwingConstants.LEFT);
    private final JButton stepPitchDown = button("−");
    private final JButton stepPitchUp = button("+");
    private final JButton stepPitchTrack = button("Use track");
    private final JComboBox<MidiOutputs.Choice> midiOut = new JComboBox<>();

    private final JList<BeatFiles.Entry> beatList = new JList<>(beats);
    private JLabel beatsTitle;
    private Path beatsDirectory;
    private static final String HINT = "Space play/stop  ·  click step  ·  shift-click loud  ·  right-click select  ·  ⌘/Ctrl S save  ·  ⌘/Ctrl Z undo";
    private final JLabel status = new JLabel(HINT);
    private final JPanel header = new JPanel(new BorderLayout());
    private JComponent coachBar;
    private boolean coachVisible;
    private Path savedFile;
    private KeyEventDispatcher keys;
    private boolean spaceDown;

    private boolean syncing;
    private boolean swingDrag;
    private boolean gateDrag;
    private boolean velocityDrag;
    private boolean accentDrag;
    private boolean playShown;

    StudioFrame(Editor editor, Player player) {
        super("Hits");
        this.editor = editor;
        this.player = player;
        this.beatsDirectory = loadBeatsDirectory();
        this.pattern = new PatternPanel(editor);
        maybeFirstRun();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setBackground(Theme.BG);
        getContentPane().setBackground(Theme.BG);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(body(), BorderLayout.CENTER);
        getContentPane().add(footer(), BorderLayout.SOUTH);
        wire();
        setSize(1180, 800);
        setMinimumSize(new Dimension(960, 680));
        setLocationByPlatform(true);
        editor.addListener(this::changed);
        reloadBeats(null);
        refresh();
        playhead = new Timer(40, event -> {
            if (!player.isPlaying()) {
                editor.stopped();
                pattern.setPlayhead(-1);
            } else {
                Chain.Place place = player.place(editor.beat());
                boolean arranged = editor.songMode() && editor.beat().hasChain();
                if (place != null && arranged && !editor.isAdjusting() && !editor.holding()) {
                    editor.showPlayingSlot(place.slot());
                }
                int step = -1;
                if (place != null && (!arranged || editor.viewSlot() == place.slot())) {
                    step = place.step();
                }
                pattern.setPlayhead(step);
            }
            if (player.isPlaying() != playShown) {
                updatePlayButton();
            }
        });
        playhead.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                close();
            }
        });
        addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowGainedFocus(java.awt.event.WindowEvent event) {
                if (!midiOut.isPopupVisible()) {
                    fillMidiOutputs(player.outputId());
                }
            }
        });
        applySavedOutput();
    }

    private JPanel transport() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        play.setToolTipText("Play or stop. Space does this unless you are typing.");
        play.addActionListener(event -> togglePlay());
        bar.add(play);
        bar.add(Box.createHorizontalStrut(16));
        JLabel bpmLabel = label("BPM");
        bpmLabel.setToolTipText("Tempo, from 40 to 240.");
        bpm.setToolTipText("Tempo, from 40 to 240.");
        bar.add(bpmLabel);
        bar.add(Box.createHorizontalStrut(6));
        fix(bpm, 72, 32);
        bar.add(bpm);
        bar.add(Box.createHorizontalStrut(16));
        JLabel swingLabel = label("Swing");
        swingLabel.setToolTipText("50% is straight. Higher values delay every other 16th note.");
        swing.setToolTipText("50% is straight. Higher values delay every other 16th note.");
        bar.add(swingLabel);
        fix(swing, 160, 32);
        bar.add(swing);
        fix(swingValue, 120, 32);
        bar.add(swingValue);
        bar.add(Box.createHorizontalStrut(16));
        slotA.setToolTipText("Pattern A. The other pattern stays in this beat.");
        slotB.setToolTipText("Pattern B. The other pattern stays in this beat.");
        fix(slotA, 44, 32);
        fix(slotB, 44, 32);
        bar.add(slotA);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(slotB);
        bar.add(Box.createHorizontalStrut(8));
        copySlot.setToolTipText("Copy this pattern onto the other slot, replacing it.");
        bar.add(copySlot);
        bar.add(Box.createHorizontalGlue());
        documentName.setFont(documentName.getFont().deriveFont(Font.BOLD, 16f));
        documentName.setToolTipText("Name stored in the file. The file name keeps letters, numbers, _ and -.");
        fix(documentName, 180, 32);
        bar.add(documentName);
        return bar;
    }

    private JPanel buildHeader() {
        header.setOpaque(false);
        JPanel stack = new JPanel(new BorderLayout());
        stack.setOpaque(false);
        stack.add(transport(), BorderLayout.NORTH);
        stack.add(chainBar(), BorderLayout.SOUTH);
        header.add(stack, BorderLayout.NORTH);
        if (coachVisible) {
            coachBar = coachBanner();
            header.add(coachBar, BorderLayout.SOUTH);
        }
        return header;
    }

    private JPanel chainBar() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(true);
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        patternMode.setToolTipText("Play the pattern on screen and loop it.");
        songMode.setToolTipText("Play the chain and loop it. Click again to follow the pattern you hear.");
        fix(patternMode, 88, 32);
        fix(songMode, 72, 32);
        bar.add(row(patternMode, songMode), BorderLayout.WEST);

        chainSlots.setLayout(new BoxLayout(chainSlots, BoxLayout.X_AXIS));
        chainSlots.setOpaque(false);
        JScrollPane chainScroll = new JScrollPane(
            chainSlots,
            JScrollPane.VERTICAL_SCROLLBAR_NEVER,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        );
        chainScroll.setBorder(BorderFactory.createEmptyBorder());
        chainScroll.setOpaque(false);
        chainScroll.getViewport().setOpaque(true);
        chainScroll.getViewport().setBackground(Theme.PANEL);
        chainScroll.setMinimumSize(new Dimension(72, 36));
        chainScroll.setPreferredSize(new Dimension(180, 36));
        bar.add(chainScroll, BorderLayout.CENTER);

        addChainA.setToolTipText("Add pattern A to the chain.");
        addChainB.setToolTipText("Add pattern B to the chain.");
        removeChain.setToolTipText("Remove the selected chain entry.");
        chainUp.setToolTipText("Move the selected entry earlier.");
        chainDown.setToolTipText("Move the selected entry later.");
        chainRepeats.setToolTipText("How many times the selected entry plays, from 1 to " + ExportOptions.MAX_REPEATS + ".");
        fix(addChainA, 48, 32);
        fix(addChainB, 48, 32);
        fix(chainUp, 52, 32);
        fix(chainDown, 64, 32);
        JLabel repeatsLabel = label("×");
        repeatsLabel.setToolTipText("Repeats for the selected entry.");
        fix(chainRepeats, 64, 32);
        bar.add(row(addChainA, addChainB, removeChain, chainUp, chainDown, repeatsLabel, chainRepeats), BorderLayout.EAST);
        bar.setMinimumSize(new Dimension(720, 52));
        bar.setPreferredSize(new Dimension(960, 52));
        return bar;
    }

    private JComponent coachBanner() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(Theme.SELECT);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JLabel text = new JLabel("Starting on Boom bap.  Space plays.  Click a square to add a hit.  Shift-click makes it loud.");
        text.setForeground(Theme.TEXT);
        panel.add(text, BorderLayout.CENTER);
        JButton dismiss = button("Got it");
        dismiss.setToolTipText("Hide this hint. Later launches start from an empty kit.");
        dismiss.addActionListener(event -> dismissCoach());
        panel.add(dismiss, BorderLayout.EAST);
        return panel;
    }

    private void maybeFirstRun() {
        try {
            coachVisible = !Preferences.userNodeForPackage(HitsApp.class).getBoolean("coachDismissed", false);
        } catch (Exception exception) {
            coachVisible = false;
            return;
        }
        if (coachVisible) {
            editor.edit(Kits::boomBap);
            editor.markClean();
        }
    }

    private void dismissCoach() {
        coachVisible = false;
        if (coachBar != null) {
            header.remove(coachBar);
            coachBar = null;
            header.revalidate();
            header.repaint();
        }
        try {
            Preferences.userNodeForPackage(HitsApp.class).putBoolean("coachDismissed", true);
        } catch (Exception ignored) {
            // The hint can come back next launch if preferences cannot be stored.
        }
    }

    private JComponent body() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(pattern);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BG);
        left.add(scroll, BorderLayout.CENTER);
        left.add(browser(), BorderLayout.SOUTH);

        JScrollPane inspectorScroll = new JScrollPane(inspector());
        inspectorScroll.setBorder(BorderFactory.createEmptyBorder());
        inspectorScroll.setPreferredSize(new Dimension(280, 400));
        inspectorScroll.getViewport().setBackground(Theme.PANEL);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, inspectorScroll);
        split.setResizeWeight(1);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(6);
        split.setBackground(Theme.BG);
        return split;
    }

    private JPanel browser() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(Theme.PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(200, 210));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        beatsTitle = label("Beats");
        beatsTitle.setFont(beatsTitle.getFont().deriveFont(Font.BOLD, 13f));
        beatsTitle.setToolTipText(beatsDirectory.toString());
        JButton folder = button("Folder…");
        folder.setToolTipText("Choose the folder Load and Save use. Hits remembers the absolute path.");
        folder.addActionListener(event -> chooseBeatsFolder());
        heading.add(beatsTitle, BorderLayout.WEST);
        heading.add(folder, BorderLayout.EAST);
        panel.add(heading, BorderLayout.NORTH);
        beatList.setBackground(Theme.BG);
        beatList.setForeground(Theme.TEXT);
        beatList.setSelectionBackground(Theme.STEP_ON);
        beatList.setSelectionForeground(Theme.INK);
        beatList.setFixedCellHeight(22);
        beatList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String text = value instanceof BeatFiles.Entry entry ? entry.label() : "";
                Component component = super.getListCellRendererComponent(list, text, index, selected, focus);
                if (!selected) {
                    component.setBackground(Theme.BG);
                    component.setForeground(Theme.TEXT);
                }
                return component;
            }
        });
        beatList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    loadSelected();
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(beatList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(listScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton load = button("Load");
        JButton save = button("Save");
        JButton saveAs = button("Save As");
        JButton export = button("Export MIDI");
        JButton exportWav = button("Export WAV");
        JButton fresh = button("New");
        JButton boom = button("Boom bap");
        JButton reggae = button("Reggae");
        load.setToolTipText("Load the selected beat. Double-click the list too.");
        save.setToolTipText("Save this beat. ⌘/Ctrl S. Asks before replacing a different file.");
        saveAs.setToolTipText("Save under a name you type. Shows the file name that will be written.");
        export.setToolTipText("Write a MIDI file. All tracks are included unless you choose As heard. Song writes the chain.");
        exportWav.setToolTipText("Write a WAV file of the same notes. Uses the built-in synth, and a loaded SoundFont when one is active. Song writes the chain.");
        fresh.setToolTipText("Start a new empty drum kit.");
        boom.setToolTipText("Replace the pattern on screen with a boom bap groove. Asks first.");
        reggae.setToolTipText("Replace the pattern on screen with a reggae groove. Asks first.");
        load.addActionListener(event -> loadSelected());
        save.addActionListener(event -> save());
        saveAs.addActionListener(event -> saveAs());
        export.addActionListener(event -> exportMidi());
        exportWav.addActionListener(event -> exportWav());
        fresh.addActionListener(event -> fresh());
        boom.addActionListener(event -> applyKit("Boom bap", Kits::boomBap));
        reggae.addActionListener(event -> applyKit("Reggae", Kits::reggae));
        actions.add(load);
        actions.add(save);
        actions.add(saveAs);
        actions.add(export);
        actions.add(exportWav);
        actions.add(fresh);
        actions.add(boom);
        actions.add(reggae);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel inspector() {
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
        JPanel noteButtons = row(
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
        JPanel stepPitch = row(stepPitchValue, stepPitchDown, stepPitchUp, stepPitchTrack);
        stepPitch.setToolTipText("Pitch of the selected step. A step without its own pitch uses the track pitch.");
        addRow(panel, constraints, label("Step pitch"));
        addRow(panel, constraints, stepPitch);
        addRow(panel, constraints, label("Sound"));
        drums.setToolTipText("Drum sound for this track.");
        programs.setToolTipText("Instrument for this track. One pitch for every step.");
        soundPicker.setOpaque(false);
        soundPicker.add(drums, "drum");
        soundPicker.add(programs, "note");
        addRow(panel, constraints, soundPicker);
        addRow(panel, constraints, labeled("Length", "How long each note holds, as a percent of the step.", gate, gateValue));
        addRow(panel, constraints, labeled("Volume", "How hard a hit is. Right-click or Alt-click a step to edit that step.", velocity, velocityValue));
        addRow(panel, constraints, labeled("Loud hit", "Shift-click uses this volume. Pads at least this loud use the bright color.", accent, accentValue));
        addRow(panel, constraints, label("Mode"));
        drumMode.setToolTipText("Drum kit sounds, on the drum channel.");
        noteMode.setToolTipText("One pitched instrument for the whole track.");
        addRow(panel, constraints, row(drumMode, noteMode));
        JButton copyBar = button("Copy bar");
        copyBar.setToolTipText("Copy the first 16 steps into a second bar and switch to 32 steps.");
        copyBar.addActionListener(event -> {
            char slot = editor.viewSlot();
            editor.edit(beat -> beat.copyBar(slot));
        });
        addRow(panel, constraints, copyBar);
        steps16.setToolTipText("One bar of 16th notes.");
        steps32.setToolTipText("Two bars of 16th notes.");
        addRow(panel, constraints, row(steps16, steps32));
        constraints.weighty = 1;
        addRow(panel, constraints, new JPanel() {{
            setOpaque(false);
        }});
        return panel;
    }

    private JButton buttonPair(String text, String tip, int delta, int width) {
        JButton button = button(text);
        button.setToolTipText(tip);
        fix(button, width, 32);
        button.addActionListener(event -> changeNote(delta));
        return button;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        status.setForeground(Theme.MUTED);
        panel.add(status, BorderLayout.CENTER);
        JPanel output = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        output.setOpaque(false);
        JLabel midiLabel = label("MIDI out");
        midiLabel.setToolTipText("Where notes are sent. Built-in synth is the Java instrument.");
        output.add(midiLabel);
        midiOut.setToolTipText("Built-in synth, or an output such as Ableton, IAC, or a hardware port.");
        midiOut.setPreferredSize(new Dimension(200, 32));
        output.add(midiOut);
        JButton soundFont = button("SoundFont…");
        soundFont.setToolTipText("Load a .sf2 SoundFont on the built-in synth. External outputs use their own sounds.");
        soundFont.addActionListener(event -> chooseSoundFont());
        output.add(soundFont);
        panel.add(output, BorderLayout.EAST);
        return panel;
    }

    private void wire() {
        bpm.addChangeListener(event -> {
            if (syncing) {
                return;
            }
            int value = (Integer) bpm.getValue();
            editor.edit(beat -> beat.setBpm(value));
        });
        drag(swing, () -> swingDrag, value -> swingDrag = value, beat -> beat.setSwing(swing.getValue()));
        drag(gate, () -> gateDrag, value -> gateDrag = value, beat -> onScreen(beat).setGate(gate.getValue()));
        drag(velocity, () -> velocityDrag, value -> velocityDrag = value, this::applyVelocity);
        drag(accent, () -> accentDrag, value -> accentDrag = value, beat -> onScreen(beat).setAccent(accent.getValue()));

        slotA.addActionListener(event -> switchSlot('a'));
        slotB.addActionListener(event -> switchSlot('b'));
        copySlot.addActionListener(event -> {
            char slot = editor.viewSlot();
            editor.edit(beat -> beat.copySlotToOther(slot));
        });
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
            if (syncing || drums.getSelectedIndex() < 0) {
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
            if (syncing || programs.getSelectedIndex() < 0) {
                return;
            }
            int program = programs.getSelectedIndex();
            if (program == editor.selectedTrack().program()) {
                return;
            }
            editor.edit(beat -> onScreen(beat).setProgram(program));
            player.audition(editor.selectedTrack());
        });
        documentName.addActionListener(event -> commitDocumentName());
        documentName.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                commitDocumentName();
            }
        });
        trackName.addActionListener(event -> commitTrackName());
        trackName.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                commitTrackName();
            }
        });

        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        var input = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actions = getRootPane().getActionMap();
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "undo");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | InputEvent.SHIFT_DOWN_MASK), "redo");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut), "save");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, shortcut | InputEvent.SHIFT_DOWN_MASK), "saveAs");
        actions.put("undo", action(event -> {
            if (!typing()) {
                editor.undo();
            }
        }));
        actions.put("redo", action(event -> {
            if (!typing()) {
                editor.redo();
            }
        }));
        actions.put("save", action(event -> save()));
        actions.put("saveAs", action(event -> saveAs()));
        stepPitchDown.addActionListener(event -> nudgeStepPitch(-1));
        stepPitchUp.addActionListener(event -> nudgeStepPitch(1));
        stepPitchTrack.addActionListener(event -> clearStepPitch());
        patternMode.addActionListener(event -> chooseMode(false));
        songMode.addActionListener(event -> chooseMode(true));
        addChainA.addActionListener(event -> appendChain('a'));
        addChainB.addActionListener(event -> appendChain('b'));
        removeChain.addActionListener(event -> removeSelectedChain());
        chainUp.addActionListener(event -> moveSelectedChain(-1));
        chainDown.addActionListener(event -> moveSelectedChain(1));
        chainRepeats.addChangeListener(event -> {
            if (syncing || chainIndex < 0 || chainIndex >= editor.beat().chain().size()) {
                return;
            }
            int repeats = (Integer) chainRepeats.getValue();
            if (editor.beat().chain().get(chainIndex).repeats() == repeats) {
                return;
            }
            int index = chainIndex;
            editor.edit(beat -> beat.setChainRepeats(index, repeats));
        });
        midiOut.addActionListener(event -> midiOutputChosen());
        keys = this::dispatchKey;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
    }

    /**
     * Space is play/stop for the whole window. Buttons and the beat list would otherwise take it.
     * A text field still receives the character.
     */
    private boolean dispatchKey(KeyEvent event) {
        boolean space = event.getKeyCode() == KeyEvent.VK_SPACE
            || (event.getKeyCode() == KeyEvent.VK_UNDEFINED && event.getKeyChar() == ' ');
        if (!space) {
            return false;
        }
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            spaceDown = false;
        }
        if (event.getModifiersEx() != 0 || typing()) {
            return false;
        }
        Window focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        if (focused != this) {
            spaceDown = false;
            return focused != null && focused.getOwner() == this;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED && !spaceDown) {
            spaceDown = true;
            javax.swing.SwingUtilities.invokeLater(this::togglePlay);
        }
        return true;
    }

    private void changed(boolean musical) {
        refresh();
        if (musical && player.isPlaying()) {
            player.update(editor.beat(), editor.songMode());
        }
    }

    private void refresh() {
        syncing = true;
        try {
            Beat beat = editor.beat();
            Track track = editor.selectedTrack();
            bpm.setValue(beat.bpm());
            if (!swing.getValueIsAdjusting()) {
                swing.setValue(beat.swing());
            }
            swingValue.setText(beat.swing() == 50 ? "50% straight" : beat.swing() + "%");
            paintToggle(slotA, editor.viewSlot() == 'a');
            paintToggle(slotB, editor.viewSlot() == 'b');
            refreshChain();
            if (!documentName.isFocusOwner()) {
                documentName.setText(beat.name());
            }
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
            int shownVelocity = shownVelocity(track);
            if (!velocity.getValueIsAdjusting()) {
                velocity.setValue(shownVelocity);
            }
            velocityValue.setText(Integer.toString(shownVelocity));
            int selectedStep = editor.stepIndex();
            if (selectedStep >= 0 && selectedStep < beat.stepCount() && track.step(selectedStep).on()) {
                velocity.setToolTipText("Volume of step " + (selectedStep + 1) + " on " + track.name() + ".");
            } else {
                velocity.setToolTipText("Volume of new hits on this track. Right-click or Alt-click a pad to edit that hit.");
            }
            String safe = BeatFiles.safeName(beat.name());
            documentName.setToolTipText(safe.isEmpty()
                ? "Add a letter or number. This name cannot be a file yet."
                : "Name stored in the file. Saves as " + safe + ".json");
            updateTitle();
            if (!accent.getValueIsAdjusting()) {
                accent.setValue(track.accent());
            }
            accentValue.setText(Integer.toString(track.accent()));
            paintToggle(drumMode, track.mode() == TrackMode.DRUM);
            paintToggle(noteMode, track.mode() == TrackMode.NOTE);
            paintToggle(steps16, beat.stepCount() == 16);
            paintToggle(steps32, beat.stepCount() == 32);
            refreshStepPitch(beat, track);
            pattern.revalidate();
            pattern.repaint();
            updatePlayButton();
        } finally {
            syncing = false;
        }
    }

    private void refreshStepPitch(Beat beat, Track track) {
        int column = editor.stepIndex();
        boolean editable = column >= 0 && column < beat.stepCount() && track.step(column).on();
        stepPitchDown.setEnabled(editable);
        stepPitchUp.setEnabled(editable);
        if (!editable) {
            stepPitchValue.setText("—");
            stepPitchTrack.setEnabled(false);
            stepPitchValue.setToolTipText("Right-click or Alt-click a step that is on.");
            return;
        }
        Step step = track.step(column);
        if (step.hasPitch()) {
            stepPitchValue.setText(Notes.name(step.pitch()));
            stepPitchValue.setToolTipText("This step plays " + Notes.name(step.pitch()) + ". The track pitch is " + Notes.name(track.note()) + ".");
            stepPitchTrack.setEnabled(true);
        } else {
            stepPitchValue.setText(Notes.name(track.note()));
            stepPitchValue.setToolTipText("Uses the track pitch, " + Notes.name(track.note()) + ".");
            stepPitchTrack.setEnabled(false);
        }
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

    private int shownVelocity(Track track) {
        int step = editor.stepIndex();
        if (step >= 0 && step < Track.CAPACITY && track.step(step).on()) {
            return track.step(step).velocity();
        }
        return track.velocity();
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

    /** Kits and copy bar act on the pattern currently on screen. */
    private void editOnScreen(Consumer<Beat> action) {
        char slot = editor.viewSlot();
        editor.edit(beat -> {
            beat.setActiveSlot(slot);
            action.accept(beat);
        });
    }

    private void changeNote(int delta) {
        editor.edit(beat -> {
            Track track = onScreen(beat);
            track.setNote(track.note() + delta);
        });
        player.audition(editor.selectedTrack());
    }

    private void setMode(TrackMode mode) {
        if (editor.selectedTrack().mode() == mode) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setMode(mode));
        player.audition(editor.selectedTrack());
    }

    private void switchSlot(char slot) {
        if (syncing) {
            return;
        }
        if (editor.songMode() && player.isPlaying()) {
            if (editor.beat().activeSlot() != slot) {
                editor.edit(beat -> beat.setActiveSlot(slot));
            }
            editor.holdSlot(slot);
            status.setText(holdMessage(slot));
            return;
        }
        if (editor.beat().activeSlot() == slot) {
            return;
        }
        editor.edit(beat -> beat.setActiveSlot(slot));
    }

    private void chooseMode(boolean song) {
        if (song == editor.songMode()) {
            if (song) {
                editor.followAgain();
                followNow();
                status.setText(editor.beat().hasChain() ? "Following the song" : "Add A or B to the chain");
            }
            return;
        }
        int step = -1;
        if (!song && player.isPlaying()) {
            Chain.Place place = player.place(editor.beat());
            if (place != null) {
                step = place.step();
            }
        }
        editor.setSongMode(song);
        if (!player.isPlaying()) {
            return;
        }
        if (song && editor.beat().hasChain()) {
            player.play(editor.beat(), true);
            followNow();
            return;
        }
        long tick = step < 0 ? 0 : SequenceBuilder.tickForStep(step, editor.beat().swing());
        player.play(editor.beat(), false, tick);
    }

    private void followNow() {
        if (!player.isPlaying() || !editor.beat().hasChain()) {
            return;
        }
        Chain.Place place = player.place(editor.beat());
        if (place != null) {
            editor.showPlayingSlot(place.slot());
        }
    }

    private void appendChain(char slot) {
        if (editor.beat().chain().size() >= Chain.MAX_PARTS) {
            status.setText("The chain holds " + Chain.MAX_PARTS + " entries");
            return;
        }
        chainIndex = editor.beat().chain().size();
        editor.edit(beat -> beat.addChain(slot));
        status.setText("Added " + slotName(slot) + " to the chain");
    }

    private void removeSelectedChain() {
        int index = chainIndex;
        if (index < 0 || index >= editor.beat().chain().size()) {
            return;
        }
        editor.edit(beat -> beat.removeChain(index));
        chainIndex = Math.min(chainIndex, editor.beat().chain().size() - 1);
        refresh();
    }

    private void moveSelectedChain(int delta) {
        int index = chainIndex;
        int target = index + delta;
        if (index < 0 || target < 0 || target >= editor.beat().chain().size()) {
            return;
        }
        chainIndex = target;
        editor.edit(beat -> beat.moveChain(index, delta));
    }

    private void selectChain(int index) {
        chainIndex = index;
        if (editor.songMode() && player.isPlaying() && index >= 0 && index < editor.beat().chain().size()) {
            char slot = editor.beat().chain().get(index).slot();
            editor.holdSlot(slot);
            status.setText(holdMessage(slot));
        }
        refresh();
    }

    private void refreshChain() {
        paintToggle(patternMode, !editor.songMode());
        paintToggle(songMode, editor.songMode());
        List<Chain.Part> chain = editor.beat().chain();
        if (chainIndex >= chain.size()) {
            chainIndex = chain.size() - 1;
        }
        String signature = chainIndex + " " + chain;
        if (!signature.equals(chainButtons)) {
            chainButtons = signature;
            chainSlots.removeAll();
            for (int i = 0; i < chain.size(); i++) {
                Chain.Part part = chain.get(i);
                String name = slotName(part.slot());
                String text = part.repeats() == 1 ? name : name + "×" + part.repeats();
                JButton partButton = button(text);
                int index = i;
                partButton.setToolTipText(name + " plays " + part.repeats() + (part.repeats() == 1 ? " time" : " times") + ". Click to select.");
                partButton.addActionListener(event -> selectChain(index));
                paintToggle(partButton, i == chainIndex);
                chainSlots.add(partButton);
                if (i + 1 < chain.size()) {
                    chainSlots.add(Box.createHorizontalStrut(4));
                }
            }
            if (chain.isEmpty()) {
                JLabel empty = label("empty");
                empty.setForeground(Theme.MUTED);
                chainSlots.add(empty);
            }
            chainSlots.revalidate();
            chainSlots.repaint();
        }
        boolean selected = chainIndex >= 0 && chainIndex < chain.size();
        boolean room = chain.size() < Chain.MAX_PARTS;
        addChainA.setEnabled(room);
        addChainB.setEnabled(room);
        removeChain.setEnabled(selected);
        chainUp.setEnabled(selected && chainIndex > 0);
        chainDown.setEnabled(selected && chainIndex + 1 < chain.size());
        chainRepeats.setEnabled(selected);
        if (selected) {
            chainRepeats.setValue(chain.get(chainIndex).repeats());
        }
    }

    private static String slotName(char slot) {
        return slot == 'b' ? "B" : "A";
    }

    private static String holdMessage(char slot) {
        return "Holding pattern " + slotName(slot) + ". Click Song to follow the chain.";
    }

    private void togglePlay() {
        if (!player.isOpen()) {
            player.open();
        }
        if (!player.isOpen()) {
            JOptionPane.showMessageDialog(this, "MIDI is not available. You can still edit and save.", "Hits", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (player.isPlaying()) {
            player.stop();
            editor.stopped();
        } else {
            boolean arranged = editor.songMode() && editor.beat().hasChain();
            if (editor.songMode() && !arranged) {
                status.setText("The chain is empty. Playing pattern " + slotName(editor.beat().activeSlot()) + ".");
            }
            editor.followAgain();
            player.play(editor.beat(), arranged);
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        playShown = player.isPlaying();
        play.setText(playShown ? "Stop" : "Play");
        play.setBackground(playShown ? Theme.PLAY : Theme.PANEL);
        play.setForeground(playShown ? Theme.INK : Theme.TEXT);
    }

    private void updateTitle() {
        StringBuilder title = new StringBuilder("Hits — ");
        title.append(editor.beat().name());
        if (editor.isDirty()) {
            title.append(" •");
        }
        if (!player.isOpen()) {
            title.append(" — MIDI unavailable");
        }
        setTitle(title.toString());
    }

    private void commitDocumentName() {
        String next = documentName.getText().trim();
        if (next.isEmpty()) {
            if (!documentName.isFocusOwner()) {
                documentName.setText(editor.beat().name());
            }
            return;
        }
        if (next.equals(editor.beat().name())) {
            return;
        }
        editor.edit(beat -> beat.setName(next));
    }

    private void commitTrackName() {
        String next = trackName.getText().trim();
        if (next.isEmpty() || next.equals(editor.selectedTrack().name())) {
            return;
        }
        editor.edit(beat -> onScreen(beat).setName(next));
    }

    private void loadSelected() {
        BeatFiles.Entry entry = beatList.getSelectedValue();
        if (entry == null || !confirmProceed()) {
            return;
        }
        try {
            editor.replace(BeatFiles.load(entry.path()), false);
            String filename = entry.path().getFileName().toString().toLowerCase(Locale.ROOT);
            savedFile = filename.endsWith(".json") ? entry.path().toAbsolutePath().normalize() : null;
            status.setText("Loaded " + entry.label());
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean save() {
        commitDocumentName();
        commitTrackName();
        return writeFile(false);
    }

    private boolean saveAs() {
        commitTrackName();
        String initial = editor.beat().name();
        JTextField nameField = new JTextField(initial, 28);
        JLabel fileLabel = new JLabel(" ");
        Runnable refreshLabel = () -> fileLabel.setText(saveAsCaption(nameField.getText()));
        onText(nameField, refreshLabel);
        refreshLabel.run();
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(new JLabel("Name stored in the file"));
        form.add(nameField);
        form.add(Box.createVerticalStrut(8));
        form.add(fileLabel);
        int choice = JOptionPane.showConfirmDialog(this, form, "Save As", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return false;
        }
        String typed = nameField.getText().trim();
        if (BeatFiles.safeName(typed).isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add a letter or number to the name before saving.", "Hits", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (!typed.equals(editor.beat().name())) {
            editor.edit(beat -> beat.setName(typed));
        }
        documentName.setText(editor.beat().name());
        return writeFile(true);
    }

    private String saveAsCaption(String typed) {
        String display = typed == null ? "" : typed.trim();
        String safe = BeatFiles.safeName(display);
        if (safe.isEmpty()) {
            return "Add a letter or number. This name cannot be a file.";
        }
        boolean exists = Files.exists(beatsDirectory.resolve(safe + ".json"));
        String kept = safe.equals(display) ? "" : "  Name in the file stays \"" + display + "\".";
        if (exists) {
            return "Replaces " + safe + ".json." + kept;
        }
        return "File: " + safe + ".json." + kept;
    }

    private boolean writeFile(boolean alreadyConfirmed) {
        String display = editor.beat().name();
        String safe = BeatFiles.safeName(display);
        if (safe.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Add a letter or number to the name before saving.",
                "Hits",
                JOptionPane.WARNING_MESSAGE
            );
            return false;
        }
        Path target = beatsDirectory.resolve(safe + ".json");
        boolean exists = Files.exists(target);
        BeatFiles.SaveChoice choice = BeatFiles.plan(display, target, savedFile, false, exists);
        if (!alreadyConfirmed && choice != BeatFiles.SaveChoice.WRITE) {
            String message = choice == BeatFiles.SaveChoice.CONFIRM_REPLACE
                ? "Replace " + safe + ".json?"
                : "Save as " + safe + ".json?";
            if (!safe.equals(display)) {
                message += "\nThe name stored in the file stays \"" + display + "\".";
            }
            String[] options = choice == BeatFiles.SaveChoice.CONFIRM_REPLACE
                ? new String[]{"Replace", "Cancel"}
                : new String[]{"Save", "Cancel"};
            int answer = JOptionPane.showOptionDialog(
                this,
                message,
                "Save",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
            );
            if (answer != 0) {
                return false;
            }
        }
        try {
            Path path = BeatFiles.write(editor.beat(), target);
            editor.markClean();
            savedFile = path.toAbsolutePath().normalize();
            reloadBeats(path);
            status.setText("Saved " + path.getFileName());
            updateTitle();
            return true;
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(this, message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void exportMidi() {
        ExportChoice choice = askExport("Export MIDI", "mid", "Standard MIDI");
        if (choice == null) {
            return;
        }
        try {
            BeatFiles.exportMidi(editor.beat(), choice.path(), choice.options());
            status.setText("Exported " + choice.path().getFileName() + exportSummary(choice.options()));
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(this, message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportWav() {
        ExportChoice choice = askExport("Export WAV", "wav", "WAV audio");
        if (choice == null) {
            return;
        }
        Beat beat = editor.beat().copy();
        Path soundFont = player.soundFont();
        JDialog dialog = new JDialog(this, "Export WAV", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setString("Rendering…");
        bar.setPreferredSize(new Dimension(320, 22));
        JButton cancel = button("Cancel");
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancel);
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        body.add(new JLabel("Rendering " + choice.path().getFileName()), BorderLayout.NORTH);
        body.add(bar, BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);
        dialog.getContentPane().add(body);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
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
            protected void process(java.util.List<Integer> chunks) {
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
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent event) {
                worker.cancel(false);
            }
        });
        worker.execute();
        dialog.setVisible(true);
        try {
            worker.get();
            status.setText("Exported " + choice.path().getFileName() + exportSummary(choice.options()));
        } catch (CancellationException exception) {
            status.setText("Export cancelled");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            status.setText("Export cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof WavRenderer.Cancelled) {
                status.setText("Export cancelled");
                return;
            }
            JOptionPane.showMessageDialog(this, message(cause), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Save dialog shared by MIDI and WAV. Null when the user cancels. */
    private ExportChoice askExport(String title, String extension, String description) {
        JFileChooser chooser = new JFileChooser(beatsDirectory.toFile());
        chooser.setDialogTitle(title);
        String stem = BeatFiles.safeName(editor.beat().name());
        if (stem.isEmpty()) {
            stem = "untitled";
        }
        chooser.setSelectedFile(new java.io.File(stem + "." + extension));
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
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path path = chooser.getSelectedFile().toPath();
        String suffix = "." + extension;
        if (!path.toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
            path = path.resolveSibling(path.getFileName() + suffix);
        }
        boolean songChain = song.isSelected() && hasChain;
        ExportOptions options = new ExportOptions(
            asHeard.isSelected(),
            both.isSelected() && !songChain,
            (Integer) repeats.getValue(),
            songChain
        );
        return new ExportChoice(path, options);
    }

    private record ExportChoice(Path path, ExportOptions options) {}

    private static String exportSummary(ExportOptions options) {
        String heard = options.asHeard() ? "as heard" : "all tracks";
        if (options.song()) {
            return " (" + heard + ", song)";
        }
        String span = options.bothSlots() ? ", A then B" : "";
        String times = options.repeats() == 1 ? "" : ", " + options.repeats() + " times";
        return " (" + heard + span + times + ")";
    }

    private void fresh() {
        if (!confirmProceed()) {
            return;
        }
        savedFile = null;
        editor.replace(Beat.drumKit("untitled"), false);
        status.setText("New kit");
    }

    private void applyKit(String name, Consumer<Beat> kit) {
        String slot = slotName(editor.viewSlot());
        int choice = JOptionPane.showConfirmDialog(
            this,
            name + " replaces pattern " + slot + ", the tempo, and the swing.\nThe other pattern is kept.",
            "Hits",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        if (choice == JOptionPane.OK_OPTION) {
            editOnScreen(kit);
            status.setText(name + " on pattern " + slot);
        }
    }

    private void reloadBeats(Path select) {
        beats.clear();
        try {
            for (BeatFiles.Entry entry : BeatFiles.list(beatsDirectory)) {
                beats.addElement(entry);
            }
        } catch (IOException exception) {
            status.setText(message(exception));
            return;
        }
        if (select == null) {
            return;
        }
        for (int i = 0; i < beats.size(); i++) {
            if (beats.get(i).path().equals(select)) {
                beatList.setSelectedIndex(i);
                return;
            }
        }
    }

    private Path loadBeatsDirectory() {
        String saved = pref(BeatFolders.PREF_DIRECTORY);
        Path folder = BeatFolders.resolve(saved, Path.of("").toAbsolutePath());
        String absolute = BeatFolders.remember(folder);
        if (!absolute.equals(saved)) {
            prefPut(BeatFolders.PREF_DIRECTORY, absolute);
        }
        return folder;
    }

    private void chooseBeatsFolder() {
        JFileChooser chooser = new JFileChooser(beatsDirectory.toFile());
        chooser.setDialogTitle("Beats folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        beatsDirectory = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        prefPut(BeatFolders.PREF_DIRECTORY, BeatFolders.remember(beatsDirectory));
        if (beatsTitle != null) {
            beatsTitle.setToolTipText(beatsDirectory.toString());
        }
        reloadBeats(savedFile);
        status.setText("Beats folder: " + beatsDirectory);
    }

    private void applySavedOutput() {
        String saved = pref(MidiOutputs.PREF_OUTPUT);
        String resolved = MidiOutputs.resolve(saved, MidiOutputs.list().stream().map(MidiOutputs.Choice::id).toList());
        fillMidiOutputs(resolved);
        if (!player.outputId().equals(resolved) || !player.isOpen()) {
            player.useOutput(resolved);
        }
        fillMidiOutputs(player.outputId());
        if (!saved.isBlank() && !saved.equals(player.outputId())) {
            status.setText("Saved MIDI output is not connected. Using the built-in synth.");
        }
        applySoundFontPreference();
    }

    private void fillMidiOutputs(String selectedId) {
        java.util.List<MidiOutputs.Choice> choices = MidiOutputs.list();
        syncing = true;
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
            syncing = false;
        }
    }

    private void midiOutputChosen() {
        if (syncing || !(midiOut.getSelectedItem() instanceof MidiOutputs.Choice choice)) {
            return;
        }
        if (choice.id().equals(player.outputId()) && player.isOpen()) {
            return;
        }
        if (!player.useOutput(choice.id())) {
            JOptionPane.showMessageDialog(
                this,
                player.failure() == null ? "That MIDI output is not available." : player.failure(),
                "Hits",
                JOptionPane.WARNING_MESSAGE
            );
            fillMidiOutputs(player.outputId());
            updateTitle();
            return;
        }
        prefPut(MidiOutputs.PREF_OUTPUT, choice.id());
        status.setText("MIDI out: " + choice.label());
        if (player.soundFontNote() != null) {
            status.setText(player.soundFontNote());
        }
        updateTitle();
    }

    private void chooseSoundFont() {
        if (!player.isBuiltIn()) {
            if (pref(SoundFonts.PREF_FILE).isBlank()) {
                JOptionPane.showMessageDialog(
                    this,
                    SoundFonts.NEEDS_BUILTIN + "\nAn external device uses its own sounds.",
                    "Hits",
                    JOptionPane.INFORMATION_MESSAGE
                );
                return;
            }
            String[] options = {"Forget saved SoundFont", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                this,
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
                prefRemove(SoundFonts.PREF_FILE);
                status.setText("Built-in sounds");
            }
            return;
        }
        if (player.soundFont() == null && !pref(SoundFonts.PREF_FILE).isBlank()) {
            String[] options = {"Load a SoundFont", "Forget saved SoundFont", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                this,
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
                prefRemove(SoundFonts.PREF_FILE);
                status.setText("Built-in sounds");
                return;
            }
            if (choice != 0) {
                return;
            }
        }
        if (player.soundFont() != null) {
            String[] options = {"Load another", "Built-in sounds", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                this,
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
                prefRemove(SoundFonts.PREF_FILE);
                status.setText("Built-in sounds");
                return;
            }
            if (choice != 0) {
                return;
            }
        }
        Path start = beatsDirectory;
        String saved = pref(SoundFonts.PREF_FILE);
        if (!saved.isBlank()) {
            Path parent = Path.of(saved).getParent();
            if (parent != null && Files.isDirectory(parent)) {
                start = parent;
            }
        }
        JFileChooser chooser = new JFileChooser(start.toFile());
        chooser.setDialogTitle("SoundFont");
        chooser.setFileFilter(new FileNameExtensionFilter("SoundFont", "sf2"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        try {
            String name = player.loadSoundFont(path);
            prefPut(SoundFonts.PREF_FILE, path.toAbsolutePath().normalize().toString());
            status.setText("SoundFont: " + name);
        } catch (RuntimeException | java.io.IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(this, message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void applySoundFontPreference() {
        if (!player.isBuiltIn()) {
            return;
        }
        String saved = pref(SoundFonts.PREF_FILE);
        if (saved.isBlank()) {
            return;
        }
        Path path = Path.of(saved);
        if (!Files.isRegularFile(path)) {
            status.setText("SoundFont not found: " + path.getFileName());
            return;
        }
        try {
            status.setText("SoundFont: " + player.loadSoundFont(path));
        } catch (RuntimeException | java.io.IOException | javax.sound.midi.InvalidMidiDataException exception) {
            status.setText(message(exception));
        }
    }

    private static String pref(String key) {
        try {
            return Preferences.userNodeForPackage(HitsApp.class).get(key, "");
        } catch (Exception exception) {
            return "";
        }
    }

    private static void prefPut(String key, String value) {
        try {
            Preferences.userNodeForPackage(HitsApp.class).put(key, value);
        } catch (Exception ignored) {
            // The choice still applies until the app closes.
        }
    }

    private static void prefRemove(String key) {
        try {
            Preferences.userNodeForPackage(HitsApp.class).remove(key);
        } catch (Exception ignored) {
            // The loaded bank is already cleared.
        }
    }

    /** Save, discard, or cancel before replacing or closing the open beat. */
    private boolean confirmProceed() {
        if (!editor.isDirty()) {
            return true;
        }
        String[] options = {"Save", "Don't Save", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
            this,
            "Save changes to \"" + editor.beat().name() + "\"?",
            "Hits",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        );
        if (choice == 0) {
            return save();
        }
        return choice == 1;
    }

    private void close() {
        if (!confirmProceed()) {
            return;
        }
        playhead.stop();
        player.close();
        dispose();
        System.exit(0);
    }

    @Override
    public void dispose() {
        if (keys != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
            keys = null;
        }
        playhead.stop();
        super.dispose();
    }

    private void drag(JSlider slider, java.util.function.BooleanSupplier dragging, Consumer<Boolean> setDragging, Consumer<Beat> apply) {
        slider.addChangeListener(event -> {
            if (syncing) {
                return;
            }
            if (slider.getValueIsAdjusting()) {
                if (!dragging.getAsBoolean()) {
                    setDragging.accept(true);
                    editor.beginAdjust();
                }
                editor.adjust(apply);
            } else if (dragging.getAsBoolean()) {
                setDragging.accept(false);
                editor.adjust(apply);
                editor.endAdjust();
            } else {
                editor.edit(apply);
            }
        });
    }

    private static void addRow(JPanel panel, GridBagConstraints constraints, JComponent component) {
        panel.add(component, constraints);
        constraints.gridy++;
        constraints.weighty = 0;
    }

    private static JPanel labeled(String name, String tip, JSlider slider, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel title = label(name);
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

    private static void onText(JTextField field, Runnable listener) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                listener.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                listener.run();
            }
        });
    }

    private static JPanel row(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setOpaque(false);
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Theme.TEXT);
        return label;
    }

    private static JSlider slider(int min, int max, int value) {
        JSlider slider = new JSlider(min, max, value);
        slider.setBackground(Theme.PANEL);
        slider.setForeground(Theme.STEP_ON);
        return slider;
    }

    private static JButton button(String text) {
        JButton button = new JButton(text);
        Dimension preferred = button.getPreferredSize();
        int height = Math.max(32, preferred.height);
        int width = Math.max(28, preferred.width);
        button.setPreferredSize(new Dimension(width, height));
        button.setMinimumSize(new Dimension(28, height));
        button.setMaximumSize(new Dimension(Math.max(width, 28), height));
        button.setFocusable(false);
        button.setRequestFocusEnabled(false);
        return button;
    }

    private static void fix(JComponent component, int width, int height) {
        Dimension size = new Dimension(width, height);
        component.setPreferredSize(size);
        component.setMinimumSize(new Dimension(Math.min(width, 28), height));
        component.setMaximumSize(size);
    }

    private static void paintToggle(JButton button, boolean on) {
        button.setBackground(on ? Theme.STEP_ON : Theme.PANEL);
        button.setForeground(on ? Theme.INK : Theme.TEXT);
        button.setOpaque(true);
    }

    private static boolean typing() {
        return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
            instanceof javax.swing.text.JTextComponent;
    }

    private static AbstractAction action(Consumer<ActionEvent> handler) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                handler.accept(event);
            }
        };
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    }
}
