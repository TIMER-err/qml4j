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

// MultiEffect blur/brightness/contrast/saturation/colorization: previously accepted
// but not applied. brightness/contrast/saturation/colorization are an exact port of
// Qt's per-pixel formula (Painter.colorGradingMatrix, verified against
// qtdeclarative's multieffect.frag). blur uses a real Gaussian (Skija's
// ImageFilter.makeBlur) rather than Qt's cheaper multi-level downsample
// approximation -- same property semantics (blur/blurMax/blurMultiplier), a
// higher-quality primitive underneath.
class MultiEffectGradingTest {

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

    private static int redAt(Bitmap bmp, int x, int y) { return (bmp.getColor(x, y) >> 16) & 0xFF; }
    private static int greenAt(Bitmap bmp, int x, int y) { return (bmp.getColor(x, y) >> 8) & 0xFF; }
    private static int blueAt(Bitmap bmp, int x, int y) { return bmp.getColor(x, y) & 0xFF; }

    private static final String GRAY_SOURCE =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 40; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"%s\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; %s }\n"
        + "}";

    @Test
    void brightnessIsAdditiveInLinearSpace() {
        // #808080 (0.502 unpremultiplied) + brightness 0.3 -> ~0.802 -> ~205.
        Bitmap bmp = render(String.format(GRAY_SOURCE, "#808080", "brightness: 0.3"), 40, 20);
        try {
            int r = redAt(bmp, 20, 10);
            assertTrue(r > 190 && r < 220, "brightness must additively lighten the source: got " + r);
        } finally {
            bmp.close();
        }
    }

    @Test
    void contrastPivotsAroundMidGray() {
        // #606060 (0.376) is below the 0.5 pivot; contrast 0.5 must push it further
        // from the pivot (darker), landing around 0.314 -> ~80, not toward mid-gray.
        Bitmap plain = render(String.format(GRAY_SOURCE, "#606060", ""), 40, 20);
        Bitmap contrasted = render(String.format(GRAY_SOURCE, "#606060", "contrast: 0.5"), 40, 20);
        try {
            int base = redAt(plain, 20, 10);
            int out = redAt(contrasted, 20, 10);
            assertTrue(out < base, "positive contrast must push a below-pivot value further from mid-gray: base="
                + base + " contrasted=" + out);
            assertTrue(out > 60 && out < 100, "contrasted value landed outside the expected range: " + out);
        } finally {
            plain.close();
            contrasted.close();
        }
    }

    private static final String COLOR_SOURCE =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 40; height: 20\n"
        + "  Rectangle { id: src; anchors.fill: parent; color: \"%s\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; %s }\n"
        + "}";

    @Test
    void saturationMinusOneFullyDesaturatesToLuma() {
        // Pure red (#ff0000), saturation -1: mix(gray, c, 1+(-1)=0) picks gray fully.
        // Rec.601 luma of pure red = 0.299 -> ~76 on all three channels.
        Bitmap bmp = render(String.format(COLOR_SOURCE, "#ff0000", "saturation: -1"), 40, 20);
        try {
            int r = redAt(bmp, 20, 10);
            int g = greenAt(bmp, 20, 10);
            int b = blueAt(bmp, 20, 10);
            assertTrue(r < 100 && r > 55, "red channel must drop toward the source's luma: r=" + r);
            assertTrue(g > 55 && g < 100, "green channel must rise toward the source's luma: g=" + g);
            assertTrue(Math.abs(r - g) < 10 && Math.abs(g - b) < 10,
                "full desaturation must equalize all three channels: r=" + r + " g=" + g + " b=" + b);
        } finally {
            bmp.close();
        }
    }

    @Test
    void colorizationAtFullStrengthReplacesHueWithColorizationColor() {
        // White source, colorizationColor blue, colorization 1.0 (full strength):
        // gray(white)=1.0, so the result is luma(1.0) * blue = pure blue.
        Bitmap bmp = render(String.format(COLOR_SOURCE, "#ffffff",
            "colorizationColor: \"#0000ff\"; colorization: 1.0"), 40, 20);
        try {
            int r = redAt(bmp, 20, 10);
            int g = greenAt(bmp, 20, 10);
            int b = blueAt(bmp, 20, 10);
            assertTrue(b > 200, "full-strength colorization must saturate the colorize color's own channel: b=" + b);
            assertTrue(r < 30 && g < 30, "full-strength colorization must suppress channels colorizationColor lacks: r="
                + r + " g=" + g);
        } finally {
            bmp.close();
        }
    }

    private static final String EDGE_SOURCE =
        "import QtQuick\n"
        + "import QtQuick.Effects\n"
        + "Item { width: 60; height: 20\n"
        // MultiEffect's drawSourceAtEffectOrigin neutralises the source item's OWN
        // x/y (so a co-located sibling like Ripple's lands at the effect's local
        // origin) -- so `src` renders at LOCAL x:0..20 regardless of its declared x.
        + "  Rectangle { id: src; x: 10; y: 0; width: 20; height: 20; color: \"#ffffff\"; visible: false }\n"
        + "  MultiEffect { anchors.fill: parent; source: src; %s }\n"
        + "}";

    @Test
    void blurSpreadsBrightnessPastTheSourcesSharpEdge() {
        // The rect renders at local x:0..20 (see EDGE_SOURCE's note). 5px past its
        // true right edge (x=25), a sharp (unblurred) render stays pure black; a
        // sufficiently blurred one bleeds visible brightness there.
        Bitmap sharp = render(String.format(EDGE_SOURCE, ""), 60, 20);
        Bitmap blurred = render(String.format(EDGE_SOURCE, "blurEnabled: true; blur: 1.0; blurMax: 24"), 60, 20);
        try {
            int sharpEdge = redAt(sharp, 25, 10);
            int blurredEdge = redAt(blurred, 25, 10);
            assertTrue(sharpEdge < 5, "an unblurred edge must stay sharp (no bleed past the rect): " + sharpEdge);
            assertTrue(blurredEdge > 20, "a blurred edge must bleed brightness past the rect's original edge: " + blurredEdge);
        } finally {
            sharp.close();
            blurred.close();
        }
    }
}
