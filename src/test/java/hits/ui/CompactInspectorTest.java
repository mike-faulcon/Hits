package hits.ui;

import hits.Beat;
import hits.BeatJson;
import hits.BuiltinSamples;
import hits.Editor;
import hits.EqBand;
import hits.ExportOptions;
import hits.PitchedPreset;
import hits.Player;
import hits.SampleRef;
import hits.SequenceBuilder;
import hits.Slide;
import hits.Track;
import hits.TrackMode;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sections follow the row, and a collapsed header still names the processing it hides. */
class CompactInspectorTest {
    @Test
    void summariesNameEachKindOfProcessing() {
        Track plain = new Track("Kick", TrackMode.DRUM, 0, 36);
        for (SectionSummary.Id id : SectionSummary.Id.values()) {
            assertEquals("", SectionSummary.summary(id, plain), id.name());
        }

        Track tone = new Track("Kick", TrackMode.DRUM, 0, 36);
        tone.setTuneParts(2, 0);
        assertEquals("Tune +2 st", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.setTuneParts(2, 10);
        assertEquals("Tune +2 st +10 ¢", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.setTuneParts(0, -40);
        assertEquals("Tune -40 ¢", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.setTune(0);
        tone.setDecay(80);
        assertEquals("Decay 80%", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.setDecay(Track.DECAY);
        tone.setCutoff(0);
        assertEquals("Cutoff 0", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.clearCutoff();
        tone.setResonance(90);
        assertEquals("Resonance 90", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.clearResonance();
        tone.setLegato(true);
        assertEquals("Legato", SectionSummary.summary(SectionSummary.Id.TONE, tone));
        tone.setTuneParts(-3, 0);
        tone.setDecay(150);
        tone.setCutoff(40);
        tone.setResonance(12);
        assertEquals(
            "Tune -3 st, Decay 150%, Cutoff 40, Resonance 12, Legato",
            SectionSummary.summary(SectionSummary.Id.TONE, tone)
        );

        Track filter = new Track("Kick", TrackMode.DRUM, 0, 36);
        filter.setLowCut(80, 12);
        filter.setEqBand(0, 120, -3, 1);
        filter.setEqBand(1, 2500, 2.5, 0.7, EqBand.Type.HIGH_SHELF, true);
        filter.setHighCut(5000, 12);
        assertEquals(
            "Low cut 80 Hz, 2 bands, High cut 5 kHz",
            SectionSummary.summary(SectionSummary.Id.FILTER, filter)
        );
        filter.setLowCut(80, 24);
        assertEquals(
            "Low cut 80 Hz, 24 dB/oct, 2 bands, High cut 5 kHz",
            SectionSummary.summary(SectionSummary.Id.FILTER, filter)
        );
        filter.clearLowCut();
        filter.clearEq();
        filter.setHighCut(2500, 6);
        assertEquals("High cut 2.5 kHz, 6 dB/oct", SectionSummary.summary(SectionSummary.Id.FILTER, filter));
        filter.clearHighCut();
        filter.setPresence(4.5);
        assertEquals("Presence +4.5 dB", SectionSummary.summary(SectionSummary.Id.FILTER, filter));
        filter.setPresence(0);
        filter.setEqBand(0, 400, -6, 1, EqBand.Type.LOW_SHELF, false);
        assertEquals("1 band", SectionSummary.summary(SectionSummary.Id.FILTER, filter));
        filter.setEqBypass(true);
        assertEquals("1 band, EQ bypassed", SectionSummary.summary(SectionSummary.Id.FILTER, filter));

        Track sound = new Track("Kick", TrackMode.DRUM, 0, 36);
        sound.setPreset(PitchedPreset.CLICK);
        assertEquals("Woodblock click", SectionSummary.summary(SectionSummary.Id.SOUND, sound));
        sound.setPreset(null);
        sound.setKit(8);
        assertEquals("Kit 8", SectionSummary.summary(SectionSummary.Id.SOUND, sound));
        sound.setKit(0);
        sound.setMode(TrackMode.NOTE);
        sound.setProgram(38);
        assertEquals("Syn Bass 1", SectionSummary.summary(SectionSummary.Id.SOUND, sound));
        sound.setProgram(0);
        sound.setBank(5);
        assertEquals("Bank 5", SectionSummary.summary(SectionSummary.Id.SOUND, sound));
        sound.setMode(TrackMode.SAMPLE);
        sound.setBank(0);
        sound.setSample(new SampleRef("/tmp/room-kick.wav", 60, 0, true));
        assertEquals("room-kick.wav", SectionSummary.summary(SectionSummary.Id.SOUND, sound));

        Track glide = new Track("Bass", TrackMode.NOTE, 38, 36);
        glide.step(3).setSlide(true);
        assertEquals("1 glide step", SectionSummary.summary(SectionSummary.Id.SLIDE, glide));
        glide.step(8).setSlide(true);
        glide.setSlideMs(120);
        glide.setSlideCurve(Slide.Curve.EXPONENTIAL);
        assertEquals(
            "2 glide steps, 120 ms, Exponential",
            SectionSummary.summary(SectionSummary.Id.SLIDE, glide)
        );
        Track drum = new Track("Kick", TrackMode.DRUM, 0, 36);
        drum.step(0).setSlide(true);
        assertEquals("", SectionSummary.summary(SectionSummary.Id.SLIDE, drum));

        Track sample = new Track("One", TrackMode.SAMPLE, 0, 60);
        sample.setSample(SampleRef.builtin(BuiltinSamples.CLICK, SampleRef.DEFAULT_ROOT));
        assertEquals("", SectionSummary.summary(SectionSummary.Id.SAMPLE, sample));
        sample.setSample(sample.sample().withRoot(48).withStart(100).withOneShot(false).withGain(0).withInvert(true));
        assertEquals(
            "Root C2, Start 100, Choke, Gain 0 dB, Invert",
            SectionSummary.summary(SectionSummary.Id.SAMPLE, sample)
        );
        Track sub = new Track("Sub", TrackMode.SAMPLE, 0, BuiltinSamples.SUB_NOTE);
        sub.setSample(SampleRef.builtin(BuiltinSamples.SUB, BuiltinSamples.SUB_NOTE));
        assertEquals("Root F#0", SectionSummary.summary(SectionSummary.Id.SAMPLE, sub));
    }

    @Test
    void sectionsFollowTheRowType() throws Exception {
        Beat beat = Beat.drumKit("rows");
        Editor editor = new Editor(beat);
        Player player = new Player();
        player.open();
        try {
            TrackInspector inspector = shown(editor, player);
            JComponent panel = inspector.component();
            assertSection(panel, "section-sound", true);
            assertSection(panel, "section-tone", true);
            assertSection(panel, "section-filter", true);
            assertSection(panel, "section-slide", false);
            assertSection(panel, "section-sample", false);
            assertTrue(section(panel, "section-sound").isOpen());
            assertFalse(section(panel, "section-tone").isOpen());
            assertFalse(section(panel, "section-filter").isOpen());

            editor.edit(current -> current.track(0).setMode(TrackMode.NOTE));
            SwingUtilities.invokeAndWait(inspector::refresh);
            assertSection(panel, "section-slide", true);
            assertSection(panel, "section-sample", false);
            assertFalse(section(panel, "section-slide").isOpen());

            editor.edit(current -> {
                Track track = current.track(0);
                track.setMode(TrackMode.SAMPLE);
                track.setSample(SampleRef.builtin(BuiltinSamples.CLICK, SampleRef.DEFAULT_ROOT));
            });
            SwingUtilities.invokeAndWait(inspector::refresh);
            assertSection(panel, "section-slide", true);
            assertSection(panel, "section-sample", true);
            assertFalse(section(panel, "section-sample").isOpen());
        } finally {
            player.close();
        }
    }

    @Test
    void anActiveSectionOpensUntilTheUserClosesItAndTheSummaryStays() throws Exception {
        Beat beat = Beat.drumKit("active");
        Editor editor = new Editor(beat);
        Player player = new Player();
        player.open();
        try {
            TrackInspector inspector = shown(editor, player);
            JComponent panel = inspector.component();
            beat.track(0).setLowCut(80, 12);
            beat.track(0).setEqBand(0, 120, -3, 1);
            beat.track(0).setEqBand(1, 2500, 2.5, 0.7, EqBand.Type.HIGH_SHELF, true);
            beat.track(0).setHighCut(5000, 12);
            SwingUtilities.invokeAndWait(inspector::refresh);

            InspectorSection filter = section(panel, "section-filter");
            assertTrue(filter.isOpen(), "active filter opens the first time");
            assertEquals("Low cut 80 Hz, 2 bands, High cut 5 kHz", filter.summaryText());
            JLabel summary = label(panel, "section-filter-summary");
            JLabel badge = label(panel, "section-filter-badge");
            assertTrue(summary.isVisible());
            assertTrue(summary.getText().contains("Low cut 80 Hz"));
            assertTrue(summary.getText().contains("2 bands"));
            assertTrue(summary.getText().contains("High cut 5 kHz"));
            assertEquals("active", badge.getText());
            assertTrue(badge.isVisible());
            assertEquals(
                "Filter & EQ, active, Low cut 80 Hz, 2 bands, High cut 5 kHz, expanded",
                filter.header().getAccessibleContext().getAccessibleName()
            );

            SwingUtilities.invokeAndWait(() -> press(filter.header()));
            assertFalse(filter.isOpen());
            assertFalse(filter.body().isVisible());
            assertTrue(filter.header().isVisible());
            assertTrue(summary.isVisible());
            assertTrue(badge.isVisible());
            assertTrue(summary.getText().contains("Low cut 80 Hz, 2 bands, High cut 5 kHz"));
            assertEquals(
                "Filter & EQ, active, Low cut 80 Hz, 2 bands, High cut 5 kHz, collapsed",
                filter.header().getAccessibleContext().getAccessibleName()
            );

            editor.selectTrack(1);
            beat.track(1).setLowCut(200, 24);
            SwingUtilities.invokeAndWait(inspector::refresh);
            assertFalse(filter.isOpen(), "a closed header stays closed on the next row");
            assertEquals("Low cut 200 Hz, 24 dB/oct", filter.summaryText());
            assertTrue(summary.getText().contains("Low cut 200 Hz"));
            assertTrue(filter.header().getAccessibleContext().getAccessibleName().contains("Low cut 200 Hz, 24 dB/oct"));
            assertTrue(filter.header().getAccessibleContext().getAccessibleName().contains("collapsed"));
        } finally {
            player.close();
        }
    }

    @Test
    void headersAreButtonsForEnterAndSpace() throws Exception {
        Editor editor = new Editor(Beat.drumKit("keys"));
        Player player = new Player();
        player.open();
        try {
            TrackInspector inspector = shown(editor, player);
            InspectorSection tone = section(inspector.component(), "section-tone");
            JComponent header = tone.header();
            assertTrue(header.isFocusable());
            assertNotNull(header.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
            assertNotNull(header.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0)));
            assertTrue(KeyboardShortcuts.sectionHeader(header));
            assertFalse(KeyboardShortcuts.spacePlays(header));
            assertTrue(KeyboardShortcuts.spacePlays(new JPanel()));
            assertFalse(tone.isOpen());

            SwingUtilities.invokeAndWait(() -> header.getActionMap().get("toggle-section").actionPerformed(
                new ActionEvent(header, ActionEvent.ACTION_PERFORMED, "toggle-section")
            ));
            assertTrue(tone.isOpen());
            assertEquals("Tone/Envelope, expanded", header.getAccessibleContext().getAccessibleName());

            SwingUtilities.invokeAndWait(() -> header.getAccessibleContext().getAccessibleAction().doAccessibleAction(0));
            assertFalse(tone.isOpen());
            assertEquals("Tone/Envelope, collapsed", header.getAccessibleContext().getAccessibleName());

            editor.beat().track(0).setLowCut(80, 12);
            SwingUtilities.invokeAndWait(inspector::refresh);
            InspectorSection filter = section(inspector.component(), "section-filter");
            JLabel summary = label(inspector.component(), "section-filter-summary");
            assertTrue(filter.isOpen(), "low cut opens the filter the first time");
            SwingUtilities.invokeAndWait(() -> click(summary));
            assertFalse(filter.isOpen());
            assertFalse(filter.body().isVisible());
            assertTrue(summary.isVisible());
            assertTrue(summary.getText().contains("Low cut 80 Hz"));
            assertTrue(filter.header().getAccessibleContext().getAccessibleName().contains("Low cut 80 Hz"));
            assertTrue(filter.header().getAccessibleContext().getAccessibleName().contains("collapsed"));
        } finally {
            player.close();
        }
    }

    @Test
    void editingAControlStillWritesTheSameRow() throws Exception {
        assertEdit("decay", (beat, inspector) -> {
            slider(inspector.component(), "decay").setValue(140);
            beat.track(0).setDecay(140);
        });
        assertEdit("presence", (beat, inspector) -> {
            int position = hits.Track.presenceSlider(4.5);
            slider(inspector.component(), "presence").setValue(position);
            beat.track(0).setPresence(hits.Track.presenceFromSlider(position));
        });
        assertEdit("tune", (beat, inspector) -> {
            spinner(inspector.component(), "tune-semitone").setValue(2);
            beat.track(0).setTuneParts(2, 0);
        });
        assertEdit("sound", (beat, inspector) -> {
            combo(inspector.component(), "drum-sound").setSelectedIndex(1);
            beat.track(0).setPreset(null);
            beat.track(0).setNote(38);
        });
    }

    @Test
    void slideAndSampleEditsMatchTheModel() throws Exception {
        Player player = new Player();
        player.open();
        try {
            Beat beat = Beat.drumKit("slide-edit");
            beat.track(0).setMode(TrackMode.NOTE);
            Editor editor = new Editor(beat);
            editor.selectStep(0);
            TrackInspector inspector = shown(editor, player);
            inspector.wire();
            SwingUtilities.invokeAndWait(inspector::refresh);
            Beat expected = editor.beat().copy();
            hits.Step step = expected.slot(editor.viewSlot())[0].step(0);
            step.setOn(true);
            step.setSlide(true);
            SwingUtilities.invokeAndWait(() -> box(inspector.component(), "slide").doClick());
            assertEquals(BeatJson.write(expected), BeatJson.write(editor.beat()));

            Beat sampleBeat = Beat.drumKit("sample-edit");
            sampleBeat.track(0).setMode(TrackMode.SAMPLE);
            sampleBeat.track(0).setSample(SampleRef.builtin(BuiltinSamples.CLICK, SampleRef.DEFAULT_ROOT));
            Editor sampleEditor = new Editor(sampleBeat);
            TrackInspector sampleInspector = shown(sampleEditor, player);
            sampleInspector.wire();
            SwingUtilities.invokeAndWait(sampleInspector::refresh);
            int gain = SampleRef.gainSlider(0);
            Beat expectedSample = sampleEditor.beat().copy();
            expectedSample.track(0).setSample(expectedSample.track(0).sample().withGain(SampleRef.gainFromSlider(gain)));
            SwingUtilities.invokeAndWait(() -> slider(sampleInspector.component(), "sample-gain").setValue(gain));
            assertEquals(BeatJson.write(expectedSample), BeatJson.write(sampleEditor.beat()));
        } finally {
            player.close();
        }
    }

    @Test
    void showingTheInspectorDoesNotChangeTheBeat() throws Exception {
        Beat beat = CompactInspectorShot.example();
        beat.track(6).setName("Bass");
        beat.track(6).setMode(TrackMode.NOTE);
        beat.track(6).setProgram(38);
        beat.track(6).setNote(36);
        beat.track(6).step(0).setOn(true);
        beat.track(6).step(0).setSlide(true);
        beat.track(6).setSlideMs(120);
        beat.track(7).setName("Snap");
        beat.track(7).setMode(TrackMode.SAMPLE);
        beat.track(7).setSample(new SampleRef(BuiltinSamples.SNAP, 48, 10, false, 0, true));
        String json = BeatJson.write(beat);
        byte[] midi = midi(beat);
        String fixture = Files.readString(Path.of("src/test/resources/slots/ab-plain.json"));
        Beat plain = BeatJson.read(fixture);
        String plainJson = BeatJson.write(plain);
        byte[] plainMidi = midi(plain);

        Player player = new Player();
        player.open();
        try {
            Editor editor = new Editor(BeatJson.read(json));
            TrackInspector inspector = shown(editor, player);
            SwingUtilities.invokeAndWait(() -> {
                inspector.refresh();
                press(section(inspector.component(), "section-filter").header());
                press(section(inspector.component(), "section-sound").header());
            });
            assertEquals(json, BeatJson.write(editor.beat()));
            assertArrayEquals(midi, midi(editor.beat()));

            Editor plainEditor = new Editor(BeatJson.read(fixture));
            TrackInspector plainInspector = shown(plainEditor, player);
            SwingUtilities.invokeAndWait(plainInspector::refresh);
            assertEquals(plainJson, BeatJson.write(plainEditor.beat()));
            assertArrayEquals(plainMidi, midi(plainEditor.beat()));
        } finally {
            player.close();
        }
    }

    @Test
    void theColumnFitsWithoutAHorizontalScroll() throws Exception {
        Beat beat = CompactInspectorShot.example();
        beat.track(0).setMode(TrackMode.SAMPLE);
        beat.track(0).setSample(new SampleRef("/tmp/room-kick.wav", 48, 20, false, -3, true));
        beat.track(0).setTuneParts(7, 0);
        beat.track(0).setDecay(150);
        beat.track(0).setCutoff(40);
        beat.track(0).step(2).setSlide(true);
        Editor editor = new Editor(beat);
        Player player = new Player();
        player.open();
        try {
            TrackInspector inspector = shown(editor, player);
            JComponent panel = inspector.component();
            assertTrue(panel instanceof javax.swing.Scrollable);
            javax.swing.Scrollable scrollable = (javax.swing.Scrollable) panel;
            assertTrue(scrollable.getScrollableTracksViewportWidth());
            assertFalse(scrollable.getScrollableTracksViewportHeight());
            int expanded = panel.getPreferredSize().height;
            panel.setSize(new Dimension(300, expanded));
            panel.validate();
            assertFits(panel);
            SwingUtilities.invokeAndWait(() -> {
                for (String name : new String[] {
                    "section-sound", "section-tone", "section-filter", "section-slide", "section-sample"
                }) {
                    InspectorSection section = section(panel, name);
                    if (section.isOpen()) {
                        press(section.header());
                    }
                }
            });
            int compact = panel.getPreferredSize().height;
            assertTrue(expanded > compact, "expanded sections are taller than the compact column");
            assertTrue(panel.getMinimumSize().width <= 300, "minimum width " + panel.getMinimumSize().width);
        } finally {
            player.close();
        }
    }

    private static void assertEdit(String name, Edit edit) throws Exception {
        Beat beat = Beat.drumKit(name);
        Editor editor = new Editor(beat);
        Player player = new Player();
        player.open();
        try {
            TrackInspector inspector = shown(editor, player);
            inspector.wire();
            SwingUtilities.invokeAndWait(inspector::refresh);
            Beat expected = editor.beat().copy();
            SwingUtilities.invokeAndWait(() -> edit.apply(expected, inspector));
            assertEquals(BeatJson.write(expected), BeatJson.write(editor.beat()), name);
        } finally {
            player.close();
        }
    }

    private interface Edit {
        void apply(Beat expected, TrackInspector inspector);
    }

    private static TrackInspector shown(Editor editor, Player player) throws Exception {
        TrackInspector inspector = new TrackInspector(editor, player, new Sync());
        SwingUtilities.invokeAndWait(() -> {
            inspector.component();
            inspector.refresh();
        });
        return inspector;
    }

    private static void assertSection(JComponent panel, String name, boolean visible) {
        InspectorSection section = section(panel, name);
        assertEquals(visible, section.isVisible(), name);
    }

    private static InspectorSection section(JComponent panel, String name) {
        InspectorSection found = find(panel, name, InspectorSection.class);
        assertNotNull(found, name);
        return found;
    }

    private static JLabel label(JComponent panel, String name) {
        JLabel found = find(panel, name, JLabel.class);
        assertNotNull(found, name);
        return found;
    }

    private static JSlider slider(JComponent panel, String name) {
        JSlider found = find(panel, name, JSlider.class);
        assertNotNull(found, name);
        return found;
    }

    private static JSpinner spinner(JComponent panel, String name) {
        JSpinner found = find(panel, name, JSpinner.class);
        assertNotNull(found, name);
        return found;
    }

    private static JComboBox<?> combo(JComponent panel, String name) {
        JComboBox<?> found = find(panel, name, JComboBox.class);
        assertNotNull(found, name);
        return found;
    }

    private static javax.swing.JCheckBox box(JComponent panel, String name) {
        javax.swing.JCheckBox found = find(panel, name, javax.swing.JCheckBox.class);
        assertNotNull(found, name);
        return found;
    }

    private static void press(JComponent header) {
        for (MouseListener listener : header.getMouseListeners()) {
            listener.mouseClicked(new MouseEvent(header, MouseEvent.MOUSE_CLICKED, 0, 0, 4, 4, 1, false));
        }
    }

    private static void click(JComponent component) {
        press(component);
    }

    private static void assertFits(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            int right = child.getX() + child.getWidth();
            assertTrue(right <= parent.getWidth() + 1, describe(child) + " ends at " + right + " in " + parent.getWidth());
            assertTrue(child.getX() >= -1, describe(child));
            if (child instanceof Container nested && nested.getComponentCount() > 0 && nested.getWidth() > 0) {
                assertFits(nested);
            }
        }
    }

    private static String describe(Component child) {
        String name = child.getName();
        return name == null ? child.getClass().getSimpleName() : name;
    }

    private static byte[] midi(Beat beat) throws Exception {
        Sequence sequence = SequenceBuilder.export(beat, ExportOptions.allTracks());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MidiSystem.write(sequence, 1, out);
        return out.toByteArray();
    }

    private static <T> T find(Component root, String name, Class<T> type) {
        if (type.isInstance(root) && name.equals(root.getName())) {
            return type.cast(root);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
