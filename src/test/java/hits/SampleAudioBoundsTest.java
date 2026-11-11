package hits;

import com.sun.management.ThreadMXBean;
import hits.cli.HitsCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 10 second cap, bad headers, and a long file that must not be read whole.
 * Before this bound, a 10 minute 44.1 kHz stereo file took about 153 ms and
 * allocated 212,906,176 bytes on the loading thread.
 */
class SampleAudioBoundsTest {
    @TempDir
    Path temp;

    @Test
    void tenMinuteFileLoadsWithinTheCap() throws Exception {
        int rate = 44100;
        long frames = (long) rate * 600;
        Path wav = temp.resolve("ten-minutes.wav");
        writeStereo(wav, rate, frames, index -> (short) ((index * 3) & 0x7fff), index -> (short) ((index * 5) & 0x7fff));
        assertNull(SampleAudio.rejection(wav));
        Path warm = temp.resolve("warm.wav");
        SampleAudio.writeWav(warm, rate, new short[] {1, -1, 2});
        SampleAudio.read(warm);

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long before = threads.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        SampleAudio.Clip clip = SampleAudio.read(wav);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        int cap = (int) Math.round(rate * SampleAudio.MAX_SECONDS);
        System.out.println("10 min load " + elapsedMs + " ms, allocated " + allocated + " bytes, frames " + clip.samples().length);
        assertEquals(rate, clip.rate());
        assertEquals(cap, clip.samples().length);
        assertEquals(stereoAt(0), clip.samples()[0]);
        assertEquals(stereoAt(cap / 2), clip.samples()[cap / 2]);
        assertEquals(stereoAt(cap - 1), clip.samples()[cap - 1]);
        assertTrue(elapsedMs < 2_000, "took " + elapsedMs + " ms");
        assertTrue(allocated < 16L * 1024 * 1024, "allocated " + allocated);
    }

    @Test
    void exactlyTenSecondsKeepsEveryFrame() throws Exception {
        int rate = 8000;
        int cap = (int) Math.round(rate * SampleAudio.MAX_SECONDS);
        short[] pcm = ramp(cap, 997);
        Path wav = temp.resolve("exact.wav");
        SampleAudio.writeWav(wav, rate, pcm);
        SampleAudio.Clip clip = SampleAudio.read(wav);
        SampleAudio.Clip full = legacy(wav);
        assertEquals(rate, clip.rate());
        assertEquals(cap, clip.samples().length);
        assertArrayEquals(pcm, clip.samples());
        assertArrayEquals(full.samples(), clip.samples());
        assertEquals(pcm[cap - 1], clip.samples()[cap - 1]);
    }

    @Test
    void oneFramePastTheCapDropsOnlyTheExtraFrame() throws Exception {
        int rate = 8000;
        int cap = (int) Math.round(rate * SampleAudio.MAX_SECONDS);
        long frames = cap + 1L;
        Path wav = temp.resolve("just-over.wav");
        writeStereo(
            wav,
            rate,
            frames,
            index -> (short) (1000 + (index % 50)),
            index -> (short) (index == cap ? 12345 : -2000 - (index % 50))
        );
        SampleAudio.Clip clip = SampleAudio.read(wav);
        SampleAudio.Clip full = legacy(wav);
        assertEquals(cap, clip.samples().length);
        assertEquals(full.rate(), clip.rate());
        assertArrayEquals(full.samples(), clip.samples());
        assertEquals(mixed(1000 + ((cap - 1L) % 50), -2000 - ((cap - 1L) % 50)), clip.samples()[cap - 1]);
        short extra = mixed(1000 + (cap % 50L), 12345);
        for (short sample : clip.samples()) {
            assertTrue(sample != extra, "kept the frame past the cap");
        }
    }

    @Test
    void shorterFilesMatchAFullDecode() throws Exception {
        Path tone = Path.of("src/test/resources/samples/tone.wav");
        assertArrayEquals(legacy(tone).samples(), SampleAudio.read(tone).samples());

        Path stereo = temp.resolve("stereo.wav");
        writeStereo(stereo, 22050, 1000, index -> (short) (index * 3), index -> (short) (-index * 5));
        assertArrayEquals(legacy(stereo).samples(), SampleAudio.read(stereo).samples());

        Path eight = temp.resolve("eight.wav");
        writePcm(eight, 16000, 1, 8, new int[] {0, 128, 255, 64, 200});
        assertArrayEquals(legacy(eight).samples(), SampleAudio.read(eight).samples());

        Path wide = temp.resolve("wide.wav");
        writePcm(wide, 32000, 1, 24, new int[] {0, 256, -256, 8388607, -8388608, 32767, 1000});
        assertArrayEquals(legacy(wide).samples(), SampleAudio.read(wide).samples());

        Path au = temp.resolve("tone.au");
        writeAu(au, 16000, new short[] {1000, -1000, 0, 32000});
        assertArrayEquals(legacy(au).samples(), SampleAudio.read(au).samples());
    }

    @Test
    void lyingHeaderIsRefusedBeforeTheAudioIsRead() throws Exception {
        Path wav = patched(temp.resolve("lie.wav"), new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f});
        assertRefused(wav, SampleAudio.FAR_TOO_LONG);
    }

    @Test
    void negativeLengthIsRefused() throws Exception {
        Path wav = patched(temp.resolve("negative.wav"), new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        assertRefused(wav, SampleAudio.BAD_LENGTH);
    }

    @Test
    void zeroLengthHasNoAudio() throws Exception {
        Path wav = patched(temp.resolve("zero.wav"), new byte[] {0, 0, 0, 0});
        assertRefused(wav, SampleAudio.NO_AUDIO);
    }

    @Test
    void truncatedFileIsRefused() throws Exception {
        Path wav = temp.resolve("cut.wav");
        SampleAudio.writeWav(wav, 44100, ramp((int) (44100 * 0.5), 17));
        try (FileChannel channel = FileChannel.open(wav, StandardOpenOption.WRITE)) {
            channel.truncate(44 + 20);
        }
        assertRefused(wav, SampleAudio.CUT_OFF);
        Beat beat = beatUsing(wav);
        String problem = SamplePaths.problem(beat.track(0), temp.resolve("cut.json"));
        assertEquals(SampleAudio.CUT_OFF + " This row plays " + Notes.name(beat.track(0).note()) + ".", problem);
        assertCli(beat, wav.getFileName().toString(), SampleAudio.CUT_OFF);
    }

    @Test
    void emptyUnsupportedAndHugeFilesArePlainErrors() throws Exception {
        Path empty = temp.resolve("empty.wav");
        Files.write(empty, new byte[0]);
        assertRefused(empty, SampleAudio.EMPTY);

        Path text = temp.resolve("notes.wav");
        Files.writeString(text, "not audio");
        assertRefused(text, SampleAudio.UNSUPPORTED);

        Path adpcm = temp.resolve("adpcm.wav");
        SampleAudio.writeWav(adpcm, 8000, new short[] {1, 2, 3, 4});
        byte[] raw = Files.readAllBytes(adpcm);
        raw[20] = 2;
        raw[21] = 0;
        Files.write(adpcm, raw);
        assertRefused(adpcm, SampleAudio.UNSUPPORTED);

        Path huge = temp.resolve("huge.wav");
        try (FileChannel channel = FileChannel.open(
            huge,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.SPARSE
        )) {
            channel.position(SampleAudio.MAX_FILE_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        long started = System.nanoTime();
        IOException rejected = assertThrows(IOException.class, () -> SampleAudio.read(huge));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(SampleAudio.TOO_LARGE, rejected.getMessage());
        assertTrue(elapsedMs < 1_000, "size check took " + elapsedMs + " ms");
    }

    @Test
    void aStalledReadIsAbandoned() throws Exception {
        Path wav = temp.resolve("small.wav");
        SampleAudio.writeWav(wav, 8000, new short[] {5, -5, 9});
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(wav.toFile())) {
            IOException rejected = assertThrows(IOException.class, () -> SampleAudio.read(stream, System.nanoTime() - 1));
            assertEquals(SampleAudio.TOO_SLOW, rejected.getMessage());
        }
    }

    private void assertRefused(Path wav, String message) throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long before = threads.getCurrentThreadAllocatedBytes();
        long started = System.nanoTime();
        IOException rejected = assertThrows(IOException.class, () -> SampleAudio.read(wav));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertEquals(message, rejected.getMessage());
        assertEquals(message, SampleAudio.rejection(wav));
        assertTrue(elapsedMs < 1_000, message + " took " + elapsedMs + " ms");
        assertTrue(allocated < 8L * 1024 * 1024, message + " allocated " + allocated);
    }

    private void assertCli(Beat beat, String fileName, String message) throws Exception {
        Path json = temp.resolve(fileName + ".json");
        Files.writeString(json, BeatJson.write(beat));
        ByteArrayOutputStream infoErr = new ByteArrayOutputStream();
        ByteArrayOutputStream infoOut = new ByteArrayOutputStream();
        int info = HitsCli.run(
            new String[] {"info", json.toString()},
            new PrintStream(infoOut, true, StandardCharsets.UTF_8),
            new PrintStream(infoErr, true, StandardCharsets.UTF_8)
        );
        String infoErrors = infoErr.toString(StandardCharsets.UTF_8);
        String listing = infoOut.toString(StandardCharsets.UTF_8);
        assertEquals(HitsCli.OK, info, infoErrors);
        assertTrue(infoErrors.contains(message), infoErrors);
        assertTrue(listing.contains("unreadable"), listing);
        assertFalse(listing.contains(", ready"), listing);

        Path midi = temp.resolve(fileName + ".mid");
        ByteArrayOutputStream renderErr = new ByteArrayOutputStream();
        int render = HitsCli.run(
            new String[] {"render", json.toString(), "--out", midi.toString()},
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(renderErr, true, StandardCharsets.UTF_8)
        );
        String renderErrors = renderErr.toString(StandardCharsets.UTF_8);
        assertEquals(HitsCli.OK, render, renderErrors);
        assertTrue(renderErrors.contains(message), renderErrors);
        assertTrue(Files.isRegularFile(midi));
    }

    private Beat beatUsing(Path wav) {
        Beat beat = Beat.drumKit("Bad sample");
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef(wav.getFileName().toString(), 36, 0, true));
        beat.track(0).tap(0, false);
        return beat;
    }

    private Path patched(Path wav, byte[] dataSize) throws Exception {
        SampleAudio.writeWav(wav, 8000, new short[] {1, 2, 3, 4});
        byte[] raw = Files.readAllBytes(wav);
        System.arraycopy(dataSize, 0, raw, 40, 4);
        Files.write(wav, raw);
        return wav;
    }

    /** The full-file read this loader used to do, including the same 10 second cut. */
    private static SampleAudio.Clip legacy(Path path) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat source = stream.getFormat();
            int channels = Math.max(1, source.getChannels());
            float rateValue = source.getSampleRate() > 0 ? source.getSampleRate() : BuiltinSamples.RATE;
            AudioFormat decoded = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                rateValue,
                16,
                channels,
                channels * 2,
                rateValue,
                false
            );
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(decoded, stream)) {
                byte[] bytes = pcm.readAllBytes();
                int frame = channels * 2;
                int frames = bytes.length / frame;
                int rate = Math.max(1, Math.round(decoded.getSampleRate()));
                int cap = Math.max(1, (int) Math.round(rate * SampleAudio.MAX_SECONDS));
                int kept = Math.min(frames, cap);
                short[] mono = new short[kept];
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(
                    decoded.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN
                );
                for (int frameIndex = 0; frameIndex < kept; frameIndex++) {
                    int sum = 0;
                    for (int channel = 0; channel < channels; channel++) {
                        sum += buffer.getShort();
                    }
                    mono[frameIndex] = (short) (sum / channels);
                }
                return new SampleAudio.Clip(rate, mono);
            }
        }
    }

    private static short[] ramp(int count, int modulo) {
        short[] pcm = new short[count];
        for (int i = 0; i < count; i++) {
            pcm[i] = (short) (i % modulo);
        }
        return pcm;
    }

    private static short stereoAt(long index) {
        return mixed((short) ((index * 3) & 0x7fff), (short) ((index * 5) & 0x7fff));
    }

    private static short mixed(long left, long right) {
        return (short) (((int) left + (int) right) / 2);
    }

    private interface Frame {
        short at(long index);
    }

    private static void writeStereo(Path wav, int rate, long frames, Frame left, Frame right) throws Exception {
        int channels = 2;
        int block = channels * 2;
        ByteBuffer body = ByteBuffer.allocate(4096 * block).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel channel = FileChannel.open(
            wav,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )) {
            ByteBuffer header = wavHeader(rate, channels, 16, frames * block);
            channel.write(header);
            long written = 0;
            while (written < frames) {
                body.clear();
                int count = (int) Math.min(4096, frames - written);
                for (int i = 0; i < count; i++) {
                    long index = written + i;
                    body.putShort(left.at(index));
                    body.putShort(right.at(index));
                }
                body.flip();
                while (body.hasRemaining()) {
                    channel.write(body);
                }
                written += count;
            }
        }
    }

    private static void writePcm(Path wav, int rate, int channels, int bits, int[] samples) throws Exception {
        int bytesPerSample = bits / 8;
        int block = channels * bytesPerSample;
        ByteBuffer data = ByteBuffer.allocate(samples.length * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN);
        for (int sample : samples) {
            if (bits == 8) {
                data.put((byte) sample);
            } else if (bits == 16) {
                data.putShort((short) sample);
            } else if (bits == 24) {
                data.put((byte) sample);
                data.put((byte) (sample >> 8));
                data.put((byte) (sample >> 16));
            } else {
                throw new IllegalArgumentException("bits");
            }
        }
        ByteBuffer header = wavHeader(rate, channels, bits, data.capacity());
        ByteBuffer file = ByteBuffer.allocate(header.capacity() + data.capacity());
        file.put(header.array());
        file.put(data.array());
        Files.write(wav, file.array());
    }

    private static ByteBuffer wavHeader(int rate, int channels, int bits, long dataBytes) {
        int block = channels * (bits / 8);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'R', 'I', 'F', 'F'});
        header.putInt((int) (36 + dataBytes));
        header.put(new byte[] {'W', 'A', 'V', 'E'});
        header.put(new byte[] {'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(rate);
        header.putInt(rate * block);
        header.putShort((short) block);
        header.putShort((short) bits);
        header.put(new byte[] {'d', 'a', 't', 'a'});
        header.putInt((int) dataBytes);
        header.flip();
        return header;
    }

    private static void writeAu(Path path, int rate, short[] samples) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(24 + samples.length * 2).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0x2e736e64);
        buffer.putInt(24);
        buffer.putInt(samples.length * 2);
        buffer.putInt(3);
        buffer.putInt(rate);
        buffer.putInt(1);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        Files.write(path, buffer.array());
    }
}
