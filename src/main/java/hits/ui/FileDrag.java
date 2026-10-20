package hits.ui;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.AWTEvent;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.dnd.InvalidDnDOperationException;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A chip that starts a copy drag of one existing file.
 * The file is written before {@link TransferHandler#exportAsDrag} so the native side
 * can offer {@link DataFlavor#javaFileListFlavor} for a path that is already on disk.
 */
final class FileDrag {
    private static boolean listening;
    private static boolean leftDown;

    private Path armed;
    private boolean busy;
    private boolean dragActive;

    static JComponent chip(String text) {
        JLabel chip = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                graphics.setColor(getBackground());
                graphics.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(graphics);
            }
        };
        chip.setOpaque(false);
        chip.setBackground(Theme.STEP_OFF);
        chip.setForeground(Theme.TEXT);
        chip.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Theme.GRID_LINE),
            BorderFactory.createEmptyBorder(6, 10, 6, 10)
        ));
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        return chip;
    }

    /** Drops a prepared file when the beat or the export choices change. */
    void disarm() {
        armed = null;
    }

    /**
     * {@code prepare} writes the temp file on the event thread and may show a modal progress dialog.
     * {@code held} runs when that dialog outlives the mouse button, so the next drag can use the file.
     */
    void install(JComponent chip, Supplier<Path> prepare, Runnable held) {
        listenForButton();
        Path[] ready = new Path[1];
        TransferHandler handler = new TransferHandler() {
            @Override
            public int getSourceActions(JComponent component) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent component) {
                if (ready[0] == null) {
                    return null;
                }
                return files(List.of(ready[0].toFile()));
            }

            @Override
            protected void exportDone(JComponent source, Transferable data, int action) {
                dragActive = false;
                ready[0] = null;
            }
        };
        chip.setTransferHandler(handler);
        MouseAdapter gesture = new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent event) {
                if (busy || dragActive || !SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                busy = true;
                try {
                    Path path = armed;
                    if (path == null || !Files.isRegularFile(path)) {
                        path = prepare.get();
                    }
                    if (path == null || !Files.isRegularFile(path)) {
                        armed = null;
                        return;
                    }
                    // Keep it until the beat or the export choices change. A second drag
                    // then skips rendering, which matters after the WAV progress window.
                    armed = path;
                    if (!leftDown) {
                        if (held != null) {
                            held.run();
                        }
                        return;
                    }
                    ready[0] = path;
                    dragActive = true;
                    try {
                        handler.exportAsDrag(chip, event, TransferHandler.COPY);
                    } catch (InvalidDnDOperationException exception) {
                        dragActive = false;
                        ready[0] = null;
                        if (held != null) {
                            held.run();
                        }
                    }
                    // A progress dialog can swallow the gesture. If the button is already up
                    // when exportAsDrag returns, the chip must still accept the next drag.
                    if (!leftDown) {
                        dragActive = false;
                        ready[0] = null;
                    }
                } finally {
                    busy = false;
                }
            }
        };
        chip.addMouseListener(gesture);
        chip.addMouseMotionListener(gesture);
    }

    /** The file list Swing hands to a DAW or a file manager. */
    static Transferable files(List<File> files) {
        return new FileListTransferable(files);
    }

    private static void listenForButton() {
        if (listening) {
            return;
        }
        listening = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent mouse)) {
                return;
            }
            if (mouse.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(mouse)) {
                leftDown = true;
            } else if (mouse.getID() == MouseEvent.MOUSE_RELEASED && SwingUtilities.isLeftMouseButton(mouse)) {
                leftDown = false;
            }
        }, AWTEvent.MOUSE_EVENT_MASK);
    }

    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        private FileListTransferable(List<File> files) {
            this.files = new ArrayList<>(files);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.javaFileListFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return new ArrayList<>(files);
        }
    }
}
