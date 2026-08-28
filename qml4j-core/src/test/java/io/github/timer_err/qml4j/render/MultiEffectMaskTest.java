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
// subtree's own rendered alpha (Painter.drawMaskedSource + maskCompositePaint), not
// approximated as a clip. maskThresholdMin/Max/maskSpreadAtMin/Max are ported from
// Qt's actual formula (qtdeclarative src/effects/qquickmultieffect.cpp +
// multieffect.frag): two smoothstep windows multiplied together. A load-bearing,
// non-obvious consequence of that formula: at the property DEFAULTS (thresholdMin 0,
// thresholdMax 1, spread 0), the windows collapse to razor-thin ramps pinned at the
// 0/1 extremes, so Qt treats ANY non-zero mask alpha as fully visible -- a silhouette
// test, not a proportional multiply. A gradual per-pixel fade needs the threshold
// moved off the extremes and spread widened; see the tests below.
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

    private static final String PARTIAL_ALPHA_MASKED =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 100; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"#ffffff\"; visible: false }\n"
        + "  Rectangle { id: msk; x: 0; y: 0; width: 50; height: 20; color: \"#4dffffff\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; maskEnabled: true; maskSource: msk }\n"
        + "}";

    @Test
    void defaultThresholdsTreatAnyNonZeroMaskAlphaAsFullyVisible() {
        // The mask rectangle is only ~30% opaque ("#4d..."). Under Qt's actual default
        // threshold/spread (0/1/0/0), that still counts as fully "inside" the mask --
        // the source shows through at full brightness, not dimmed to ~30%.
        Bitmap bmp = render(PARTIAL_ALPHA_MASKED, 100, 20);
        try {
            assertTrue(brightnessAt(bmp, 10, 10) > 200,
                "a merely partially-opaque mask must still fully reveal the source at Qt's default thresholds");
            assertTrue(brightnessAt(bmp, 90, 10) < 10, "outside the mask's shape the source must stay hidden");
        } finally {
            bmp.close();
        }
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
        + "  MultiEffect { anchors.fill: parent; source: src; maskEnabled: true; maskSource: msk%s }\n"
        + "}";

    @Test
    void maskThresholdMinCutsHardAtItsValueWithNoSpread() {
        // A horizontal alpha gradient (1 at x=0 down to 0 at x=100) with
        // maskThresholdMin: 0.5 and no spread: alpha values above 0.5 (left of the
        // x=50 crossover) pass fully; below 0.5 (right of it) are fully cut -- a hard
        // step at the threshold, not a gradual ramp, because spread is still 0.
        Bitmap bmp = render(String.format(GRADIENT_MASKED, "; maskThresholdMin: 0.5"), 100, 20);
        try {
            assertTrue(brightnessAt(bmp, 20, 10) > 200, "alpha well above thresholdMin must pass fully");
            assertTrue(brightnessAt(bmp, 80, 10) < 10, "alpha well below thresholdMin must be cut fully, not faded");
        } finally {
            bmp.close();
        }
    }

    @Test
    void maskThresholdMinWithSpreadProducesAGenuineGradualFade() {
        // Same alpha gradient, but with maskThresholdMin: 0.5 and maskSpreadAtMin: 1.0
        // (maximum spread): the smoothstep window widens enough to cover nearly the
        // whole [0,1] alpha range, so brightness now ramps smoothly across the item --
        // this is how MultiEffect actually achieves a "fade the source out pixel by
        // pixel" effect in Qt; a gradient mask alone at default thresholds does not.
        Bitmap bmp = render(String.format(GRADIENT_MASKED, "; maskThresholdMin: 0.5; maskSpreadAtMin: 1.0"), 100, 20);
        try {
            int x10 = brightnessAt(bmp, 10, 10);
            int x30 = brightnessAt(bmp, 30, 10);
            int x50 = brightnessAt(bmp, 50, 10);
            int x70 = brightnessAt(bmp, 70, 10);
            int x90 = brightnessAt(bmp, 90, 10);
            assertTrue(x10 > x30 && x30 > x50 && x50 > x70 && x70 > x90,
                "brightness must decrease smoothly and monotonically across the fade: "
                + x10 + ", " + x30 + ", " + x50 + ", " + x70 + ", " + x90);
            assertTrue(x50 > 40 && x50 < 215,
                "the midpoint of a genuine gradual fade must land well inside the range, not near either extreme: " + x50);
        } finally {
            bmp.close();
        }
    }
}
