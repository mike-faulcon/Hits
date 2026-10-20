package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.Notes;
import hits.Step;
import hits.Track;

import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Track names, mute, solo, the step grid, and a velocity lane for the selected track. */
final class PatternPanel extends JPanel implements Scrollable {
    private static final int LANE_RESERVE = 64;
    private static final int MIN_CELL = 32;

    private final Editor editor;
    private int playhead = -1;
    private Gesture gesture;

    PatternPanel(Editor editor) {
        this.editor = editor;
        setOpaque(true);
        setFocusable(true);
        setBackground(Theme.BG);
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        setToolTipText("Click a step. Shift-click makes a loud hit. Drag up or down to set velocity. Right-click or Alt-click selects it.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                pressed(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragged(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                released();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setPlayhead(int step) {
        if (playhead == step) {
            return;
        }
        playhead = step;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int columns = editor.beat().stepCount();
        int groups = Math.max(0, columns / 4 - 1);
        return new Dimension(248 + columns * 30 + groups * 12, 28 + Beat.TRACKS * MIN_CELL + LANE_RESERVE);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Metrics metrics = metrics();
        FontMetrics names = g.getFontMetrics(getFont().deriveFont(Font.BOLD));
        g.setFont(getFont());
        g.setColor(Theme.MUTED);
        for (int group = 0; group < metrics.columns / 4; group++) {
            String label = Integer.toString((group % 4) + 1);
            int center = metrics.stepX(group * 4) + metrics.cellWidth * 2;
            g.drawString(label, center - g.getFontMetrics().stringWidth(label) / 2, 18);
        }
        for (int row = 0; row < Beat.TRACKS; row++) {
            paintRow(g, metrics, names, row);
        }
        paintLane(g, metrics);
        g.setColor(Theme.GRID_LINE);
        for (int column = 4; column < metrics.columns; column += 4) {
            int line = metrics.stepX(column) - metrics.groupGap / 2;
            g.fillRect(line, metrics.header, 2, Beat.TRACKS * metrics.cellHeight);
        }
        if (playhead >= 0 && playhead < metrics.columns) {
            int x = metrics.stepX(playhead) + metrics.cellWidth / 2;
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x, metrics.header - 4, x, getHeight() - 6);
        }
        g.dispose();
    }

    private void paintRow(Graphics2D g, Metrics metrics, FontMetrics names, int row) {
        Track[] tracks = editor.viewTracks();
        Track track = tracks[row];
        int y = metrics.header + row * metrics.cellHeight;
        if (row == editor.trackIndex()) {
            g.setColor(Theme.SELECT);
            g.fillRect(0, y, getWidth(), metrics.cellHeight);
        }
        Composite previous = g.getComposite();
        if (!Beat.audibleIn(tracks, track)) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        }
        g.setColor(Theme.MUTED);
        g.setFont(getFont());
        String index = Integer.toString(row + 1);
        g.drawString(index, 10, y + metrics.cellHeight / 2 + 5);
        g.setFont(getFont().deriveFont(Font.BOLD));
        g.setColor(Theme.TEXT);
        g.drawString(clip(names, track.name(), 108), 28, y + metrics.cellHeight / 2 - 2);
        g.setFont(getFont().deriveFont(11f));
        g.setColor(Theme.MUTED);
        g.drawString(clip(g.getFontMetrics(), track.soundName(), 108), 28, y + metrics.cellHeight / 2 + 14);
        paintChip(g, metrics.mute(row), "M", track.mute(), Theme.MUTE_ON);
        paintChip(g, metrics.solo(row), "S", track.solo(), Theme.SOLO);
        for (int column = 0; column < metrics.columns; column++) {
            Rectangle cell = metrics.step(row, column);
            Step cellStep = track.step(column);
            g.setColor(Theme.STEP_OFF);
            g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 6, 6);
            if (cellStep.on()) {
                int filled = VelocityGesture.fillHeight(cellStep.velocity(), cell.height);
                java.awt.Shape clip = g.getClip();
                g.clipRect(cell.x, cell.y + cell.height - filled, cell.width, filled);
                g.setColor(colorFor(cellStep.shade(track.accent())));
                g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 6, 6);
                g.setClip(clip);
            }
            if (cellStep.uncertain()) {
                String label = Integer.toString(cellStep.probability());
                g.setFont(getFont().deriveFont(Font.BOLD, 9f));
                FontMetrics tiny = g.getFontMetrics();
                int textX = cell.x + Math.max(2, cell.width - tiny.stringWidth(label) - 3);
                int textY = cell.y + tiny.getAscent();
                boolean covered = cellStep.on() && VelocityGesture.fillHeight(cellStep.velocity(), cell.height) > 14;
                g.setColor(covered ? Theme.INK : Theme.TEXT);
                g.drawString(label, textX, textY);
            }
            if (cellStep.hasPitch()) {
                g.setColor(Theme.LINE);
                int mark = Math.max(2, cell.height / 8);
                g.fillRect(cell.x + 4, cell.y + cell.height - mark - 3, Math.max(4, cell.width - 8), 2);
            }
            if (row == editor.trackIndex() && column == editor.stepIndex()) {
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(cell.x + 1, cell.y + 1, cell.width - 3, cell.height - 3, 6, 6);
            }
        }
        g.setComposite(previous);
    }

    private void paintLane(Graphics2D g, Metrics metrics) {
        Track track = editor.selectedTrack();
        Composite previous = g.getComposite();
        if (!Beat.audibleIn(editor.viewTracks(), track)) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
        }
        g.setFont(getFont().deriveFont(11f));
        g.setColor(Theme.MUTED);
        g.drawString("Velocity", 28, metrics.laneTop() + 12);
        for (int column = 0; column < metrics.columns; column++) {
            Rectangle bar = metrics.laneBar(column);
            Step step = track.step(column);
            g.setColor(Theme.STEP_OFF);
            g.fillRoundRect(bar.x, bar.y, bar.width, bar.height, 4, 4);
            if (step.on()) {
                int filled = VelocityGesture.fillHeight(step.velocity(), bar.height);
                g.setColor(colorFor(step.shade(track.accent())));
                g.fillRoundRect(bar.x, bar.y + bar.height - filled, bar.width, filled, 4, 4);
            }
            if (column == editor.stepIndex()) {
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(bar.x + 1, bar.y + 1, bar.width - 3, bar.height - 3, 4, 4);
            }
        }
        g.setComposite(previous);
    }

    private void paintChip(Graphics2D g, Rectangle box, String letter, boolean on, Color onColor) {
        g.setColor(on ? onColor : Theme.STEP_OFF);
        g.fillRoundRect(box.x, box.y, box.width, box.height, 6, 6);
        g.setColor(on ? Theme.INK : Theme.TEXT);
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        FontMetrics metrics = g.getFontMetrics();
        int textX = box.x + (box.width - metrics.stringWidth(letter)) / 2;
        int textY = box.y + (box.height + metrics.getAscent() - metrics.getDescent()) / 2;
        g.drawString(letter, textX, textY);
    }

    private void pressed(MouseEvent event) {
        gesture = null;
        Metrics metrics = metrics();
        int laneColumn = metrics.laneColumn(event.getX(), event.getY());
        if (laneColumn >= 0) {
            int row = editor.trackIndex();
            if (SwingUtilities.isRightMouseButton(event) || event.isAltDown() || event.isPopupTrigger()) {
                editor.selectStep(row, laneColumn);
                gesture = Gesture.selectOnly(row, laneColumn);
                return;
            }
            char slot = editor.viewSlot();
            editor.selectStep(row, laneColumn);
            editor.beginAdjust();
            Rectangle bar = metrics.laneBar(laneColumn);
            int velocity = VelocityGesture.fromLane(event.getY(), bar.y, bar.height);
            gesture = Gesture.lane(row, laneColumn, slot);
            applyVelocity(slot, row, laneColumn, velocity);
            return;
        }
        int row = metrics.rowAt(event.getY());
        if (row < 0) {
            return;
        }
        if (metrics.mute(row).contains(event.getPoint())) {
            editor.toggleMute(row);
            return;
        }
        if (metrics.solo(row).contains(event.getPoint())) {
            editor.toggleSolo(row);
            return;
        }
        int column = metrics.columnAt(event.getX(), event.getY());
        if (column >= 0) {
            boolean selectOnly = SwingUtilities.isRightMouseButton(event)
                || event.isAltDown()
                || event.isPopupTrigger();
            if (selectOnly) {
                editor.selectStep(row, column);
                gesture = Gesture.selectOnly(row, column);
                return;
            }
            Track track = editor.viewTrack(row);
            Step step = track.step(column);
            int start = step.on() ? step.velocity() : (event.isShiftDown() ? track.accent() : track.velocity());
            gesture = Gesture.pad(row, column, event.isShiftDown(), event.getY(), start, editor.viewSlot());
            return;
        }
        editor.selectTrack(row);
    }

    private void dragged(MouseEvent event) {
        if (gesture == null || gesture.selectOnly) {
            return;
        }
        Metrics metrics = metrics();
        if (gesture.lane) {
            int column = metrics.laneColumn(event.getX(), event.getY());
            if (column < 0) {
                return;
            }
            if (column != editor.stepIndex() || gesture.row != editor.trackIndex()) {
                editor.selectStep(gesture.row, column);
            }
            Rectangle bar = metrics.laneBar(column);
            applyVelocity(gesture.slot, gesture.row, column, VelocityGesture.fromLane(event.getY(), bar.y, bar.height));
            return;
        }
        int delta = event.getY() - gesture.y;
        if (!gesture.dragging) {
            if (Math.abs(delta) < VelocityGesture.DRAG_SLOP) {
                return;
            }
            gesture.dragging = true;
            editor.selectStep(gesture.row, gesture.column);
            editor.beginAdjust();
        }
        applyVelocity(gesture.slot, gesture.row, gesture.column, VelocityGesture.fromDrag(gesture.startVelocity, delta));
    }

    private void released() {
        Gesture current = gesture;
        gesture = null;
        if (current == null) {
            return;
        }
        if (current.lane || current.dragging) {
            editor.endAdjust();
            return;
        }
        if (!current.selectOnly && current.column >= 0) {
            editor.tap(current.row, current.column, current.shift);
        }
    }

    private void applyVelocity(char slot, int row, int column, int velocity) {
        editor.adjust(beat -> {
            Step step = beat.slot(slot)[row].step(column);
            if (!step.on()) {
                step.setOn(true);
            }
            step.setVelocity(velocity);
        });
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (event == null) {
            return getToolTipText();
        }
        Metrics metrics = metrics();
        int row = metrics.rowAt(event.getY());
        if (row >= 0 && metrics.mute(row).contains(event.getPoint())) {
            return "Mute this track";
        }
        if (row >= 0 && metrics.solo(row).contains(event.getPoint())) {
            return "Solo this track. While any solo is on, only soloed tracks play.";
        }
        if (metrics.laneColumn(event.getX(), event.getY()) >= 0) {
            return "Drag to set the velocity of this step on " + editor.selectedTrack().name() + ".";
        }
        int column = row >= 0 ? metrics.columnAt(event.getX(), event.getY()) : -1;
        if (column >= 0) {
            Step step = editor.viewTrack(row).step(column);
            String pitch = step.hasPitch() ? " Step pitch " + Notes.name(step.pitch()) + "." : "";
            String chance = step.uncertain() ? " Chance " + step.probability() + "%." : "";
            return "Click toggles the step. Shift-click makes a loud hit. Drag up or down to set velocity. Right-click or Alt-click selects it so you can change its volume, chance, or step pitch."
                + pitch + chance;
        }
        if (row >= 0) {
            return "Select this track";
        }
        return getToolTipText();
    }

    private static Color colorFor(Step.Shade shade) {
        return switch (shade) {
            case OFF -> Theme.STEP_OFF;
            case QUIET -> Theme.STEP_DIM;
            case ACCENT -> Theme.STEP_ACCENT;
            case NORMAL -> Theme.STEP_ON;
        };
    }

    private Metrics metrics() {
        int columns = editor.beat().stepCount();
        int width = Math.max(getWidth(), getPreferredSize().width);
        int height = Math.max(getHeight(), getPreferredSize().height);
        int gutter = 236;
        int header = 28;
        int groupGap = 12;
        int groups = Math.max(0, columns / 4 - 1);
        int availableWidth = width - gutter - 12 - groups * groupGap;
        int cellWidth = Math.max(28, availableWidth / columns);
        int cellHeight = Math.max(MIN_CELL, (height - header - LANE_RESERVE) / Beat.TRACKS);
        return new Metrics(gutter, header, groupGap, cellWidth, cellHeight, columns);
    }

    private static String clip(FontMetrics metrics, String text, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String trimmed = text;
        while (trimmed.length() > 1 && metrics.stringWidth(trimmed + "…") > width) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "…";
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 40;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return 40;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return getParent() != null && getPreferredSize().width <= getParent().getWidth();
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() != null && getPreferredSize().height <= getParent().getHeight();
    }

    private record Metrics(int gutter, int header, int groupGap, int cellWidth, int cellHeight, int columns) {
        int stepX(int column) {
            return gutter + 8 + column * cellWidth + (column / 4) * groupGap;
        }

        Rectangle step(int row, int column) {
            int inset = 4;
            return new Rectangle(
                stepX(column) + inset,
                header + row * cellHeight + inset,
                Math.max(8, cellWidth - inset * 2),
                Math.max(8, cellHeight - inset * 2)
            );
        }

        Rectangle mute(int row) {
            return chip(row, gutter - 72);
        }

        Rectangle solo(int row) {
            return chip(row, gutter - 40);
        }

        int rowAt(int y) {
            if (y < header) {
                return -1;
            }
            int row = (y - header) / cellHeight;
            if (row < 0 || row >= Beat.TRACKS) {
                return -1;
            }
            return row;
        }

        int columnAt(int x, int y) {
            int row = rowAt(y);
            if (row < 0) {
                return -1;
            }
            return columnIn(x, y, header + row * cellHeight, cellHeight);
        }

        int laneTop() {
            return header + Beat.TRACKS * cellHeight + 4;
        }

        int laneColumn(int x, int y) {
            Rectangle bar = laneBar(0);
            if (y < bar.y || y >= bar.y + bar.height) {
                return -1;
            }
            return columnIn(x, y, bar.y, bar.height);
        }

        Rectangle laneBar(int column) {
            Rectangle cell = step(0, column);
            int top = laneTop() + 14;
            return new Rectangle(cell.x, top, cell.width, 36);
        }

        private int columnIn(int x, int y, int top, int height) {
            for (int column = 0; column < columns; column++) {
                Rectangle hit = new Rectangle(stepX(column), top, cellWidth, height);
                if (hit.contains(x, y)) {
                    return column;
                }
            }
            return -1;
        }

        private Rectangle chip(int row, int x) {
            int y = header + row * cellHeight + Math.max(0, (cellHeight - 28) / 2);
            return new Rectangle(x, y, 28, 28);
        }
    }

    private static final class Gesture {
        final int row;
        final int column;
        final boolean lane;
        final boolean selectOnly;
        final boolean shift;
        final int y;
        final int startVelocity;
        final char slot;
        boolean dragging;

        private Gesture(int row, int column, boolean lane, boolean selectOnly, boolean shift, int y, int startVelocity, char slot) {
            this.row = row;
            this.column = column;
            this.lane = lane;
            this.selectOnly = selectOnly;
            this.shift = shift;
            this.y = y;
            this.startVelocity = startVelocity;
            this.slot = slot;
            this.dragging = lane;
        }

        static Gesture pad(int row, int column, boolean shift, int y, int startVelocity, char slot) {
            return new Gesture(row, column, false, false, shift, y, startVelocity, slot);
        }

        static Gesture lane(int row, int column, char slot) {
            return new Gesture(row, column, true, false, false, 0, 0, slot);
        }

        static Gesture selectOnly(int row, int column) {
            return new Gesture(row, column, false, true, false, 0, 0, 'a');
        }
    }
}
