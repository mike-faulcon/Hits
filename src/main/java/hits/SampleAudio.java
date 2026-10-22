package hits;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a WAV down to mono 16-bit and writes a mono WAV. No extra libraries. */
public final class SampleAudio {
    /** About ten seconds. Longer files are cut so one row cannot fill the bank. */
    public static final double MAX_SECONDS = 10;

    private SampleAudio() {}

    public record Clip(int rate, short[] samples) {}

    public static Clip read(Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IOException("Sample file was not found");
        }
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            return read(stream);
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Could not read " + path.getFileName(), exception);
        }
    }

    static Clip read(AudioInputStream stream) throws IOException {
        AudioFormat decoded = decode(stream.getFormat());
        AudioInputStream pcm = AudioSystem.getAudioInputStream(decoded, stream);
        byte[] bytes = pcm.readAllBytes();
        int channels = decoded.getChannels();
        int frame = channels * 2;
        if (frame <= 0 || bytes.length < frame) {
            throw new IOException("Sample file has no audio");
        }
        int frames = bytes.length / frame;
        int rate = Math.max(1, Math.round(decoded.getSampleRate()));
        int cap = Math.max(1, (int) Math.round(rate * MAX_SECONDS));
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
        return new Clip(rate, mono);
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
}
