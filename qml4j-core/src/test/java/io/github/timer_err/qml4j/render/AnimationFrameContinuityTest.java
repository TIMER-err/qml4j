package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Bitmap;
import io.github.humbleui.skija.ColorAlphaType;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks both animation state and painted pixels across short, long and idle frames. */
class AnimationFrameContinuityTest {
    private static final int[] FRAME_TIMES_MS = {0, 8, 16, 50, 66, 82, 110, 130, 180, 200, 216, 280};
    private static final String SCENE = "import QtQuick\nimport QtQuick.Effects\n"
        + "Rectangle { width: 260; height: 64; color: \"#000000\"; property real destination: 16\n"
        + " Item { width: 260; height: 64; layer.enabled: true\n"
        + "  layer.effect: MultiEffect { maskEnabled: true; maskSource: mask }\n"
        + "  Rectangle { id: mask; anchors.fill: parent; radius: 16; visible: false }\n"
        + "  Rectangle { objectName: \"moving\"; x: destination; y: 20; width: 12; height: 24; color: \"#ffffff\"\n"
        + "   Behavior on x { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }\n"
        + "  }\n }\n}";

    @Test
    void maskedAnimationNeverReplaysOlderPixels() {
        checkSequence(false);
    }

    @Test
    void cachedAnimationNeverReplaysOlderPixels() {
        checkSequence(true);
    }

    private static void checkSequence(boolean cached) {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        view.load(SCENE);
        view.renderer().setPictureCache(cached);
        try (Surface surface = Surface.makeRasterN32Premul(260, 64);
             Bitmap pixels = new Bitmap()) {
            pixels.allocPixels(ImageInfo.makeN32(260, 64, ColorAlphaType.PREMUL));
            draw(view, surface, 1_000_000_000L);
            sweep(view, surface, pixels, 220, 2_000_000_000L);
            sweep(view, surface, pixels, 16, 3_000_000_000L);
        } finally {
            view.dispose();
            Item.setContentCacheEnabled(false);
        }
    }

    private static void sweep(QmlView view, Surface surface, Bitmap pixels, int target, long start) {
        Item moving = view.findByObjectName("moving");
        double previous = moving.x.peekDouble();
        boolean forward = target > previous;
        MemberAccess.writeMember(view.root(), "destination", target);
        for (int elapsed : FRAME_TIMES_MS) {
            draw(view, surface, start + elapsed * 1_000_000L);
            double current = moving.x.peekDouble();
            assertTrue(forward ? current >= previous : current <= previous,
                "animation reversed at " + elapsed + " ms: " + previous + " -> " + current);
            assertTrue(surface.readPixels(pixels, 0, 0));
            assertEquals(current, firstWhitePixel(pixels), 1.0,
                "rendered frame must agree with current animation state at " + elapsed + " ms");
            previous = current;
        }
        assertEquals(target, previous, 0.001);
    }

    private static void draw(QmlView view, Surface surface, long timestamp) {
        view.dirtyQueue().install();
        try {
            view.tickAnimations(timestamp);
            view.dirtyQueue().flush();
            surface.getCanvas().clear(0xFF000000);
            view.renderer().render(surface.getCanvas(), view.root());
        } finally {
            view.dirtyQueue().uninstall();
        }
    }

    private static int firstWhitePixel(Bitmap pixels) {
        for (int x = 0; x < 260; x++) {
            if ((pixels.getColor(x, 32) & 0xFF) > 127) return x;
        }
        throw new AssertionError("animated rectangle missing from frame");
    }
}
