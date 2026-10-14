package hits.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityGestureTest {
    @Test
    void dragAndLaneMapOntoTheMidiRange() {
        assertEquals(105, VelocityGesture.fromDrag(100, -10));
        assertEquals(95, VelocityGesture.fromDrag(100, 10));
        assertEquals(1, VelocityGesture.fromDrag(1, 80));
        assertEquals(127, VelocityGesture.fromDrag(127, -400));

        assertEquals(127, VelocityGesture.fromLane(20, 20, 44));
        assertEquals(1, VelocityGesture.fromLane(63, 20, 44));
        assertEquals(127, VelocityGesture.fromLane(0, 20, 44));
        assertEquals(1, VelocityGesture.fromLane(200, 20, 44));

        assertEquals(40, VelocityGesture.fillHeight(127, 40));
        assertEquals(3, VelocityGesture.fillHeight(1, 40));
        assertEquals(6, VelocityGesture.DRAG_SLOP);
    }
}
