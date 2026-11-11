package hits.ui;

import hits.Beat;
import hits.Editor;
import hits.NoteSpan;
import hits.SampleRef;
import hits.Slide;
import hits.Step;
import hits.Track;
import hits.TrackMode;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Track names, mute, solo, the step grid, and a velocity lane for the selected track. */
final class PatternPanel extends JPanel implements Scrollable {
    private static final int LANE_RESERVE = 56;
    private static final int MIN_CELL = 26;
    private static final int MIN_CELL_WIDTH = 16;
    private static final int GUTTER = 210;
    private static final int GROUP_GAP = 6;
    private static final int HEADER = 24;

    private final Editor editor;
    private int playhead = -1;
    private Gesture gesture;
    /** Enter toggles once per press. A held key must not flip the step on every repeat. */
    private boolean enterDown;

    PatternPanel(Editor editor) {
        this.editor = editor;
        setOpaque(true);
        setFocusable(true);
        setName("step-grid");
        setBackground(Theme.BG);
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        setToolTipText(KeyboardShortcuts.gridTip());
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (handleKey(event)) {
                    event.consume();
                }
            }

            @Override
            public void keyReleased(KeyEvent event) {
                handleKey(event);
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                if (editor.stepIndex() < 0) {
                    int rows = editor.beat().trackCount();
                    int columns = editor.beat().gridColumns(editor.viewSlot());
                    if (rows > 0 && columns > 0) {
                        int row = editor.trackIndex();
                        if (row < 0 || row >= rows) {
                            row = 0;
                        }
                        editor.selectStep(row, 0);
                    }
                }
                announce(null);
                revealCursor();
                repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                repaint();
            }
        });
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
        setTransferHandler(wavDrop());
    }

    private TransferHandler wavDrop() {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return false;
                }
                if (support.getDropLocation() == null) {
                    return true;
                }
                return metrics().rowAt(support.getDropLocation().getDropPoint().y) >= 0;
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    Object data = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(data instanceof List<?> files)) {
                        return false;
                    }
                    File wav = null;
                    for (Object item : files) {
                        if (item instanceof File file && isWav(file)) {
                            wav = file;
                            break;
                        }
                    }
                    if (wav == null || support.getDropLocation() == null) {
                        return false;
                    }
                    int row = metrics().rowAt(support.getDropLocation().getDropPoint().y);
                    if (row < 0) {
                        return false;
                    }
                    String path = wav.toPath().toAbsolutePath().normalize().toString();
                    editor.selectTrack(row);
                    editor.edit(beat -> {
                        Track track = beat.slot(editor.viewSlot())[row];
                        track.setMode(TrackMode.SAMPLE);
                        SampleRef sample = track.sample();
                        if (sample == null) {
                            track.setSample(new SampleRef(path, track.note(), 0, true));
                        } else {
                            track.setSample(sample.withPath(path));
                        }
                    });
                    return true;
                } catch (Exception exception) {
                    return false;
                }
            }
        };
    }

    private static boolean isWav(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".wav") || name.endsWith(".wave");
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
        int columns = editor.beat().gridColumns(editor.viewSlot());
        int groups = Math.max(0, columns / 4 - 1);
        int rows = rowCount();
        return new Dimension(
            GUTTER + 16 + columns * MIN_CELL_WIDTH + groups * GROUP_GAP,
            HEADER + rows * minCell(rows) + LANE_RESERVE
        );
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Metrics metrics = metrics();
        int length = editor.beat().stepCount(editor.viewSlot());
        FontMetrics names = g.getFontMetrics(getFont().deriveFont(Font.BOLD));
        g.setFont(getFont());
        g.setColor(Theme.MUTED);
        for (int group = 0; group < metrics.columns / 4; group++) {
            String label = Integer.toString((group % 4) + 1);
            int center = metrics.stepX(group * 4) + metrics.cellWidth * 2;
            if (group * 4 >= length) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            }
            g.drawString(label, center - g.getFontMetrics().stringWidth(label) / 2, 18);
            g.setComposite(AlphaComposite.SrcOver);
        }
        for (int row = 0; row < metrics.rows; row++) {
            paintRow(g, metrics, names, row, length);
        }
        paintLane(g, metrics, length);
        g.setColor(Theme.GRID_LINE);
        for (int column = 4; column < metrics.columns; column += 4) {
            int line = metrics.stepX(column) - metrics.groupGap / 2;
            g.fillRect(line, metrics.header, 2, metrics.rows * metrics.cellHeight);
        }
        if (playhead >= 0 && playhead < metrics.columns) {
            int x = metrics.stepX(playhead) + metrics.cellWidth / 2;
            Composite previous = g.getComposite();
            if (playhead >= length) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            }
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x, metrics.header - 4, x, getHeight() - 6);
            g.setComposite(previous);
        }
        if (PatternHints.quiet(editor.beat(), editor.viewSlot())) {
            paintEmpty(g, metrics, length);
        }
        paintCursor(g, metrics);
        g.dispose();
    }

    private void paintEmpty(Graphics2D g, Metrics metrics, int length) {
        String hint = PatternHints.EMPTY;
        g.setFont(getFont().deriveFont(Font.BOLD, 14f));
        FontMetrics text = g.getFontMetrics();
        int width = text.stringWidth(hint);
        int left = metrics.stepX(0);
        int right = metrics.stepX(Math.max(0, length - 1)) + metrics.cellWidth;
        int x = left + Math.max(0, (right - left - width) / 2);
        int y = metrics.header + metrics.rows * metrics.cellHeight / 2;
        int boxX = Math.max(left, x - 14);
        int boxY = y - text.getAscent() - 8;
        int boxW = Math.min(width + 28, Math.max(0, right - boxX));
        g.setColor(new Color(Theme.PANEL.getRed(), Theme.PANEL.getGreen(), Theme.PANEL.getBlue(), 230));
        g.fillRoundRect(boxX, boxY, boxW, text.getHeight() + 16, 10, 10);
        g.setColor(Theme.TEXT);
        g.drawString(hint, x, y);
    }

    private void paintRow(Graphics2D g, Metrics metrics, FontMetrics names, int row, int length) {
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
        g.drawString(clip(names, track.name(), 96), 28, y + metrics.cellHeight / 2 - 2);
        g.setFont(getFont().deriveFont(11f));
        g.setColor(Theme.MUTED);
        g.drawString(clip(g.getFontMetrics(), track.soundName(), 96), 28, y + metrics.cellHeight / 2 + 12);
        paintChip(g, metrics.mute(row), "M", track.mute(), Theme.MUTE_ON);
        paintChip(g, metrics.solo(row), "S", track.solo(), Theme.SOLO);
        for (int column = 0; column < metrics.columns; column++) {
            Rectangle cell = metrics.step(row, column);
            Step cellStep = track.step(column);
            Composite cellComposite = g.getComposite();
            if (column >= length) {
                dim(g, cellComposite);
            }
            g.setColor(Theme.STEP_OFF);
            g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 6, 6);
            if (cellStep.on()) {
                int filled = VelocityGesture.fillHeight(cellStep.velocity(), cell.height);
                java.awt.Shape clip = g.getClip();
                g.clipRect(cell.x, cell.y + cell.height - filled, cell.width, filled);
                g.setColor(colorFor(cellStep.shade(track.accent())));
                g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 6, 6);
                g.setClip(clip);
                paintNudge(g, cell, track.playedNudge(column), cellStep.shade(track.accent()));
            } else {
                int holder = NoteSpan.holder(track, column, length);
                if (holder >= 0) {
                    paintHold(g, cell, colorFor(track.step(holder).shade(track.accent())));
                }
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
            if (cellStep.slide() && Slide.applies(track)) {
                paintSlide(g, metrics, row, column, cell);
            }
            g.setComposite(cellComposite);
        }
        g.setComposite(previous);
    }

    private void paintLane(Graphics2D g, Metrics metrics, int length) {
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
            Composite barComposite = g.getComposite();
            if (column >= length) {
                dim(g, barComposite);
            }
            g.setColor(Theme.STEP_OFF);
            g.fillRoundRect(bar.x, bar.y, bar.width, bar.height, 4, 4);
            if (step.on()) {
                int filled = VelocityGesture.fillHeight(step.velocity(), bar.height);
                g.setColor(colorFor(step.shade(track.accent())));
                g.fillRoundRect(bar.x, bar.y + bar.height - filled, bar.width, filled, 4, 4);
            }
            g.setComposite(barComposite);
        }
        g.setComposite(previous);
    }

    /** A green wedge on the left of a slide step, and a line back to the previous step. */
    private void paintSlide(Graphics2D g, Metrics metrics, int row, int column, Rectangle cell) {
        g.setColor(Theme.PLAY);
        int size = Math.max(6, Math.min(cell.height / 2, 10));
        int x = cell.x + 2;
        int y = cell.y + 2;
        g.fillPolygon(new int[] {x, x + size, x}, new int[] {y, y + size / 2, y + size}, 3);
        if (column > 0) {
            Rectangle previous = metrics.step(row, column - 1);
            int yMid = cell.y + cell.height / 2;
            g.setStroke(new BasicStroke(2f));
            g.drawLine(previous.x + previous.width - 2, yMid, cell.x + 1, yMid);
        }
    }

    private static void dim(Graphics2D g, Composite base) {
        float alpha = 0.38f;
        if (base instanceof AlphaComposite existing) {
            alpha *= existing.getAlpha();
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
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
            if ((event.isControlDown() || event.isMetaDown()) && Slide.applies(track)) {
                editor.toggleSlide(row, column);
                return;
            }
            int start = step.on() ? step.velocity() : (event.isShiftDown() ? track.accent() : track.velocity());
            gesture = Gesture.pad(
                row,
                column,
                event.isShiftDown(),
                event.getX(),
                event.getY(),
                start,
                editor.viewSlot(),
                step.on()
            );
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
        int dx = event.getX() - gesture.x;
        int dy = event.getY() - gesture.y;
        if (!gesture.dragging) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) < VelocityGesture.DRAG_SLOP) {
                return;
            }
            gesture.dragging = true;
            gesture.stretch = gesture.canStretch && Math.abs(dx) > Math.abs(dy);
            editor.selectStep(gesture.row, gesture.column);
            editor.beginAdjust();
        }
        if (gesture.stretch) {
            int column = metrics.columnForX(event.getX());
            int length = NoteSpan.lengthForDrag(gesture.column, column, editor.beat().stepCount(editor.viewSlot()));
            applyLength(gesture.slot, gesture.row, gesture.column, length);
            return;
        }
        applyVelocity(gesture.slot, gesture.row, gesture.column, VelocityGesture.fromDrag(gesture.startVelocity, dy));
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

    private void applyLength(char slot, int row, int column, int length) {
        editor.adjust(beat -> {
            Step step = beat.slot(slot)[row].step(column);
            if (step.on()) {
                step.setLength(length);
            }
        });
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
            return "Mute this row. M mutes the selected row.";
        }
        if (row >= 0 && metrics.solo(row).contains(event.getPoint())) {
            return "Solo this row. S solos the selected row. While any solo is on, only soloed rows play.";
        }
        if (metrics.laneColumn(event.getX(), event.getY()) >= 0) {
            return "Drag to set the velocity of this step on " + editor.selectedTrack().name() + ".";
        }
        int column = row >= 0 ? metrics.columnAt(event.getX(), event.getY()) : -1;
        if (column >= 0) {
            Track rowTrack = editor.viewTrack(row);
            Step step = rowTrack.step(column);
            String pitch = step.hasPitch()
                ? " Step pitch " + TrackReadout.tunedName(step.pitch(), rowTrack.tune()) + "."
                : "";
            String chance = step.uncertain() ? " Chance " + step.probability() + "%." : "";
            String held = step.extended() ? " Holds " + step.length() + " steps." : "";
            int offset = editor.viewTrack(row).playedNudge(column);
            String nudged = offset != 0 ? " Nudge " + offset + "%." : "";
            String glide = step.slide() && Slide.applies(rowTrack)
                ? " Slides in from the previous note."
                : "";
            String past = column >= editor.beat().stepCount(editor.viewSlot())
                ? " Past this pattern's length, so it does not play."
                : "";
            return KeyboardShortcuts.gridTip() + pitch + chance + held + nudged + glide + past;
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
        int columns = editor.beat().gridColumns(editor.viewSlot());
        int width = Math.max(getWidth(), getPreferredSize().width);
        int height = Math.max(getHeight(), getPreferredSize().height);
        int groups = Math.max(0, columns / 4 - 1);
        int availableWidth = width - GUTTER - 12 - groups * GROUP_GAP;
        int cellWidth = Math.max(MIN_CELL_WIDTH, availableWidth / Math.max(1, columns));
        int rows = rowCount();
        int cellHeight = Math.max(minCell(rows), (height - HEADER - LANE_RESERVE) / rows);
        return new Metrics(GUTTER, HEADER, GROUP_GAP, cellWidth, cellHeight, columns, rows);
    }

    private int rowCount() {
        return Math.max(1, editor.beat().trackCount());
    }

    /** Sixteen rows use a shorter cell so the grid can sit in a short window, and still scrolls. */
    private static int minCell(int rows) {
        if (rows >= 14) {
            return 20;
        }
        if (rows >= 11) {
            return 22;
        }
        return MIN_CELL;
    }

    /** Scrolls the selected row into view after a row is added or moved. */
    void revealSelection() {
        Metrics metrics = metrics();
        int row = Math.max(0, Math.min(metrics.rows - 1, editor.trackIndex()));
        int y = metrics.header + row * metrics.cellHeight;
        scrollRectToVisible(new Rectangle(0, y, Math.max(1, getWidth()), metrics.cellHeight));
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

    private record Metrics(int gutter, int header, int groupGap, int cellWidth, int cellHeight, int columns, int rows) {
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
            if (row < 0 || row >= rows) {
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
            return header + rows * cellHeight + 4;
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

        /** Column under {@code x}, or {@code -1} left of the grid. Gaps between bars belong to the cell on the left. */
        int columnForX(int x) {
            if (columns <= 0 || x < stepX(0)) {
                return -1;
            }
            for (int column = 0; column < columns; column++) {
                int right = column + 1 < columns ? stepX(column + 1) : stepX(column) + cellWidth;
                if (x < right) {
                    return column;
                }
            }
            return columns - 1;
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

    private static void paintHold(Graphics2D g, Rectangle cell, Color color) {
        g.setColor(color);
        int bar = Math.max(6, cell.height / 3);
        int y = cell.y + (cell.height - bar) / 2;
        g.fillRoundRect(cell.x, y, cell.width, bar, 6, 6);
    }

    /** A short tick, shifted left or right, so a nudged hit is visible on the pad. */
    private static void paintNudge(Graphics2D g, Rectangle cell, int percent, Step.Shade shade) {
        if (percent == 0) {
            return;
        }
        int travel = Math.max(2, cell.width / 2 - 4);
        int dx = (int) Math.round(travel * (percent / 50.0));
        int x = cell.x + cell.width / 2 + dx;
        x = Math.max(cell.x + 2, Math.min(cell.x + cell.width - 4, x));
        boolean bright = shade == Step.Shade.NORMAL || shade == Step.Shade.ACCENT;
        g.setColor(bright ? Theme.INK : Theme.LINE);
        g.fillRect(x, cell.y + 3, 2, Math.max(4, cell.height / 3));
    }

    /**
     * Keyboard cursor. Drawn after the cells, at full opacity, so a slide wedge,
     * velocity fill, mute, or solo cannot fade it. The cyan ring sits inside a dark
     * edge so it still reads on a bright accent.
     */
    private void paintCursor(Graphics2D g, Metrics metrics) {
        int column = editor.stepIndex();
        if (column < 0 || column >= metrics.columns) {
            return;
        }
        int row = editor.trackIndex();
        if (row < 0 || row >= metrics.rows) {
            return;
        }
        boolean focused = isFocusOwner();
        paintMark(g, metrics.step(row, column), 6, focused);
        paintMark(g, metrics.laneBar(column), 4, focused);
    }

    private static void paintMark(Graphics2D g, Rectangle cell, int arc, boolean focused) {
        Composite previous = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver);
        if (focused) {
            g.setColor(Theme.FOCUS_EDGE);
            g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(cell.x - 2, cell.y - 2, cell.width + 3, cell.height + 3, arc + 2, arc + 2);
            g.setColor(Theme.FOCUS);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(cell.x + 1, cell.y + 1, cell.width - 3, cell.height - 3, arc, arc);
        } else {
            g.setColor(Theme.FOCUS_EDGE);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(cell.x - 1, cell.y - 1, cell.width + 1, cell.height + 1, arc, arc);
            g.setColor(Theme.LINE);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(cell.x + 1, cell.y + 1, cell.width - 3, cell.height - 3, arc, arc);
        }
        g.setComposite(previous);
    }

    /**
     * Grid keys while this panel has focus. Enter toggles through {@link Editor#tap}.
     * A repeated Enter is ignored until the key is released. Returns true when the
     * grid owns the event.
     */
    boolean handleKey(KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                enterDown = false;
            }
            return false;
        }
        if (event.getKeyCode() == KeyEvent.VK_ENTER && enterDown && GridKeys.gridClaim(event)) {
            return true;
        }
        if (!GridKeys.gridClaim(event)) {
            return false;
        }
        String before = currentCellName();
        GridKeys.apply(editor, event);
        if (event.getKeyCode() == KeyEvent.VK_ENTER) {
            enterDown = true;
        }
        revealCursor();
        announce(before);
        repaint();
        return true;
    }

    private void revealCursor() {
        int columns = editor.beat().gridColumns(editor.viewSlot());
        if (columns <= 0) {
            return;
        }
        Metrics metrics = metrics();
        int row = Math.max(0, Math.min(metrics.rows - 1, editor.trackIndex()));
        int column = editor.stepIndex();
        if (column < 0 || column >= metrics.columns) {
            column = 0;
        }
        Rectangle cell = metrics.step(row, column);
        cell.grow(6, 6);
        scrollRectToVisible(cell);
    }

    private String currentCellName() {
        int rows = editor.beat().trackCount();
        int columns = editor.beat().gridColumns(editor.viewSlot());
        if (rows <= 0 || columns <= 0) {
            return GridAccess.GRID_NAME;
        }
        return GridAccess.cellName(editor.viewTrack(cursorRow(rows)), cursorColumn(columns), columns);
    }

    private int cursorRow(int rows) {
        int row = editor.trackIndex();
        if (row < 0 || row >= rows) {
            return 0;
        }
        return row;
    }

    private int cursorColumn(int columns) {
        int column = editor.stepIndex();
        if (column < 0 || column >= columns) {
            return 0;
        }
        return column;
    }

    /** Tells assistive tech the focused cell changed. The grid's own name stays "Step grid". */
    private void announce(String previous) {
        String next = currentCellName();
        if (next.equals(previous)) {
            return;
        }
        AccessibleContext grid = getAccessibleContext();
        grid.firePropertyChange(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY, previous, next);
        Accessible cell = focusedCell();
        if (cell != null) {
            cell.getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, previous, next);
            grid.firePropertyChange(AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY, null, cell);
        }
    }

    Accessible focusedCell() {
        int rows = editor.beat().trackCount();
        int columns = editor.beat().gridColumns(editor.viewSlot());
        if (rows <= 0 || columns <= 0) {
            return null;
        }
        Accessible row = getAccessibleContext().getAccessibleChild(cursorRow(rows));
        if (row == null) {
            return null;
        }
        return row.getAccessibleContext().getAccessibleChild(cursorColumn(columns));
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new GridAccessible();
        }
        return accessibleContext;
    }

    private Locale componentLocale() {
        try {
            return getLocale();
        } catch (IllegalComponentStateException exception) {
            return Locale.getDefault();
        }
    }

    private final class GridAccessible extends AccessibleJPanel {
        private RowAccessible[] rows = new RowAccessible[0];

        @Override
        public String getAccessibleName() {
            return GridAccess.GRID_NAME;
        }

        @Override
        public String getAccessibleDescription() {
            String tip = getToolTipText();
            return tip == null ? super.getAccessibleDescription() : tip;
        }

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.TABLE;
        }

        @Override
        public int getAccessibleChildrenCount() {
            return editor.beat().trackCount();
        }

        @Override
        public Accessible getAccessibleChild(int index) {
            int count = editor.beat().trackCount();
            if (index < 0 || index >= count) {
                return null;
            }
            if (rows.length != count) {
                rows = Arrays.copyOf(rows, count);
            }
            if (rows[index] == null) {
                rows[index] = new RowAccessible(index);
            }
            return rows[index];
        }
    }

    private final class RowAccessible extends AccessibleContext implements Accessible {
        private final int row;
        private CellAccessible[] cells = new CellAccessible[0];

        private RowAccessible(int row) {
            this.row = row;
            setAccessibleParent(PatternPanel.this);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            return this;
        }

        @Override
        public String getAccessibleName() {
            return GridAccess.rowName(editor.viewTrack(row));
        }

        @Override
        public String getAccessibleDescription() {
            return editor.viewTrack(row).soundName();
        }

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.LABEL;
        }

        @Override
        public AccessibleStateSet getAccessibleStateSet() {
            AccessibleStateSet states = new AccessibleStateSet();
            states.add(AccessibleState.ENABLED);
            states.add(AccessibleState.VISIBLE);
            if (row == editor.trackIndex()) {
                states.add(AccessibleState.SELECTED);
            }
            return states;
        }

        @Override
        public int getAccessibleIndexInParent() {
            return row;
        }

        @Override
        public Locale getLocale() {
            return componentLocale();
        }

        @Override
        public int getAccessibleChildrenCount() {
            return editor.beat().gridColumns(editor.viewSlot());
        }

        @Override
        public Accessible getAccessibleChild(int index) {
            int count = editor.beat().gridColumns(editor.viewSlot());
            if (index < 0 || index >= count) {
                return null;
            }
            if (cells.length < count) {
                cells = Arrays.copyOf(cells, count);
            }
            if (cells[index] == null) {
                cells[index] = new CellAccessible(row, index, this);
            }
            return cells[index];
        }
    }

    private final class CellAccessible extends AccessibleContext implements Accessible {
        private final int row;
        private final int column;

        private CellAccessible(int row, int column, Accessible parent) {
            this.row = row;
            this.column = column;
            setAccessibleParent(parent);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            return this;
        }

        @Override
        public String getAccessibleName() {
            int count = editor.beat().gridColumns(editor.viewSlot());
            return GridAccess.cellName(editor.viewTrack(row), column, count);
        }

        @Override
        public AccessibleRole getAccessibleRole() {
            return AccessibleRole.LABEL;
        }

        @Override
        public AccessibleStateSet getAccessibleStateSet() {
            AccessibleStateSet states = new AccessibleStateSet();
            states.add(AccessibleState.ENABLED);
            states.add(AccessibleState.VISIBLE);
            states.add(AccessibleState.SELECTABLE);
            states.add(AccessibleState.FOCUSABLE);
            int rows = editor.beat().trackCount();
            int columns = editor.beat().gridColumns(editor.viewSlot());
            if (rows > 0 && columns > 0 && row == cursorRow(rows) && column == cursorColumn(columns)
                && editor.stepIndex() == column) {
                states.add(AccessibleState.SELECTED);
                if (isFocusOwner()) {
                    states.add(AccessibleState.FOCUSED);
                }
            }
            return states;
        }

        @Override
        public int getAccessibleIndexInParent() {
            return column;
        }

        @Override
        public Locale getLocale() {
            return componentLocale();
        }

        @Override
        public int getAccessibleChildrenCount() {
            return 0;
        }

        @Override
        public Accessible getAccessibleChild(int index) {
            return null;
        }
    }

    private static final class Gesture {
        final int row;
        final int column;
        final boolean lane;
        final boolean selectOnly;
        final boolean shift;
        final boolean canStretch;
        final int x;
        final int y;
        final int startVelocity;
        final char slot;
        boolean dragging;
        boolean stretch;

        private Gesture(
            int row,
            int column,
            boolean lane,
            boolean selectOnly,
            boolean shift,
            boolean canStretch,
            int x,
            int y,
            int startVelocity,
            char slot
        ) {
            this.row = row;
            this.column = column;
            this.lane = lane;
            this.selectOnly = selectOnly;
            this.shift = shift;
            this.canStretch = canStretch;
            this.x = x;
            this.y = y;
            this.startVelocity = startVelocity;
            this.slot = slot;
            this.dragging = lane;
        }

        static Gesture pad(int row, int column, boolean shift, int x, int y, int startVelocity, char slot, boolean on) {
            return new Gesture(row, column, false, false, shift, on, x, y, startVelocity, slot);
        }

        static Gesture lane(int row, int column, char slot) {
            return new Gesture(row, column, true, false, false, false, 0, 0, 0, slot);
        }

        static Gesture selectOnly(int row, int column) {
            return new Gesture(row, column, false, true, false, false, 0, 0, 0, 'a');
        }
    }
}
