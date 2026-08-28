package io.github.timer_err.qml4j.render.items.effect;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;

// QtQuick.Effects MultiEffect. Supports source + mask (maskEnabled/maskSource/maskInverted):
// the source item is painted at the effect's geometry, masked pixel-for-pixel by the mask
// subtree's own rendered alpha (so a gradient mask fades the source, not just clips it).
// Colour/blur/shadow knobs are accepted but not yet applied (shadow excepted).
public class MultiEffect extends Item {
    public final Property<Object> source = new Property<>(null);
    @SuppressWarnings("unused")
    public final Property<Boolean> autoPaddingEnabled = new Property<>(Boolean.TRUE);
    public final Property<Boolean> maskEnabled = new Property<>(Boolean.FALSE);
    public final Property<Object> maskSource = new Property<>(null);
    public final Property<Boolean> maskInverted = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Number> maskThresholdMin = new Property<>(0.0);
    @SuppressWarnings("unused")
    public final Property<Number> maskThresholdMax = new Property<>(1.0);
    @SuppressWarnings("unused")
    public final Property<Number> maskSpreadAtMin = new Property<>(0.0);
    @SuppressWarnings("unused")
    public final Property<Number> maskSpreadAtMax = new Property<>(0.0);

    @SuppressWarnings("unused")
    public final Property<Boolean> blurEnabled = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Number> blur = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> blurMax = new Property<>(32);
    public final Property<Boolean> shadowEnabled = new Property<>(Boolean.FALSE);
    public final Property<String> shadowColor = new Property<>("#000000");
    public final Property<Number> shadowBlur = new Property<>(0);
    public final Property<Number> shadowVerticalOffset = new Property<>(0);
    public final Property<Number> shadowHorizontalOffset = new Property<>(0);
    public final Property<Number> shadowOpacity = new Property<>(1.0);
    @SuppressWarnings("unused")
    public final Property<Number> shadowScale = new Property<>(1.0);
    @SuppressWarnings("unused")
    public final Property<Boolean> colorizationEnabled = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Number> brightness = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> contrast = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> saturation = new Property<>(0);

    public MultiEffect() {
        wireContentInvalidation(source, maskEnabled, maskSource, maskInverted,
            maskThresholdMin, maskThresholdMax, maskSpreadAtMin, maskSpreadAtMax,
            blurEnabled, blur, blurMax,
            shadowEnabled, shadowColor, shadowBlur, shadowVerticalOffset, shadowHorizontalOffset,
            shadowOpacity, shadowScale,
            colorizationEnabled, brightness, contrast, saturation);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        p.drawMultiEffect(this, w, h, alpha);
    }
}
