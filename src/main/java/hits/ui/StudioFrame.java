package hits.ui;

import hits.Beat;
import hits.Chain;
import hits.ChannelPlan;
import hits.Editor;
import hits.Kits;
import hits.Player;
import hits.SampleLibrary;
import hits.SampleUse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Studio window. Builds the desk and connects the panels.
 * Transport, the chain, the inspector, files, export, drag-out, and MIDI output live in their own classes.
 */
final class StudioFrame extends JFrame {
    private final Editor editor;
    private final Player player;
    private final Sync sync = new Sync();
    private final StatusLine status = new StatusLine();
    private final SaveSession session = new SaveSession();
    private final JPanel header = new JPanel(new BorderLayout());
    private final PatternPanel pattern;
    private final MixerPanel mixer;
    private final CardLayout views = new CardLayout();
    private final JPanel viewCards = new JPanel(views);
    private final JButton stepsView = Widgets.button("Steps");
    private final JButton mixerView = Widgets.button("Mixer");
    private final JButton addRow = rowButton(
        "Add row",
        "add-row",
        "Add a row on every pattern. Alt and equals. The first extra row is a sine 808 named Sub."
    );
    private final JButton removeRow = rowButton(
        "Remove",
        "remove-row",
        "Remove the selected row from every pattern. Alt and minus."
    );
    private final JButton duplicateRow = rowButton(
        "Duplicate",
        "duplicate-row",
        "Copy the selected row onto the next row, in every pattern. Alt+D."
    );
    private final JButton moveUp = rowButton(
        "Up",
        "move-row-up",
        "Move the selected row up, in every pattern. Alt+Up."
    );
    private final JButton moveDown = rowButton(
        "Down",
        "move-row-down",
        "Move the selected row down, in every pattern. Alt+Down."
    );
    private final JLabel channelWarning = Widgets.label(" ");
    private String channelVoiceKey = "";
    private SampleUse channelUse = SampleUse.none();
    private int revealedRow = -1;
    private boolean mixerShown;
    private final BeatsFolder beatsFolder;
    private final TransportBar transport;
    private final SongChainBar chain;
    private final TrackInspector inspector;
    private final BeatBrowser browser;
    private final OutputBar output;
    private final FileCommands files;
    private final ExportCommands exports;
    private final CoachBanner coach;
    private final Timer playhead;
    private KeyEventDispatcher keys;
    private KeyEventDispatcher tapKeys;
    private KeyEventDispatcher nudgeKeys;
    private KeyEventDispatcher rowKeys;

    StudioFrame(Editor editor, Player player) {
        super("Hits");
        this.editor = editor;
        this.player = player;
        this.beatsFolder = new BeatsFolder();
        this.pattern = new PatternPanel(editor);
        this.mixer = new MixerPanel(editor, sync);
        this.coach = new CoachBanner(editor, header);
        coach.maybeFirstRun();
        this.transport = new TransportBar(editor, player, sync, status);
        this.chain = new SongChainBar(editor, player, sync, status, this::refresh);
        this.inspector = new TrackInspector(editor, player, sync);
        this.browser = new BeatBrowser(beatsFolder, status);
        this.output = new OutputBar(this, player, sync, status, beatsFolder, this::updateTitle);
        this.files = new FileCommands(
            this,
            editor,
            session,
            beatsFolder,
            status,
            browser,
            transport::commitDocumentName,
            inspector::commitTrackName,
            transport::showDocumentName,
            this::updateTitle
        );
        this.exports = new ExportCommands(this, editor, player, beatsFolder, status);
        player.setBeatFile(session::file);
        exports.setBeatFile(session::file);
        inspector.setBeatFile(session::file);
        inspector.onCollect(files::collectSamples);
        browser.dragHandles(exports.midiChip(), exports.wavChip());
        browser.onLoad(files::loadSelected);
        browser.onSave(() -> files.save());
        browser.onSaveAs(() -> files.saveAs());
        browser.onExportMidi(exports::exportMidi);
        browser.onExportWav(exports::exportWav);
        browser.onFresh(files::fresh);
        browser.onBoomBap(() -> files.applyKit("Boom bap", Kits::boomBap));
        browser.onReggae(() -> files.applyKit("Reggae", Kits::reggae));
        GrooveCommands grooves = new GrooveCommands(this, editor, status);
        browser.onMakeBeat(grooves::makeBeat);
        inspector.onShuffle(grooves::shuffleSelected);
        browser.onChooseFolder(files::chooseBeatsFolder);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setBackground(Theme.BG);
        getContentPane().setBackground(Theme.BG);
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(body(), BorderLayout.CENTER);
        getContentPane().add(footer(), BorderLayout.SOUTH);
        wire();
        setSize(1240, 800);
        setMinimumSize(new Dimension(1100, 680));
        setLocationByPlatform(true);
        editor.addListener(this::changed);
        browser.reload(null);
        refresh();
        playhead = new Timer(40, event -> tickPlayhead());
        playhead.start();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent event) {
                if (!output.popupOpen()) {
                    output.fill(player.outputId());
                }
            }
        });
        output.applySaved();
        // The BPM field would otherwise take the first focus and swallow T and undo.
        SwingUtilities.invokeLater(pattern::requestFocusInWindow);
    }

    private JPanel buildHeader() {
        header.setOpaque(false);
        JPanel stack = new JPanel(new BorderLayout());
        stack.setOpaque(false);
        stack.add(transport.component(), BorderLayout.NORTH);
        stack.add(chain.component(), BorderLayout.SOUTH);
        header.add(stack, BorderLayout.NORTH);
        coach.attach();
        return header;
    }

    private JSplitPane body() {
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane stepsScroll = new JScrollPane(pattern);
        stepsScroll.setBorder(BorderFactory.createEmptyBorder());
        stepsScroll.getViewport().setBackground(Theme.BG);
        JScrollPane mixerScroll = new JScrollPane(mixer.component());
        mixerScroll.setBorder(BorderFactory.createEmptyBorder());
        mixerScroll.getViewport().setBackground(Theme.BG);
        viewCards.setOpaque(false);
        viewCards.add(stepsScroll, "steps");
        viewCards.add(mixerScroll, "mixer");
        left.add(viewBar(), BorderLayout.NORTH);
        left.add(viewCards, BorderLayout.CENTER);
        left.add(browser.component(), BorderLayout.SOUTH);

        JScrollPane inspectorScroll = new JScrollPane(inspector.component());
        inspectorScroll.setBorder(BorderFactory.createEmptyBorder());
        inspectorScroll.setPreferredSize(new Dimension(320, 400));
        // The grid's preferred width at 32 steps is wider than the window. Without a floor
        // the split gives that extra to the grid and the inspector collapses.
        inspectorScroll.setMinimumSize(new Dimension(300, 160));
        inspectorScroll.getViewport().setBackground(Theme.PANEL);
        left.setMinimumSize(new Dimension(320, 200));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, inspectorScroll);
        split.setResizeWeight(1);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(6);
        split.setBackground(Theme.BG);
        return split;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(Theme.BG);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        panel.add(status.component(), BorderLayout.CENTER);
        panel.add(output.component(), BorderLayout.EAST);
        return panel;
    }

    private void wire() {
        transport.wire(this);
        chain.wire();
        inspector.wire();
        mixer.wire();
        stepsView.addActionListener(event -> showMixer(false));
        mixerView.addActionListener(event -> showMixer(true));
        addRow.addActionListener(event -> editor.addRow());
        removeRow.addActionListener(event -> editor.removeRow());
        duplicateRow.addActionListener(event -> editor.duplicateRow());
        moveUp.addActionListener(event -> editor.moveRow(-1));
        moveDown.addActionListener(event -> editor.moveRow(1));
        output.wire();
        KeyboardShortcuts.install(
            getRootPane(),
            () -> {
                if (!KeyboardShortcuts.typing()) {
                    editor.undo();
                }
            },
            () -> {
                if (!KeyboardShortcuts.typing()) {
                    editor.redo();
                }
            },
            () -> files.save(),
            () -> files.saveAs()
        );
        keys = KeyboardShortcuts.spacePlay(this, () -> transport.togglePlay(this));
        tapKeys = KeyboardShortcuts.tapTempo(this, transport::tapTempo);
        nudgeKeys = KeyboardShortcuts.nudge(this, () -> editor.bumpStepNudge(-1), () -> editor.bumpStepNudge(1));
        rowKeys = KeyboardShortcuts.rows(
            this,
            editor::addRow,
            editor::removeRow,
            editor::duplicateRow,
            () -> editor.moveRow(-1),
            () -> editor.moveRow(1)
        );
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        manager.addKeyEventDispatcher(keys);
        manager.addKeyEventDispatcher(tapKeys);
        manager.addKeyEventDispatcher(nudgeKeys);
        manager.addKeyEventDispatcher(rowKeys);
    }

    private void changed(boolean musical) {
        exports.noteChange(musical);
        refresh();
        if (musical && player.isPlaying()) {
            player.update(editor.beat(), editor.songMode(), editor.isAdjusting());
        }
    }

    private void refresh() {
        sync.run(() -> {
            refreshRows();
            refreshChannels();
            transport.refresh();
            chain.refresh();
            inspector.refresh();
            mixer.refresh();
            Widgets.paintToggle(stepsView, !mixerShown);
            Widgets.paintToggle(mixerView, mixerShown);
            updateTitle();
            pattern.revalidate();
            pattern.repaint();
            if (editor.trackIndex() != revealedRow) {
                revealedRow = editor.trackIndex();
                pattern.revealSelection();
            }
            transport.updatePlayButton();
        });
    }

    private JPanel viewBar() {
        stepsView.setToolTipText("The step grid.");
        mixerView.setToolTipText("Level, pan, reverb, and chorus for every track, and the master volume.");
        Widgets.fix(stepsView, 72, 32);
        Widgets.fix(mixerView, 80, 32);
        channelWarning.setName("channel-warning");
        channelWarning.setForeground(Theme.STEP_ACCENT);
        channelWarning.setVisible(false);
        channelWarning.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel buttons = Widgets.row(stepsView, mixerView, addRow, removeRow, duplicateRow, moveUp, moveDown);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel bar = new JPanel();
        bar.setLayout(new javax.swing.BoxLayout(bar, javax.swing.BoxLayout.Y_AXIS));
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        bar.add(buttons);
        bar.add(channelWarning);
        return bar;
    }

    private void refreshRows() {
        showRowActions(
            addRow,
            duplicateRow,
            removeRow,
            moveUp,
            moveDown,
            editor.beat().trackCount(),
            editor.trackIndex()
        );
    }

    /** Enables the row buttons for {@code count} rows and a selection at {@code index}. */
    static void showRowActions(
        JButton add,
        JButton duplicate,
        JButton remove,
        JButton up,
        JButton down,
        int count,
        int index
    ) {
        add.setEnabled(count < Beat.MAX_TRACKS);
        duplicate.setEnabled(count < Beat.MAX_TRACKS);
        remove.setEnabled(count > Beat.MIN_TRACKS);
        up.setEnabled(index > 0);
        down.setEnabled(index + 1 < count);
    }

    /**
     * Channel plan for every pattern. Sample and filter voices are included on the built-in synth.
     * A slider drag keeps the last plan until the mouse goes up, so the bank is not rebuilt on every tick.
     */
    private void refreshChannels() {
        Beat beat = editor.beat();
        List<Character> slots = new ArrayList<>();
        for (int i = 0; i < Beat.SLOT_COUNT; i++) {
            slots.add(Beat.slotLetter(i));
        }
        SampleUse use = SampleUse.none();
        if (player.isBuiltIn()) {
            if (!editor.isAdjusting()) {
                String key = SampleLibrary.cacheKey(beat, session.file(), slots, player.soundbank(), true);
                if (!key.equals(channelVoiceKey)) {
                    channelUse = SampleLibrary.prepare(beat, session.file(), slots, player.soundbank(), true).use();
                    if (channelUse == null) {
                        channelUse = SampleUse.none();
                    }
                    channelVoiceKey = key;
                }
            }
            use = channelUse;
        } else {
            channelUse = SampleUse.none();
            channelVoiceKey = "";
        }
        ChannelPlan plan = ChannelPlan.of(beat, slots, player.soundbank(), use);
        inspector.showChannels(plan);
        String warning = plan.channelWarning(beat);
        boolean on = warning != null && !warning.isBlank();
        channelWarning.setVisible(on);
        if (!on) {
            channelWarning.setText(" ");
            channelWarning.getAccessibleContext().setAccessibleName("Channels");
            channelWarning.setToolTipText(null);
            return;
        }
        channelWarning.setText("<html><body style='width: 640px'>" + html(warning) + "</body></html>");
        channelWarning.getAccessibleContext().setAccessibleName(warning);
        channelWarning.setToolTipText(warning);
    }

    private static String html(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static JButton rowButton(String text, String name, String tip) {
        JButton button = Widgets.button(text);
        button.setName(name);
        String accessible = switch (name) {
            case "remove-row" -> "Remove row";
            case "duplicate-row" -> "Duplicate row";
            case "move-row-up" -> "Move row up";
            case "move-row-down" -> "Move row down";
            default -> text;
        };
        button.getAccessibleContext().setAccessibleName(accessible);
        button.setToolTipText(tip);
        return button;
    }

    private void showMixer(boolean mixer) {
        if (mixerShown == mixer) {
            return;
        }
        mixerShown = mixer;
        views.show(viewCards, mixer ? "mixer" : "steps");
        viewCards.revalidate();
        viewCards.repaint();
        refresh();
    }

    private void tickPlayhead() {
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
        transport.syncPlayButton();
    }

    private void updateTitle() {
        setTitle(SaveSession.windowTitle(editor.beat().name(), editor.isDirty(), player.isOpen()));
    }

    private void close() {
        if (!files.confirmProceed()) {
            return;
        }
        playhead.stop();
        player.close();
        dispose();
        System.exit(0);
    }

    @Override
    public void dispose() {
        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (keys != null) {
            manager.removeKeyEventDispatcher(keys);
            keys = null;
        }
        if (tapKeys != null) {
            manager.removeKeyEventDispatcher(tapKeys);
            tapKeys = null;
        }
        if (nudgeKeys != null) {
            manager.removeKeyEventDispatcher(nudgeKeys);
            nudgeKeys = null;
        }
        if (rowKeys != null) {
            manager.removeKeyEventDispatcher(rowKeys);
            rowKeys = null;
        }
        playhead.stop();
        exports.close();
        super.dispose();
    }
}
