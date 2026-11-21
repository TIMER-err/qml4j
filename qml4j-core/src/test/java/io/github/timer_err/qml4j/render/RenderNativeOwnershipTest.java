package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.impl.Stats;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.effect.ColorOverlay;
import io.github.timer_err.qml4j.render.items.effect.DropShadow;
import io.github.timer_err.qml4j.render.items.effect.Glow;
import io.github.timer_err.qml4j.render.items.effect.MultiEffect;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderNativeOwnershipTest {
    @Test
    void repeatedMultiEffectFramesReleaseEveryTemporaryFilterWithoutGc() {
        Renderer renderer = new Renderer();
        MultiEffect effect = new MultiEffect();
        effect.width.set(16);
        effect.shadowBlur.set(0.25);
        effect.height.set(16);
        effect.source.set(rectangle());
        effect.maskEnabled.set(true);
        effect.maskSource.set(rectangle());
        effect.blurEnabled.set(true);
        effect.blur.set(0.25);
        effect.shadowEnabled.set(true);
        effect.brightness.set(0.1);
        try (Surface surface = Surface.makeRasterN32Premul(16, 16)) {
            renderer.render(surface.getCanvas(), effect);
            boolean enabled = Stats.enabled;
            Stats.enabled = true;
            try {
                int imageFilters = allocated("ImageFilter");
                int colorFilters = allocated("ColorFilter");
                for (int frame = 0; frame < 100; frame++) {
                    renderer.render(surface.getCanvas(), effect);
                    assertEquals(imageFilters, allocated("ImageFilter"), "frame " + frame);
                    assertEquals(colorFilters, allocated("ColorFilter"), "frame " + frame);
                }
            } finally {
                Stats.enabled = enabled;
            }
        } finally {
            effect.dispose();
            renderer.dispose();
        }
    }

    @Test
    void repeatedLayerEffectsReleaseFiltersFromBothCullingAndDrawing() {
        MultiEffect shadow = new MultiEffect();
        shadow.shadowEnabled.set(true);
        for (Object effect : Arrays.asList(new DropShadow(), new Glow(), new ColorOverlay(), shadow)) {
            Renderer renderer = new Renderer();
            Rectangle rectangle = rectangle();
            rectangle.layer.enabled.set(true);
            rectangle.layer.effect.set(effect);
            try (Surface surface = Surface.makeRasterN32Premul(16, 16)) {
                renderer.render(surface.getCanvas(), rectangle);
                boolean enabled = Stats.enabled;
                Stats.enabled = true;
                try {
                    int imageFilters = allocated("ImageFilter");
                    int colorFilters = allocated("ColorFilter");
                    for (int frame = 0; frame < 100; frame++) {
                        renderer.render(surface.getCanvas(), rectangle);
                        assertEquals(imageFilters, allocated("ImageFilter"), effect.getClass().getSimpleName());
                        assertEquals(colorFilters, allocated("ColorFilter"), effect.getClass().getSimpleName());
                    }
                } finally {
                    Stats.enabled = enabled;
                }
            } finally {
                rectangle.dispose();
                renderer.dispose();
            }
        }
    }

    private static Rectangle rectangle() {
        Rectangle rectangle = new Rectangle();
        rectangle.width.set(16);
        rectangle.height.set(16);
        rectangle.color.set("white");
        return rectangle;
    }

    private static int allocated(String type) {
        return Stats.allocated.getOrDefault(type, 0);
    }
}
