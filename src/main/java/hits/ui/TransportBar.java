package hits.ui;

import hits.Editor;
import hits.Player;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/** Play/stop, tempo, swing, the A/B slots, and the beat name. */
final class TransportBar {
    private final Editor editor;
    private final Player player;
    private final Sync sync;
    private final StatusLine status;
    private final JButton play = Widgets.button("Play");
    private final JSpinner bpm = new JSpinner(new SpinnerNumberModel(96, 40, 240, 1));
    private final JSlider swing = Widgets.slider(50, 75, 50);
    private final JLabel swingValue = Widgets.label("50%");
    private final JButton slotA = Widgets.button("A");
    private final JButton slotB = Widgets.button("B");
    private final JButton copySlot = Widgets.button("Copy");
    private final JTextField documentName = new JTextField("untitled", 12);
    private final SliderDrag swingDrag;
    private boolean playShown;

    TransportBar(Editor editor, Player player, Sync sync, StatusLine status) {
        this.editor = editor;
        this.player = player;
        this.sync = sync;
        this.status = status;
        this.swingDrag = new SliderDrag(editor, sync);
    }

    JPanel component() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        play.setToolTipText("Play or stop. Space does this unless you are typing.");
        bar.add(play);
        bar.add(Box.createHorizontalStrut(16));
        JLabel bpmLabel = Widgets.label("BPM");
        bpmLabel.setToolTipText("Tempo, from 40 to 240.");
        bpm.setToolTipText("Tempo, from 40 to 240.");
        bar.add(bpmLabel);
        bar.add(Box.createHorizontalStrut(6));
        Widgets.fix(bpm, 72, 32);
        bar.add(bpm);
        bar.add(Box.createHorizontalStrut(16));
        JLabel swingLabel = Widgets.label("Swing");
        swingLabel.setToolTipText("50% is straight. Higher values delay every other 16th note.");
        swing.setToolTipText("50% is straight. Higher values delay every other 16th note.");
        bar.add(swingLabel);
        Widgets.fix(swing, 160, 32);
        bar.add(swing);
        Widgets.fix(swingValue, 120, 32);
        bar.add(swingValue);
        bar.add(Box.createHorizontalStrut(16));
        slotA.setToolTipText("Pattern A. The other pattern stays in this beat.");
        slotB.setToolTipText("Pattern B. The other pattern stays in this beat.");
        Widgets.fix(slotA, 44, 32);
        Widgets.fix(slotB, 44, 32);
        bar.add(slotA);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(slotB);
        bar.add(Box.createHorizontalStrut(8));
        copySlot.setToolTipText("Copy this pattern onto the other slot, replacing it.");
        bar.add(copySlot);
        bar.add(Box.createHorizontalGlue());
        documentName.setFont(documentName.getFont().deriveFont(Font.BOLD, 16f));
        documentName.setToolTipText("Name stored in the file. The file name keeps letters, numbers, _ and -.");
        Widgets.fix(documentName, 180, 32);
        bar.add(documentName);
        return bar;
    }

    void wire(Window parent) {
        play.addActionListener(event -> togglePlay(parent));
        bpm.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            int value = (Integer) bpm.getValue();
            editor.edit(beat -> beat.setBpm(value));
        });
        swingDrag.wire(swing, beat -> beat.setSwing(swing.getValue()));
        slotA.addActionListener(event -> switchSlot('a'));
        slotB.addActionListener(event -> switchSlot('b'));
        copySlot.addActionListener(event -> {
            char slot = editor.viewSlot();
            editor.edit(beat -> beat.copySlotToOther(slot));
        });
        documentName.addActionListener(event -> commitDocumentName());
        documentName.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                commitDocumentName();
            }
        });
    }

    void refresh() {
        var beat = editor.beat();
        bpm.setValue(beat.bpm());
        if (!swing.getValueIsAdjusting()) {
            swing.setValue(beat.swing());
        }
        swingValue.setText(TrackReadout.swing(beat.swing()));
        Widgets.paintToggle(slotA, editor.viewSlot() == 'a');
        Widgets.paintToggle(slotB, editor.viewSlot() == 'b');
        if (!documentName.isFocusOwner()) {
            documentName.setText(beat.name());
        }
        documentName.setToolTipText(SaveSession.nameTooltip(beat.name()));
    }

    void commitDocumentName() {
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

    void showDocumentName() {
        documentName.setText(editor.beat().name());
    }

    void togglePlay(Window parent) {
        if (!player.isOpen()) {
            player.open();
        }
        if (!player.isOpen()) {
            JOptionPane.showMessageDialog(parent, "MIDI is not available. You can still edit and save.", "Hits", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (player.isPlaying()) {
            player.stop();
            editor.stopped();
        } else {
            boolean arranged = editor.songMode() && editor.beat().hasChain();
            if (editor.songMode() && !arranged) {
                status.set(ChainLabels.playingEmpty(editor.beat().activeSlot()));
            }
            editor.followAgain();
            player.play(editor.beat(), arranged);
        }
        updatePlayButton();
    }

    void syncPlayButton() {
        if (player.isPlaying() != playShown) {
            updatePlayButton();
        }
    }

    void updatePlayButton() {
        playShown = player.isPlaying();
        play.setText(playShown ? "Stop" : "Play");
        play.setBackground(playShown ? Theme.PLAY : Theme.PANEL);
        play.setForeground(playShown ? Theme.INK : Theme.TEXT);
    }

    private void switchSlot(char slot) {
        if (sync.on()) {
            return;
        }
        if (editor.songMode() && player.isPlaying()) {
            if (editor.beat().activeSlot() != slot) {
                editor.edit(beat -> beat.setActiveSlot(slot));
            }
            editor.holdSlot(slot);
            status.set(ChainLabels.hold(slot));
            return;
        }
        if (editor.beat().activeSlot() == slot) {
            return;
        }
        editor.edit(beat -> beat.setActiveSlot(slot));
    }
}
