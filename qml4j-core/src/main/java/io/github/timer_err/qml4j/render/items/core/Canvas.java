package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.Signal;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Context2D;
import io.github.timer_err.qml4j.render.Painter;

// QtQuick Canvas: an imperative 2D drawing surface. The `onPaint` handler runs into an
// offscreen backing surface only when dirty (requestPaint / resize); every frame just
// blits the cached image, so an animated canvas's JS runs at its own fps, not the render
// loop's. Local coordinates -- the Renderer has already translated to this item's origin.
public class Canvas extends Item {

    public final Signal paint = new Signal(); // onPaint
    public final Property<Boolean> available = new Property<>(Boolean.TRUE);
    @SuppressWarnings("unused")
    public final Property<String> contextType = new Property<>("2d");
    @SuppressWarnings("unused")
    public final Property<String> renderStrategy = new Property<>("Immediate");
    @SuppressWarnings("unused")
    public final Property<String> renderTarget = new Property<>("Image");

    // Offscreen backing store, managed by Painter.paintCanvas (a skija handle kept as a
    // field, like Image.skiaImage -- the only skija an Item is allowed to hold).
    public io.github.humbleui.skija.Surface backing;
    public int backingW = -1;
    public int backingH = -1;
    // Renderer.gpuGeneration() the backing was created on; a mismatch means the GL
    // context behind it is gone and the backing must be rebuilt.
    public int backingGeneration = -1;
    public boolean dirty = true;

    // Set for the duration of a paint pass so the onPaint handler's getContext() returns
    // a context bound to the backing surface.
    private Context2D ctx;

    @SuppressWarnings("unused")
    public Object getContext(String type) {
        return ctx;
    }

    public void bindContext(Context2D c) {
        this.ctx = c;
    }

    // A repaint request re-runs onPaint on the next frame; until then the cached image is
    // blitted. The Timer/animation in an animated canvas calls this at its target fps.
    @SuppressWarnings("unused")
    public void requestPaint() {
        dirty = true;
        // The offscreen will be redrawn this frame; invalidate the enclosing cache boundary so
        // its picture re-records the new blit (imperative onPaint isn't a Property change).
        markContentDirty();
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        if (!available.peek()) return;
        p.paintCanvas(this, w, h, alpha);
    }

    // Close the offscreen backing surface when discarded — native, not GC-managed.
    @Override
    protected void releaseResources() {
        if (backing != null) {
            backing.close();
            backing = null;
        }
    }
}
