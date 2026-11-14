package io.github.timer_err.qml4j.render;

import com.sun.net.httpserver.HttpServer;
import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Image;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.engine.QmlEngine;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void publicNetworkPolicyChecksInitialSourcesAndEveryRedirect() throws Exception {
        byte[] png = stripedImage();
        AtomicInteger deniedRequests = new AtomicInteger();
        AtomicInteger allowedRequests = new AtomicInteger();
        AtomicInteger redirects = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/denied", exchange -> {
            deniedRequests.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/allowed", exchange -> {
            allowedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, png.length);
            try (var body = exchange.getResponseBody()) {
                body.write(png);
            }
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            redirects.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "/denied");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            List<String> checkedUrls = Collections.synchronizedList(new ArrayList<>());
            NetworkResourcePolicy policy = url -> {
                checkedUrls.add(url);
                return !url.endsWith("/denied");
            };

            assertImageStatus(base + "/denied", policy, 3);
            assertEquals(0, deniedRequests.get(), "denied sources must not open a connection");
            assertImageStatus(base + "/redirect", policy, 3);
            assertEquals(1, redirects.get(), "the approved redirect source was requested");
            assertEquals(0, deniedRequests.get(), "redirect targets require a separate policy check");
            assertImageStatus(base + "/allowed", policy, 1);
            assertEquals(1, allowedRequests.get(), "approved images still load and decode");
            assertEquals(List.of(base + "/denied", base + "/redirect",
                    base + "/denied", base + "/allowed"), checkedUrls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recyclingQueuedImagesLoadsOnlyTheLatestCoverAndSkipsDiscardedRows() throws Exception {
        byte[] png = solidImage(0xFF00CC66);
        CountDownLatch started = new CountDownLatch(ImageLoader.WORKER_COUNT);
        CountDownLatch release = new CountDownLatch(1);
        List<String> requested = Collections.synchronizedList(new ArrayList<>());
        ResourceLoader resources = source -> {
            if (source.startsWith("hold/")) {
                started.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) return null;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } else {
                requested.add(source);
            }
            return png;
        };
        StringBuilder qml = new StringBuilder("Item { width: 32; height: 8;");
        for (int i = 0; i < ImageLoader.WORKER_COUNT; i++) {
            qml.append("Image { x: 16; width: 16; height: 8; source: \"hold/")
                    .append(i).append("\" }");
        }
        qml.append("Image { objectName: \"recycled\"; width: 16; height: 8; source: \"old\" }")
                .append("Image { objectName: \"discarded\"; x: 16; width: 16; height: 8; source: \"discarded\" }}");
        QmlView view = QmlView.withStockTypes(new QmlEngine()).resources(resources);
        var root = view.load(qml.toString());
        var recycled = (io.github.timer_err.qml4j.render.items.core.Image)
                view.findByObjectName("recycled");
        var discarded = view.findByObjectName("discarded");
        try (Surface surface = Surface.makeRasterN32Premul(32, 8);
             Bitmap pixels = new Bitmap()) {
            view.renderer().render(surface.getCanvas(), root);
            assertTrue(started.await(5, TimeUnit.SECONDS), "hold active downloads while recycling rows");
            for (int i = 0; i < 400; i++) {
                recycled.source.set("obsolete/" + i);
                view.renderer().render(surface.getCanvas(), root);
            }
            root.children.remove(discarded);
            discarded.dispose();
            recycled.source.set("latest");
            view.renderer().render(surface.getCanvas(), root);
            release.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                view.renderer().render(surface.getCanvas(), root);
                if (recycled.status.peek().intValue() == 1) break;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            assertEquals(1, recycled.status.peek().intValue(), "the current cover must finish loading");
            assertEquals(List.of("latest"), requested, "obsolete and disposed rows must not perform I/O");
            pixels.allocN32Pixels(32, 8);
            assertTrue(surface.readPixels(pixels, 0, 0));
            assertEquals(0xFF00CC66, pixels.getColor(4, 4));
        } finally {
            release.countDown();
            view.dispose();
        }
    }

    @Test
    void clearingOrDisposingAnImageClosesPendingAndLateNativeResults() {
        byte[] png = solidImage(0xFF00CC66);
        for (boolean dispose : new boolean[] {false, true}) {
            var node = new io.github.timer_err.qml4j.render.items.core.Image();
            long gen = node.startDecode("cover");
            try (Image pending = Image.makeFromEncoded(png);
                 Image late = Image.makeFromEncoded(png)) {
                node.publishDecode(gen, pending, 1, 1);
                if (dispose) node.dispose();
                else node.clearSource();
                assertTrue(pending.isClosed(), "unadopted native images must be released");
                node.publishDecode(gen, late, 1, 1);
                node.adoptDecode();
                assertTrue(late.isClosed(), "completion must not revive a cleared or disposed image");
                assertNull(node.skiaImage);
                assertFalse(node.hasPendingDecode());
            } finally {
                node.dispose();
            }
        }
    }

    private static byte[] solidImage(int color) {
        try (Surface surface = Surface.makeRasterN32Premul(1, 1)) {
            surface.getCanvas().clear(color);
            try (Image image = surface.makeImageSnapshot(); Data data = image.encodeToData()) {
                return data.getBytes();
            }
        }
    }

    private static void assertImageStatus(String source, NetworkResourcePolicy policy, int expected)
            throws InterruptedException {
        QmlView view = QmlView.withStockTypes(new QmlEngine()).networkPolicy(policy);
        io.github.timer_err.qml4j.render.items.core.Image node =
                (io.github.timer_err.qml4j.render.items.core.Image) view.load(
                        "Image { width: 16; height: 8; source: \"" + source + "\" }");
        try (Surface surface = Surface.makeRasterN32Premul(16, 8)) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                view.renderer().render(surface.getCanvas(), node);
                int status = node.status.peek().intValue();
                if (status == 1 || status == 3) break;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            assertEquals(expected, node.status.peek().intValue());
            if (expected == 1) {
                assertEquals(128, node.intrinsicWidth);
                assertEquals(64, node.intrinsicHeight);
            }
        } finally {
            view.dispose();
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
