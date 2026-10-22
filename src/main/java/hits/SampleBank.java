package hits;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A small SoundFont 2 writer. The bytes are loaded with
 * {@link MidiSystem#getSoundbank(java.io.InputStream)}. The JDK's
 * {@code SF2Soundbank} class is not referenced, so this file compiles with
 * {@code --release 21}.
 * <p>
 * Every sample track shares one melodic bank, {@link #SF2_BANK}. That bank is not
 * 120, 121, or 128. Each distinct sample on a row gets its own program in that bank,
 * so two patterns can play two samples on the same row. A one-shot is a sample that
 * does not loop, with a release long enough that note-off does not cut it.
 * Choke uses a short release, so note-off and the step length stop the sound.
 * Decay is the volume-envelope decay, sustain, and release of that preset.
 * Gain is {@code initialAttenuation}. Gervill scales that generator by about −0.376,
 * so a negative amount is a boost.
 */
public final class SampleBank {
    /**
     * SoundFont preset bank. Gervill turns this into MIDI bank {@code SF2_BANK << 7},
     * which is CC 0 = {@code SF2_BANK} and CC 32 = 0.
     */
    public static final int SF2_BANK = 64;
    /** MSB×128+LSB. CC 0 is 64 and CC 32 is 0. Not 120, 121, or 128. */
    public static final int MIDI_BANK = SF2_BANK * 128;
    /** Timecents. About two minutes, so a note-off does not fade a one-shot. */
    public static final int RELEASE_ONE_SHOT = 8400;
    /** Timecents. About 16 ms, so note-off cuts a choked sample. */
    public static final int RELEASE_CHOKE = -7200;
    /** Timecents. About 300 ms, so a held note fades instead of clicking off. */
    public static final int RELEASE_HELD = -2100;
    /** Zeros after each sample so the interpolator can read past the end. */
    private static final int PAD = 46;
    /** SF2 default: the decay stage is skipped and the voice sits at the sustain level. */
    public static final int DECAY_NONE = -12000;
    /** SF2 sustain, in centibels of attenuation. 0 is full level. 1000 is silence. */
    public static final int SUSTAIN_FULL = 0;
    public static final int SUSTAIN_SILENT = 1000;
    /**
     * Gervill adds {@code initialAttenuation * SCALE} to mixer gain.
     * Mixer gain is in centibels (10 units = 1 dB), and the scale is about −0.376,
     * so the generator amount for {@code +N} dB is {@code N * 10 / SCALE}.
     */
    private static final double GERVILL_ATTENUATION_SCALE = -0.3762870132923126;

    private SampleBank() {}

    /**
     * One preset. {@code decay}, {@code sustain}, and {@code release} are the volume envelope.
     * {@code attenuation} is SF2 {@code initialAttenuation} (negative boosts).
     * {@code loop} holds a sustained note between the inner part of the PCM.
     * A one-shot never loops. The 7-argument form does not loop, which is every sample row.
     */
    public record Voice(
        int program,
        String name,
        int rate,
        short[] samples,
        int root,
        int start,
        boolean oneShot,
        boolean loop,
        int decay,
        int sustain,
        int release,
        int attenuation
    ) {
        /** A voice at decay 100: a one-shot rings out, a choke follows note-off, and gain is untouched. */
        public Voice(int program, String name, int rate, short[] samples, int root, int start, boolean oneShot) {
            this(
                program,
                name,
                rate,
                samples,
                root,
                start,
                oneShot,
                false,
                DECAY_NONE,
                SUSTAIN_FULL,
                oneShot ? RELEASE_ONE_SHOT : RELEASE_CHOKE,
                0
            );
        }

        public Voice {
            if (oneShot) {
                loop = false;
            }
        }
    }

    /** Volume envelope for one preset. Times are SoundFont timecents. Sustain is centibels. */
    public record Envelope(int decay, int sustain, int release) {}

    /**
     * Decay percent mapped onto the volume envelope.
     * <p>
     * One-shot at 100% and above keeps a release long enough that note-off does not cut the file.
     * The file is the limit, so 200% matches 100%. Below 100% the decay stage fades to silence
     * in that fraction of the recording, and the release is the same length so a short step
     * does not chop the fade. Choke at 100% is the short release. Above 100% lengthens that
     * release the way CC 72 used to. Below 100% fades from note-on across that fraction of the
     * file, and note-off still cuts.
     */
    public static Envelope envelope(boolean oneShot, int percent, int frames, int rate) {
        int clamped = Math.max(Track.DECAY_MIN, Math.min(Track.DECAY_MAX, percent));
        double duration = Math.max(0.02, frames / (double) Math.max(1, rate));
        if (oneShot) {
            if (clamped >= Track.DECAY) {
                return new Envelope(DECAY_NONE, SUSTAIN_FULL, RELEASE_ONE_SHOT);
            }
            int fade = fadeTimecents(duration * clamped / 100.0);
            return new Envelope(fade, SUSTAIN_SILENT, fade);
        }
        if (clamped >= Track.DECAY) {
            return new Envelope(DECAY_NONE, SUSTAIN_FULL, chokeRelease(clamped));
        }
        return new Envelope(fadeTimecents(duration * clamped / 100.0), SUSTAIN_SILENT, RELEASE_CHOKE);
    }

    /**
     * SF2 {@code initialAttenuation} for a gain in decibels.
     * Positive decibels become a negative generator amount, which Gervill treats as a boost.
     */
    public static int attenuation(int decibels) {
        if (decibels == 0) {
            return 0;
        }
        int amount = (int) Math.round(decibels * 10.0 / GERVILL_ATTENUATION_SCALE);
        return Math.max(-1440, Math.min(1440, amount));
    }

    /** Timecents whose −40 dB point is about {@code audibleSeconds}. */
    private static int fadeTimecents(double audibleSeconds) {
        double silence = Math.max(0.004, audibleSeconds) * (96.0 / 40.0);
        return timecents(silence);
    }

    /**
     * Choke release at {@code percent} of 100 and above.
     * 100% is {@link #RELEASE_CHOKE}. Higher percents add the same timecents CC 72 used to add,
     * so a choke can still ring past the step. CC 72 itself is not sent for a sample.
     */
    private static int chokeRelease(int percent) {
        int controller = Track.decayController(percent);
        double bipolar = (controller / 128.0) * 2.0 - 1.0;
        return timecentsFrom(RELEASE_CHOKE + (int) Math.round(bipolar * 6000.0));
    }

    private static int timecents(double seconds) {
        return timecentsFrom((int) Math.round(1200.0 * Math.log(Math.max(0.001, seconds)) / Math.log(2.0)));
    }

    private static int timecentsFrom(int value) {
        return Math.max(-12000, Math.min(RELEASE_ONE_SHOT, value));
    }

    public static byte[] write(List<Voice> voices) {
        int count = voices == null ? 0 : voices.size();
        ByteArrayOutputStream samples = new ByteArrayOutputStream();
        int[] starts = new int[count];
        int[] ends = new int[count];
        int[] loopStarts = new int[count];
        int[] loopEnds = new int[count];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            Voice voice = voices.get(i);
            short[] pcm = voice.samples == null ? new short[0] : voice.samples;
            starts[i] = cursor;
            ends[i] = cursor + pcm.length;
            int loopStart = 0;
            int loopEnd = Math.min(pcm.length, 32);
            if (voice.loop() && pcm.length > 128) {
                loopStart = pcm.length * 2 / 5;
                loopEnd = pcm.length * 4 / 5;
                if (loopEnd < loopStart + 32) {
                    loopEnd = Math.min(pcm.length, loopStart + 32);
                }
            }
            if (loopEnd < 1) {
                loopEnd = 1;
            }
            loopStarts[i] = starts[i] + loopStart;
            loopEnds[i] = Math.min(ends[i], starts[i] + loopEnd);
            if (loopEnds[i] <= loopStarts[i]) {
                loopEnds[i] = ends[i];
            }
            for (short sample : pcm) {
                samples.write(sample & 0xff);
                samples.write((sample >> 8) & 0xff);
            }
            for (int pad = 0; pad < PAD; pad++) {
                samples.write(0);
                samples.write(0);
            }
            cursor = ends[i] + PAD;
        }
        byte[] info = list("INFO", infoChunks());
        byte[] sdta = list("sdta", chunk("smpl", samples.toByteArray()));
        byte[] pdta = list("pdta", presetData(voices, starts, ends, loopStarts, loopEnds));
        return riff("sfbk", concat(info, sdta, pdta));
    }

    public static Soundbank load(byte[] sf2) throws IOException, InvalidMidiDataException {
        if (sf2 == null || sf2.length == 0) {
            throw new IOException("Sample bank is empty");
        }
        return MidiSystem.getSoundbank(new ByteArrayInputStream(sf2));
    }

    private static byte[] infoChunks() {
        return concat(
            chunk("ifil", new byte[] {2, 0, 1, 0}),
            chunk("isng", zstring("EMU8000")),
            chunk("INAM", zstring("Hits Samples")),
            chunk("ISFT", zstring("Hits"))
        );
    }

    private static byte[] presetData(List<Voice> voices, int[] starts, int[] ends, int[] loopStarts, int[] loopEnds) {
        int count = voices == null ? 0 : voices.size();
        ByteArrayOutputStream presets = new ByteArrayOutputStream();
        ByteArrayOutputStream presetBags = new ByteArrayOutputStream();
        ByteArrayOutputStream presetGens = new ByteArrayOutputStream();
        ByteArrayOutputStream instruments = new ByteArrayOutputStream();
        ByteArrayOutputStream instrumentBags = new ByteArrayOutputStream();
        ByteArrayOutputStream instrumentGens = new ByteArrayOutputStream();
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int presetGen = 0;
        int instrumentGen = 0;
        for (int i = 0; i < count; i++) {
            Voice voice = voices.get(i);
            presets.writeBytes(presetHeader(name(voice.name(), "row" + voice.program()), voice.program(), SF2_BANK, i));
            presetBags.writeBytes(bag(presetGen, 0));
            presetGens.writeBytes(generator(43, 0x7F00));
            presetGens.writeBytes(generator(44, 0x7F00));
            presetGens.writeBytes(generator(41, i));
            presetGen += 3;

            instruments.writeBytes(instrumentHeader(name(voice.name(), "i" + i), i));
            instrumentBags.writeBytes(bag(instrumentGen, 0));
            int gens = writeZone(instrumentGens, voice, i);
            instrumentGen += gens;

            headers.writeBytes(sampleHeader(
                name(voice.name(), "s" + i),
                starts[i],
                ends[i],
                loopStarts[i],
                loopEnds[i],
                Math.max(1, voice.rate()),
                voice.root()
            ));
        }
        presets.writeBytes(presetHeader("EOP", 0, 0, count));
        presetBags.writeBytes(bag(presetGen, 0));
        presetGens.writeBytes(generator(0, 0));
        instruments.writeBytes(instrumentHeader("EOI", count));
        instrumentBags.writeBytes(bag(instrumentGen, 0));
        instrumentGens.writeBytes(generator(0, 0));
        headers.writeBytes(sampleHeader("EOS", 0, 0, 0, 0, 0, 0));
        return concat(
            chunk("phdr", presets.toByteArray()),
            chunk("pbag", presetBags.toByteArray()),
            chunk("pmod", new byte[10]),
            chunk("pgen", presetGens.toByteArray()),
            chunk("inst", instruments.toByteArray()),
            chunk("ibag", instrumentBags.toByteArray()),
            chunk("imod", new byte[10]),
            chunk("igen", instrumentGens.toByteArray()),
            chunk("shdr", headers.toByteArray())
        );
    }

    /** Zone generators. sampleID is last, which is what the SoundFont reader expects. */
    private static int writeZone(ByteArrayOutputStream out, Voice voice, int sampleIndex) {
        int count = 0;
        int start = Math.max(0, voice.start());
        int length = voice.samples == null ? 0 : voice.samples.length;
        if (length > 0 && start >= length) {
            start = length - 1;
        }
        if (start > 0) {
            int coarse = start / 32768;
            int fine = start % 32768;
            if (coarse != 0) {
                out.writeBytes(generator(4, coarse));
                count++;
            }
            if (fine != 0) {
                out.writeBytes(generator(0, fine));
                count++;
            }
        }
        out.writeBytes(generator(36, voice.decay()));
        out.writeBytes(generator(37, voice.sustain()));
        out.writeBytes(generator(38, voice.release()));
        if (voice.attenuation() != 0) {
            out.writeBytes(generator(48, voice.attenuation()));
            count++;
        }
        out.writeBytes(generator(43, 0x7F00));
        out.writeBytes(generator(44, 0x7F00));
        out.writeBytes(generator(54, voice.loop() ? 1 : 0));
        out.writeBytes(generator(58, Math.max(0, Math.min(127, voice.root()))));
        out.writeBytes(generator(53, sampleIndex));
        return count + 8;
    }

    private static byte[] presetHeader(String name, int preset, int bank, int bag) {
        byte[] record = new byte[38];
        writeName(record, name);
        writeShort(record, 20, preset);
        writeShort(record, 22, bank);
        writeShort(record, 24, bag);
        return record;
    }

    private static byte[] instrumentHeader(String name, int bag) {
        byte[] record = new byte[22];
        writeName(record, name);
        writeShort(record, 20, bag);
        return record;
    }

    private static byte[] sampleHeader(
        String name,
        int start,
        int end,
        int loopStart,
        int loopEnd,
        int rate,
        int root
    ) {
        byte[] record = new byte[46];
        writeName(record, name);
        writeInt(record, 20, start);
        writeInt(record, 24, end);
        writeInt(record, 28, loopStart);
        writeInt(record, 32, loopEnd);
        writeInt(record, 36, rate);
        record[40] = (byte) Math.max(0, Math.min(127, root));
        record[41] = 0;
        writeShort(record, 42, 0);
        writeShort(record, 44, 1);
        return record;
    }

    private static byte[] bag(int generator, int modulator) {
        byte[] record = new byte[4];
        writeShort(record, 0, generator);
        writeShort(record, 2, modulator);
        return record;
    }

    private static byte[] generator(int operator, int amount) {
        byte[] record = new byte[4];
        writeShort(record, 0, operator);
        writeShort(record, 2, amount);
        return record;
    }

    private static String name(String name, String fallback) {
        String text = name == null || name.isBlank() ? fallback : name.trim();
        return text.length() > 19 ? text.substring(0, 19) : text;
    }

    private static void writeName(byte[] record, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        int length = Math.min(19, bytes.length);
        System.arraycopy(bytes, 0, record, 0, length);
    }

    private static byte[] zstring(String text) {
        byte[] raw = text.getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[raw.length + 1];
        System.arraycopy(raw, 0, data, 0, raw.length);
        return data;
    }

    private static byte[] chunk(String id, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        if ((payload.length & 1) == 1) {
            byte[] even = new byte[payload.length + 1];
            System.arraycopy(payload, 0, even, 0, payload.length);
            payload = even;
        }
        byte[] out = new byte[8 + payload.length];
        writeFour(out, 0, id);
        writeInt(out, 4, payload.length);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] list(String type, byte[] body) {
        byte[] payload = new byte[4 + body.length];
        writeFour(payload, 0, type);
        System.arraycopy(body, 0, payload, 4, body.length);
        return chunk("LIST", payload);
    }

    private static byte[] riff(String type, byte[] body) {
        byte[] payload = new byte[4 + body.length];
        writeFour(payload, 0, type);
        System.arraycopy(body, 0, payload, 4, body.length);
        byte[] out = new byte[8 + payload.length];
        writeFour(out, 0, "RIFF");
        writeInt(out, 4, payload.length);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int cursor = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, cursor, part.length);
            cursor += part.length;
        }
        return out;
    }

    private static void writeFour(byte[] out, int offset, String id) {
        for (int i = 0; i < 4; i++) {
            out[offset + i] = i < id.length() ? (byte) id.charAt(i) : 0;
        }
    }

    private static void writeShort(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
        out[offset + 2] = (byte) ((value >> 16) & 0xff);
        out[offset + 3] = (byte) ((value >> 24) & 0xff);
    }
}
