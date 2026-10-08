package hits.ui;

import hits.BeatFiles;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;

/** The beats list, the folder button, and the load, save, export, new, and kit buttons. */
final class BeatBrowser {
    private final BeatsFolder beatsFolder;
    private final StatusLine status;
    private final DefaultListModel<BeatFiles.Entry> beats = new DefaultListModel<>();
    private final JList<BeatFiles.Entry> beatList = new JList<>(beats);
    private final JLabel beatsTitle = Widgets.label("Beats");
    private Runnable load = () -> {};
    private Runnable save = () -> {};
    private Runnable saveAs = () -> {};
    private Runnable exportMidi = () -> {};
    private Runnable exportWav = () -> {};
    private Runnable fresh = () -> {};
    private Runnable boomBap = () -> {};
    private Runnable reggae = () -> {};
    private Runnable chooseFolder = () -> {};

    BeatBrowser(BeatsFolder beatsFolder, StatusLine status) {
        this.beatsFolder = beatsFolder;
        this.status = status;
        beatsTitle.setFont(beatsTitle.getFont().deriveFont(Font.BOLD, 13f));
        beatsTitle.setToolTipText(beatsFolder.get().toString());
    }

    void onLoad(Runnable action) {
        load = action;
    }

    void onSave(Runnable action) {
        save = action;
    }

    void onSaveAs(Runnable action) {
        saveAs = action;
    }

    void onExportMidi(Runnable action) {
        exportMidi = action;
    }

    void onExportWav(Runnable action) {
        exportWav = action;
    }

    void onFresh(Runnable action) {
        fresh = action;
    }

    void onBoomBap(Runnable action) {
        boomBap = action;
    }

    void onReggae(Runnable action) {
        reggae = action;
    }

    void onChooseFolder(Runnable action) {
        chooseFolder = action;
    }

    BeatFiles.Entry selected() {
        return beatList.getSelectedValue();
    }

    void noteFolder() {
        beatsTitle.setToolTipText(beatsFolder.get().toString());
    }

    void reload(Path select) {
        beats.clear();
        try {
            for (BeatFiles.Entry entry : BeatFiles.list(beatsFolder.get())) {
                beats.addElement(entry);
            }
        } catch (IOException exception) {
            status.set(Widgets.message(exception));
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

    JPanel component() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(Theme.PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.setPreferredSize(new Dimension(200, 210));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JButton folder = Widgets.button("Folder…");
        folder.setToolTipText("Choose the folder Load and Save use. Hits remembers the absolute path.");
        folder.addActionListener(event -> chooseFolder.run());
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
                    load.run();
                }
            }
        });
        JScrollPane listScroll = new JScrollPane(beatList);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(listScroll, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        JButton loadButton = Widgets.button("Load");
        JButton saveButton = Widgets.button("Save");
        JButton saveAsButton = Widgets.button("Save As");
        JButton export = Widgets.button("Export MIDI");
        JButton exportWavButton = Widgets.button("Export WAV");
        JButton freshButton = Widgets.button("New");
        JButton boom = Widgets.button("Boom bap");
        JButton reggaeButton = Widgets.button("Reggae");
        loadButton.setToolTipText("Load the selected beat. Double-click the list too.");
        saveButton.setToolTipText("Save this beat. ⌘/Ctrl S. Asks before replacing a different file.");
        saveAsButton.setToolTipText("Save under a name you type. Shows the file name that will be written.");
        export.setToolTipText("Write a MIDI file. All tracks are included unless you choose As heard. Song writes the chain.");
        exportWavButton.setToolTipText("Write a WAV file of the same notes. Uses the built-in synth, and a loaded SoundFont when one is active. Song writes the chain.");
        freshButton.setToolTipText("Start a new empty drum kit.");
        boom.setToolTipText("Replace the pattern on screen with a boom bap groove. Asks first.");
        reggaeButton.setToolTipText("Replace the pattern on screen with a reggae groove. Asks first.");
        loadButton.addActionListener(event -> load.run());
        saveButton.addActionListener(event -> save.run());
        saveAsButton.addActionListener(event -> saveAs.run());
        export.addActionListener(event -> exportMidi.run());
        exportWavButton.addActionListener(event -> exportWav.run());
        freshButton.addActionListener(event -> fresh.run());
        boom.addActionListener(event -> boomBap.run());
        reggaeButton.addActionListener(event -> reggae.run());
        actions.add(loadButton);
        actions.add(saveButton);
        actions.add(saveAsButton);
        actions.add(export);
        actions.add(exportWavButton);
        actions.add(freshButton);
        actions.add(boom);
        actions.add(reggaeButton);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }
}
