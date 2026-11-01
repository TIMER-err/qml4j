package io.github.timer_err.qml4j.render;

/** A bounded, allocation-free history in logical coordinates and host event time.
 *  Only samples within one input stream share a clock; never compare these times
 *  with the render loop's animation clock. */
public final class PointerVelocityTracker {
    private static final int CAPACITY = 32;
    private static final long WINDOW_NANOS = 100_000_000L;
    private static final long STOPPED_NANOS = 80_000_000L;
    private static final long MIN_SPAN_NANOS = 10_000_000L;
    private static final float REVERSAL_DISTANCE = 2f;
    private static final float MAX_VELOCITY = 8000f;
    private final long[] times = new long[CAPACITY];
    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];
    private int next;
    private int size;
    private long lastMotionX;
    private long lastMotionY;

    public void clear() {
        next = 0;
        size = 0;
    }

    public void reset(float x, float y, long eventTimeNanos) {
        clear();
        add(x, y, eventTimeNanos);
    }

    public void add(float x, float y, long eventTimeNanos) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        if (size > 0) {
            int last = index(0);
            long gap = eventTimeNanos - times[last];
            // Coalesced duplicate times have no measurable velocity. A backwards
            // clock or a long pause starts a new history rather than a huge slope.
            if (gap == 0L) return;
            if (gap < 0L || gap > WINDOW_NANOS) {
                clear();
            } else {
                if (x != xs[last]) lastMotionX = eventTimeNanos;
                if (y != ys[last]) lastMotionY = eventTimeNanos;
            }
        }
        if (size == 0) {
            lastMotionX = eventTimeNanos;
            lastMotionY = eventTimeNanos;
        }
        times[next] = eventTimeNanos;
        xs[next] = x;
        ys[next] = y;
        next = (next + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    public float velocityX() {
        return velocity(xs, lastMotionX);
    }

    public float velocityY() {
        return velocity(ys, lastMotionY);
    }

    // age=0 is the newest sample; the history never shifts or copies its arrays.
    private int index(int age) {
        return (next - 1 - age + CAPACITY) % CAPACITY;
    }

    private float velocity(float[] axis, long lastMotion) {
        if (size < 2) return 0f;
        int newestIndex = index(0);
        long newest = times[newestIndex];
        if (newest - lastMotion >= STOPPED_NANOS) return 0f;
        int oldest = 0;
        for (int age = 1; age < size; age++) {
            if (newest - times[index(age)] > WINDOW_NANOS) break;
            oldest = age;
        }

        // A meaningful reversal starts a new directional segment. Sub-pixel lift
        // jitter does not erase the drag, but a deliberate reversal must not fling
        // in the direction from before the turn.
        float latest = axis[newestIndex];
        int direction = 0;
        int extremumAge = 0;
        float extremum = latest;
        for (int age = 1; age <= oldest; age++) {
            float position = axis[index(age)];
            if (direction == 0) {
                float delta = latest - position;
                if (Math.abs(delta) < REVERSAL_DISTANCE) continue;
                direction = delta > 0f ? 1 : -1;
                extremum = position;
                extremumAge = age;
            } else if (direction * (position - extremum) <= 0f) {
                extremum = position;
                extremumAge = age;
            } else if (direction * (position - extremum) >= REVERSAL_DISTANCE) {
                oldest = extremumAge;
                break;
            }
        }
        if (oldest == 0 || newest - times[index(oldest)] < MIN_SPAN_NANOS) return 0f;

        // Weighted least-squares line through the recent trajectory. Relative
        // positions/times keep the fit well-conditioned even after long uptimes.
        double sumW = 0d, sumT = 0d, sumP = 0d, sumTT = 0d, sumTP = 0d;
        for (int age = 0; age <= oldest; age++) {
            int i = index(age);
            double t = (times[i] - newest) / 1_000_000_000d;
            double recency = 1d + 0.5d * (times[i] - newest) / WINDOW_NANOS;
            double w = recency * recency;
            double p = axis[i] - latest;
            sumW += w;
            sumT += w * t;
            sumP += w * p;
            sumTT += w * t * t;
            sumTP += w * t * p;
        }
        double variance = sumW * sumTT - sumT * sumT;
        if (variance <= 1e-12) return 0f;
        double velocity = (sumW * sumTP - sumT * sumP) / variance;
        return (float) Math.max(-MAX_VELOCITY, Math.min(velocity, MAX_VELOCITY));
    }
}
