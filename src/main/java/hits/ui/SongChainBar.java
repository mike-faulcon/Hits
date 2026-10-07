package hits.ui;

import hits.Chain;
import hits.Editor;
import hits.ExportOptions;
import hits.Player;
import hits.SequenceBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;

/** Pattern/Song toggle and the chain row under the transport. */
final class SongChainBar {
    private final Editor editor;
    private final Player player;
    private final Sync sync;
    private final StatusLine status;
    private final ChainEdits edits;
    private final Runnable refreshStudio;
    private final JButton patternMode = Widgets.button("Pattern");
    private final JButton songMode = Widgets.button("Song");
    private final JPanel chainSlots = new JPanel();
    private final JButton addChainA = Widgets.button("+A");
    private final JButton addChainB = Widgets.button("+B");
    private final JButton removeChain = Widgets.button("Remove");
    private final JButton chainUp = Widgets.button("Up");
    private final JButton chainDown = Widgets.button("Down");
    private final JSpinner chainRepeats = new JSpinner(new SpinnerNumberModel(1, 1, ExportOptions.MAX_REPEATS, 1));
    private String chainButtons = "";

    SongChainBar(Editor editor, Player player, Sync sync, StatusLine status, Runnable refreshStudio) {
        this.editor = editor;
        this.player = player;
        this.sync = sync;
        this.status = status;
        this.edits = new ChainEdits(editor);
        this.refreshStudio = refreshStudio;
    }

    JPanel component() {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setOpaque(true);
        bar.setBackground(Theme.PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        patternMode.setToolTipText("Play the pattern on screen and loop it.");
        songMode.setToolTipText("Play the chain and loop it. Click again to follow the pattern you hear.");
        Widgets.fix(patternMode, 88, 32);
        Widgets.fix(songMode, 72, 32);
        bar.add(Widgets.row(patternMode, songMode), BorderLayout.WEST);

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
        Widgets.fix(addChainA, 48, 32);
        Widgets.fix(addChainB, 48, 32);
        Widgets.fix(chainUp, 52, 32);
        Widgets.fix(chainDown, 64, 32);
        JLabel repeatsLabel = Widgets.label("×");
        repeatsLabel.setToolTipText("Repeats for the selected entry.");
        Widgets.fix(chainRepeats, 64, 32);
        bar.add(Widgets.row(addChainA, addChainB, removeChain, chainUp, chainDown, repeatsLabel, chainRepeats), BorderLayout.EAST);
        bar.setMinimumSize(new Dimension(720, 52));
        bar.setPreferredSize(new Dimension(960, 52));
        return bar;
    }

    void wire() {
        patternMode.addActionListener(event -> chooseMode(false));
        songMode.addActionListener(event -> chooseMode(true));
        addChainA.addActionListener(event -> appendChain('a'));
        addChainB.addActionListener(event -> appendChain('b'));
        removeChain.addActionListener(event -> removeSelectedChain());
        chainUp.addActionListener(event -> moveSelectedChain(-1));
        chainDown.addActionListener(event -> moveSelectedChain(1));
        chainRepeats.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            edits.setRepeats((Integer) chainRepeats.getValue());
        });
    }

    void refresh() {
        Widgets.paintToggle(patternMode, !editor.songMode());
        Widgets.paintToggle(songMode, editor.songMode());
        List<Chain.Part> chain = editor.beat().chain();
        edits.clamp(chain.size());
        String signature = edits.index() + " " + chain;
        if (!signature.equals(chainButtons)) {
            chainButtons = signature;
            chainSlots.removeAll();
            for (int i = 0; i < chain.size(); i++) {
                Chain.Part part = chain.get(i);
                JButton partButton = Widgets.button(ChainLabels.partText(part));
                int index = i;
                partButton.setToolTipText(ChainLabels.partTip(part));
                partButton.addActionListener(event -> selectChain(index));
                Widgets.paintToggle(partButton, i == edits.index());
                chainSlots.add(partButton);
                if (i + 1 < chain.size()) {
                    chainSlots.add(Box.createHorizontalStrut(4));
                }
            }
            if (chain.isEmpty()) {
                JLabel empty = Widgets.label("empty");
                empty.setForeground(Theme.MUTED);
                chainSlots.add(empty);
            }
            chainSlots.revalidate();
            chainSlots.repaint();
        }
        boolean selected = edits.selected();
        addChainA.setEnabled(edits.canAdd());
        addChainB.setEnabled(edits.canAdd());
        removeChain.setEnabled(selected);
        chainUp.setEnabled(edits.canMoveUp());
        chainDown.setEnabled(edits.canMoveDown());
        chainRepeats.setEnabled(selected);
        if (selected) {
            chainRepeats.setValue(chain.get(edits.index()).repeats());
        }
    }

    private void chooseMode(boolean song) {
        if (song == editor.songMode()) {
            if (song) {
                editor.followAgain();
                followNow();
                status.set(editor.beat().hasChain() ? ChainLabels.following() : ChainLabels.needEntry());
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
        status.set(edits.append(slot));
    }

    private void removeSelectedChain() {
        if (!edits.remove()) {
            return;
        }
        refreshStudio.run();
    }

    private void moveSelectedChain(int delta) {
        edits.move(delta);
    }

    private void selectChain(int index) {
        edits.select(index);
        if (editor.songMode() && player.isPlaying() && index >= 0 && index < editor.beat().chain().size()) {
            char slot = editor.beat().chain().get(index).slot();
            editor.holdSlot(slot);
            status.set(ChainLabels.hold(slot));
        }
        refreshStudio.run();
    }
}
