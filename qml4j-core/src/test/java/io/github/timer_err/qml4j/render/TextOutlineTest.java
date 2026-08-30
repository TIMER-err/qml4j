package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Text.style/styleColor/styleWidth: Qt's Text.Outline/Raised/Sunken decoration (see
// qml-qtquick-text.html), plus a qml4j styleWidth extension controlling the Outline
// stroke's thickness (stock Qt draws a fixed ~1px offset ring with no such control).
class TextOutlineTest {

    private static QmlView newView() {
        return QmlView.withStockTypes(new QmlEngine());
    }

    @Test
    void styleAcceptsQtOutlineEnumAndCustomWidthAndColor() {
        Item root = newView().load(
            "import QtQuick\n" +
            "Text { text: \"A\"; style: Text.Outline; styleColor: \"#ff0000\"; styleWidth: 4 }");
        try {
            assertEquals(1L, propertyOf(root, "style").peek());
            assertEquals("#ff0000", propertyOf(root, "styleColor").peek());
            assertEquals(4L, propertyOf(root, "styleWidth").peek());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void defaultStyleIsNormalWithNoOutline() {
        Item root = newView().load("import QtQuick\nText { text: \"A\" }");
        try {
            assertEquals(0, ((Number) propertyOf(root, "style").peek()).intValue());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Property<?> propertyOf(Item root, String name) throws Exception {
        Field f = root.getClass().getField(name);
        return (Property<?>) f.get(root);
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

    private static int nonBlackPixelCount(Bitmap bmp, int w, int h) {
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((bmp.getColor(x, y) & 0x00FFFFFF) != 0) count++;
            }
        }
        return count;
    }

    private static final String PLAIN =
        "import QtQuick\n"
        + "Item { width: 80; height: 80\n"
        + "  Text { x: 10; y: 10; text: \"H\"; font.pixelSize: 48; color: \"#ffffff\" }\n"
        + "}";

    private static final String OUTLINED =
        "import QtQuick\n"
        + "Item { width: 80; height: 80\n"
        + "  Text { x: 10; y: 10; text: \"H\"; font.pixelSize: 48; color: \"#ffffff\"\n"
        + "         style: Text.Outline; styleColor: \"#ff0000\"; styleWidth: 10 }\n"
        + "}";

    @Test
    void outlineStyleGrowsTheDrawnGlyphFootprint() {
        Bitmap plain = render(PLAIN, 80, 80);
        Bitmap outlined = render(OUTLINED, 80, 80);
        try {
            int plainCount = nonBlackPixelCount(plain, 80, 80);
            int outlinedCount = nonBlackPixelCount(outlined, 80, 80);
            assertTrue(outlinedCount > plainCount,
                "a thick outline stroke must paint more of the box than the bare glyph fill: "
                + plainCount + " vs " + outlinedCount);
        } finally {
            plain.close();
            outlined.close();
        }
    }

    private static final String RAISED =
        "import QtQuick\n"
        + "Item { width: 80; height: 80\n"
        + "  Text { x: 10; y: 10; text: \"H\"; font.pixelSize: 48; color: \"#ffffff\"\n"
        + "         style: Text.Raised; styleColor: \"#ff0000\" }\n"
        + "}";

    @Test
    void raisedStyleAlsoGrowsTheDrawnGlyphFootprint() {
        Bitmap plain = render(PLAIN, 80, 80);
        Bitmap raised = render(RAISED, 80, 80);
        try {
            int plainCount = nonBlackPixelCount(plain, 80, 80);
            int raisedCount = nonBlackPixelCount(raised, 80, 80);
            assertTrue(raisedCount > plainCount,
                "a 1px shadow offset must paint at least the shifted row/column beyond the bare glyph");
        } finally {
            plain.close();
            raised.close();
        }
    }
}
