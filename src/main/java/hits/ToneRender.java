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
 * The recording is the hit at {@link #CAPTURE_VELOCITY}, captured dry: reverb and chorus
 * are applied later from the row, the same way an unfiltered row sends them.
 * The synth's leading render latency is trimmed off, so the hit lines up with the
 * built-in sound. A hot peak is scaled into 16-bit and the lost level is given back
 * as SoundFont attenuation, so a kick is not turned down to stay in range.
 * Tune and decay stay as MIDI. Moving the filter records the hit again.
 */
public final class ToneRender {
    /**
     * Velocity the hit is recorded at. A step at this velocity plays the recording
     * back at the level the built-in sound had.
     */
    public static final int CAPTURE_VELOCITY = 100;

    /** Held-note capture: eight 16ths at 120 bpm, one second of sustain, then the release. */
    private static final int NOTE_BPM = 120;
    private static final int NOTE_STEPS = 8;

    private static final int RAW_LIMIT = 16;
    private static final Map<String, short[]> RAW = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, short[]> eldest) {
            return size() > RAW_LIMIT;
        }
    };
    private static volatile double playbackGain = Double.NaN;
    /** Samples of leading silence the offline synth inserts. {@code -1} until measured. */
    private static volatile int leadSamples = -1;

    private ToneRender() {}

    /**
     * Filtered PCM for {@code track}, whether a note should loop, and the SoundFont
     * attenuation that restores any level the 16-bit fit removed.
     * Loop points are sample indexes. {@code -1} asks the bank for its usual loop.
     */
    public record Rendered(
        short[] samples,
        boolean loop,
        int attenuation,
        int loopStart,
        int loopEnd,
        int sampleMode
    ) {}

    public static Rendered render(Soundbank kits, hits.Track track) throws IOException {
        if (track == null) {
            throw new IOException("No row to render");
        }
        String id = identity(kits, track);
        short[] raw;
        synchronized (RAW) {
            raw = RAW.get(id);
            if (raw == null) {
                raw = capture(kits, track);
                RAW.put(id, raw);
            }
        }
        double[] audio = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            audio[i] = raw[i];
        }
        if (track.hasTone()) {
            audio = ToneFilter.process(raw, WavAudio.SAMPLE_RATE, track.lowCutHz(), track.lowCutSlope(), track.presence());
        }
        double gain = playbackGain();
        if (Math.abs(gain - 1.0) >= 0.02) {
            for (int i = 0; i < audio.length; i++) {
                audio[i] *= gain;
            }
        }
        boolean note = track.mode() == TrackMode.NOTE;
        int loopStart = -1;
        int loopEnd = -1;
        int sampleMode = -1;
        boolean loop = note && sustains(audio);
        if (loop) {
            Loop marked = markLoop(audio, WavAudio.SAMPLE_RATE);
            if (marked == null) {
                loop = false;
            } else {
                loopStart = marked.start;
                loopEnd = marked.end;
                sampleMode = SampleBank.LOOP_UNTIL_RELEASE;
            }
        }
        ToneFilter.Fitted fitted = ToneFilter.fit(audio);
        return new Rendered(
            fitted.samples(),
            loop,
            SampleBank.attenuation(fitted.makeupDb()),
            loopStart,
            loopEnd,
            sampleMode
        );
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

    private static boolean sustains(double[] pcm) {
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
        beat.setBpm(NOTE_BPM);
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
        // The built-in synth's default reverb is part of a plain drum. Baking it in and
        // then sending the row's own reverb plays the room twice. Capture dry.
        row.setReverb(0);
        row.setChorus(0);
        boolean note = row.mode() == TrackMode.NOTE;
        if (note) {
            beat.setStepCount(NOTE_STEPS);
            row.setGate(100);
            row.tap(0, false);
            row.step(0).setVelocity(CAPTURE_VELOCITY);
            row.step(0).setLength(NOTE_STEPS);
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
            short[] trimmed = dropLead(mono, leadIn());
            int releaseAt = note ? noteReleaseAt(WavAudio.SAMPLE_RATE) : 0;
            int minEnd = note ? releaseAt + WavAudio.SAMPLE_RATE * 3 / 10 : 0;
            return trimTail(trimmed, minEnd);
        } catch (InvalidMidiDataException exception) {
            throw new IOException(
                exception.getMessage() == null ? "The built-in sound did not render." : exception.getMessage(),
                exception
            );
        }
    }

    /** Sample index where the captured note's release begins, after the lead-in is gone. */
    private static int noteReleaseAt(int rate) {
        double seconds = NOTE_STEPS * (60.0 / NOTE_BPM) / 4.0;
        return (int) Math.round(seconds * rate);
    }

    /**
     * How much louder the sample bank plays a recording back, measured once with a sine
     * at {@link #CAPTURE_VELOCITY}. Captured hits are scaled by this so the passband
     * stays at the built-in level. A peak that would not fit is scaled down in
     * {@link ToneFilter#fit} and restored with SoundFont attenuation.
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

    /**
     * Leading samples the offline synth inserts before a note at tick 0.
     * Measured with a one-sample impulse, dry, through the same renderer as a capture.
     */
    private static int leadIn() throws IOException {
        if (leadSamples >= 0) {
            return leadSamples;
        }
        synchronized (ToneRender.class) {
            if (leadSamples >= 0) {
                return leadSamples;
            }
            int rate = WavAudio.SAMPLE_RATE;
            short[] impulse = new short[64];
            impulse[0] = 28000;
            try {
                byte[] sf2 = SampleBank.write(List.of(new SampleBank.Voice(0, "imp", rate, impulse, 60, 0, true)));
                Soundbank bank = SampleBank.load(sf2);
                short[] heard = WavRenderer.renderMono(impulseSequence(), null, bank);
                int peak = peak(heard);
                if (peak < 40) {
                    throw new IOException("The sample bank did not play the latency impulse.");
                }
                int gate = Math.max(40, peak / 20);
                int at = 0;
                for (int i = 0; i < heard.length; i++) {
                    if (Math.abs(heard[i]) >= gate) {
                        at = i;
                        break;
                    }
                }
                int cap = rate / 20;
                leadSamples = Math.max(0, Math.min(at, cap));
                return leadSamples;
            } catch (InvalidMidiDataException exception) {
                throw new IOException("The sample bank did not play the latency impulse.", exception);
            }
        }
    }

    /**
     * The sine is played the way a captured hit is played back: velocity
     * {@link #CAPTURE_VELOCITY}, and the synth's own reverb. The impulse that
     * measures latency is a separate, dry note so the onset is not the room.
     */
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

    /** A dry note at tick 0. Reverb and chorus are 0 so the onset is not the room. */
    private static Sequence impulseSequence() throws InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, SequenceBuilder.PPQ);
        Track midi = sequence.createTrack();
        MetaMessage tempo = new MetaMessage();
        tempo.setMessage(0x51, new byte[] {0x07, (byte) 0xA1, 0x20}, 3);
        midi.add(new MidiEvent(tempo, 0));
        control(midi, 0, SampleBank.SF2_BANK, 0);
        control(midi, 32, 0, 0);
        control(midi, 10, 64, 0);
        control(midi, 91, 0, 0);
        control(midi, 93, 0, 0);
        ShortMessage program = new ShortMessage();
        program.setMessage(ShortMessage.PROGRAM_CHANGE, 0, 0, 0);
        midi.add(new MidiEvent(program, 0));
        ShortMessage on = new ShortMessage();
        on.setMessage(ShortMessage.NOTE_ON, 0, 60, 127);
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

    /** Drops the offline synth's leading latency. The instrument's own attack stays. */
    private static short[] dropLead(short[] pcm, int lead) {
        if (pcm == null || lead <= 0 || lead >= pcm.length - 64) {
            return pcm;
        }
        short[] trimmed = new short[pcm.length - lead];
        System.arraycopy(pcm, lead, trimmed, 0, trimmed.length);
        return trimmed;
    }

    /**
     * Drops the quiet export tail. Keeps down to about −40 dB, and at least {@code minEnd}
     * samples, so a note's release is still in the recording.
     */
    private static short[] trimTail(short[] pcm, int minEnd) {
        if (pcm == null || pcm.length == 0) {
            return pcm == null ? new short[0] : pcm;
        }
        int gate = Math.max(12, peak(pcm) / 100);
        int last = 0;
        for (int i = 0; i < pcm.length; i++) {
            if (Math.abs(pcm[i]) >= gate) {
                last = i;
            }
        }
        int end = Math.min(pcm.length, last + WavAudio.SAMPLE_RATE / 10);
        int floor = Math.min(pcm.length, Math.max(WavAudio.SAMPLE_RATE / 10, minEnd));
        end = Math.max(end, floor);
        if (end >= pcm.length) {
            return pcm;
        }
        short[] trimmed = new short[end];
        System.arraycopy(pcm, 0, trimmed, 0, end);
        return trimmed;
    }

    /**
     * A short loop in the steady sustain, crossfaded into the samples just before it,
     * with the recorded release left after {@code end}. Sample mode 3 plays that tail
     * once the key is up. The loop is leveled so a slow decay does not pump each cycle.
     */
    private record Loop(int start, int end) {}

    private static Loop markLoop(double[] pcm, int rate) {
        int releaseAt = noteReleaseAt(rate);
        int minTail = rate * 3 / 10;
        if (pcm == null || releaseAt + minTail > pcm.length) {
            return null;
        }
        int fade = Math.min(rate / 20, 2048);
        int loopLen = Math.min(rate / 8, releaseAt / 3);
        if (loopLen < fade * 2) {
            return null;
        }
        int loopEnd = releaseAt;
        int loopStart = loopEnd - loopLen;
        if (loopStart < fade) {
            return null;
        }
        int window = Math.min(fade, loopLen / 4);
        double startLevel = rms(pcm, loopStart, loopStart + window);
        double endLevel = rms(pcm, loopEnd - window, loopEnd);
        if (startLevel > 1 && endLevel > 1) {
            double ratio = startLevel / endLevel;
            ratio = Math.max(0.75, Math.min(1.35, ratio));
            for (int i = 0; i < loopLen; i++) {
                double t = i / (double) Math.max(1, loopLen - 1);
                pcm[loopStart + i] *= 1 + (ratio - 1) * t;
            }
        }
        for (int i = 0; i < fade; i++) {
            double t = i / (double) fade;
            double keep = Math.cos(t * Math.PI / 2);
            double bring = Math.sin(t * Math.PI / 2);
            int at = loopEnd - fade + i;
            double joined = pcm[at] * keep + pcm[loopStart - fade + i] * bring;
            pcm[at] = joined;
        }
        return new Loop(loopStart, loopEnd);
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

    private static double rms(double[] pcm, int start, int end) {
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
        leadSamples = -1;
    }
}
