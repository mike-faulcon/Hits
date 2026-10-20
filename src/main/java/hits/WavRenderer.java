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

    public static void render(
        Sequence sequence,
        Path path,
        Soundbank soundbank,
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
            try {
                receiver = synthesizer.getReceiver();
            } catch (MidiUnavailableException exception) {
                throw unavailable(exception);
            }
            WavAudio.send(receiver, sequence);
            long frames = WavAudio.frameCount(WavAudio.renderMicros(sequence));
            Path parent = path.getParent();
            Path directory = parent == null ? Path.of(".") : parent;
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                temp = Files.createTempFile(directory, "hits-wav", ".part");
                write(stream, temp, frames, progress, cancelled);
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
        BooleanSupplier cancelled
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
}
