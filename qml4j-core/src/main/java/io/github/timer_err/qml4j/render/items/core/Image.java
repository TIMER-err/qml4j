package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;

import java.util.concurrent.Future;

public class Image extends Item {
    public final Property<String> source = new Property<>(null);
    // String ("Stretch"/"PreserveAspectCrop"/...) or the Image.* fillMode enum (a Long);
    // Object so the enum value doesn't fail the typed read/listener cast.
    public final Property<Object> fillMode = new Property<>("Stretch");
    @SuppressWarnings("unused")
    public final Property<Boolean> asynchronous = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Boolean> cache = new Property<>(Boolean.TRUE);
    // Rounded corners clipped directly at draw time (clipRRect). Lets a list/grid
    // delegate get a rounded cover without a layer.effect mask -- the mask path
    // allocates an offscreen surface (saveLayer) per delegate every frame, which
    // is the dominant cost when scrolling a grid of cover images.
    public final Property<Number> radius = new Property<>(0);
    public final Size sourceSize = new Size();
    @SuppressWarnings("unused")
    public final Property<Number> horizontalAlignment = new Property<>(4); // AlignHCenter
    @SuppressWarnings("unused")
    public final Property<Number> verticalAlignment = new Property<>(128); // AlignVCenter
    public final Property<Number> paintedWidth = new Property<>(0);
    public final Property<Number> paintedHeight = new Property<>(0);
    // Image.Null=0 / Ready=1 / Loading=2 / Error=3 (Qt). MD3 spinners bind to it.
    public final Property<Number> status = new Property<>(0);

    public io.github.humbleui.skija.Image skiaImage;   // render-thread only
    public String loadedSource;
    public int intrinsicWidth;
    public int intrinsicHeight;
    // Workers only publish raster images. Native-image adoption and status changes stay
    // on the render thread; the monitor protects publication against source changes/disposal.
    private volatile long decodeGen;
    private volatile long decodeReadyGen = -1;
    private io.github.humbleui.skija.Image pendingImage;
    private int pendW, pendH;
    private long adoptedGen = -1;
    private Future<?> decodeTask;

    public Image() {
        wireContentInvalidation(source, fillMode, radius, horizontalAlignment, verticalAlignment,
            status, sourceSize.width, sourceSize.height);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        p.drawImage(this, w, h, alpha);
    }

    public boolean isDecodeCurrent(long gen) {
        return decodeGen == gen;
    }

    public boolean hasPendingDecode() {
        return loadedSource != null && !loadedSource.isEmpty() && decodeGen != adoptedGen;
    }

    public synchronized long startDecode(String src) {
        cancelDecode();
        long gen = decodeGen;
        loadedSource = src;
        status.set(2); // Loading; retain the previously adopted image until ready.
        return gen;
    }

    public synchronized boolean attachDecodeTask(long gen, Future<?> task) {
        if (decodeGen != gen) {
            task.cancel(false);
            return false;
        }
        decodeTask = task;
        return true;
    }

    public synchronized void publishDecode(long gen, io.github.humbleui.skija.Image image,
                                           int width, int height) {
        if (decodeGen != gen) {
            if (image != null) image.close();
            return;
        }
        pendingImage = image;
        pendW = width;
        pendH = height;
        decodeTask = null;
        decodeReadyGen = gen;
        // Invalidate cached pictures even when no input or animation dirties the scene.
        markContentDirtyAsync();
    }

    public void adoptDecode() {
        if (decodeReadyGen != decodeGen || adoptedGen == decodeGen) return;
        synchronized (this) {
            if (decodeReadyGen != decodeGen || adoptedGen == decodeGen) return;
            adoptedGen = decodeGen;
            if (skiaImage != null) skiaImage.close();
            skiaImage = pendingImage;
            pendingImage = null;
            if (skiaImage != null) {
                intrinsicWidth = pendW;
                intrinsicHeight = pendH;
                status.set(1); // Ready
            } else {
                status.set(3); // Error
            }
        }
    }

    public synchronized void clearSource() {
        if (loadedSource == null && skiaImage == null && decodeTask == null) return;
        releaseImages();
        status.set(0); // Null
    }

    // All callers hold the monitor. Invalidate before cancelling: completion can race
    // cancellation, and a running URL connection need not respond to interruption.
    private void cancelDecode() {
        decodeGen++;
        if (decodeTask != null) {
            decodeTask.cancel(true);
            decodeTask = null;
        }
        if (pendingImage != null) {
            pendingImage.close();
            pendingImage = null;
        }
    }

    private void releaseImages() {
        cancelDecode();
        if (skiaImage != null) {
            skiaImage.close();
            skiaImage = null;
        }
        loadedSource = null;
    }

    @Override
    protected synchronized void releaseResources() {
        releaseImages();
    }
}
