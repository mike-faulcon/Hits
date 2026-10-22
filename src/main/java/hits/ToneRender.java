package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Track;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records one built-in drum or note so a high-pass can be stored in the sample bank.
 * Live playback and WAV export then play that recording, which is why they match.
 * The recording is the hit at {@link #CAPTURE_VELOCITY}. A later velocity still
 * follows the SoundFont curve. Tune, decay, cutoff, resonance, reverb, and chorus
 * stay as MIDI, so moving those does not record the hit again.
 */
public final class ToneRender {
    /**
     * Velocity the hit is recorded at. A step at this velocity plays the recording
     * back at the level the built-in sound had.
     */
    public static final int CAPTURE_VELOCITY = 100;

    private static final int RAW_LIMIT = 16;
    private static final Map<String, short[]> RAW = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, short[]> eldest) {
            return size() > RAW_LIMIT;
        }
    };
    private static volatile double playbackGain = Double.NaN;

    private ToneRender() {}

    /** Filtered PCM for {@code track}, and whether a note should loop. */
    public record Rendered(short[] samples, boolean loop) {}

    public static Rendered render(Soundbank kits, hits.Track track) throws IOException {
        if (track == null) {
            throw new IOException("No row to render");
        }
        String id = identity(kits, track);
        short[] raw;
        synchronized (RAW) {
            raw = RAW.get(id);
            if (raw == null) {
                raw = scale(capture(kits, track), playbackGain());
                RAW.put(id, raw);
            }
        }
        short[] filtered = ToneFilter.apply(
            raw.clone(),
            WavAudio.SAMPLE_RATE,
            track.lowCutHz(),
            track.lowCutSlope(),
            track.presence()
        );
        boolean loop = track.mode() == TrackMode.NOTE && sustains(filtered);
        if (loop) {
            crossfade(filtered);
        }
        return new Rendered(filtered, loop);
    }

    /**
     * A held tone is still loud well after the attack. A drum hit is not.
     * The check looks at the body of the recording, before the export tail.
     */
    public static boolean sustains(short[] pcm) {
        int rate = WavAudio.SAMPLE_RATE;
        if (pcm == null || pcm.length < rate / 2) {
            return false;
        }
        double early = rms(pcm, rate / 20, rate / 5);
        double later = rms(pcm, rate / 2, Math.min(pcm.length, rate * 3 / 4));
        return early > 200 && later > early * 0.4;
    }

    private static short[] capture(Soundbank kits, hits.Track source) throws IOException {
        Beat beat = Beat.drumKit("tone");
        beat.setBpm(120);
        beat.setSwing(50);
        hits.Track row = beat.track(0);
        row.setName(source.name());
        row.setMode(source.mode() == TrackMode.NOTE ? TrackMode.NOTE : TrackMode.DRUM);
        row.setProgram(source.program());
        row.setNote(source.note());
        row.setBank(source.bank());
        row.setKit(source.kit());
        row.setPreset(source.preset());
        row.setVelocity(CAPTURE_VELOCITY);
        boolean note = row.mode() == TrackMode.NOTE;
        if (note) {
            beat.setStepCount(8);
            row.setGate(100);
            row.tap(0, false);
            row.step(0).setVelocity(CAPTURE_VELOCITY);
            row.step(0).setLength(8);
        } else {
            beat.setStepCount(1);
            row.tap(0, false);
            row.step(0).setVelocity(CAPTURE_VELOCITY);
        }
        try {
            Sequence sequence = SequenceBuilder.export(beat, ExportOptions.matchingPlayback(), kits, SampleUse.none());
            short[] mono = WavRenderer.renderMono(sequence, kits, null);
            if (peak(mono) < 40) {
                throw new IOException("The built-in sound did not render.");
            }
            return trim(mono);
        } catch (InvalidMidiDataException exception) {
            throw new IOException(
                exception.getMessage() == null ? "The built-in sound did not render." : exception.getMessage(),
                exception
            );
        }
    }

    /**
     * How much louder the sample bank plays a recording back, measured once with a sine
     * at {@link #CAPTURE_VELOCITY}. Captured hits are scaled by this so the passband
     * stays at the built-in level.
     */
    private static double playbackGain() throws IOException {
        if (!Double.isNaN(playbackGain)) {
            return playbackGain;
        }
        synchronized (ToneRender.class) {
            if (!Double.isNaN(playbackGain)) {
                return playbackGain;
            }
            int rate = WavAudio.SAMPLE_RATE;
            int length = rate / 2;
            short[] sine = new short[length];
            for (int i = 0; i < length; i++) {
                sine[i] = (short) Math.round(Math.sin(2 * Math.PI * 440.0 * i / rate) * 8000);
            }
            try {
                byte[] sf2 = SampleBank.write(List.of(new SampleBank.Voice(0, "cal", rate, sine, 60, 0, true)));
                Soundbank bank = SampleBank.load(sf2);
                short[] heard = WavRenderer.renderMono(calibrationSequence(), null, bank);
                double source = rms(sine, rate / 20, rate * 2 / 5);
                double output = rms(heard, rate / 20, Math.min(heard.length, rate * 2 / 5));
                if (output < 1) {
                    throw new IOException("The sample bank did not play the calibration tone.");
                }
                playbackGain = source / output;
                return playbackGain;
            } catch (InvalidMidiDataException exception) {
                throw new IOException("The sample bank did not play the calibration tone.", exception);
            }
        }
    }

    private static Sequence calibrationSequence() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, SequenceBuilder.PPQ);
        Track midi = sequence.createTrack();
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[] {0x07, (byte) 0xA1, 0x20}, 3);
        midi.add(new MidiEvent(tempo, 0));
        control(midi, 0, SampleBank.SF2_BANK, 0);
        control(midi, 32, 0, 0);
        control(midi, 10, 64, 0);
        ShortMessage program = new ShortMessage();
        program.setMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0);
        midi.add(new MidiEvent(program, 0));
        ShortMessage on = new ShortMessage();
        on.setMessage(ShortMessage.NOTE_ON, 0, 60, CAPTURE_VELOCITY);
        midi.add(new MidiEvent(on, 0));
        ShortMessage off = new ShortMessage();
        off.setMessage(ShortMessage.NOTE_OFF, 0, 60, 0);
        midi.add(new MidiEvent(off, SequenceBuilder.PPQ));
        MetaMessage end = new MetaMessage();
        end.setMessage(0x2F, new byte[0], 0);
        midi.add(new MidiEvent(end, SequenceBuilder.PPQ));
        return sequence;
    }

    private static void control(Track midi, int controller, int value, long tick) throws InvalidMidiDataException {
        ShortMessage message = new ShortMessage();
        message.setMessage(ShortMessage.CONTROL_CHANGE, 0, controller, value);
        midi.add(new MidiEvent(message, tick));
    }

    private static short[] scale(short[] pcm, double gain) {
        if (Math.abs(gain - 1.0) < 0.02) {
            return pcm;
        }
        double peak = peak(pcm);
        double used = gain;
        if (peak * used > 32000) {
            used = 32000 / peak;
        }
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            int value = (int) Math.round(pcm[i] * used);
            if (value > 32767) {
                value = 32767;
            } else if (value < -32768) {
                value = -32768;
            }
            out[i] = (short) value;
        }
        return out;
    }

    /** Drops the quiet export tail. Keeps a short ring after the last loud frame. */
    private static short[] trim(short[] pcm) {
        int last = 0;
        for (int i = 0; i < pcm.length; i++) {
            if (Math.abs(pcm[i]) > 180) {
                last = i;
            }
        }
        int end = Math.min(pcm.length, last + WavAudio.SAMPLE_RATE / 5);
        int floor = Math.min(pcm.length, WavAudio.SAMPLE_RATE / 10);
        end = Math.max(end, floor);
        if (end >= pcm.length) {
            return pcm;
        }
        short[] trimmed = new short[end];
        System.arraycopy(pcm, 0, trimmed, 0, end);
        return trimmed;
    }

    /** Blends the loop end toward the loop start so a held note does not click. */
    private static void crossfade(short[] pcm) {
        if (pcm.length < 512) {
            return;
        }
        int loopStart = pcm.length * 2 / 5;
        int loopEnd = pcm.length * 4 / 5;
        int fade = 256;
        if (loopEnd - fade <= loopStart || loopStart + fade >= pcm.length) {
            return;
        }
        for (int i = 0; i < fade; i++) {
            double t = i / (double) fade;
            int at = loopEnd - fade + i;
            double mixed = (1 - t) * pcm[at] + t * pcm[loopStart + i];
            pcm[at] = (short) Math.max(-32768, Math.min(32767, Math.round(mixed)));
        }
    }

    private static String identity(Soundbank kits, hits.Track track) {
        String preset = track.preset() == null ? "-" : track.preset().json();
        String bank = kits == null ? "-" : kits.getName() + "#" + System.identityHashCode(kits);
        return bank
            + "|" + track.mode().json()
            + "|" + track.program()
            + "|" + track.note()
            + "|" + track.playedNote()
            + "|" + track.bank()
            + "|" + track.kit()
            + "|" + preset;
    }

    private static int peak(short[] pcm) {
        int peak = 0;
        if (pcm == null) {
            return 0;
        }
        for (short sample : pcm) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private static double rms(short[] pcm, int start, int end) {
        if (pcm == null || pcm.length == 0) {
            return 0;
        }
        int from = Math.max(0, start);
        int to = Math.min(pcm.length, end);
        if (to <= from) {
            return 0;
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            double sample = pcm[i];
            sum += sample * sample;
        }
        return Math.sqrt(sum / (to - from));
    }

    /** For tests that need a fresh calibration after a JDK change. Not used by the app. */
    static void forget() {
        synchronized (RAW) {
            RAW.clear();
        }
        playbackGain = Double.NaN;
    }
}
