package io.github.timer_err.qml4j.render.items.effect;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;

// QtQuick.Effects MultiEffect. Pipeline order -- blur, then colour grade, then
// shadow, then mask -- is verified against qtdeclarative's multieffect.frag main();
// see Painter.drawMultiEffect. blur is a real Gaussian (Skija's ImageFilter.makeBlur),
// not Qt's cheaper multi-level downsample approximation, but keeps Qt's parameter
// semantics (blur/blurMax/blurMultiplier). brightness/contrast/saturation/
// colorization/colorizationColor are an exact port of Qt's per-pixel formula. shadow
// is generated from the (blurred+graded) source's own silhouette, independent of any
// mask. mask is applied LAST, pixel-for-pixel against the mask subtree's own rendered
// alpha (maskEnabled/maskSource/maskInverted/maskThresholdMin/maskThresholdMax/
// maskSpreadAtMin/maskSpreadAtMax, also an exact port of Qt's formula) -- it crops the
// whole blur+grade+shadow composite, not just the raw source. Note Qt has no
// colorizationEnabled: those four colour knobs are always live, per Qt's own source.
public class MultiEffect extends Item {
    public final Property<Object> source = new Property<>(null);
    @SuppressWarnings("unused")
    public final Property<Boolean> autoPaddingEnabled = new Property<>(Boolean.TRUE);
    public final Property<Boolean> maskEnabled = new Property<>(Boolean.FALSE);
    public final Property<Object> maskSource = new Property<>(null);
    public final Property<Boolean> maskInverted = new Property<>(Boolean.FALSE);
    public final Property<Number> maskThresholdMin = new Property<>(0.0);
    public final Property<Number> maskThresholdMax = new Property<>(1.0);
    public final Property<Number> maskSpreadAtMin = new Property<>(0.0);
    public final Property<Number> maskSpreadAtMax = new Property<>(0.0);

    public final Property<Boolean> blurEnabled = new Property<>(Boolean.FALSE);
    public final Property<Number> blur = new Property<>(0.0);
    public final Property<Number> blurMax = new Property<>(32);
    public final Property<Number> blurMultiplier = new Property<>(0.0);
    public final Property<Boolean> shadowEnabled = new Property<>(Boolean.FALSE);
    public final Property<String> shadowColor = new Property<>("#000000");
    public final Property<Number> shadowBlur = new Property<>(0);
    public final Property<Number> shadowVerticalOffset = new Property<>(0);
    public final Property<Number> shadowHorizontalOffset = new Property<>(0);
    public final Property<Number> shadowOpacity = new Property<>(1.0);
    @SuppressWarnings("unused")
    public final Property<Number> shadowScale = new Property<>(1.0);
    public final Property<Number> brightness = new Property<>(0.0);
    public final Property<Number> contrast = new Property<>(0.0);
    public final Property<Number> saturation = new Property<>(0.0);
    public final Property<Number> colorization = new Property<>(0.0);
    public final Property<String> colorizationColor = new Property<>("#ff0000");

    public MultiEffect() {
        wireContentInvalidation(source, maskEnabled, maskSource, maskInverted,
            maskThresholdMin, maskThresholdMax, maskSpreadAtMin, maskSpreadAtMax,
            blurEnabled, blur, blurMax, blurMultiplier,
            shadowEnabled, shadowColor, shadowBlur, shadowVerticalOffset, shadowHorizontalOffset,
            shadowOpacity, shadowScale,
            brightness, contrast, saturation, colorization, colorizationColor);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        p.drawMultiEffect(this, w, h, alpha);
    }
}
