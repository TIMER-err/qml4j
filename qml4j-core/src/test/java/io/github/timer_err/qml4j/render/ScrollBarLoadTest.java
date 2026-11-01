package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Load and interact with the production MD3 scrollbar through real pointer dispatch.
class ScrollBarLoadTest {

    private static byte[] res(String path) {
        try (InputStream in = ScrollBarLoadTest.class.getResourceAsStream("/" + path)) {
            assertNotNull(in, "missing test resource " + path);
            return in.readAllBytes();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void emptyViewportCanBecomeScrollableWithoutPoisoningThumbGeometry() {
        for (boolean horizontal : new boolean[]{false, true}) {
            QmlView view = scrollScene(horizontal, true);
            try {
                Flickable flickable = (Flickable) view.findByObjectName("scrollTarget");
                Item bar = view.findByObjectName("scrollTrack");
                Item thumb = bar.children.stream().filter(child -> child instanceof Rectangle)
                        .findFirst().orElseThrow();
                long clock = 1_000_000_000L;
                frames(view, clock, 20);
                assertTrue(Float.isFinite(horizontal ? thumb.width.peekFloat() : thumb.height.peekFloat()),
                        "an initially empty target must not seed NaN into thumb animation");
                flickable.width.set(400);
                flickable.height.set(400);
                flickable.contentWidth.set(horizontal ? 2000 : 400);
                flickable.contentHeight.set(horizontal ? 400 : 2000);
                frames(view, clock + 320_000_000L, 20);
                assertTrue(bar.visible.peek());
                assertEquals(80f, horizontal ? thumb.width.peekFloat() : thumb.height.peekFloat(), 0.01f);

                view.dispatchPointerDown(horizontal ? 40 : 390, horizontal ? 390 : 40,
                        clock + 650_000_000L);
                view.dispatchPointerMove(horizontal ? 80 : 390, horizontal ? 390 : 80,
                        clock + 680_000_000L);
                assertEquals(200f, horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat(),
                        0.01f, "the newly visible thumb must support a real drag");
                view.dispatchPointerUp(horizontal ? 80 : 390, horizontal ? 390 : 80,
                        clock + 690_000_000L);
            } finally {
                view.dispose();
            }
        }
    }

    @Test
    void scrollbarTakesOverFlingAndKeepsDragScaleAcrossThumbAnimation() {
        for (boolean horizontal : new boolean[]{false, true}) {
            QmlView view = scrollScene(horizontal, false);
            Flickable flickable = (Flickable) view.findByObjectName("scrollTarget");
            long clock = 1_000_000_000L;
            frames(view, clock, 20);
            clock += 320_000_000L;

            view.dispatchPointerDown(200, 200, clock);
            view.dispatchPointerMove(horizontal ? 170 : 200, horizontal ? 200 : 170,
                    clock + 10_000_000L);
            view.dispatchPointerMove(horizontal ? 130 : 200, horizontal ? 200 : 130,
                    clock + 20_000_000L);
            view.dispatchPointerUp(horizontal ? 130 : 200, horizontal ? 200 : 130,
                    clock + 21_000_000L);
            assertTrue(flickable.moving.peek(), "the list must really be coasting first");

            // Press the visible thumb, not the content underneath.
            float position = horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat();
            float thumbCenter = position / 1600f * 320f + 40f;
            float x = horizontal ? thumbCenter : 390;
            float y = horizontal ? 390 : thumbCenter;
            view.dispatchPointerDown(x, y, clock + 22_000_000L);
            assertFalse(flickable.moving.peek(), "thumb press stops inertia immediately");
            double held = horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat();
            frames(view, clock + 32_000_000L, 2);
            assertEquals(held, horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat(),
                    0.01, "holding the thumb must not drift");

            // Move across the track boundary; capture must survive. Equal deltas
            // must keep equal scale even while the pressed thumb is changing size.
            view.dispatchPointerMove(horizontal ? x + 20 : 430, horizontal ? 430 : y + 20,
                    clock + 64_000_000L);
            double first = horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat();
            frames(view, clock + 80_000_000L, 20);
            view.dispatchPointerMove(horizontal ? x + 40 : 430, horizontal ? 430 : y + 40,
                    clock + 420_000_000L);
            double second = horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat();
            assertTrue(first > held, "captured drag outside the track still scrolls");
            assertEquals(first - held, second - first, 0.05, "thumb animation cannot rescale a drag");
            view.dispatchPointerUp(horizontal ? x + 40 : 430, horizontal ? 430 : y + 40,
                    clock + 430_000_000L);
            frames(view, clock + 440_000_000L, 40);
            assertEquals(second, horizontal ? flickable.contentX.peekFloat() : flickable.contentY.peekFloat(),
                    0.01, "release must not spring back to an old animation target");
            view.dispose();
        }
    }

    private static QmlView scrollScene(boolean horizontal, boolean empty) {
        Map<String, byte[]> files = new HashMap<>();
        files.put("md3/Core/qmldir", res("md3/Core/qmldir"));
        files.put("md3/Core/Theme.qml", res("md3/Core/Theme.qml"));
        files.put("md3/Core/ScrollBar.qml", res("md3/Core/ScrollBar.qml"));
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        view.resources(files::get);
        view.load("import QtQuick\nimport md3.Core\n"
                + "Item { width: 440; height: 440\n"
                + "Flickable { id: fl; objectName: \"scrollTarget\"; width: " + (empty ? 0 : 400)
                + "; height: " + (empty ? 0 : 400)
                + "; contentWidth: " + (empty ? 0 : horizontal ? 2000 : 400)
                + "; contentHeight: " + (empty ? 0 : horizontal ? 400 : 2000)
                + "; contentX: " + (!empty && horizontal ? 500 : 0)
                + "; contentY: " + (!empty && !horizontal ? 500 : 0) + " }\n"
                + "ScrollBar { objectName: \"scrollTrack\"; target: fl; orientation: Qt."
                + (horizontal ? "Horizontal" : "Vertical")
                + "; x: " + (horizontal ? 0 : 380) + "; y: " + (horizontal ? 380 : 0)
                + "; width: " + (horizontal ? 400 : 20) + "; height: " + (horizontal ? 20 : 400)
                + " }\n}");
        return view;
    }

    private static void frames(QmlView view, long clock, int count) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            for (int i = 0; i < count; i++) {
                view.tickAnimations(clock + i * 16_000_000L);
                queue.flush();
                view.renderer().layoutOnly(view.root());
                queue.flush();
            }
        } finally {
            queue.uninstall();
        }
    }
}
