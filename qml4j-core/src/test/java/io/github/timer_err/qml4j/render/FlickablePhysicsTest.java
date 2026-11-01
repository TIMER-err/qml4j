package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.core.Flickable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlickablePhysicsTest {
    private static Flickable flickable() {
        Flickable f = new Flickable();
        f.width.set(300);
        f.height.set(400);
        f.contentWidth.set(3000);
        f.contentHeight.set(6000);
        f.contentX.set(500f);
        f.contentY.set(800f);
        f.syncTarget();
        return f;
    }

    @Test
    void displayedPositionIntegratesReleaseVelocityWithoutTargetLag() {
        Flickable f = flickable();
        f.startFling(0f, 1000f);
        f.tick(0L);
        f.tick(100_000_000L);
        double distance = 1000d * -Math.expm1(-4.2d * 0.1d) / 4.2d;
        assertEquals(800d + distance, f.contentY.peekDouble(), 0.001d);
        assertEquals(f.contentY.peekFloat(), f.targetY());
        assertTrue(f.moving.peek());
    }

    @Test
    void refreshRatesAndAStalledFrameHaveTheSameEndPosition() {
        double expected = 800d + (1000d - 5d) / 4.2d;
        for (long frame : new long[] {8_333_333L, 16_666_667L, 33_333_333L, 2_000_000_000L}) {
            Flickable f = flickable();
            f.startFling(0f, 1000f);
            f.tick(0L);
            for (long time = frame; time < 2_000_000_000L; time += frame) f.tick(time);
            f.tick(2_000_000_000L);
            assertEquals(expected, f.contentY.peekDouble(), 0.02d, "frame interval " + frame);
            assertFalse(f.moving.peek());
            float settled = f.contentY.peekFloat();
            f.tick(5_000_000_000L);
            assertEquals(settled, f.contentY.peekFloat(), "no second target-chasing phase");
        }
    }

    @Test
    void stopAndRestartDoNotReuseTheOldAnimationClockOrVelocity() {
        Flickable f = flickable();
        f.startFling(0f, 1000f);
        f.tick(0L);
        f.tick(100_000_000L);
        f.stopScroll();
        float stopped = f.contentY.peekFloat();
        assertFalse(f.moving.peek());
        assertEquals(stopped, f.targetY());
        f.tick(20_000_000_000L);
        assertEquals(stopped, f.contentY.peekFloat());

        f.startFling(0f, -500f);
        f.tick(30_000_000_000L);
        assertEquals(stopped, f.contentY.peekFloat(), "new fling establishes its own clock");
        f.tick(30_100_000_000L);
        assertEquals(stopped - 500d * -Math.expm1(-0.42d) / 4.2d,
            f.contentY.peekDouble(), 0.001d);
    }

    @Test
    void directPositionTakeoverDiscardsBothKindsOfAnimation() {
        for (boolean fling : new boolean[] {false, true}) {
            Flickable f = flickable();
            if (fling) f.startFling(0f, 1500f); else f.setScrollTarget(500f, 1800f);
            f.tick(0L);
            f.tick(100_000_000L);
            f.contentY.setPaintOnly(175f);
            f.syncTarget();
            assertFalse(f.moving.peek());
            assertEquals(175f, f.targetY());
            f.tick(500_000_000L);
            f.tick(10_000_000_000L);
            assertEquals(175f, f.contentY.peekFloat());
        }
    }

    @Test
    void allowedAxesAndBoundsStopWithoutLeavingMovingStuck() {
        Flickable f = flickable();
        f.flickableDirection.set("VerticalFlick");
        f.startFling(4000f, -2000f);
        f.tick(0L);
        f.tick(2_000_000_000L);
        assertEquals(500f, f.contentX.peekFloat(), "disabled axis stays fixed");
        assertTrue(f.contentY.peekFloat() > 0f, "this fling stops before the boundary");
        assertFalse(f.moving.peek());

        f.contentY.set(20f);
        f.syncTarget();
        f.startFling(4000f, -2000f);
        f.tick(3_000_000_000L);
        f.tick(3_100_000_000L);
        assertEquals(0f, f.contentY.peekFloat());
        assertEquals(0f, f.targetY());
        assertFalse(f.moving.peek());
        f.startFling(4000f, -2000f);
        assertFalse(f.moving.peek(), "an outward or disallowed velocity cannot restart motion");
    }

    @Test
    void shrinkingContentClampsAndStopsTheFlingAtTheNewEdge() {
        Flickable f = flickable();
        f.startFling(0f, 1000f);
        f.tick(0L);
        f.tick(100_000_000L);
        f.contentHeight.set(600);
        assertEquals(200f, f.contentY.peekFloat());
        assertEquals(200f, f.targetY());
        assertFalse(f.moving.peek());
        f.tick(2_000_000_000L);
        assertEquals(200f, f.contentY.peekFloat());
    }

    @Test
    void explicitSmoothTargetsStillEaseAndRespectAxisRestrictions() {
        Flickable f = flickable();
        f.flickableDirection.set("VerticalFlick");
        f.setScrollTarget(1000f, 1600f);
        f.tick(0L);
        f.tick(50_000_000L);
        assertEquals(500f, f.contentX.peekFloat());
        assertTrue(f.contentY.peekFloat() > 800f && f.contentY.peekFloat() < 1600f);
        assertTrue(f.moving.peek());
        f.tick(2_000_000_000L);
        assertEquals(1600f, f.contentY.peekFloat());
        assertFalse(f.moving.peek());
    }

    @Test
    void continuousSmoothRetargetingKeepsAdvancingOnEveryFrame() {
        Flickable f = flickable();
        f.setScrollTarget(500f, 1600f);
        f.tick(0L);
        double expected = 800d;
        long frame = 16_666_667L;
        double factor = -Math.expm1(-18d * frame / 1_000_000_000d);
        for (int i = 1; i <= 5; i++) {
            f.nudge(0f, 100f);
            expected += (1600d + i * 100d - expected) * factor;
            f.tick(i * frame);
            assertEquals(expected, f.contentY.peekDouble(), 0.001d);
            assertTrue(f.moving.peek());
        }
        assertEquals(2100f, f.targetY());
    }
}
