package hits;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.Sequence;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * Renders a sequence to a WAV file through Gervill's {@code AudioSynthesizer.openStream}.
 * Pulling the stream is faster than letting the notes play, and it does not open an audio device.
 */
public final class WavRenderer {
    private WavRenderer() {}

    /** The render was stopped. The destination file is left as it was. */
    public static final class Cancelled extends IOException {
        Cancelled() {
            super("Export cancelled");
        }
    }

    public static String render(
        Sequence sequence,
        Path path,
        Soundbank soundbank,
        IntConsumer progress,
        BooleanSupplier cancelled
    ) throws IOException {
        return render(sequence, path, soundbank, null, progress, cancelled);
    }

    /**
     * Same render as {@link #render(Sequence, Path, Soundbank, IntConsumer, BooleanSupplier)}.
     * {@code soundbank} replaces the default bank. {@code samples} is loaded after it and is not
     * unloaded, so both banks play. Null {@code samples} leaves the sample bank out.
     */
    /**
     * Same render as {@link #render(Sequence, Path, Soundbank, IntConsumer, BooleanSupplier)}.
     * Returns a warning when the master clips or the limiter reduces it by more than 1 dB.
     * Null when the file stays inside that.
     */
    public static String render(
        Sequence sequence,
        Path path,
        Soundbank soundbank,
        Soundbank samples,
        IntConsumer progress,
        BooleanSupplier cancelled
    ) throws IOException {
        if (path == null) {
            throw new IOException("Choose a file for the WAV export");
        }
        throwIfCancelled(cancelled);
        Synthesizer synthesizer = openSynthesizer();
        Receiver receiver = null;
        AudioInputStream stream = null;
        Path temp = null;
        boolean finished = false;
        try {
            stream = openStream(synthesizer);
            if (soundbank != null) {
                SoundFonts.install(synthesizer, soundbank, synthesizer.getDefaultSoundbank());
            }
            if (samples != null) {
                SoundFonts.install(synthesizer, samples, null);
            }
            try {
                receiver = synthesizer.getReceiver();
            } catch (MidiUnavailableException exception) {
                throw unavailable(exception);
            }
            WavAudio.send(receiver, sequence);
            long frames = WavAudio.frameCount(WavAudio.renderMicros(sequence));
            MasterWatch watch = MasterWatch.attach(synthesizer);
            Path parent = path.getParent();
            Path directory = parent == null ? Path.of(".") : parent;
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                temp = Files.createTempFile(directory, "hits-wav", ".part");
                write(stream, temp, frames, progress, cancelled, watch);
                // createTempFile is owner-only. MIDI is created with the process umask, usually 0644.
                applyUmaskPermissions(temp);
                moveIntoPlace(temp, path);
            } catch (IOException exception) {
                if (exception instanceof Cancelled) {
                    throw exception;
                }
                throw writeError(path, exception);
            }
            finished = true;
            report(progress, 100);
            return watch.warning();
        } catch (javax.sound.midi.InvalidMidiDataException exception) {
            throw new IOException(
                exception.getMessage() == null ? "The beat could not be rendered." : exception.getMessage(),
                exception
            );
        } finally {
            if (receiver != null) {
                receiver.close();
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The synth is closed next. A failed stream close should not hide the render error.
                }
            }
            if (synthesizer.isOpen()) {
                synthesizer.close();
            }
            if (temp != null && !finished) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A failed delete should not hide the render error. The destination file was not replaced.
                }
            }
        }
    }

    /**
     * The same synth path as {@link #render}, mixed to mono and kept in memory.
     * Used to record one built-in hit so a high-pass can be baked into the sample bank.
     * {@code soundbank} null leaves the JDK's default bank loaded, matching a WAV export
     * that was not given a user SoundFont.
     */
    public static short[] renderMono(Sequence sequence, Soundbank soundbank, Soundbank samples) throws IOException {
        Synthesizer synthesizer = openSynthesizer();
        Receiver receiver = null;
        AudioInputStream stream = null;
        try {
            stream = openStream(synthesizer);
            if (soundbank != null) {
                SoundFonts.install(synthesizer, soundbank, synthesizer.getDefaultSoundbank());
            }
            if (samples != null) {
                SoundFonts.install(synthesizer, samples, null);
            }
            try {
                receiver = synthesizer.getReceiver();
            } catch (MidiUnavailableException exception) {
                throw unavailable(exception);
            }
            WavAudio.send(receiver, sequence);
            long frames = WavAudio.frameCount(WavAudio.renderMicros(sequence));
            if (frames > Integer.MAX_VALUE) {
                throw new IOException("The hit is too long to render.");
            }
            return mixMono(stream, (int) frames, MasterWatch.attach(synthesizer));
        } catch (javax.sound.midi.InvalidMidiDataException exception) {
            throw new IOException(
                exception.getMessage() == null ? "The beat could not be rendered." : exception.getMessage(),
                exception
            );
        } finally {
            if (receiver != null) {
                receiver.close();
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // The synth is closed next.
                }
            }
            if (synthesizer.isOpen()) {
                synthesizer.close();
            }
        }
    }

    private static short[] mixMono(AudioInputStream stream, int frames, MasterWatch watch) throws IOException {
        short[] mono = new short[frames];
        byte[] buffer = new byte[WavAudio.BYTES_PER_FRAME * 4096];
        int frame = 0;
        int filled = 0;
        while (frame < frames) {
            int want = Math.min(buffer.length - filled, (frames - frame) * WavAudio.BYTES_PER_FRAME);
            int read = stream.read(buffer, filled, want);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                throw new IOException("The built-in synth stopped rendering.");
            }
            filled += read;
            watch.poll();
            int whole = filled / WavAudio.BYTES_PER_FRAME;
            for (int i = 0; i < whole; i++) {
                int offset = i * WavAudio.BYTES_PER_FRAME;
                int left = (short) ((buffer[offset] & 0xff) | (buffer[offset + 1] << 8));
                int right = (short) ((buffer[offset + 2] & 0xff) | (buffer[offset + 3] << 8));
                watch.hear(left);
                watch.hear(right);
                mono[frame++] = (short) ((left + right) / 2);
            }
            int used = whole * WavAudio.BYTES_PER_FRAME;
            if (used < filled) {
                System.arraycopy(buffer, used, buffer, 0, filled - used);
            }
            filled -= used;
        }
        return mono;
    }

    static boolean unavailable(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof MidiUnavailableException
                || cursor instanceof IllegalAccessException
                || cursor instanceof InaccessibleObjectException) {
                return true;
            }
        }
        return false;
    }

    private static Synthesizer openSynthesizer() throws IOException {
        try {
            return MidiSystem.getSynthesizer();
        } catch (MidiUnavailableException exception) {
            throw unavailable(exception);
        }
    }

    /**
     * {@code openStream} lives on {@code com.sun.media.sound.AudioSynthesizer}, which the JDK does not export.
     * Gradle opens that package for {@code run} and {@code test}. The packaged app passes the same flag.
     */
    private static AudioInputStream openStream(Synthesizer synthesizer) throws IOException {
        try {
            Method method = synthesizer.getClass().getMethod("openStream", AudioFormat.class, Map.class);
            Object opened = method.invoke(synthesizer, WavAudio.format(), null);
            if (!(opened instanceof AudioInputStream stream)) {
                throw new IOException("The built-in synth cannot render a WAV file.");
            }
            AudioFormat actual = stream.getFormat();
            if (Math.round(actual.getSampleRate()) != WavAudio.SAMPLE_RATE
                || actual.getSampleSizeInBits() != WavAudio.BITS
                || actual.getChannels() != WavAudio.CHANNELS
                || actual.isBigEndian()
                || actual.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                throw new IOException("The built-in synth did not render 16-bit stereo audio.");
            }
            return stream;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException(text(cause, "The built-in synth cannot render a WAV file."), cause);
        } catch (IllegalAccessException exception) {
            throw new IOException(
                "The built-in synth cannot render a WAV file. Launch Hits with --add-exports java.desktop/com.sun.media.sound=ALL-UNNAMED.",
                exception
            );
        } catch (NoSuchMethodException exception) {
            throw new IOException("The built-in synth cannot render a WAV file.", exception);
        }
    }

    private static void write(
        AudioInputStream stream,
        Path temp,
        long frames,
        IntConsumer progress,
        BooleanSupplier cancelled,
        MasterWatch watch
    ) throws IOException {
        long total = frames * WavAudio.BYTES_PER_FRAME;
        report(progress, 0);
        try (OutputStream out = Files.newOutputStream(temp)) {
            out.write(WavAudio.header(frames));
            byte[] buffer = new byte[WavAudio.BYTES_PER_FRAME * 4096];
            long remaining = total;
            int reported = 0;
            while (remaining > 0) {
                throwIfCancelled(cancelled);
                int want = (int) Math.min(buffer.length, remaining);
                int read = stream.read(buffer, 0, want);
                if (read < 0) {
                    Arrays.fill(buffer, 0, want, (byte) 0);
                    out.write(buffer, 0, want);
                    remaining -= want;
                } else if (read == 0) {
                    throw new IOException("The built-in synth stopped rendering.");
                } else {
                    watch.poll();
                    watch.hear(buffer, read);
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                int percent = total == 0 ? 100 : (int) ((total - remaining) * 100 / total);
                if (percent != reported) {
                    reported = percent;
                    report(progress, Math.min(99, percent));
                }
            }
        }
    }

    /**
     * Copies the permissions of a file created the normal way, so the WAV follows the umask
     * the same way {@link javax.sound.midi.MidiSystem#write} does.
     */
    private static void applyUmaskPermissions(Path file) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        Path probe = file.resolveSibling(file.getFileName().toString() + ".umask");
        try {
            Files.newByteChannel(
                probe,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            ).close();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(probe);
            view.setPermissions(permissions);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** Names the destination. A temp-file failure must not be the path the user sees. */
    private static IOException writeError(Path path, IOException exception) {
        String message = exception.getMessage();
        boolean tempPath = message != null && (message.contains("hits-wav") || message.contains(".part") || message.contains(".umask"));
        String detail = message == null || message.isBlank() || tempPath
            ? "the folder is not writable or the path is not a file"
            : message;
        return new IOException("Could not write " + path + ": " + detail, exception);
    }

    private static void moveIntoPlace(Path temp, Path path) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void throwIfCancelled(BooleanSupplier cancelled) throws Cancelled {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
    }

    private static void report(IntConsumer progress, int percent) {
        if (progress != null) {
            progress.accept(percent);
        }
    }

    private static IOException unavailable(MidiUnavailableException exception) {
        return new IOException(text(exception, "The built-in synth is unavailable."), exception);
    }

    private static String text(Throwable exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    /**
     * Watches Gervill's master limiter while samples are pulled. The gain recovers
     * after the loud part, so it has to be read during the render. Reflection needs
     * {@code --add-opens java.desktop/com.sun.media.sound=ALL-UNNAMED}. Without that,
     * only samples sitting on the 16-bit rail are reported.
     */
    private static final class MasterWatch {
        private Object limiter;
        private java.lang.reflect.Field gainField;
        private float minGain = 1f;
        private boolean clipped;

        static MasterWatch attach(Synthesizer synthesizer) {
            MasterWatch watch = new MasterWatch();
            try {
                java.lang.reflect.Field mixerField = synthesizer.getClass().getDeclaredField("mainmixer");
                mixerField.setAccessible(true);
                Object mixer = mixerField.get(synthesizer);
                if (mixer == null) {
                    return watch;
                }
                java.lang.reflect.Field agc = mixer.getClass().getDeclaredField("agc");
                agc.setAccessible(true);
                Object limiter = agc.get(mixer);
                if (limiter == null) {
                    return watch;
                }
                java.lang.reflect.Field gain = limiter.getClass().getDeclaredField("gain");
                gain.setAccessible(true);
                watch.limiter = limiter;
                watch.gainField = gain;
            } catch (ReflectiveOperationException | InaccessibleObjectException ignored) {
                // Hard clips are still counted.
            }
            return watch;
        }

        void poll() {
            if (gainField == null) {
                return;
            }
            try {
                float value = gainField.getFloat(limiter);
                if (value > 0f && value < minGain) {
                    minGain = value;
                }
            } catch (IllegalAccessException ignored) {
                gainField = null;
            }
        }

        void hear(int sample) {
            if (sample >= 32767 || sample <= -32768) {
                clipped = true;
            }
        }

        void hear(byte[] buffer, int length) {
            int frame = WavAudio.BYTES_PER_FRAME;
            int frames = length / frame;
            for (int i = 0; i < frames; i++) {
                int offset = i * frame;
                int left = (short) ((buffer[offset] & 0xff) | (buffer[offset + 1] << 8));
                int right = (short) ((buffer[offset + 2] & 0xff) | (buffer[offset + 3] << 8));
                hear(left);
                hear(right);
            }
        }

        String warning() {
            double reduction = minGain < 1f ? -20.0 * Math.log10(minGain) : 0;
            boolean heavy = reduction > 1.0;
            if (!heavy && !clipped) {
                return null;
            }
            if (heavy && clipped) {
                return String.format(
                    Locale.ROOT,
                    "The master output clipped. The limiter reduced the master by %.1f dB.",
                    reduction
                );
            }
            if (heavy) {
                return String.format(Locale.ROOT, "The limiter reduced the master by %.1f dB.", reduction);
            }
            return "The master output clipped.";
        }
    }
}
