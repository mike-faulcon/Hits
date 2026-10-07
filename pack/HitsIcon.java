import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws the Hits dock icon: a dark step grid with a few pads lit.
 * Writes PNG for Linux, ICO for Windows, and ICNS for macOS.
 */
public final class HitsIcon {
    private static final Color BACKGROUND = new Color(0x1A1C1F);
    private static final Color PAD_OFF = new Color(0x2C3036);
    private static final Color PAD_ON = new Color(0xE0A020);
    private static final Color PAD_HOT = new Color(0xFFD27A);
    private static final boolean[] ON = {
        true, false, true, false,
        false, false, true, false,
        true, false, false, true,
        false, true, false, true
    };

    private HitsIcon() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: HitsIcon <output-directory>");
        }
        Path directory = Path.of(args[0]);
        Files.createDirectories(directory);
        Map<Integer, BufferedImage> images = new LinkedHashMap<>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256, 512, 1024}) {
            images.put(size, draw(size));
        }
        ImageIO.write(images.get(512), "png", directory.resolve("hits.png").toFile());
        Files.write(directory.resolve("hits.ico"), ico(
            images.get(16),
            images.get(32),
            images.get(48),
            images.get(256)
        ));
        Map<String, byte[]> icons = new LinkedHashMap<>();
        icons.put("icp4", png(images.get(16)));
        icons.put("icp5", png(images.get(32)));
        icons.put("icp6", png(images.get(64)));
        icons.put("ic07", png(images.get(128)));
        icons.put("ic08", png(images.get(256)));
        icons.put("ic09", png(images.get(512)));
        icons.put("ic10", png(images.get(1024)));
        icons.put("ic11", png(images.get(32)));
        icons.put("ic12", png(images.get(64)));
        icons.put("ic13", png(images.get(256)));
        icons.put("ic14", png(images.get(512)));
        Files.write(directory.resolve("hits.icns"), icns(icons));
    }

    static BufferedImage draw(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);
            int arc = Math.max(2, size / 5);
            g.setColor(BACKGROUND);
            g.fillRoundRect(0, 0, size, size, arc, arc);
            int margin = Math.max(1, size / 8);
            int gap = Math.max(1, size / 28);
            int columns = 4;
            int grid = size - margin * 2;
            int cell = (grid - gap * (columns - 1)) / columns;
            if (cell < 1) {
                cell = 1;
            }
            int used = cell * columns + gap * (columns - 1);
            int origin = (size - used) / 2;
            int padArc = Math.max(1, cell / 4);
            for (int index = 0; index < ON.length; index++) {
                int column = index % columns;
                int row = index / columns;
                int x = origin + column * (cell + gap);
                int y = origin + row * (cell + gap);
                g.setColor(ON[index] ? PAD_ON : PAD_OFF);
                g.fillRoundRect(x, y, cell, cell, padArc, padArc);
                if (ON[index] && cell >= 8) {
                    g.setColor(PAD_HOT);
                    int inset = Math.max(1, cell / 6);
                    int shine = Math.max(1, cell / 5);
                    g.fillRoundRect(x + inset, y + inset, cell - inset * 2, shine, padArc, padArc);
                }
            }
            if (size >= 32) {
                g.setColor(new Color(0xE0A020));
                g.setStroke(new BasicStroke(Math.max(1f, size / 64f)));
                int inset = Math.max(1, size / 64);
                g.drawRoundRect(inset, inset, size - inset * 2 - 1, size - inset * 2 - 1, arc, arc);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IllegalStateException("PNG writer is missing");
        }
        return out.toByteArray();
    }

    /** BMP-in-ICO. Windows and WiX read this without a PNG-only icon. */
    private static byte[] ico(BufferedImage... images) {
        int count = images.length;
        byte[][] frames = new byte[count][];
        int bytes = 6 + 16 * count;
        for (int index = 0; index < count; index++) {
            frames[index] = dib(images[index]);
            bytes += frames[index].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) count);
        int offset = 6 + 16 * count;
        for (int index = 0; index < count; index++) {
            int width = images[index].getWidth();
            int height = images[index].getHeight();
            buffer.put((byte) (width >= 256 ? 0 : width));
            buffer.put((byte) (height >= 256 ? 0 : height));
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            buffer.putShort((short) 1);
            buffer.putShort((short) 32);
            buffer.putInt(frames[index].length);
            buffer.putInt(offset);
            offset += frames[index].length;
        }
        for (byte[] frame : frames) {
            buffer.put(frame);
        }
        return buffer.array();
    }

    private static byte[] dib(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int maskRow = ((width + 31) / 32) * 4;
        int maskBytes = maskRow * height;
        int pixelBytes = width * height * 4;
        ByteBuffer buffer = ByteBuffer.allocate(40 + pixelBytes + maskBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(40);
        buffer.putInt(width);
        buffer.putInt(height * 2);
        buffer.putShort((short) 1);
        buffer.putShort((short) 32);
        buffer.putInt(0);
        buffer.putInt(pixelBytes + maskBytes);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) argb);
                buffer.put((byte) (argb >>> 8));
                buffer.put((byte) (argb >>> 16));
                buffer.put((byte) (argb >>> 24));
            }
        }
        buffer.put(new byte[maskBytes]);
        return buffer.array();
    }

    /** PNG images inside an icns container. macOS accepts these types from 10.7 on. */
    private static byte[] icns(Map<String, byte[]> images) {
        int bytes = 8;
        for (byte[] image : images.values()) {
            bytes += 8 + image.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.BIG_ENDIAN);
        buffer.put("icns".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(bytes);
        for (Map.Entry<String, byte[]> image : images.entrySet()) {
            byte[] type = image.getKey().getBytes(StandardCharsets.US_ASCII);
            if (type.length != 4) {
                throw new IllegalArgumentException(image.getKey());
            }
            buffer.put(type);
            buffer.putInt(8 + image.getValue().length);
            buffer.put(image.getValue());
        }
        return buffer.array();
    }
}
