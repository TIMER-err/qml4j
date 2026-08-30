package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.TextLayout;

public class Text extends Item {
    public final Property<String> text = new Property<>("");
    public final Property<String> color = new Property<>("#000000");
    public final Property<Number> fontSize = new Property<>(14);
    public final Font font = new Font();

    // Qt Text.style: Normal/Outline/Raised/Sunken. styleColor is the outline colour for
    // Outline, the shadow colour for Raised/Sunken. styleWidth is a qml4j extension --
    // stock Qt Quick draws Outline as a fixed ~1px offset ring with no configurable
    // thickness; a value <= 0 falls back to that same ~1px look, > 0 makes it thicker.
    public static final int STYLE_NORMAL = 0;
    public static final int STYLE_OUTLINE = 1;
    public static final int STYLE_RAISED = 2;
    public static final int STYLE_SUNKEN = 3;
    public final Property<Number> style = new Property<>(STYLE_NORMAL);
    public final Property<String> styleColor = new Property<>("#000000");
    public final Property<Number> styleWidth = new Property<>(0);

    public final Property<Number> wrapMode = new Property<>(0);            // Text.NoWrap
    public final Property<Number> horizontalAlignment = new Property<>(1); // Text.AlignLeft
    @SuppressWarnings("unused")
    public final Property<Number> verticalAlignment = new Property<>(32);  // Text.AlignTop
    public final Property<Number> elide = new Property<>(0);                // Text.ElideNone
    // 0 = unlimited. When wrapping produces more lines than this, the last kept
    // line is force-elided with an ellipsis and the reserved height is clamped.
    public final Property<Number> maximumLineCount = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> lineHeight = new Property<>(1);
    @SuppressWarnings("unused")
    public final Property<Number> lineHeightMode = new Property<>(0);       // ProportionalHeight
    // Qt Text.contentWidth/contentHeight: the painted text's natural size (== the measured
    // implicit size). A bubble sized to `label.contentWidth + pad` reads these.
    public final Property<Number> contentWidth = new Property<>(0);
    public final Property<Number> contentHeight = new Property<>(0);

    public Text() {
        wireContentInvalidation(text, color, fontSize, wrapMode, horizontalAlignment,
            verticalAlignment, elide, maximumLineCount, lineHeight,
            font.family, font.pixelSize, font.pointSize, font.weight, font.bold, font.italic,
            font.capitalization, style, styleColor, styleWidth);
    }

    // Effective pixel size: Qt's font.pixelSize wins when set, else flat fontSize.
    // Either may be null (a binding that evaluated to undefined -- QML-tolerant).
    public float effectiveFontSize() {
        Number fp = font.pixelSize.peek();
        if (fp != null && fp.floatValue() > 0) return fp.floatValue();
        Number fs = fontSize.peek();
        return fs != null ? fs.floatValue() : 14f;
    }

    @SuppressWarnings("unused")
    public String lastMeasuredText;
    @SuppressWarnings("unused")
    public float lastMeasuredSize = -1f;
    public double lastSetWidth = Double.NaN;
    public double lastSetHeight = Double.NaN;

    // Text-measurement cache (TextLayout.measureText): the inputs that affect the shaped
    // natural size, and the cached result, so an unchanged label skips re-shaping.
    public String cachedText;
    public float cachedSize = -1f;
    public boolean cachedBold;
    public float cachedWrapW = -1f;
    public int cachedMaxLines = -1;
    public float cachedW;
    public float cachedH;

    @Override
    public void measure(TextLayout t) {
        t.measureText(this);
    }

    @Override
    public void paint(Painter p, float w, float h, float alpha) {
        int argb = p.alphaColor(color.peek(), alpha);
        float size = effectiveFontSize();
        String ig = p.iconGlyphFor(this);
        if (ig != null) {
            if (!ig.isEmpty()) p.drawIconGlyph(ig, w, h, argb, size, horizontalAlignment.peekInt());
            return;
        }
        String s = p.displayTextFor(this);
        if (s.isEmpty()) return;
        p.drawWrappedText(this, s, w, alpha);
    }
}
