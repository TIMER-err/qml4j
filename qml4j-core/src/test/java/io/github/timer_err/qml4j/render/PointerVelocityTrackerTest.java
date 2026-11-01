package io.github.timer_err.qml4j.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerVelocityTrackerTest {
    private static final long EPOCH = 6_000_000_000_000_000L;

    @Test
    void samplingFrequencyAndIrregularDeliveryDoNotChangeConstantVelocity() {
        for (int hz : new int[] {30, 60, 120, 240}) {
            PointerVelocityTracker tracker = new PointerVelocityTracker();
            tracker.reset(0f, 0f, EPOCH);
            for (int i = 1; i <= hz; i++) {
                long elapsed = 1_000_000_000L * i / hz;
                float seconds = elapsed / 1_000_000_000f;
                tracker.add(750f * seconds, -1200f * seconds, EPOCH + elapsed);
            }
            assertEquals(750f, tracker.velocityX(), 0.1f, "sample rate " + hz);
            assertEquals(-1200f, tracker.velocityY(), 0.1f, "sample rate " + hz);
        }
        PointerVelocityTracker batched = new PointerVelocityTracker();
        for (int ms : new int[] {0, 7, 19, 20, 43, 61, 62, 77, 91, 100}) {
            // No processing delay is simulated: the original event clock alone
            // determines the velocity, even when these samples arrive together.
            batched.add(ms * 0.75f, ms * -1.2f, EPOCH + ms * 1_000_000L);
        }
        assertEquals(750f, batched.velocityX(), 0.1f);
        assertEquals(-1200f, batched.velocityY(), 0.1f);
    }

    @Test
    void ringWrapRetainsOnlyRecentMotion() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        for (int i = 0; i < 1000; i++) {
            tracker.add(i * 2f, i * -3f, EPOCH + i * 2_000_000L);
        }
        assertEquals(1000f, tracker.velocityX(), 0.1f);
        assertEquals(-1500f, tracker.velocityY(), 0.1f);
    }

    @Test
    void pauseAndStationarySamplesRemoveReleaseVelocity() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(100f, 0f, EPOCH + 100_000_000L);
        assertEquals(1000f, tracker.velocityX(), 0.1f);
        tracker.add(100f, 0f, EPOCH + 600_000_000L);
        assertEquals(0f, tracker.velocityX());

        tracker.reset(0f, 0f, EPOCH);
        tracker.add(50f, 0f, EPOCH + 50_000_000L);
        for (int ms = 60; ms <= 140; ms += 10) {
            tracker.add(50f, 0f, EPOCH + ms * 1_000_000L);
        }
        assertEquals(0f, tracker.velocityX());
    }

    @Test
    void releasePreservesTheFitUntilTheStopTimeoutWithoutAddingAStationarySample() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(50f, -100f, EPOCH + 50_000_000L);
        tracker.add(100f, -200f, EPOCH + 100_000_000L);
        for (long elapsed : new long[] {100_000_000L, 104_000_000L, 179_999_999L}) {
            tracker.release(EPOCH + elapsed);
            assertEquals(1000f, tracker.velocityX(), 0.1f);
            assertEquals(-2000f, tracker.velocityY(), 0.1f);
        }
        tracker.release(EPOCH + 180_000_000L);
        assertEquals(0f, tracker.velocityX());
        assertEquals(0f, tracker.velocityY());
    }

    @Test
    void releaseAgesEachAxisFromItsLastMotionRatherThanItsLastSample() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(50f, 100f, EPOCH + 50_000_000L);
        tracker.add(50f, 200f, EPOCH + 100_000_000L);
        tracker.release(EPOCH + 129_999_999L);
        assertTrue(tracker.velocityX() > 0f);
        assertEquals(2000f, tracker.velocityY(), 0.1f);
        tracker.release(EPOCH + 130_000_000L);
        assertEquals(0f, tracker.velocityX());
        assertEquals(2000f, tracker.velocityY(), 0.1f);
        tracker.release(EPOCH + 180_000_000L);
        assertEquals(0f, tracker.velocityY());
    }

    @Test
    void backwardsReleaseDiscardsHistoryIncludingAnEarlierReleaseClock() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        for (boolean afterRelease : new boolean[] {false, true}) {
            tracker.reset(0f, 0f, EPOCH);
            tracker.add(50f, -50f, EPOCH + 50_000_000L);
            if (afterRelease) tracker.release(EPOCH + 70_000_000L);
            tracker.release(EPOCH + (afterRelease ? 60_000_000L : 49_000_000L));
            assertEquals(0f, tracker.velocityX());
            assertEquals(0f, tracker.velocityY());
            tracker.add(80f, -80f, EPOCH + 80_000_000L);
            assertEquals(0f, tracker.velocityX(), "cleared history cannot reappear");
        }
    }

    @Test
    void addResetAndClearKeepTheReleaseClockCoherent() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(50f, 0f, EPOCH + 50_000_000L);
        tracker.release(EPOCH + 70_000_000L);
        tracker.add(60f, 0f, EPOCH + 60_000_000L);
        assertEquals(0f, tracker.velocityX(), "a move before the release clock starts new history");
        tracker.add(80f, 0f, EPOCH + 80_000_000L);
        tracker.release(EPOCH + 159_999_999L);
        assertEquals(1000f, tracker.velocityX(), 0.1f);
        tracker.release(EPOCH + 160_000_000L);
        assertEquals(0f, tracker.velocityX());

        tracker.reset(0f, 0f, EPOCH);
        tracker.add(20f, 0f, EPOCH + 20_000_000L);
        tracker.release(EPOCH + 30_000_000L);
        assertEquals(1000f, tracker.velocityX(), 0.1f);
        tracker.clear();
        tracker.release(EPOCH + 1_000_000_000L);
        assertEquals(0f, tracker.velocityX());
        tracker.clear();
        tracker.add(0f, 0f, EPOCH);
        tracker.add(20f, 0f, EPOCH + 20_000_000L);
        tracker.release(EPOCH + 30_000_000L);
        assertEquals(1000f, tracker.velocityX(), 0.1f);
    }

    @Test
    void reversalUsesNewDirectionButSmallMoveJitterDoesNotReverseTheFling() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        for (int i = 0; i <= 4; i++) tracker.add(i * 25f, 0f, EPOCH + i * 25_000_000L);
        tracker.add(99f, 0f, EPOCH + 110_000_000L);
        assertTrue(tracker.velocityX() > 700f, "one-pixel MOVE jitter is not a reversal");

        tracker.reset(0f, 0f, EPOCH);
        for (int i = 1; i <= 4; i++) tracker.add(i * 25f, 0f, EPOCH + i * 25_000_000L);
        for (int i = 1; i <= 3; i++) tracker.add(100f - i * 10f, 0f, EPOCH + 100_000_000L + i * 10_000_000L);
        assertEquals(-1000f, tracker.velocityX(), 0.1f);
        tracker.release(EPOCH + 134_000_000L);
        assertEquals(-1000f, tracker.velocityX(), 0.1f);
    }

    @Test
    void duplicateBackwardsAndTooShortTimesCannotCreateExtremeVelocity() {
        PointerVelocityTracker tracker = new PointerVelocityTracker();
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(50f, 0f, EPOCH + 50_000_000L);
        tracker.add(1_000_000f, 0f, EPOCH + 50_000_000L);
        assertEquals(1000f, tracker.velocityX(), 0.1f, "duplicate time is ignored");
        tracker.add(1_000_000f, 0f, EPOCH + 49_000_000L);
        assertEquals(0f, tracker.velocityX(), "backwards time discards the old trajectory");
        tracker.reset(0f, 0f, EPOCH);
        tracker.add(1000f, 0f, EPOCH + 1L);
        assertEquals(0f, tracker.velocityX(), "a nanosecond cannot establish a velocity");
        tracker.add(1_000_000f, 0f, EPOCH + 20_000_000L);
        assertTrue(Float.isFinite(tracker.velocityX()));
        assertTrue(Math.abs(tracker.velocityX()) <= 8000f, "velocity stays in logical px/s limits");
    }
}
