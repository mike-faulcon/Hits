package hits.ui;

import hits.Beat;
import hits.Editor;

import javax.swing.JSlider;
import java.util.function.Consumer;

/** One slider gesture is a single undo step. A click that is not a drag is a normal edit. */
final class SliderDrag {
    private final Editor editor;
    private final Sync sync;
    private boolean dragging;

    SliderDrag(Editor editor, Sync sync) {
        this.editor = editor;
        this.sync = sync;
    }

    void wire(JSlider slider, Consumer<Beat> apply) {
        slider.addChangeListener(event -> {
            if (sync.on()) {
                return;
            }
            if (slider.getValueIsAdjusting()) {
                if (!dragging) {
                    dragging = true;
                    editor.beginAdjust();
                }
                editor.adjust(apply);
            } else if (dragging) {
                dragging = false;
                // Clear the gesture before the last apply so playback rebuilds a sample
                // bank once, when the slider settles, instead of on this same tick.
                editor.endAdjust();
                editor.adjust(apply);
            } else {
                editor.edit(apply);
            }
        });
    }
}
