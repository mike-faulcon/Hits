package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Mix;
import hits.Track;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.UIDefaults;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

/** Per-track level, pan, mute, and solo, plus the master fader. */
final class MixerPanel {
    private final Editor editor;
    private final Sync sync;
    private final JPanel panel;
    private final JSlider master = Widgets.slider(0, 127, Beat.MASTER);
    private final JLabel masterValue = Widgets.label(Integer.toString(Beat.MASTER));
    private final JCheckBox allPatterns = new JCheckBox("All patterns", true);
    private final SliderDrag masterDrag;
    private final JLabel[] names = new JLabel[Beat.TRACKS];
    private final JButton[] mutes = new JButton[Beat.TRACKS];
    private final JButton[] solos = new JButton[Beat.TRACKS];
    private final JSlider[] levels = new JSlider[Beat.TRACKS];
    private final JLabel[] levelValues = new JLabel[Beat.TRACKS];
    private final JSlider[] pans = new JSlider[Beat.TRACKS];
    private final JLabel[] panValues = new JLabel[Beat.TRACKS];
    private final SliderDrag[] levelDrags = new SliderDrag[Beat.TRACKS];
    private final SliderDrag[] panDrags = new SliderDrag[Beat.TRACKS];

    MixerPanel(Editor editor, Sync sync) {
        this.editor = editor;
        this.sync = sync;
        this.masterDrag = new SliderDrag(editor, sync);
        for (int row = 0; row < Beat.TRACKS; row++) {
            levelDrags[row] = new SliderDrag(editor, sync);
            panDrags[row] = new SliderDrag(editor, sync);
        }
        this.panel = build();
    }

    JPanel component() {
        return panel;
    }

    void wire() {
        master.setToolTipText("Master volume. 127 is full. This turns every track down together.");
        masterDrag.wire(master, beat -> beat.setMaster(master.getValue()));
        allPatterns.setToolTipText("Level and pan apply to every pattern whose row has the same name and sound. A different instrument on that row is left alone. Turn this off to edit only the pattern on screen. Mute and solo stay on this pattern.");
        for (int row = 0; row < Beat.TRACKS; row++) {
            int index = row;
            levels[row].setToolTipText("Track level. 100 leaves each hit as programmed. 0 is silent.");
            pans[row].setToolTipText("Pan. C is center. L is left, R is right.");
            mutes[row].setToolTipText("Mute this track.");
            solos[row].setToolTipText("Solo this track. While any solo is on, only soloed tracks play.");
            levelDrags[row].wire(levels[row], beat -> Mix.setLevel(
                beat, editor.viewSlot(), index, levels[index].getValue(), allPatterns.isSelected()));
            panDrags[row].wire(pans[row], beat -> Mix.setPan(
                beat, editor.viewSlot(), index, pans[index].getValue(), allPatterns.isSelected()));
            mutes[row].addActionListener(event -> editor.toggleMute(index));
            solos[row].addActionListener(event -> editor.toggleSolo(index));
        }
    }

    void refresh() {
        Beat beat = editor.beat();
        if (!master.getValueIsAdjusting()) {
            master.setValue(beat.master());
        }
        masterValue.setText(Integer.toString(beat.master()));
        Track[] tracks = editor.viewTracks();
        for (int row = 0; row < Beat.TRACKS; row++) {
            Track track = tracks[row];
            names[row].setText((row + 1) + "  " + track.name());
            names[row].setForeground(Beat.audibleIn(tracks, track) ? Theme.TEXT : Theme.MUTED);
            if (!levels[row].getValueIsAdjusting()) {
                levels[row].setValue(track.level());
            }
            levelValues[row].setText(Integer.toString(track.level()));
            if (!pans[row].getValueIsAdjusting()) {
                pans[row].setValue(track.pan());
            }
            panValues[row].setText(Mix.panText(track.pan()));
            paintChip(mutes[row], track.mute(), Theme.MUTE_ON);
            paintChip(solos[row], track.solo(), Theme.SOLO);
        }
    }

    private JPanel build() {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Theme.BG);
        body.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel hint = Widgets.label("Mute silences a track. While any solo is on, only soloed tracks play.");
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        allPatterns.setOpaque(false);
        allPatterns.setForeground(Theme.TEXT);
        allPatterns.setFocusable(false);
        allPatterns.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension allPatternsSize = allPatterns.getPreferredSize();
        allPatterns.setMaximumSize(new Dimension(Integer.MAX_VALUE, allPatternsSize.height));
        body.add(hint);
        body.add(Box.createVerticalStrut(6));
        body.add(allPatterns);
        body.add(Box.createVerticalStrut(10));
        body.add(masterRow());
        body.add(Box.createVerticalStrut(12));
        for (int row = 0; row < Beat.TRACKS; row++) {
            body.add(trackRow(row));
            body.add(Box.createVerticalStrut(4));
        }
        return body;
    }

    private JPanel masterRow() {
        JPanel row = strip();
        JLabel title = Widgets.label("Master");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        pin(title, 128, 32);
        grow(master);
        pin(masterValue, 44, 32);
        row.add(title);
        row.add(Box.createHorizontalStrut(8));
        row.add(master);
        row.add(Box.createHorizontalStrut(6));
        row.add(masterValue);
        return row;
    }

    private JPanel trackRow(int row) {
        names[row] = Widgets.label((row + 1) + "");
        names[row].setFont(names[row].getFont().deriveFont(Font.BOLD));
        mutes[row] = mixChip("M");
        solos[row] = mixChip("S");
        levels[row] = Widgets.slider(0, 127, Track.LEVEL);
        levelValues[row] = Widgets.label(Integer.toString(Track.LEVEL));
        pans[row] = Widgets.slider(0, 127, Track.PAN_CENTER);
        panValues[row] = Widgets.label("C");
        JLabel level = Widgets.label("Level");
        JLabel pan = Widgets.label("Pan");
        pin(names[row], 128, 32);
        pin(mutes[row], 44, 32);
        pin(solos[row], 44, 32);
        pin(level, 44, 32);
        pin(pan, 36, 32);
        pin(levelValues[row], 36, 32);
        pin(panValues[row], 40, 32);
        grow(levels[row]);
        grow(pans[row]);
        JPanel strip = strip();
        strip.add(names[row]);
        strip.add(Box.createHorizontalStrut(8));
        strip.add(mutes[row]);
        strip.add(Box.createHorizontalStrut(4));
        strip.add(solos[row]);
        strip.add(Box.createHorizontalStrut(10));
        strip.add(level);
        strip.add(levels[row]);
        strip.add(levelValues[row]);
        strip.add(Box.createHorizontalStrut(8));
        strip.add(pan);
        strip.add(pans[row]);
        strip.add(panValues[row]);
        return strip;
    }

    private static JPanel strip() {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static void pin(JComponent component, int width, int height) {
        Widgets.fix(component, width, height);
        component.setAlignmentY(Component.CENTER_ALIGNMENT);
    }

    private static void grow(JSlider slider) {
        slider.setPreferredSize(new Dimension(140, 32));
        slider.setMaximumSize(new Dimension(Short.MAX_VALUE, 32));
        slider.setAlignmentY(Component.CENTER_ALIGNMENT);
    }

    /**
     * Nimbus insets are wide enough that a 36px "M" is drawn as an ellipsis.
     * A slightly wider chip and tighter content margins keep the letter.
     */
    private static JButton mixChip(String letter) {
        JButton button = Widgets.button(letter);
        UIDefaults overrides = new UIDefaults();
        overrides.put("Button.contentMargins", new Insets(2, 6, 2, 6));
        button.putClientProperty("Nimbus.Overrides", overrides);
        button.putClientProperty("Nimbus.Overrides.InheritDefaults", Boolean.TRUE);
        button.updateUI();
        return button;
    }

    private static void paintChip(JButton button, boolean on, Color color) {
        button.setBackground(on ? color : Theme.PANEL);
        button.setForeground(on ? Theme.INK : Theme.TEXT);
        button.setOpaque(true);
    }
}
