package hits.ui;

import hits.Beat;
import hits.BeatFiles;
import hits.Editor;
import hits.Gm;
import hits.Kits;
import hits.Notes;
import hits.Player;
import hits.Track;
import hits.TrackMode;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
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
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

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
    private final JButton steps32 = button("32");

    private final JList<BeatFiles.Entry> beatList = new JList<>(beats);
    private final JLabel status = new JLabel("Space play  ·  click step  ·  shift-click accent  ·  ⌘/Ctrl Z undo");

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
        this.pattern = new PatternPanel(editor);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setBackground(Theme.BG);
        getContentPane().setBackground(Theme.BG);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(transport(), BorderLayout.NORTH);
        getContentPane().add(body(), BorderLayout.CENTER);
        getContentPane().add(footer(), BorderLayout.SOUTH);
        wire();
        setSize(1180, 760);
        setMinimumSize(new Dimension(960, 640));
        setLocationByPlatform(true);
        editor.addListener(this::changed);
        reloadBeats(null);
        refresh();
        playhead = new Timer(40, event -> {
            pattern.setPlayhead(player.currentStep(editor.beat()));
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
    }

    private JPanel transport() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        play.addActionListener(event -> togglePlay());
        bar.add(play);
        bar.add(Box.createHorizontalStrut(16));
        bar.add(label("BPM"));
        bar.add(Box.createHorizontalStrut(6));
        fix(bpm, 72, 32);
        bar.add(bpm);
        bar.add(Box.createHorizontalStrut(16));
        bar.add(label("Swing"));
        fix(swing, 160, 32);
        bar.add(swing);
        bar.add(swingValue);
        bar.add(Box.createHorizontalStrut(16));
        fix(slotA, 44, 32);
        fix(slotB, 44, 32);
        bar.add(slotA);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(slotB);
        bar.add(Box.createHorizontalStrut(8));
        copySlot.setToolTipText("Copy this pattern onto the other slot");
        bar.add(copySlot);
        bar.add(Box.createHorizontalGlue());
        documentName.setFont(documentName.getFont().deriveFont(Font.BOLD, 16f));
        fix(documentName, 160, 32);
        bar.add(documentName);
        return bar;
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
        panel.setPreferredSize(new Dimension(200, 176));
        JLabel title = label("Beats");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(title, BorderLayout.NORTH);
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
        JButton export = button("Export MIDI");
        JButton fresh = button("New");
        JButton boom = button("Boom bap");
        JButton reggae = button("Reggae");
        load.addActionListener(event -> loadSelected());
        save.addActionListener(event -> save());
        export.addActionListener(event -> exportMidi());
        fresh.addActionListener(event -> fresh());
        boom.addActionListener(event -> editor.edit(Kits::boomBap));
        reggae.addActionListener(event -> editor.edit(Kits::reggae));
        actions.add(load);
        actions.add(save);
        actions.add(export);
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

        addRow(panel, constraints, trackName);
        addRow(panel, constraints, noteValue);
        JPanel noteButtons = row(buttonPair("−", "Note down", -1), buttonPair("+", "Note up", 1),
            buttonPair("−8", "Octave down", -12), buttonPair("+8", "Octave up", 12));
        addRow(panel, constraints, noteButtons);
        addRow(panel, constraints, label("Sound"));
        soundPicker.setOpaque(false);
        soundPicker.add(drums, "drum");
        soundPicker.add(programs, "note");
        addRow(panel, constraints, soundPicker);
        addRow(panel, constraints, labeled("Gate", gate, gateValue));
        addRow(panel, constraints, labeled("Velocity", velocity, velocityValue));
        addRow(panel, constraints, labeled("Accent", accent, accentValue));
        addRow(panel, constraints, label("Mode"));
        addRow(panel, constraints, row(drumMode, noteMode));
        JButton copyBar = button("Copy bar");
        copyBar.addActionListener(event -> editor.edit(Beat::copyBar));
        addRow(panel, constraints, copyBar);
        addRow(panel, constraints, row(steps16, steps32));
        constraints.weighty = 1;
        addRow(panel, constraints, new JPanel() {{
            setOpaque(false);
        }});
        return panel;
    }

    private JButton buttonPair(String text, String tip, int delta) {
        JButton button = button(text);
        button.setToolTipText(tip);
        fix(button, 44, 32);
        button.addActionListener(event -> changeNote(delta));
        return button;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        status.setForeground(Theme.MUTED);
        panel.add(status, BorderLayout.WEST);
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
        drag(gate, () -> gateDrag, value -> gateDrag = value, beat -> beat.track(editor.trackIndex()).setGate(gate.getValue()));
        drag(velocity, () -> velocityDrag, value -> velocityDrag = value, this::applyVelocity);
        drag(accent, () -> accentDrag, value -> accentDrag = value, beat -> beat.track(editor.trackIndex()).setAccent(accent.getValue()));

        slotA.addActionListener(event -> switchSlot('a'));
        slotB.addActionListener(event -> switchSlot('b'));
        copySlot.addActionListener(event -> editor.edit(Beat::copyActiveToOther));
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
            editor.edit(beat -> beat.track(editor.trackIndex()).setNote(note));
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
            editor.edit(beat -> beat.track(editor.trackIndex()).setProgram(program));
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
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "play");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut), "undo");
        input.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, shortcut | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo");
        actions.put("play", action(event -> {
            if (!typing()) {
                togglePlay();
            }
        }));
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
    }

    private void changed(boolean musical) {
        refresh();
        if (musical && player.isPlaying()) {
            player.update(editor.beat());
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
            swingValue.setText(beat.swing() + "%");
            paintToggle(slotA, beat.activeSlot() == 'a');
            paintToggle(slotB, beat.activeSlot() == 'b');
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
            if (!accent.getValueIsAdjusting()) {
                accent.setValue(track.accent());
            }
            accentValue.setText(Integer.toString(track.accent()));
            paintToggle(drumMode, track.mode() == TrackMode.DRUM);
            paintToggle(noteMode, track.mode() == TrackMode.NOTE);
            paintToggle(steps16, beat.stepCount() == 16);
            paintToggle(steps32, beat.stepCount() == 32);
            pattern.revalidate();
            pattern.repaint();
            updatePlayButton();
        } finally {
            syncing = false;
        }
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
        Track track = beat.track(editor.trackIndex());
        int step = editor.stepIndex();
        int value = velocity.getValue();
        if (step >= 0 && track.step(step).on()) {
            track.step(step).setVelocity(value);
        } else {
            track.setVelocity(value);
        }
    }

    private void changeNote(int delta) {
        editor.edit(beat -> {
            Track track = beat.track(editor.trackIndex());
            track.setNote(track.note() + delta);
        });
        player.audition(editor.selectedTrack());
    }

    private void setMode(TrackMode mode) {
        if (editor.selectedTrack().mode() == mode) {
            return;
        }
        editor.edit(beat -> beat.track(editor.trackIndex()).setMode(mode));
        player.audition(editor.selectedTrack());
    }

    private void switchSlot(char slot) {
        if (syncing || editor.beat().activeSlot() == slot) {
            return;
        }
        editor.edit(beat -> beat.setActiveSlot(slot));
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
        } else {
            player.play(editor.beat());
        }
        updatePlayButton();
    }

    private void updatePlayButton() {
        playShown = player.isPlaying();
        play.setText(playShown ? "Stop" : "Play");
        play.setBackground(playShown ? Theme.PLAY : Theme.PANEL);
        play.setForeground(playShown ? Theme.INK : Theme.TEXT);
    }

    private void commitDocumentName() {
        String next = BeatFiles.safeName(documentName.getText());
        if (next.isEmpty() || next.equals(editor.beat().name())) {
            if (!documentName.isFocusOwner()) {
                documentName.setText(editor.beat().name());
            }
            return;
        }
        editor.edit(beat -> beat.setName(next));
    }

    private void commitTrackName() {
        String next = trackName.getText().trim();
        if (next.isEmpty() || next.equals(editor.selectedTrack().name())) {
            return;
        }
        editor.edit(beat -> beat.track(editor.trackIndex()).setName(next));
    }

    private void loadSelected() {
        BeatFiles.Entry entry = beatList.getSelectedValue();
        if (entry == null || !confirmDiscard()) {
            return;
        }
        try {
            editor.replace(BeatFiles.load(entry.path()), false);
            status.setText("Loaded " + entry.label());
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void save() {
        commitDocumentName();
        try {
            Path path = BeatFiles.save(editor.beat(), BeatFiles.directory());
            editor.markClean();
            reloadBeats(path);
            status.setText("Saved " + path.getFileName());
        } catch (RuntimeException | IOException exception) {
            JOptionPane.showMessageDialog(this, message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportMidi() {
        JFileChooser chooser = new JFileChooser(BeatFiles.directory().toFile());
        chooser.setSelectedFile(new java.io.File(editor.beat().name() + ".mid"));
        chooser.setFileFilter(new FileNameExtensionFilter("Standard MIDI", "mid"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        if (!path.toString().toLowerCase().endsWith(".mid")) {
            path = path.resolveSibling(path.getFileName() + ".mid");
        }
        try {
            BeatFiles.exportMidi(editor.beat(), path);
            status.setText("Exported " + path.getFileName());
        } catch (RuntimeException | IOException | javax.sound.midi.InvalidMidiDataException exception) {
            JOptionPane.showMessageDialog(this, message(exception), "Hits", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void fresh() {
        if (!confirmDiscard()) {
            return;
        }
        editor.replace(Beat.drumKit("untitled"), false);
        status.setText("New kit");
    }

    private void reloadBeats(Path select) {
        beats.clear();
        try {
            for (BeatFiles.Entry entry : BeatFiles.list(BeatFiles.directory())) {
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

    private boolean confirmDiscard() {
        if (!editor.isDirty()) {
            return true;
        }
        return JOptionPane.showConfirmDialog(
            this,
            "Discard unsaved changes?",
            "Hits",
            JOptionPane.YES_NO_OPTION
        ) == JOptionPane.YES_OPTION;
    }

    private void close() {
        if (!confirmDiscard()) {
            return;
        }
        playhead.stop();
        player.close();
        dispose();
        System.exit(0);
    }

    @Override
    public void dispose() {
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

    private static JPanel labeled(String name, JSlider slider, JLabel value) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        JLabel title = label(name);
        title.setPreferredSize(new Dimension(72, 32));
        panel.add(title, BorderLayout.WEST);
        panel.add(slider, BorderLayout.CENTER);
        value.setPreferredSize(new Dimension(44, 32));
        panel.add(value, BorderLayout.EAST);
        return panel;
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

    private static String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
