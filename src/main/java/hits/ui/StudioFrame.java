package hits.ui;

import hits.Chain;
import hits.Editor;
import hits.Kits;
import hits.Player;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Studio window. Builds the desk and connects the panels.
 * Transport, the chain, the inspector, files, export, and MIDI output live in their own classes.
 */
final class StudioFrame extends JFrame {
    private final Editor editor;
    private final Player player;
    private final Sync sync = new Sync();
    private final StatusLine status = new StatusLine();
    private final SaveSession session = new SaveSession();
    private final JPanel header = new JPanel(new BorderLayout());
    private final PatternPanel pattern;
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

    StudioFrame(Editor editor, Player player) {
        super("Hits");
        this.editor = editor;
        this.player = player;
        this.beatsFolder = new BeatsFolder();
        this.pattern = new PatternPanel(editor);
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
        browser.onLoad(files::loadSelected);
        browser.onSave(() -> files.save());
        browser.onSaveAs(() -> files.saveAs());
        browser.onExportMidi(exports::exportMidi);
        browser.onExportWav(exports::exportWav);
        browser.onFresh(files::fresh);
        browser.onBoomBap(() -> files.applyKit("Boom bap", Kits::boomBap));
        browser.onReggae(() -> files.applyKit("Reggae", Kits::reggae));
        browser.onChooseFolder(files::chooseBeatsFolder);

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
        JScrollPane scroll = new JScrollPane(pattern);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BG);
        left.add(scroll, BorderLayout.CENTER);
        left.add(browser.component(), BorderLayout.SOUTH);

        JScrollPane inspectorScroll = new JScrollPane(inspector.component());
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
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys);
    }

    private void changed(boolean musical) {
        refresh();
        if (musical && player.isPlaying()) {
            player.update(editor.beat(), editor.songMode());
        }
    }

    private void refresh() {
        sync.run(() -> {
            transport.refresh();
            chain.refresh();
            inspector.refresh();
            updateTitle();
            pattern.revalidate();
            pattern.repaint();
            transport.updatePlayButton();
        });
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
        if (keys != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys);
            keys = null;
        }
        playhead.stop();
        super.dispose();
    }
}
