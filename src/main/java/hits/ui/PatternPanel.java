package hits.ui;

import hits.Beat;
import hits.Editor;
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

/** Track names, mute, solo, and the step grid, kept in one component so rows stay aligned. */
final class PatternPanel extends JPanel implements Scrollable {
    private final Editor editor;
    private int playhead = -1;

    PatternPanel(Editor editor) {
        this.editor = editor;
        setOpaque(true);
        setFocusable(true);
        setBackground(Theme.BG);
        setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        setToolTipText("Click a step. Shift-click makes a loud hit. Right-click or Alt-click selects it.");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                handle(event);
            }
        });
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
        return new Dimension(248 + columns * 30 + groups * 12, 36 + Beat.TRACKS * 40);
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
        Track track = editor.beat().track(row);
        int y = metrics.header + row * metrics.cellHeight;
        if (row == editor.trackIndex()) {
            g.setColor(Theme.SELECT);
            g.fillRect(0, y, getWidth(), metrics.cellHeight);
        }
        Composite previous = g.getComposite();
        if (!editor.beat().audible(track)) {
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
            g.setColor(colorFor(track.step(column).shade(track.accent())));
            g.fillRoundRect(cell.x, cell.y, cell.width, cell.height, 6, 6);
            if (row == editor.trackIndex() && column == editor.stepIndex()) {
                g.setColor(Theme.LINE);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(cell.x + 1, cell.y + 1, cell.width - 3, cell.height - 3, 6, 6);
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

    private void handle(MouseEvent event) {
        Metrics metrics = metrics();
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
                return;
            }
            editor.tap(row, column, event.isShiftDown());
            return;
        }
        editor.selectTrack(row);
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
            return "Solo this track. Other tracks go quiet.";
        }
        if (row >= 0 && metrics.columnAt(event.getX(), event.getY()) >= 0) {
            return "Click toggles the step. Shift-click makes a loud hit. Right-click or Alt-click selects it so you can change its volume.";
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
        int cellHeight = Math.max(40, (height - header - 8) / Beat.TRACKS);
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
            for (int column = 0; column < columns; column++) {
                Rectangle hit = new Rectangle(stepX(column), header + row * cellHeight, cellWidth, cellHeight);
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
}
