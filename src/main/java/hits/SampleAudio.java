package hits;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads a sample down to mono 16-bit and writes a mono WAV. No extra libraries.
 * <p>
 * A file is cut at {@link #MAX_SECONDS} of its own rate and channel count.
 * The loader keeps that rate, so nothing is resampled here, and the SoundFont's
 * interpolator pad is silence added later. Only those frames are read.
 * A longer recording keeps the same leading audio a full read used to keep.
 */
public final class SampleAudio {
    /** About ten seconds. Longer files are cut so one row cannot fill the bank. */
    public static final double MAX_SECONDS = 10;
    /** A file larger than this is refused before any audio is decoded. */
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    /**
     * Source bytes actually read for one sample.
     * Ten seconds of a normal recording is a few megabytes. This stops a huge
     * rate or channel count from allocating the whole cap.
     */
    public static final long MAX_READ_BYTES = 64L * 1024 * 1024;
    /** A header that claims more audio than this is refused. */
    public static final long MAX_DECLARED_BYTES = 512L * 1024 * 1024;
    /** How long a read may take before it is abandoned. */
    public static final long MAX_READ_MILLIS = 5_000;
    /** One-shots do not need a huge bus. */
    public static final int MAX_CHANNELS = 16;
    /** Rates above this are refused. */
    public static final int MAX_RATE = 384_000;

    /**
     * Extra source frames after the cap. The file stays at its own rate, so no
     * resampler runs, and the crossfade used for held notes is not applied to a
     * loaded sample. The read stops at the cap.
     */
    private static final int READ_MARGIN_FRAMES = 0;
    private static final int MAX_CHUNKS = 128;
    private static final int MAX_FMT_BYTES = 1024;
    private static final int MAX_BLOCK_ALIGN = 64;

    static final String EMPTY = "This sample file is empty.";
    static final String TOO_LARGE = "This sample file is too large to open.";
    static final String BAD_LENGTH = "This sample file's header says the audio length is not usable.";
    static final String NO_AUDIO = "This sample file has no audio.";
    static final String FAR_TOO_LONG = "This sample file's header says the audio is far too long.";
    static final String CUT_OFF = "This sample file is cut off.";
    static final String BAD_HEADER = "This sample file's header is not usable.";
    static final String UNSUPPORTED = "Hits cannot play this kind of audio file. Use a WAV file.";
    static final String TOO_SLOW = "This sample took too long to read.";

    private SampleAudio() {}

    public record Clip(int rate, short[] samples) {}

    /** Why {@code path} cannot be loaded, or null when the header is usable. Does not decode the audio. */
    public static String rejection(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            probe(path);
            return null;
        } catch (IOException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                return UNSUPPORTED;
            }
            return message;
        }
    }

    public static Clip read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Sample file was not found");
        }
        Probe header = probe(path);
        long deadline = System.nanoTime() + MAX_READ_MILLIS * 1_000_000L;
        if (header.wave()) {
            byte[] slice = slice(path, header, deadline);
            try (ByteArrayInputStream in = new ByteArrayInputStream(slice);
                 AudioInputStream stream = AudioSystem.getAudioInputStream(in)) {
                return read(stream, deadline);
            } catch (UnsupportedAudioFileException exception) {
                throw new IOException(UNSUPPORTED, exception);
            }
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            return read(stream, deadline);
        } catch (UnsupportedAudioFileException exception) {
            throw new IOException(UNSUPPORTED, exception);
        }
    }

    static Clip read(AudioInputStream stream) throws IOException {
        return read(stream, System.nanoTime() + MAX_READ_MILLIS * 1_000_000L);
    }

    /** {@code deadlineNanos} is {@link System#nanoTime()} when the read must already have finished. */
    static Clip read(AudioInputStream stream, long deadlineNanos) throws IOException {
        AudioFormat source = stream.getFormat();
        if (source.getChannels() <= 0 || source.getChannels() > MAX_CHANNELS
            || !(source.getSampleRate() > 0) || source.getSampleRate() > MAX_RATE) {
            throw new IOException(BAD_HEADER);
        }
        AudioFormat decoded = decode(source);
        long declared = stream.getFrameLength();
        int rate = Math.max(1, Math.round(decoded.getSampleRate()));
        int cap = Math.max(1, (int) Math.round(rate * MAX_SECONDS));
        int framesToRead = cap + READ_MARGIN_FRAMES;
        if (declared > 0 && declared < framesToRead) {
            framesToRead = (int) declared;
        }
        int channels = decoded.getChannels();
        int frame = channels * 2;
        long byteCount = (long) framesToRead * frame;
        if (frame <= 0 || byteCount <= 0 || byteCount > MAX_READ_BYTES) {
            throw new IOException(BAD_HEADER);
        }
        AudioInputStream pcm;
        try {
            pcm = AudioSystem.getAudioInputStream(decoded, stream);
        } catch (IllegalArgumentException exception) {
            throw new IOException(UNSUPPORTED, exception);
        }
        try {
            byte[] bytes = new byte[(int) byteCount];
            int got = readAtMost(pcm, bytes, deadlineNanos);
            int frames = got / frame;
            if (frames < 1) {
                throw new IOException(NO_AUDIO);
            }
            if (declared > frames && frames < cap) {
                throw new IOException(CUT_OFF);
            }
            int kept = Math.min(frames, cap);
            short[] mono = new short[kept];
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, kept * frame).order(
                decoded.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
            );
            for (int frameIndex = 0; frameIndex < kept; frameIndex++) {
                int sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    sum += buffer.getShort();
                }
                mono[frameIndex] = (short) (sum / channels);
            }
            return new Clip(rate, mono);
        } finally {
            if (pcm != stream) {
                pcm.close();
            }
        }
    }

    public static void writeWav(Path path, int rate, short[] samples) throws IOException {
        if (path == null) {
            throw new IOException("Choose a file for the sample");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int count = samples == null ? 0 : samples.length;
        int data = count * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(36 + data);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(rate);
        buffer.putInt(rate * 2);
        buffer.putShort((short) 2);
        buffer.putShort((short) 16);
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(data);
        if (samples != null) {
            for (short sample : samples) {
                buffer.putShort(sample);
            }
        }
        Files.write(path, buffer.array());
    }

    /** Signed 16-bit PCM at the source rate, keeping the channel count for the downmix. */
    private static AudioFormat decode(AudioFormat source) {
        int channels = Math.max(1, source.getChannels());
        float rate = source.getSampleRate() > 0 ? source.getSampleRate() : BuiltinSamples.RATE;
        return new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            rate,
            16,
            channels,
            channels * 2,
            rate,
            false
        );
    }

    /** A clip already in memory, for tests that do not want a file. */
    public static Clip clip(int rate, short[] samples) throws IOException {
        byte[] wav = wavBytes(rate, samples);
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            return read(stream);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not read the sample", exception);
        }
    }

    private static byte[] wavBytes(int rate, short[] samples) throws IOException {
        Path temp = Files.createTempFile("hits-sample", ".wav");
        try {
            writeWav(temp, rate, samples);
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private record Probe(boolean wave, long dataOffset, long dataBytes, int blockAlign, int rate, byte[] fmt) {}

    private static Probe probe(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0) {
            throw new IOException(EMPTY);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IOException(TOO_LARGE);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (size < 12) {
                byte[] start = readAt(channel, 0, (int) size);
                if (start != null && start.length >= 4 && id(start, 0, 'R', 'I', 'F', 'F')) {
                    throw new IOException(CUT_OFF);
                }
                throw new IOException(UNSUPPORTED);
            }
            byte[] riff = readAt(channel, 0, 12);
            if (riff == null) {
                throw new IOException(CUT_OFF);
            }
            if (!isRiffWave(riff)) {
                probeForeign(path, size);
                return new Probe(false, 0, 0, 0, 0, null);
            }
            return probeWave(channel, size);
        }
    }

    private static Probe probeWave(FileChannel channel, long size) throws IOException {
        boolean sawFmt = false;
        boolean sawData = false;
        boolean endedEarly = false;
        int format = -1;
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int blockAlign = 0;
        byte[] fmt = null;
        long dataOffset = 0;
        long dataBytes = 0;
        long cursor = 12;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            if (sawFmt && sawData) {
                break;
            }
            if (cursor + 8 > size) {
                endedEarly = cursor < size;
                break;
            }
            byte[] head = readAt(channel, cursor, 8);
            if (head == null) {
                endedEarly = true;
                break;
            }
            int signed = intLe(head, 4);
            long unsigned = signed & 0xffffffffL;
            long payload = cursor + 8;
            boolean data = id(head, 0, 'd', 'a', 't', 'a');
            boolean fmtChunk = id(head, 0, 'f', 'm', 't', ' ');
            if (data) {
                if (signed < 0) {
                    throw new IOException(BAD_LENGTH);
                }
                if (signed == 0) {
                    throw new IOException(NO_AUDIO);
                }
                if (unsigned > MAX_DECLARED_BYTES) {
                    throw new IOException(FAR_TOO_LONG);
                }
                if (payload + unsigned > size) {
                    throw new IOException(CUT_OFF);
                }
                sawData = true;
                dataOffset = payload;
                dataBytes = unsigned;
            } else {
                if (signed < 0 || unsigned > MAX_DECLARED_BYTES || payload + unsigned > size) {
                    throw new IOException(BAD_HEADER);
                }
                if (fmtChunk) {
                    if (unsigned < 16 || unsigned > MAX_FMT_BYTES) {
                        throw new IOException(BAD_HEADER);
                    }
                    fmt = readAt(channel, payload, (int) unsigned);
                    if (fmt == null) {
                        throw new IOException(CUT_OFF);
                    }
                    format = u16(fmt, 0);
                    channels = u16(fmt, 2);
                    rate = intLe(fmt, 4);
                    blockAlign = u16(fmt, 12);
                    bits = u16(fmt, 14);
                    sawFmt = true;
                }
            }
            long padded = unsigned + (unsigned & 1L);
            long next = payload + padded;
            if (next <= cursor) {
                throw new IOException(BAD_HEADER);
            }
            if (next > size) {
                next = size;
            }
            cursor = next;
        }
        if (!sawFmt || (endedEarly && !sawData)) {
            throw new IOException(CUT_OFF);
        }
        if (!sawData) {
            throw new IOException(NO_AUDIO);
        }
        if (!supported(format)) {
            throw new IOException(UNSUPPORTED);
        }
        if (channels <= 0 || channels > MAX_CHANNELS
            || rate <= 0 || rate > MAX_RATE
            || bits <= 0 || bits > 64
            || blockAlign <= 0 || blockAlign > MAX_BLOCK_ALIGN) {
            throw new IOException(BAD_HEADER);
        }
        if (dataBytes < blockAlign) {
            throw new IOException(NO_AUDIO);
        }
        long frames = dataBytes / blockAlign;
        long want = Math.min(frames, Math.round(rate * MAX_SECONDS) + READ_MARGIN_FRAMES);
        if (want < 1 || want * blockAlign > MAX_READ_BYTES) {
            throw new IOException(BAD_HEADER);
        }
        return new Probe(true, dataOffset, dataBytes, blockAlign, rate, fmt);
    }

    private static void probeForeign(Path path, long size) throws IOException {
        AudioFileFormat file;
        try {
            file = AudioSystem.getAudioFileFormat(path.toFile());
        } catch (UnsupportedAudioFileException exception) {
            throw new IOException(UNSUPPORTED, exception);
        }
        AudioFormat format = file.getFormat();
        long frames = file.getFrameLength();
        int frame = format.getFrameSize();
        if (format.getChannels() <= 0 || format.getChannels() > MAX_CHANNELS
            || !(format.getSampleRate() > 0) || format.getSampleRate() > MAX_RATE
            || frame <= 0 || frame > MAX_BLOCK_ALIGN) {
            throw new IOException(BAD_HEADER);
        }
        if (frames == 0) {
            throw new IOException(NO_AUDIO);
        }
        if (frames < 0 && frames != AudioSystem.NOT_SPECIFIED) {
            throw new IOException(BAD_LENGTH);
        }
        if (frames > 0) {
            long declared = frames * frame;
            if (declared > MAX_DECLARED_BYTES) {
                throw new IOException(FAR_TOO_LONG);
            }
            if (declared > size) {
                throw new IOException(CUT_OFF);
            }
            long cap = Math.round(format.getSampleRate() * MAX_SECONDS) + READ_MARGIN_FRAMES;
            long want = Math.min(frames, cap);
            if (want < 1 || want * frame > MAX_READ_BYTES) {
                throw new IOException(BAD_HEADER);
            }
        }
    }

    private static byte[] slice(Path path, Probe header, long deadlineNanos) throws IOException {
        if (System.nanoTime() > deadlineNanos) {
            throw new IOException(TOO_SLOW);
        }
        long frames = header.dataBytes / header.blockAlign;
        long cap = Math.round(header.rate * MAX_SECONDS) + READ_MARGIN_FRAMES;
        long want = Math.min(frames, cap);
        int bytes = (int) (want * header.blockAlign);
        byte[] data = readRange(path, header.dataOffset, bytes, deadlineNanos);
        return wavSlice(header.fmt, data);
    }

    private static byte[] wavSlice(byte[] fmt, byte[] data) {
        int fmtPad = fmt.length & 1;
        int dataPad = data.length & 1;
        int riff = 4 + (8 + fmt.length + fmtPad) + (8 + data.length + dataPad);
        ByteBuffer buffer = ByteBuffer.allocate(8 + riff).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(riff);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(fmt.length);
        buffer.put(fmt);
        if (fmtPad == 1) {
            buffer.put((byte) 0);
        }
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(data.length);
        buffer.put(data);
        if (dataPad == 1) {
            buffer.put((byte) 0);
        }
        return buffer.array();
    }

    private static byte[] readRange(Path path, long offset, int count, long deadlineNanos) throws IOException {
        byte[] data = new byte[count];
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            while (buffer.hasRemaining()) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new IOException(TOO_SLOW);
                }
                // The buffer position is how many payload bytes are already in hand.
                int n = channel.read(buffer, offset + buffer.position());
                if (n < 0) {
                    throw new IOException(CUT_OFF);
                }
                if (n == 0) {
                    throw new IOException(TOO_SLOW);
                }
            }
        }
        return data;
    }

    private static int readAtMost(AudioInputStream in, byte[] bytes, long deadlineNanos) throws IOException {
        int got = 0;
        int zeros = 0;
        while (got < bytes.length) {
            if (System.nanoTime() > deadlineNanos) {
                throw new IOException(TOO_SLOW);
            }
            int n = in.read(bytes, got, bytes.length - got);
            if (n < 0) {
                break;
            }
            if (n == 0) {
                if (++zeros > 8) {
                    throw new IOException(TOO_SLOW);
                }
                continue;
            }
            zeros = 0;
            got += n;
        }
        return got;
    }

    private static byte[] readAt(FileChannel channel, long position, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        long at = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, at);
            if (n < 0) {
                return null;
            }
            if (n == 0) {
                return null;
            }
            at += n;
        }
        return buffer.array();
    }

    private static boolean isRiffWave(byte[] header) {
        return id(header, 0, 'R', 'I', 'F', 'F') && id(header, 8, 'W', 'A', 'V', 'E');
    }

    private static boolean id(byte[] raw, int offset, char a, char b, char c, char d) {
        return raw[offset] == a && raw[offset + 1] == b && raw[offset + 2] == c && raw[offset + 3] == d;
    }

    private static boolean supported(int format) {
        return format == 1 || format == 3 || format == 6 || format == 7 || format == 0xFFFE;
    }

    private static int u16(byte[] raw, int offset) {
        return (raw[offset] & 0xff) | ((raw[offset + 1] & 0xff) << 8);
    }

    private static int intLe(byte[] raw, int offset) {
        return (raw[offset] & 0xff)
            | ((raw[offset + 1] & 0xff) << 8)
            | ((raw[offset + 2] & 0xff) << 16)
            | (raw[offset + 3] << 24);
    }
}
