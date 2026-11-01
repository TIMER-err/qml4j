package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.animation.Animatable;

public class Flickable extends Item implements Animatable {
    public final Property<Number> contentX = new Property<>(0);
    public final Property<Number> contentY = new Property<>(0);
    public final Property<Number> contentWidth = new Property<>(0);
    public final Property<Number> contentHeight = new Property<>(0);
    public final Property<String> flickableDirection = new Property<>("AutoFlickDirection");
    public final Property<Boolean> interactive = new Property<>(Boolean.TRUE);
    public final Property<Boolean> moving = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Number> boundsBehavior = new Property<>(3); // DragAndOvershootBounds
    // Content margins (Qt Flickable.topMargin/...): accepted so documents load; the
    // content is laid out by its own anchors/size here, so these are not yet applied.
    @SuppressWarnings("unused")
    public final Property<Number> topMargin = new Property<>(0);
    public final Property<Number> bottomMargin = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> leftMargin = new Property<>(0);
    public final Property<Number> rightMargin = new Property<>(0);
    // Touch press-delay before the flick steals the press from children; accepted so
    // documents load (no delayed-press synthesis here).
    @SuppressWarnings("unused")
    public final Property<Number> pressDelay = new Property<>(0);

    // Programmatic targets ease independently; a fling integrates its displayed
    // position directly, so release does not introduce a second catch-up animation.
    private float targetX;
    private float targetY;
    private boolean smoothing;
    private float flingVX;
    private float flingVY;
    private boolean flinging;
    private long lastNanos;
    private boolean clockStarted;
    // AndroidX FlingAnimation's default exponential friction, in seconds^-1.
    private static final double FRICTION = 4.2;
    private static final float MIN_FLING = 50f; // logical px/s at release
    private static final float STOP_VELOCITY = 5f;
    private static final float MAX_FLING = 8000f;
    private static final float EASE = 18f;

    public Flickable() {
        // When the content shrinks (a collapsed section) or the viewport resizes, the
        // shown/target scroll can sit past the new bottom; re-clamp so the content
        // doesn't get stuck scrolled off the top with no way back.
        contentHeight.addListener(v -> clampToBounds());
        contentWidth.addListener(v -> clampToBounds());
        height.addListener(v -> clampToBounds());
        width.addListener(v -> clampToBounds());
        // Scrolling translates the content at draw time -- a pure paint change (setPaintOnly,
        // no version bump), but it does alter the recorded pixels, so invalidate the cache.
        wireContentInvalidation(contentX, contentY);
    }

    private void clampToBounds() {
        float mx = maxX(), my = maxY();
        float cx = contentX.peekFloat();
        float cy = contentY.peekFloat();
        float ncx = clamp(cx, 0f, mx);
        float ncy = clamp(cy, 0f, my);
        if (ncx != cx) contentX.setPaintOnly(ncx);
        if (ncy != cy) contentY.setPaintOnly(ncy);
        targetX = clamp(targetX, 0f, mx);
        targetY = clamp(targetY, 0f, my);
        if (flinging) {
            if (ncx <= 0f && flingVX < 0f || ncx >= mx && flingVX > 0f) flingVX = 0f;
            if (ncy <= 0f && flingVY < 0f || ncy >= my && flingVY > 0f) flingVY = 0f;
            if (flingVX == 0f && flingVY == 0f) stopScroll();
        } else if (smoothing && targetX == ncx && targetY == ncy) {
            stopScroll();
        }
    }

    private float maxX() {
        return Math.max(0f, contentWidth.peekFloat() + rightMargin.peekFloat() - width.peekFloat());
    }

    private float maxY() {
        return Math.max(0f, contentHeight.peekFloat() + bottomMargin.peekFloat() - height.peekFloat());
    }

    /** Set the absolute scroll target (clamped); content eases toward it. */
    public void setScrollTarget(float x, float y) {
        if (flinging) stopScroll();
        targetX = allowX() ? clamp(x, 0f, maxX()) : contentX.peekFloat();
        targetY = allowY() ? clamp(y, 0f, maxY()) : contentY.peekFloat();
        smoothing = targetX != contentX.peekFloat() || targetY != contentY.peekFloat();
        // Retarget an active ease without restarting its clock on every input.
        if (smoothing) moving.set(Boolean.TRUE);
        else stopScroll();
    }

    /** Adjust the target by a delta (wheel notches accumulate before the ease catches up). */
    @SuppressWarnings("unused")
    public void nudge(float dx, float dy) {
        setScrollTarget(targetX + dx, targetY + dy);
    }

    /** Take over at an instantly written content position, discarding any old
     *  fling or eased target. A live direct drag keeps its moving state. */
    public void syncTarget() {
        boolean animated = smoothing || flinging;
        smoothing = false;
        flinging = false;
        flingVX = 0f;
        flingVY = 0f;
        lastNanos = 0L;
        clockStarted = false;
        targetX = contentX.peekFloat();
        targetY = contentY.peekFloat();
        if (animated) moving.set(Boolean.FALSE);
    }

    /** Stop all scrolling at the displayed position, including its animation clock. */
    public void stopScroll() {
        syncTarget();
        moving.set(Boolean.FALSE);
    }

    @SuppressWarnings("unused")
    public void stopFling() {
        stopScroll();
    }

    /** Release velocity in logical content px/s; input timestamps are deliberately
     *  not used here because the render clock can have a different monotonic epoch. */
    public void startFling(float vx, float vy) {
        stopScroll();
        flingVX = allowX() ? releaseVelocity(vx, contentX.peekFloat(), maxX()) : 0f;
        flingVY = allowY() ? releaseVelocity(vy, contentY.peekFloat(), maxY()) : 0f;
        flinging = flingVX != 0f || flingVY != 0f;
        moving.set(flinging);
    }

    @SuppressWarnings("unused")
    public float targetX() {
        return targetX;
    }

    @SuppressWarnings("unused")
    public float targetY() {
        return targetY;
    }

    @Override
    public void tick(long nowNanos) {
        if (!smoothing && !flinging) return;
        if (!clockStarted) {
            lastNanos = nowNanos;
            clockStarted = true;
            return;
        }
        if (nowNanos <= lastNanos) return;
        double dt = (nowNanos - lastNanos) / 1_000_000_000d;
        lastNanos = nowNanos;

        boolean allowX = allowX(), allowY = allowY();
        float mx = maxX(), my = maxY();
        float cx = contentX.peekFloat(), cy = contentY.peekFloat();
        if (flinging) {
            // Exact exponential integration, with no frame-duration cap. Finish at
            // the exact velocity threshold even when a frame crosses it, making
            // both long frames and different refresh rates travel the same distance.
            double decay = Math.exp(-FRICTION * dt);
            float nx = cx, ny = cy;
            if (allowX && flingVX != 0f) {
                float nextV = (float) (flingVX * decay);
                if (Math.abs(nextV) <= STOP_VELOCITY) {
                    nx = (float) (nx + (flingVX - Math.copySign(STOP_VELOCITY, flingVX)) / FRICTION);
                    flingVX = 0f;
                } else {
                    nx = (float) (nx + flingVX * (1d - decay) / FRICTION);
                    flingVX = nextV;
                }
                nx = clamp(nx, 0f, mx);
                if (nx <= 0f || nx >= mx) flingVX = 0f;
            } else {
                flingVX = 0f;
            }
            if (allowY && flingVY != 0f) {
                float nextV = (float) (flingVY * decay);
                if (Math.abs(nextV) <= STOP_VELOCITY) {
                    ny = (float) (ny + (flingVY - Math.copySign(STOP_VELOCITY, flingVY)) / FRICTION);
                    flingVY = 0f;
                } else {
                    ny = (float) (ny + flingVY * (1d - decay) / FRICTION);
                    flingVY = nextV;
                }
                ny = clamp(ny, 0f, my);
                if (ny <= 0f || ny >= my) flingVY = 0f;
            } else {
                flingVY = 0f;
            }
            contentX.setPaintOnly(nx);
            contentY.setPaintOnly(ny);
            targetX = nx;
            targetY = ny;
            if (flingVX == 0f && flingVY == 0f) stopScroll();
            return;
        }

        targetX = allowX ? clamp(targetX, 0f, mx) : cx;
        targetY = allowY ? clamp(targetY, 0f, my) : cy;
        float a = (float) -Math.expm1(-EASE * dt);
        float nx = cx + (targetX - cx) * a;
        float ny = cy + (targetY - cy) * a;
        if (Math.abs(targetX - nx) < 0.25f) nx = targetX;
        if (Math.abs(targetY - ny) < 0.25f) ny = targetY;
        // Scroll offsets are draw-time translates: preserve the layout fast path.
        contentX.setPaintOnly(nx);
        contentY.setPaintOnly(ny);
        if (nx == targetX && ny == targetY) stopScroll();
    }

    private boolean allowX() {
        return !"VerticalFlick".equals(flickableDirection.peek());
    }

    private boolean allowY() {
        return !"HorizontalFlick".equals(flickableDirection.peek());
    }

    private static float releaseVelocity(float velocity, float position, float max) {
        if (!Float.isFinite(velocity) || Math.abs(velocity) < MIN_FLING) return 0f;
        if (position <= 0f && velocity < 0f || position >= max && velocity > 0f) return 0f;
        return clamp(velocity, -MAX_FLING, MAX_FLING);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
