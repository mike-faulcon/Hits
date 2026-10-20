package hits.ui;

import org.junit.jupiter.api.Test;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDragTest {
    @Test
    void dragOffersTheJavaFileListFlavor() throws Exception {
        File file = new File("AlbumTrackv3-song.mid");
        Transferable data = FileDrag.files(List.of(file));
        assertTrue(data.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
        assertFalse(data.isDataFlavorSupported(DataFlavor.stringFlavor));
        DataFlavor[] flavors = data.getTransferDataFlavors();
        assertEquals(1, flavors.length);
        assertEquals(DataFlavor.javaFileListFlavor, flavors[0]);

        @SuppressWarnings("unchecked")
        List<File> first = (List<File>) data.getTransferData(DataFlavor.javaFileListFlavor);
        assertEquals(List.of(file), first);
        first.clear();
        @SuppressWarnings("unchecked")
        List<File> second = (List<File>) data.getTransferData(DataFlavor.javaFileListFlavor);
        assertEquals(List.of(file), second);
        assertThrows(UnsupportedFlavorException.class, () -> data.getTransferData(DataFlavor.stringFlavor));
    }
}
