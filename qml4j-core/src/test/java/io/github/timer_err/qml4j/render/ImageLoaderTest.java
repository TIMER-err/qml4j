package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageLoaderTest {
    @Test
    void downscaleAveragesFineDetailInsteadOfMissingIt() {
        // One white column per eight pixels: an 8:1 reduction should preserve
        // its average brightness. Bilinear-only sampling misses every stripe.
        byte[] encoded = stripedImage();
        try (Image image = ImageLoader.decodeRaster(encoded, 16, 8);
             Bitmap pixels = new Bitmap()) {
            assertEquals(16, image.getWidth());
            assertEquals(8, image.getHeight());
            assertTrue(pixels.allocN32Pixels(16, 8));
            assertTrue(image.readPixels(pixels));
            for (int x = 1; x < 15; x++) {
                int red = (pixels.getColor(x, 4) >>> 16) & 255;
                assertTrue(Math.abs(red - 32) <= 1, "stripe average was " + red);
            }
        }
    }

    @Test
    void unspecifiedSizePreservesSourcePixels() {
        try (Image image = ImageLoader.decodeRaster(stripedImage(), 0, 0);
             Bitmap pixels = new Bitmap()) {
            assertEquals(128, image.getWidth());
            assertEquals(64, image.getHeight());
            assertTrue(pixels.allocN32Pixels(128, 64));
            assertTrue(image.readPixels(pixels));
            assertEquals(0xFFFFFFFF, pixels.getColor(0, 4));
            assertEquals(0xFF000000, pixels.getColor(1, 4));
        }
    }

    private static byte[] stripedImage() {
        try (Surface surface = Surface.makeRasterN32Premul(128, 64);
             Paint paint = new Paint()) {
            surface.getCanvas().clear(0xFF000000);
            paint.setColor(0xFFFFFFFF);
            for (int x = 0; x < 128; x += 8) {
                surface.getCanvas().drawRect(Rect.makeXYWH(x, 0, 1, 64), paint);
            }
            try (Image image = surface.makeImageSnapshot(); Data data = image.encodeToData()) {
                return data.getBytes();
            }
        }
    }
}
