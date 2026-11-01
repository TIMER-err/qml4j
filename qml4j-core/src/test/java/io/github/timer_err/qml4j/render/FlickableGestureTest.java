package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import io.github.timer_err.qml4j.render.items.window.AbstractButton;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlickableGestureTest {
    // Deliberately different epochs: Android event uptime and the render clock
    // must never be subtracted from one another.
    private static final long INPUT_EPOCH = 37_000_000L;
    private static final long RENDER_EPOCH = 9_000_000_000_000_000L;

    private static QmlView scene(boolean child) {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        Flickable f = (Flickable) view.load("import QtQuick\n"
            + "Flickable { width: 300; height: 400; contentWidth: 300; contentHeight: 6000\n"
            + " flickableDirection: \"VerticalFlick\"\n"
            + (child ? " MouseArea { width: 300; height: 6000 }\n" : "")
            + "}");
        f.contentY.set(800f);
        f.syncTarget();
        return view;
    }

    private static Flickable flickable(QmlView view) {
        return (Flickable) view.root();
    }

    private static void flick(QmlView view, int sampleMillis) {
        assertTrue(view.dispatchPointerDown(150f, 300f, INPUT_EPOCH));
        for (int ms = sampleMillis; ms < 100; ms += sampleMillis) {
            view.dispatchPointerMove(150f, 300f - ms, INPUT_EPOCH + ms * 1_000_000L);
        }
        view.dispatchPointerMove(150f, 200f, INPUT_EPOCH + 100_000_000L);
        view.dispatchPointerUp(150f, 200f, INPUT_EPOCH + 100_000_000L);
    }

    @Test
    void sameGestureAtDifferentInputAndFrameRatesTravelsTheSameDistance() {
        for (int sampleMillis : new int[] {4, 8, 16, 33}) {
            for (long frameNanos : new long[] {8_333_333L, 16_666_667L, 33_333_333L}) {
                QmlView view = scene(false);
                flick(view, sampleMillis);
                Flickable f = flickable(view);
                assertEquals(900f, f.contentY.peekFloat(), "direct drag remains 1:1");
                assertTrue(f.moving.peek());
                view.tickAnimations(RENDER_EPOCH);
                for (long elapsed = frameNanos; elapsed < 2_000_000_000L; elapsed += frameNanos) {
                    view.tickAnimations(RENDER_EPOCH + elapsed);
                }
                view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
                assertEquals(900d + (1000d - 5d) / 4.2d, f.contentY.peekDouble(), 0.03d,
                    "sample ms " + sampleMillis + ", frame ns " + frameNanos);
                assertFalse(f.moving.peek());
            }
        }
    }

    @Test
    void pressingAChildStopsTheOldFlingBeforeItsPressedHandler() {
        QmlView view = scene(true);
        Flickable f = flickable(view);
        MouseArea child = (MouseArea) f.children.get(0);
        f.startFling(0f, 1500f);
        view.tickAnimations(RENDER_EPOCH);
        view.tickAnimations(RENDER_EPOCH + 100_000_000L);
        float stopped = f.contentY.peekFloat();
        child.pressedSignal.connect(() -> assertFalse(f.moving.peek()));
        assertTrue(view.dispatchPointerDown(150f, 200f, INPUT_EPOCH));
        assertFalse(f.moving.peek());
        view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
        assertEquals(stopped, f.contentY.peekFloat());
        view.dispatchPointerCancel();
    }

    @Test
    void holdingStillBeforeReleaseDoesNotRestartTheDragVelocity() {
        QmlView view = scene(false);
        Flickable f = flickable(view);
        view.dispatchPointerDown(150f, 300f, INPUT_EPOCH);
        view.dispatchPointerMove(150f, 200f, INPUT_EPOCH + 100_000_000L);
        view.dispatchPointerUp(150f, 200f, INPUT_EPOCH + 600_000_000L);
        assertEquals(900f, f.contentY.peekFloat());
        assertFalse(f.moving.peek());
        view.tickAnimations(RENDER_EPOCH);
        view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
        assertEquals(900f, f.contentY.peekFloat());
    }

    @Test
    void aReversedGestureNeverCoastsInTheOldDirection() {
        QmlView view = scene(false);
        Flickable f = flickable(view);
        view.dispatchPointerDown(150f, 300f, INPUT_EPOCH);
        view.dispatchPointerMove(150f, 250f, INPUT_EPOCH + 50_000_000L);
        view.dispatchPointerMove(150f, 200f, INPUT_EPOCH + 100_000_000L);
        view.dispatchPointerMove(150f, 210f, INPUT_EPOCH + 110_000_000L);
        view.dispatchPointerMove(150f, 220f, INPUT_EPOCH + 120_000_000L);
        view.dispatchPointerUp(150f, 230f, INPUT_EPOCH + 130_000_000L);
        assertEquals(870f, f.contentY.peekFloat());
        view.tickAnimations(RENDER_EPOCH);
        float previous = f.contentY.peekFloat();
        for (int ms = 16; ms <= 1600; ms += 16) {
            view.tickAnimations(RENDER_EPOCH + ms * 1_000_000L);
            assertTrue(f.contentY.peekFloat() <= previous, "old direction cannot leak past the turn");
            previous = f.contentY.peekFloat();
        }
        assertEquals(870d - (1000d - 5d) / 4.2d, f.contentY.peekDouble(), 0.03d);
        assertFalse(f.moving.peek());
    }

    @Test
    void cancelClearsMouseCaptureWithoutReleaseOrClick() {
        QmlView view = scene(true);
        Flickable f = flickable(view);
        MouseArea child = (MouseArea) f.children.get(0);
        int[] events = new int[3];
        child.canceled.connect(() -> events[0]++);
        child.released.connect(() -> events[1]++);
        child.clicked.connect(() -> events[2]++);
        view.dispatchPointerDown(150f, 200f, INPUT_EPOCH);
        view.dispatchPointerMove(150f, 195f, INPUT_EPOCH + 20_000_000L);
        assertTrue(view.dispatchPointerCancel());
        assertFalse(child.pressed.peek());
        assertFalse(child.containsMouse.peek());
        assertFalse(f.moving.peek());
        assertEquals(1, events[0]);
        assertEquals(0, events[1]);
        assertEquals(0, events[2]);
        assertFalse(view.dispatchPointerUp(150f, 195f, INPUT_EPOCH + 40_000_000L));
        assertEquals(0, events[2]);
    }

    @Test
    void cancelStopsADragAndAnAlreadyReleasedFlingWithoutStartingAnother() {
        for (boolean release : new boolean[] {false, true}) {
            QmlView view = scene(false);
            Flickable f = flickable(view);
            view.dispatchPointerDown(150f, 300f, INPUT_EPOCH);
            view.dispatchPointerMove(150f, 200f, INPUT_EPOCH + 100_000_000L);
            if (release) view.dispatchPointerUp(150f, 200f, INPUT_EPOCH + 100_000_000L);
            assertTrue(f.moving.peek());
            assertTrue(view.dispatchPointerCancel());
            assertFalse(f.moving.peek());
            assertFalse(view.dispatchPointerUp(150f, 180f, INPUT_EPOCH + 120_000_000L));
            view.tickAnimations(RENDER_EPOCH);
            view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
            assertEquals(900f, f.contentY.peekFloat());
        }
    }

    @Test
    void canceledButtonDoesNotActivateOrStayPressed() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        AbstractButton button = (AbstractButton) view.load("import QtQuick.Controls\n"
            + "Button { width: 200; height: 100; checkable: true }");
        int[] clicks = new int[1];
        button.clicked.connect(() -> clicks[0]++);
        view.dispatchPointerDown(50f, 50f, INPUT_EPOCH);
        assertTrue(button.down.peek());
        assertTrue(view.dispatchPointerCancel());
        assertFalse(button.down.peek());
        assertFalse(button.checked.peek());
        assertFalse(view.dispatchPointerUp(50f, 50f, INPUT_EPOCH + 100_000_000L));
        assertEquals(0, clicks[0]);
    }

    @Test
    void subSlopMotionStillClicksTheChildWithoutScrollingOrFlinging() {
        QmlView view = scene(true);
        Flickable f = flickable(view);
        MouseArea child = (MouseArea) f.children.get(0);
        int[] clicks = new int[1];
        child.clicked.connect(() -> clicks[0]++);
        view.dispatchPointerDown(150f, 200f, INPUT_EPOCH);
        view.dispatchPointerMove(150f, 195f, INPUT_EPOCH + 10_000_000L);
        assertTrue(child.pressed.peek());
        view.dispatchPointerUp(150f, 195f, INPUT_EPOCH + 20_000_000L);
        assertEquals(1, clicks[0]);
        assertFalse(child.pressed.peek());
        assertFalse(f.moving.peek());
        view.tickAnimations(RENDER_EPOCH);
        view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
        assertEquals(800f, f.contentY.peekFloat());
        view.dispose();
    }

    @Test
    void childHandoffPreservesTheWholeGestureAndVelocityAtDifferentInputRates() {
        for (int sampleMillis : new int[]{4, 8, 16, 33, 100}) {
            QmlView view = scene(true);
            try {
                Flickable f = flickable(view);
                MouseArea child = (MouseArea) f.children.get(0);
                int[] events = new int[2];
                child.canceled.connect(() -> events[0]++);
                child.clicked.connect(() -> events[1]++);
                flick(view, sampleMillis);
                assertEquals(900f, f.contentY.peekFloat(),
                        "crossing slop must retain the first movement, even in a single coalesced move");
                assertFalse(child.pressed.peek());
                assertEquals(1, events[0]);
                assertEquals(0, events[1]);
                assertTrue(f.moving.peek(), "handoff must retain the original input history");
                view.tickAnimations(RENDER_EPOCH);
                view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
                assertEquals(900d + (1000d - 5d) / 4.2d, f.contentY.peekDouble(), 0.03d);
                assertFalse(f.moving.peek());
            } finally {
                view.dispose();
            }
        }
    }

    @Test
    void nestedAxisSelectionAppliesTheFirstMoveToTheSelectedViewport() {
        for (boolean horizontal : new boolean[]{false, true}) {
            QmlView view = QmlView.withStockTypes(new QmlEngine());
            try {
                Flickable outer = (Flickable) view.load("import QtQuick\n"
                        + "Flickable { width: 300; height: 400; contentWidth: 300; contentHeight: 6000;"
                        + " flickableDirection: \"VerticalFlick\"\n"
                        + " Flickable { objectName: \"inner\"; width: 300; height: 400;"
                        + " contentWidth: 2000; contentHeight: 400; flickableDirection: \"HorizontalFlick\" }\n"
                        + "}");
                Flickable inner = (Flickable) view.findByObjectName("inner");
                view.dispatchPointerDown(200f, 200f, INPUT_EPOCH);
                view.dispatchPointerMove(horizontal ? 50f : 200f, horizontal ? 200f : 50f,
                        INPUT_EPOCH + 80_000_000L);
                assertEquals(horizontal ? 150f : 0f, inner.contentX.peekFloat());
                assertEquals(horizontal ? 0f : 150f, outer.contentY.peekFloat());
                view.dispatchPointerCancel();
                assertFalse(inner.moving.peek());
                assertFalse(outer.moving.peek());
            } finally {
                view.dispose();
            }
        }
    }

    @Test
    void wheelTakesOverFromTheOldFlingWithoutItsVelocityReturning() {
        QmlView view = scene(false);
        flick(view, 10);
        Flickable f = flickable(view);
        view.tickAnimations(RENDER_EPOCH);
        view.tickAnimations(RENDER_EPOCH + 100_000_000L);
        float beforeWheel = f.contentY.peekFloat();
        assertTrue(view.dispatchWheel(150f, 200f, 0f, -0.25f));
        assertEquals(beforeWheel + 12f, f.contentY.peekFloat());
        assertFalse(f.moving.peek());
        view.tickAnimations(RENDER_EPOCH + 2_000_000_000L);
        assertEquals(beforeWheel + 12f, f.contentY.peekFloat());
    }
}
