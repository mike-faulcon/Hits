package hits.ui;

import hits.Beat;
import hits.Tempo;

import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.text.ParseException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The tempo spinner steps by 1 and still shows a fractional BPM. */
class TempoControlTest {
    @Test
    void arrowsStepByOneAndTheFieldKeepsHundredths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JSpinner spinner = TransportBar.bpmSpinner();
            JSpinner.NumberEditor editor = (JSpinner.NumberEditor) spinner.getEditor();
            spinner.setValue(96.0);
            assertEquals("96", editor.getTextField().getText());
            assertEquals(97.0, ((Number) spinner.getModel().getNextValue()).doubleValue(), 0.0);
            assertEquals(95.0, ((Number) spinner.getModel().getPreviousValue()).doubleValue(), 0.0);

            spinner.setValue(92.5);
            assertEquals("92.5", editor.getTextField().getText());
            assertEquals(93.5, ((Number) spinner.getModel().getNextValue()).doubleValue(), 0.0);

            spinner.setValue(98.33);
            assertEquals("98.33", editor.getTextField().getText());
            assertEquals(98.33, ((Number) spinner.getValue()).doubleValue(), 0.0);
            assertEquals(9833, Tempo.centi(((Number) spinner.getValue()).doubleValue()));

            editor.getTextField().setText("92.5");
            try {
                spinner.commitEdit();
            } catch (ParseException exception) {
                throw new AssertionError(exception);
            }
            assertEquals(92.5, ((Number) spinner.getValue()).doubleValue(), 0.0);
            SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
            assertEquals(Beat.MIN_BPM, ((Number) model.getMinimum()).doubleValue(), 0.0);
            assertEquals(Beat.MAX_BPM, ((Number) model.getMaximum()).doubleValue(), 0.0);
        });
    }
}
