package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

// MultiEffect mask: the source is composited pixel-for-pixel against the mask
// subtree's own rendered alpha (Painter.drawMaskedSource), not approximated as a
// clip -- so a gradient mask fades the source instead of only clipping its outline.
class MultiEffectMaskTest {

    private static QmlView newView() {
        return QmlView.withStockTypes(new QmlEngine());
    }

    private static final class RasterBackend implements SurfaceBackend {
        final Surface surface;
        final int w;
        final int h;
        RasterBackend(int w, int h) {
            this.w = w;
            this.h = h;
            this.surface = Surface.makeRasterN32Premul(w, h);
        }
        public void init(int w, int h) {}
        public Canvas acquireCanvas() {
            Canvas c = surface.getCanvas();
            c.resetMatrix();
            return c;
        }
        public void present() {}
        public void resize(int w, int h) {}
        public void dispose() { surface.close(); }
        public int width() { return w; }
        public int height() { return h; }
    }

    private static Bitmap render(String scene, int w, int h) {
        QmlView v = newView();
        Item root = v.load(scene);
        root.width.set(w);
        root.height.set(h);
        RasterBackend bk = new RasterBackend(w, h);
        bk.surface.getCanvas().clear(0xFF000000);
        v.renderFrame(bk);
        Bitmap out = new Bitmap();
        out.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL));
        bk.surface.readPixels(out, 0, 0);
        return out;
    }

    // Brightness (red channel) of a white-on-black composite is a direct readout of
    // the effective alpha at that pixel: fully masked-in is 255, fully masked-out is 0.
    private static int brightnessAt(Bitmap bmp, int x, int y) {
        return (bmp.getColor(x, y) >> 16) & 0xFF;
    }

    private static final String GRADIENT_MASKED =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 100; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"#ffffff\"; visible: false }\n"
        + "  Rectangle { id: msk; anchors.fill: parent; visible: false\n"
        + "    gradient: Gradient { orientation: 1\n"
        + "      GradientStop { position: 0.0; color: \"#ffffffff\" }\n"
        + "      GradientStop { position: 1.0; color: \"#00ffffff\" }\n"
        + "    }\n"
        + "  }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; maskEnabled: true; maskSource: msk }\n"
        + "}";

    @Test
    void gradientMaskFadesSourceAlphaPerPixelInsteadOfClippingItsOutline() {
        Bitmap bmp = render(GRADIENT_MASKED, 100, 20);
        try {
            int left = brightnessAt(bmp, 5, 10);
            int mid = brightnessAt(bmp, 50, 10);
            int right = brightnessAt(bmp, 95, 10);
            assertTrue(left > mid && mid > right,
                "a gradient mask must fade brightness monotonically across the ramp, not clip it: "
                + "left=" + left + " mid=" + mid + " right=" + right);
            assertTrue(right < 40, "the transparent end of the mask must leave the source nearly invisible: right=" + right);
            assertTrue(left > 200, "the opaque end of the mask must leave the source fully visible: left=" + left);
        } finally {
            bmp.close();
        }
    }

    private static final String HALF_MASKED =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 100; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"#ffffff\"; visible: false }\n"
        + "  Rectangle { id: msk; x: 0; y: 0; width: 50; height: 20; color: \"#ffffff\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; maskEnabled: true; maskSource: msk }\n"
        + "}";

    @Test
    void solidMaskStillFullyHidesSourceOutsideItsShape() {
        Bitmap bmp = render(HALF_MASKED, 100, 20);
        try {
            assertTrue(brightnessAt(bmp, 10, 10) > 200, "inside the mask's shape the source must show through");
            assertTrue(brightnessAt(bmp, 90, 10) < 10, "outside the mask's shape the source must stay hidden");
        } finally {
            bmp.close();
        }
    }

    private static final String HALF_MASKED_INVERTED =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 100; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"#ffffff\"; visible: false }\n"
        + "  Rectangle { id: msk; x: 0; y: 0; width: 50; height: 20; color: \"#ffffff\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; maskEnabled: true; maskSource: msk; maskInverted: true }\n"
        + "}";

    @Test
    void maskInvertedFlipsWhichSideIsHidden() {
        Bitmap bmp = render(HALF_MASKED_INVERTED, 100, 20);
        try {
            assertTrue(brightnessAt(bmp, 10, 10) < 10, "maskInverted must hide the source under the mask's shape");
            assertTrue(brightnessAt(bmp, 90, 10) > 200, "maskInverted must reveal the source outside the mask's shape");
        } finally {
            bmp.close();
        }
    }
}
