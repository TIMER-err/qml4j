package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.impl.Native;
import io.github.humbleui.skija.FontMetrics;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.SamplingMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathDirection;
import io.github.humbleui.skija.PathEllipseArc;
import io.github.humbleui.skija.PathFillMode;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.TextBlob;
import io.github.humbleui.skija.TextLine;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;
import io.github.timer_err.qml4j.render.items.core.Gradient;
import io.github.timer_err.qml4j.render.items.core.GradientStop;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Text;
import io.github.timer_err.qml4j.render.items.core.TextWrap;
import io.github.timer_err.qml4j.render.items.effect.MultiEffect;
import io.github.timer_err.qml4j.render.items.input.TextEdit;
import io.github.timer_err.qml4j.render.items.input.TextField;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.shape.ImageFill;
import io.github.timer_err.qml4j.render.items.shape.PathArc;
import io.github.timer_err.qml4j.render.items.shape.PathCubic;
import io.github.timer_err.qml4j.render.items.shape.PathElement;
import io.github.timer_err.qml4j.render.items.shape.PathLine;
import io.github.timer_err.qml4j.render.items.shape.PathMove;
import io.github.timer_err.qml4j.render.items.shape.PathQuad;
import io.github.timer_err.qml4j.render.items.shape.Shape;
import io.github.timer_err.qml4j.render.items.shape.ShapePath;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The drawing surface handed to Item.paint(): skija-backed primitives keyed by
// geometry and colour, so item subclasses can render themselves without ever
// importing skija. The current Canvas is bound once per frame by the Renderer.
public final class Painter {

    private final Renderer renderer;
    private Canvas canvas;

    Painter(Renderer renderer) {
        this.renderer = renderer;
    }

    void bind(Canvas canvas) {
        this.canvas = canvas;
    }

    // The currently bound canvas, so the Renderer can swap in a PictureRecorder's canvas
    // while recording a cached subtree and restore the on-screen one afterwards.
    Canvas canvas() {
        return canvas;
    }

    // A 2D drawing context bound to the current canvas (already translated to the
    // painting item's origin), for a Canvas item's onPaint handler.
    public Context2D context2D() {
        return new Context2D(canvas, renderer);
    }

    // Paint a Canvas item's onPaint into an offscreen layer (the item rect) and composite
    // it back at `alpha`. The layer is what makes clearRect (BlendMode.CLEAR) erase only
    // the canvas instead of punching a transparent hole through the scene to the
    // framebuffer -- which shows as black on a GL backend.
    public void inLayer(float w, float h, float alpha, Runnable body) {
        int save = canvas.saveLayerAlpha(Rect.makeXYWH(0, 0, Math.max(0f, w), Math.max(0f, h)), Math.round(alpha * 255));
        try {
            body.run();
        } finally {
            canvas.restoreToCount(save);
        }
    }

    // A QtQuick Canvas: run onPaint into an offscreen backing surface only when dirty
    // (requestPaint / resize), then blit the cached image every frame. Without this the
    // onPaint handler -- particle sims, gradients -- re-runs every render frame regardless
    // of requestPaint, far above the widget's intended fps. (The QML Canvas item is FQN'd:
    // its simple name clashes with the imported skija Canvas this Painter draws through.)
    // GPU-backed offscreen when rendering to a GPU surface (so the blit is GPU->GPU and
    // composites every frame); raster when headless. Falls back to raster if the GPU
    // surface can't be created.
    // gpu != null is a defensive fallback: makeRenderTarget can fail at runtime on a lost/limited
    // GL context even though its return is annotated non-null -- keep the raster fallback.
    @SuppressWarnings("ConstantValue")
    private Surface makeBackingSurface(int w, int h) {
        io.github.humbleui.skija.DirectContext ctx = renderer.gpuContext();
        if (ctx != null) {
            // Match the GL window surface's BOTTOM_LEFT origin: a TOP_LEFT offscreen blitted
            // onto a BOTTOM_LEFT canvas is vertically flipped, displacing small transformed
            // content (a centred, rotated loading spinner) off its own bounds -- it vanished.
            Surface gpu = Surface.makeRenderTarget(ctx, true,
                io.github.humbleui.skija.ImageInfo.makeN32Premul(w, h), 0,
                io.github.humbleui.skija.SurfaceOrigin.BOTTOM_LEFT, null);
            if (gpu != null) return gpu;
        }
        return Surface.makeRaster(io.github.humbleui.skija.ImageInfo.makeN32Premul(w, h));
    }

    // Cache onPaint into an offscreen, repainting only when dirty (big win for animated/
    // static canvases). -Dqml4j.canvasCache=false falls back to per-frame direct draw.
    private static final boolean CANVAS_CACHE = !"false".equals(System.getProperty("qml4j.canvasCache", "true"));

    // Snap a device scale to 0.05 steps (min 1) so transient scale animations don't
    // resize the canvas backing every frame. Finer than the original 0.5-step snap:
    // the blit below resamples the backing by whatever "residual scale" is left
    // between this quantized value and the item's live matrix scale, and 0.5 steps
    // left up to a 25% residual for common device scales (e.g. 1.25x quantized up
    // to 1.5x) that showed up as visibly soft/aliased edges on stroked Canvas
    // content (MD3's wavy progress bars) even with nothing animating -- a static
    // item sits at that one quantized resolution for as long as its size is
    // unchanged. 0.05 steps make the common integer/quarter/tenth device scales
    // (1x, 1.25x, 1.5x, 2x, ...) land exactly on a step, so the steady-state case
    // resamples 1:1, while still coalescing per-frame float jitter during an
    // actual scale animation.
    private static float quantizeScale(float s) {
        if (s < 0.01f) return 1f;
        return Math.max(1f, Math.round(s * 20f) / 20f);
    }

    public void paintCanvas(io.github.timer_err.qml4j.render.items.core.Canvas node, float w, float h, float alpha) {
        if (!CANVAS_CACHE) {
            node.bindContext(context2D());
            try {
                inLayer(w, h, alpha, node.paint::emit);
            } finally {
                node.bindContext(null);
            }
            return;
        }
        // Back the canvas at DEVICE resolution: the main canvas carries the host
        // uiScale, so a logical-sized backing (w x h) gets upsampled blurry on
        // blit. Size the offscreen by the live logical->device scale and render
        // onPaint into it scaled to match, then blit it back 1:1 in device px.
        float[] m = canvas.getLocalToDevice().getMat();
        float sx = m[0], sy = m[5], tx = m[3], ty = m[7];
        // Quantise the backing scale (snap to 0.5 steps) so a transient item-scale
        // animation (a dialog popping 0.9->1.0) doesn't change the offscreen size
        // every frame -- that would recreate + repaint the backing each frame and
        // stutter the animation. The blit below still uses the live matrix scale,
        // so the result stays correctly sized; only the cached resolution is
        // pinned. Residual scale just resamples the (already device-res) cache.
        float dsx = quantizeScale(Math.abs(sx));
        float dsy = quantizeScale(Math.abs(sy));
        int iw = Math.max(1, Math.round(w * dsx));
        int ih = Math.max(1, Math.round(h * dsy));
        if (node.backing == null || node.backingW != iw || node.backingH != ih) {
            if (node.backing != null) node.backing.close();
            node.backing = makeBackingSurface(iw, ih);
            node.backingW = iw;
            node.backingH = ih;
            node.dirty = true;
        }
        if (node.dirty) {
            io.github.humbleui.skija.Canvas bc = node.backing.getCanvas();
            // The backing canvas is persistent; ctx.translate/rotate mutate its matrix and
            // ctx.reset() does NOT restore it (the direct path got a fresh per-frame matrix
            // from the Renderer's save/restore). Save/restore here so each onPaint starts from
            // identity -- else a rotating canvas (the loading spinner) drifts off-surface and
            // blanks after a few frames.
            int sv = bc.save();
            bc.clear(0x00000000);
            // onPaint draws in logical coords (0..w, 0..h); scale up to fill the
            // device-resolution backing so the result is crisp.
            bc.scale(dsx, dsy);
            node.bindContext(new Context2D(bc, renderer));
            try {
                node.paint.emit();
            } finally {
                node.bindContext(null);
                bc.restoreToCount(sv);
            }
            node.dirty = false;
        }
        // Surface.draw blits the backing onto the main canvas (the matrix already carries
        // this item's scale/position) without the makeImageSnapshot/close churn -- closing a
        // just-snapshotted raster Image while its blit is still queued on the GPU backend
        // blanked the canvas a frame after it appeared.
        Paint p = renderer.paint();
        p.setShader(null);
        p.setMode(PaintMode.FILL);
        p.setColor(Renderer.applyAlpha(0xFFFFFFFF, alpha));
        // Snap the blit to an integer device pixel (a canvas centred at a fractional
        // device position would resample blurry), then scale the device-res backing
        // back down so its pixels land 1:1 on device pixels.
        int save = canvas.save();
        if (sx != 0 && sy != 0) {
            canvas.translate((Math.round(tx) - tx) / sx, (Math.round(ty) - ty) / sy);
        }
        canvas.scale(1f / dsx, 1f / dsy);
        node.backing.draw(canvas, 0, 0, p);
        canvas.restoreToCount(save);
    }

    public int alphaColor(String color, float alpha) {
        return Renderer.applyAlpha(Renderer.parseColor(color), alpha);
    }

    public String iconGlyphFor(Text t) {
        return renderer.icons().iconGlyph(t);
    }

    public String displayTextFor(Text t) {
        return renderer.icons().displayText(t);
    }

    // A single Material Symbols icon, shaped from its ligature name (so the font's GSUB
    // forms the glyph) and vertically centred in the box via real font metrics.
    // Shaping a Material Symbols ligature (name -> glyph via the font's GSUB) is expensive
    // and re-runs every frame for every icon; cache the shaped TextLine (and per-size Font)
    // keyed on (name, size). Native handles are long-lived and bounded by the app's distinct
    // icon/size set, so they are intentionally not closed.
    // Render runs single-threaded, so each text cache uses ONE reusable probe key
    // for lookups: get() mutates the probe and never retains it; only a cache MISS
    // allocates an immutable key to put(). The steady-state scroll path is all hits,
    // so it is allocation-free -- where the previous concatenated <fontId>+sep+<text>
    // String key allocated one String per label per frame and churned the young gen
    // (Haedus rule: no uncontrolled allocation on the per-frame render path).
    private static final class LineKey {
        int fontId;
        String s;
        LineKey() { }
        LineKey(int fontId, String s) { this.fontId = fontId; this.s = s; }
        @Override
        public int hashCode() { return fontId * 31 + s.hashCode(); }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LineKey)) return false;
            LineKey k = (LineKey) o;
            return fontId == k.fontId && s.equals(k.s);
        }
    }

    // (font, roundedWidth, mode, text). mode is null for elision.
    private static final class SizedKey {
        int fontId;
        int w;
        String mode;
        String s;
        SizedKey() { }
        SizedKey(int fontId, int w, String mode, String s) {
            this.fontId = fontId; this.w = w; this.mode = mode; this.s = s;
        }
        @Override
        public int hashCode() {
            int h = fontId * 31 + w;
            h = h * 31 + (mode == null ? 0 : mode.hashCode());
            return h * 31 + s.hashCode();
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof SizedKey)) return false;
            SizedKey k = (SizedKey) o;
            return fontId == k.fontId && w == k.w
                && java.util.Objects.equals(mode, k.mode) && s.equals(k.s);
        }
    }

    private final LineKey lineProbe = new LineKey();
    private final SizedKey sizedProbe = new SizedKey();

    // Icon ligatures (name -> glyph via GSUB). Unbounded (bounded by the app's
    // distinct icon/size set); handles are long-lived and intentionally not closed.
    private final Map<LineKey, TextLine> iconLines = new HashMap<>();
    private final Map<TextLine, TextBlob> lineBlobs = new java.util.IdentityHashMap<>();

    // Shaping a string (HarfBuzz via Skia) on every drawString/measureTextWidth
    // re-runs for every visible label every frame -- the dominant paint cost. Cache
    // the shaped TextLine per (font, text). Bounded LRU (strings are unbounded),
    // closing evicted native handles.
    private final Map<LineKey, TextLine> textLines =
        new java.util.LinkedHashMap<LineKey, TextLine>(512, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<LineKey, TextLine> e) {
                if (size() > 600) {
                    closeBlob(e.getValue());
                    e.getValue().close();
                    return true;
                }
                return false;
            }
        };

    private TextLine cachedLine(Map<LineKey, TextLine> cache, int fontId, String s, Font font) {
        lineProbe.fontId = fontId;
        lineProbe.s = s;
        TextLine hit = cache.get(lineProbe);
        if (hit != null) return hit;
        TextLine line = TextLine.make(s, font);
        TextBlob blob = line.getTextBlob();
        if (blob != null) lineBlobs.put(line, blob);
        cache.put(new LineKey(fontId, s), line);
        return line;
    }

    private void drawTextLine(TextLine line, float x, float y, Paint paint) {
        TextBlob blob = lineBlobs.get(line);
        if (blob == null) {
            blob = line.getTextBlob();
            if (blob != null) lineBlobs.put(line, blob);
        }
        if (blob != null) canvas.drawTextBlob(blob, x, y, paint);
    }

    private void closeBlob(TextLine line) {
        TextBlob blob = lineBlobs.remove(line);
        if (blob != null) {
            try { blob.close(); } catch (Throwable ignored) { }
        }
    }

    private TextLine textLine(Font font, String s) {
        return cachedLine(textLines, System.identityHashCode(font), s, font);
    }

    private float textWidth(Font font, String s) {
        return textLine(font, s).getWidth();
    }

    // Elision recomputed for every label every frame measured each substring via
    // uncached shaping -- a real per-frame draw cost on text-heavy screens. Cache
    // the elided result per (font, width, text).
    private final Map<SizedKey, String> elideCache =
        new java.util.LinkedHashMap<SizedKey, String>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SizedKey, String> e) {
                return size() > 600;
            }
        };

    // Wrapping (TextWrap.wrap) re-shapes every segment every frame for every
    // wrapped label -- a big cost for large bodies. Cache the wrapped lines per
    // (font, mode, width, text).
    private final Map<SizedKey, String[]> wrapCache =
        new java.util.LinkedHashMap<SizedKey, String[]>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SizedKey, String[]> e) {
                return size() > 256;
            }
        };

    // Close every cached native handle when the owning renderer/view is disposed. The
    // TextLine caches hold native SkShaper output; like fonts, they never pressure the JVM
    // heap, so leaving them for GC leaks them across hot-reloads. Called from Renderer.dispose().
    void dispose() {
        for (TextLine l : iconLines.values()) {
            closeBlob(l);
            if (l != null) { try { l.close(); } catch (Throwable ignored) {} }
        }
        iconLines.clear();
        for (TextLine l : textLines.values()) {
            closeBlob(l);
            if (l != null) { try { l.close(); } catch (Throwable ignored) {} }
        }
        textLines.clear();
        lineBlobs.clear();
        elideCache.clear();
        wrapCache.clear();
    }

    private String[] wrapLines(Font font, String s, String mode, float boxW) {
        int fontId = System.identityHashCode(font);
        int w = Math.round(boxW);
        sizedProbe.fontId = fontId;
        sizedProbe.w = w;
        sizedProbe.mode = mode;
        sizedProbe.s = s;
        String[] hit = wrapCache.get(sizedProbe);
        if (hit != null) return hit;
        String[] lines = TextWrap.wrap(s, mode, boxW, seg -> textWidth(font, seg))
                .lines.toArray(new String[0]);
        wrapCache.put(new SizedKey(fontId, w, mode, s), lines);
        return lines;
    }

    private String elideRightToWidth(Font font, String line, float boxW) {
        if (boxW <= 0f) return line;
        int fontId = System.identityHashCode(font);
        int w = Math.round(boxW);
        sizedProbe.fontId = fontId;
        sizedProbe.w = w;
        sizedProbe.mode = null;
        sizedProbe.s = line;
        String hit = elideCache.get(sizedProbe);
        if (hit != null) return hit;
        String result;
        if (textWidth(font, line) <= boxW) {
            result = line;
        } else {
            float ellW = textWidth(font, "…");
            int end = line.length();
            while (end > 0 && textWidth(font, line.substring(0, end)) + ellW > boxW) end--;
            result = line.substring(0, end) + "…";
        }
        elideCache.put(new SizedKey(fontId, w, null, line), result);
        return result;
    }

    // Like elideRightToWidth but always ends with an ellipsis: used for the last
    // visible line of a maximumLineCount-clamped block, where text continues
    // beyond the cut even if this line itself fits the box. Cached under a
    // distinct mode key so it doesn't collide with the plain elide cache.
    private static final String FORCE_ELLIPSIS_MODE = "max";

    private String elideForceEllipsis(Font font, String line, float boxW) {
        if (boxW <= 0f) return line;
        int fontId = System.identityHashCode(font);
        int w = Math.round(boxW);
        sizedProbe.fontId = fontId;
        sizedProbe.w = w;
        sizedProbe.mode = FORCE_ELLIPSIS_MODE;
        sizedProbe.s = line;
        String hit = elideCache.get(sizedProbe);
        if (hit != null) return hit;
        float ellW = textWidth(font, "…");
        String result;
        if (textWidth(font, line) + ellW <= boxW) {
            result = line + "…";
        } else {
            int end = line.length();
            while (end > 0 && textWidth(font, line.substring(0, end)) + ellW > boxW) end--;
            result = line.substring(0, end) + "…";
        }
        elideCache.put(new SizedKey(fontId, w, FORCE_ELLIPSIS_MODE, line), result);
        return result;
    }

    public void drawIconGlyph(String name, float boxW, float boxH, int argb, float size, int hAlign) {
        Font f = renderer.fonts().iconFont(size);
        TextLine line = cachedLine(iconLines, System.identityHashCode(f), name, f);
        FontMetrics fm = f.getMetrics();
        float baseline = boxH / 2f - (fm.getAscent() + fm.getDescent()) / 2f;
        // Center/right within boxW at paint time from the glyph's own line width. A Text
        // sized to the glyph and centred by anchors relies on its measured width, which a
        // cache-skipped (off-screen) row never computes, so an icon placeholder that fills
        // a wider box must be aligned here instead.
        float x = hAlign == 4 ? (boxW - line.getWidth()) / 2f
                : hAlign == 2 ? boxW - line.getWidth()
                : 0f;
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setShader(null);
        p.setColor(argb);
        drawTextLine(line, x, baseline, p);
    }

    // Qt Text.Outline's default look is a fixed ~1px offset ring (see qquicktextnode.cpp);
    // styleWidth <= 0 reproduces that same thickness via a real stroked pass instead.
    private static final float DEFAULT_OUTLINE_WIDTH = 1f;
    // Qt draws Raised/Sunken as a single styleColor copy shifted 1px down/up, underneath
    // the normal-coloured text (qquicktextnode.cpp shiftForStyle).
    private static final float RAISED_SUNKEN_OFFSET = 1f;

    // Multi-line text: optional wrap to boxW, optional right-elision, from y=0. Each line
    // is offset by hAlign (Text.AlignHCenter/AlignRight) within boxW, so a centred Text
    // centres every wrapped line, not just the block. t.style selects Qt's Outline/Raised/
    // Sunken text decoration, drawn under the normal fill pass.
    public void drawWrappedText(Text t, String s, float boxW, float alpha) {
        int argb = alphaColor(t.color.peek(), alpha);
        float size = t.effectiveFontSize();
        boolean elideRight = t.elide.peekInt() == 3; // Text.ElideRight
        boolean bold = Boolean.TRUE.equals(t.font.bold.peek()) || t.font.weight.peekInt() >= 63;
        int hAlign = t.horizontalAlignment.peekInt();
        int maxLines = t.maximumLineCount.peekInt();
        int style = t.style.peekInt();
        int styleArgb = style == Text.STYLE_NORMAL ? 0 : alphaColor(t.styleColor.peek(), alpha);
        float styleWidth = t.styleWidth.peekFloat();

        String wrapMode = TextLayout.wrapModeString(t.wrapMode.peekInt());
        Font font = renderer.fonts().fontFor(size, s, bold);
        float baseline0 = TextLayout.baselineInLine(font);
        Paint p = renderer.paint();
        p.setShader(null);
        // Common path drawn every frame: no wrapping and no embedded newline -> a
        // single line. Draw it directly; splitLines(s) would allocate a String[]
        // per label per frame just to hold that one line.
        boolean wrapping = wrapMode != null && boxW > 0f;
        if (!wrapping && s.indexOf('\n') < 0) {
            String line = elideRight ? elideRightToWidth(font, s, boxW) : s;
            TextLine shaped = textLine(font, line);
            drawStyledGlyphLine(shaped, lineOffset(line, font, boxW, hAlign), baseline0,
                                argb, style, styleArgb, styleWidth, p);
            return;
        }
        String[] lines = wrapping ? wrapLines(font, s, wrapMode, boxW) : TextLayout.splitLines(s);
        // Clamp to maximumLineCount: draw only the first maxLines rows and mark the
        // last kept row truncated so it gets a trailing ellipsis.
        int drawCount = lines.length;
        boolean truncated = false;
        if (maxLines > 0 && drawCount > maxLines) {
            drawCount = maxLines;
            truncated = true;
        }
        float lineH = TextLayout.lineHeight(font);
        for (int i = 0; i < drawCount; i++) {
            if (lines[i].isEmpty()) continue;
            String line;
            if (truncated && i == drawCount - 1) {
                line = elideForceEllipsis(font, lines[i], boxW);
            } else {
                line = elideRight ? elideRightToWidth(font, lines[i], boxW) : lines[i];
            }
            float tx = lineOffset(line, font, boxW, hAlign);
            drawStyledGlyphLine(textLine(font, line), tx, baseline0 + i * lineH,
                                argb, style, styleArgb, styleWidth, p);
        }
    }

    // Draws one shaped line's Text.style decoration (Outline/Raised/Sunken), then the
    // normal fill pass on top -- the decoration sits underneath so the fill reads cleanly.
    private void drawStyledGlyphLine(TextLine line, float x, float y, int fillArgb,
                                     int style, int styleArgb, float styleWidth, Paint p) {
        switch (style) {
            case Text.STYLE_OUTLINE:
                p.setMode(PaintMode.STROKE);
                p.setStrokeWidth(styleWidth > 0f ? styleWidth : DEFAULT_OUTLINE_WIDTH);
                p.setColor(styleArgb);
                drawTextLine(line, x, y, p);
                break;
            case Text.STYLE_RAISED:
                p.setMode(PaintMode.FILL);
                p.setColor(styleArgb);
                drawTextLine(line, x, y + RAISED_SUNKEN_OFFSET, p);
                break;
            case Text.STYLE_SUNKEN:
                p.setMode(PaintMode.FILL);
                p.setColor(styleArgb);
                drawTextLine(line, x, y - RAISED_SUNKEN_OFFSET, p);
                break;
            default:
                break;
        }
        p.setMode(PaintMode.FILL);
        p.setColor(fillArgb);
        drawTextLine(line, x, y, p);
    }

    // The x offset placing a line within boxW per its horizontal alignment.
    // AlignHCenter (4) centres, AlignRight (2) right-aligns; AlignLeft/justify stay at 0.
    private float lineOffset(String line, Font font, float boxW, int hAlign) {
        if (boxW <= 0f || hAlign == 1) return 0f;
        if (hAlign == 4) return (boxW - textWidth(font, line)) / 2f;
        if (hAlign == 2) return boxW - textWidth(font, line);
        return 0f;
    }

    // A single line of text, horizontally centred and baseline-centred in the box.
    public void drawCenteredText(String s, float boxW, float boxH, int argb, float size) {
        { Font font = renderer.fonts().fontFor(size, s);
            float tw = textWidth(font, s);
            float tx = (boxW - tw) / 2f;
            float ty = TextLayout.centeredBaseline(font, boxH);
            Paint p = renderer.paint();
            p.setMode(PaintMode.FILL);
            p.setShader(null);
            p.setColor(argb);
            drawTextLine(textLine(font, s), tx, ty, p);
        }
    }

    // Skija's public drawRect/drawRRect/clipRect each allocate a fresh Rect/RRect
    // value object. Over every visible primitive at 60fps that churn drove periodic
    // young-gen GC pauses (the "stutter every few seconds"). Call the native draw
    // ops directly with floats instead -- identical to what the public wrappers do,
    // minus the allocation. canvas/paint are strongly held (field + renderer paint)
    // for the synchronous native call, so no reachability fence is needed (and it's
    // API 28+ anyway, above our minSdk). The RRect radii array is reused.
    private final float[] radius1 = new float[1];
    private final float[] radius4 = new float[4];

    private void rawRect(float x, float y, float w, float h, Paint p) {
        Canvas._nDrawRect(Native.getPtr(canvas), x, y, x + w, y + h, Native.getPtr(p));
    }

    private void rawRRect(float x, float y, float w, float h, float radius, Paint p) {
        radius1[0] = radius;
        Canvas._nDrawRRect(Native.getPtr(canvas), x, y, x + w, y + h, radius1, Native.getPtr(p));
    }

    // Skia reads the RRect kind from the radii array's length: 4 entries are tl,tr,br,bl.
    private void rawRRect(float x, float y, float w, float h,
                          float tl, float tr, float br, float bl, Paint p) {
        radius4[0] = tl;
        radius4[1] = tr;
        radius4[2] = br;
        radius4[3] = bl;
        Canvas._nDrawRRect(Native.getPtr(canvas), x, y, x + w, y + h, radius4, Native.getPtr(p));
    }

    public void fillRect(float x, float y, float w, float h, int argb) {
        if (w <= 0f || h <= 0f) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setShader(null);
        p.setColor(argb);
        rawRect(x, y, w, h, p);
    }

    public void fillRoundRect(float x, float y, float w, float h, float radius, int argb) {
        if (w <= 0f || h <= 0f) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setShader(null);
        p.setColor(argb);
        if (radius > 0f) {
            rawRRect(x, y, w, h, radius, p);
        } else {
            rawRect(x, y, w, h, p);
        }
    }

    public void fillRoundRect(float x, float y, float w, float h,
                              float tl, float tr, float br, float bl, int argb) {
        if (w <= 0f || h <= 0f) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setShader(null);
        p.setColor(argb);
        if (tl > 0f || tr > 0f || br > 0f || bl > 0f) {
            rawRRect(x, y, w, h, tl, tr, br, bl, p);
        } else {
            rawRect(x, y, w, h, p);
        }
    }

    public void fillGradientRoundRect(float x, float y, float w, float h, float radius,
                                      Gradient gradient, float alpha) {
        if (w <= 0f || h <= 0f) return;
        Shader shader = buildLinearGradient(gradient, w, h);
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setShader(shader);
        p.setColor(Renderer.applyAlpha(0xFFFFFFFF, alpha));
        if (radius > 0f) {
            rawRRect(x, y, w, h, radius, p);
        } else {
            rawRect(x, y, w, h, p);
        }
        p.setShader(null);
        if (shader != null) shader.close();
    }

    public void strokeRoundRect(float x, float y, float w, float h, float radius,
                                int argb, float strokeWidth) {
        if (w <= 0f || h <= 0f) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth(strokeWidth);
        p.setShader(null);
        p.setColor(argb);
        if (radius > 0f) {
            rawRRect(x, y, w, h, radius, p);
        } else {
            rawRect(x, y, w, h, p);
        }
        p.setMode(PaintMode.FILL);
    }

    public void strokeRoundRect(float x, float y, float w, float h,
                                float tl, float tr, float br, float bl,
                                int argb, float strokeWidth) {
        if (w <= 0f || h <= 0f) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth(strokeWidth);
        p.setShader(null);
        p.setColor(argb);
        if (tl > 0f || tr > 0f || br > 0f || bl > 0f) {
            rawRRect(x, y, w, h, tl, tr, br, bl, p);
        } else {
            rawRect(x, y, w, h, p);
        }
        p.setMode(PaintMode.FILL);
    }

    private static Shader buildLinearGradient(Gradient g, float w, float h) {
        List<GradientStop> stops = g.stops;
        int n = stops.size();
        if (n == 0) return null;
        int[] colors = new int[n];
        float[] positions = new float[n];
        for (int i = 0; i < n; i++) {
            GradientStop s = stops.get(i);
            colors[i] = Renderer.parseColor(s.color.peek());
            positions[i] = s.position.peekFloat();
        }
        boolean horizontal = g.orientation.peekDouble() == 1;
        return horizontal
            ? Shader.makeLinearGradient(0, 0, w, 0, colors, positions)
            : Shader.makeLinearGradient(0, 0, 0, h, colors, positions);
    }

    // Image is its own subsystem: source loading/decoding, intrinsic-size probe,
    // ImageFill plan, and the draw/tile blit. It lives here (not on the Image item)
    // because it touches skija decoding and the resource loader. skija's Image is
    // FQN'd throughout this section: its simple name clashes with the QML Image item.
    // Image.fillMode may be the string form or the Image.* enum (a Long); map to the
    // string ImageFill understands.
    private static String fillModeString(Object o) {
        if (o instanceof String) return (String) o;
        if (o instanceof Number) {
            switch (((Number) o).intValue()) {
                case 1: return "PreserveAspectFit";
                case 2: return "PreserveAspectCrop";
                case 3: return "Tile";
                case 4: return "TileVertically";
                case 5: return "TileHorizontally";
                case 6: return "Pad";
                default: return "Stretch";
            }
        }
        return "Stretch";
    }


    // decodeGen is incremented only on the render thread (here and Image.releaseResources);
    // it is volatile for visibility to the decode worker (which only reads it), so the
    // non-atomic ++ is a safe single-writer increment.
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    public void drawImage(Image node, float w, float h, float alpha) {
        String src = node.source.peek();
        if (src == null || src.isEmpty()) {
            if (node.skiaImage != null) { node.skiaImage.close(); node.skiaImage = null; }
            node.loadedSource = null;
            node.status.set(0);
            return;
        }
        if (!src.equals(node.loadedSource)) {
            // New source: keep showing the old image (no blank flash) and decode the new
            // one off the render thread; adopt it below once ready.
            node.loadedSource = src;
            long gen = ++node.decodeGen;
            node.status.set(2); // Loading
            ImageLoader.decode(node, src, gen, renderer.resources());
        }
        if (node.decodeReadyGen == node.decodeGen && node.adoptedGen != node.decodeGen) {
            node.adoptedGen = node.decodeGen;
            if (node.skiaImage != null) { node.skiaImage.close(); node.skiaImage = null; }
            io.github.humbleui.skija.Image pend = node.pendingImage;
            node.pendingImage = null;
            if (pend != null) {
                node.skiaImage = pend;
                node.intrinsicWidth = node.pendW;
                node.intrinsicHeight = node.pendH;
                node.status.set(1); // Ready
            } else {
                node.status.set(3); // Error
            }
        }
        if (node.skiaImage == null) return;
        int iw = node.intrinsicWidth;
        int ih = node.intrinsicHeight;
        if (iw <= 0 || ih <= 0) return;
        if (w <= 0) w = iw;
        if (h <= 0) h = ih;
        ImageFill.Plan plan = ImageFill.compute(fillModeString(node.fillMode.peek()), iw, ih, w, h);
        if (plan == null) return;
        node.paintedWidth.set(plan.paintedWidth);
        node.paintedHeight.set(plan.paintedHeight);
        float radius = node.radius.peekFloat();
        int save = radius > 0 ? canvas.save() : -1;
        if (radius > 0) canvas.clipRRect(RRect.makeXYWH(0, 0, w, h, radius), true);
        try {
            // null paint = fully opaque; only build an alpha paint when the
            // inherited opacity is actually < 1 (page fades, etc.).
            Paint ip = alpha < 0.999f ? imageAlphaPaint(alpha) : null;
            switch (plan.op) {
                case DRAW_RECT:
                    drawImagePlan(node.skiaImage, plan, ip);
                    break;
                case TILE_X:
                case TILE_Y:
                case TILE_XY:
                    drawTilePlan(node.skiaImage, plan, w, h, ip);
                    break;
            }
        } finally {
            if (save >= 0) canvas.restoreToCount(save);
        }
    }

    // Bilinear for the on-screen draw: the carousel image is pre-shrunk near its display
    // size in decodeInto, so this only resolves a small residual scale. Bicubic (Mitchell)
    // overshoots at high-contrast edges -- visible ringing/jaggies -- so linear stays clean.
    private static final SamplingMode IMAGE_SAMPLING = SamplingMode.LINEAR;

    private void drawImagePlan(io.github.humbleui.skija.Image img, ImageFill.Plan plan, Paint paint) {
        Rect src = Rect.makeXYWH(plan.srcX, plan.srcY, plan.srcW, plan.srcH);
        Rect dst = Rect.makeXYWH(plan.dstX, plan.dstY, plan.dstW, plan.dstH);
        canvas.drawImageRect(img, src, dst, IMAGE_SAMPLING, paint, true);
    }

    private void drawTilePlan(io.github.humbleui.skija.Image img,
                              ImageFill.Plan plan, float boundsW, float boundsH, Paint paint) {
        int saved = canvas.save();
        try {
            canvas.clipRect(Rect.makeXYWH(plan.clipX, plan.clipY, plan.clipW, plan.clipH));
            float stepX = plan.tileStepX > 0 ? plan.tileStepX : boundsW;
            float stepY = plan.tileStepY > 0 ? plan.tileStepY : boundsH;
            Rect src = Rect.makeXYWH(plan.srcX, plan.srcY, plan.srcW, plan.srcH);
            for (float y = 0; y < boundsH; y += stepY) {
                for (float x = 0; x < boundsW; x += stepX) {
                    Rect dst = Rect.makeXYWH(x, y, plan.dstW, plan.dstH);
                    canvas.drawImageRect(img, src, dst, IMAGE_SAMPLING, paint, true);
                }
            }
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    // Reused paint carrying the inherited opacity for image draws (alpha < 1).
    private Paint imageAlphaPaintField;
    private Paint imageAlphaPaint(float alpha) {
        if (imageAlphaPaintField == null) imageAlphaPaintField = new Paint();
        imageAlphaPaintField.setAlphaf(alpha);
        return imageAlphaPaintField;
    }



    public void drawShape(Shape shape, float alpha) {
        try (Paint p = new Paint()) {
            p.setAntiAlias(true);
            for (ShapePath sp : shape.elements) {
                try (Path path = buildPath(sp)) {
                    fillPath(path, sp, alpha, p);
                    strokePath(path, sp, alpha, p);
                }
            }
        }
    }

    private Path buildPath(ShapePath sp) {
        PathBuilder pb = new PathBuilder();
        pb.setFillMode("WindingFill".equals(sp.fillRule.peek())
            ? PathFillMode.WINDING : PathFillMode.EVEN_ODD);
        pb.moveTo(sp.startX.peekFloat(), sp.startY.peekFloat());
        for (PathElement e : sp.pathElements) {
            appendElement(pb, e);
        }
        return pb.build();
    }

    private void appendElement(PathBuilder pb, PathElement e) {
        if (e instanceof PathLine) {
            PathLine l = (PathLine) e;
            pb.lineTo(l.x.peekFloat(), l.y.peekFloat());
        } else if (e instanceof PathMove) {
            PathMove m = (PathMove) e;
            pb.moveTo(m.x.peekFloat(), m.y.peekFloat());
        } else if (e instanceof PathQuad) {
            PathQuad q = (PathQuad) e;
            pb.quadTo(q.controlX.peekFloat(), q.controlY.peekFloat(),
                      q.x.peekFloat(), q.y.peekFloat());
        } else if (e instanceof PathCubic) {
            PathCubic c = (PathCubic) e;
            pb.cubicTo(c.control1X.peekFloat(), c.control1Y.peekFloat(),
                       c.control2X.peekFloat(), c.control2Y.peekFloat(),
                       c.x.peekFloat(), c.y.peekFloat());
        } else if (e instanceof PathArc) {
            PathArc a = (PathArc) e;
            PathEllipseArc size = Boolean.TRUE.equals(a.useLargeArc.peek())
                ? PathEllipseArc.LARGER : PathEllipseArc.SMALLER;
            PathDirection dir = "Counterclockwise".equals(a.direction.peek())
                ? PathDirection.COUNTER_CLOCKWISE : PathDirection.CLOCKWISE;
            pb.ellipticalArcTo(a.radiusX.peekFloat(), a.radiusY.peekFloat(),
                               a.xAxisRotation.peekFloat(), size, dir,
                               a.x.peekFloat(), a.y.peekFloat());
        }
    }

    private void fillPath(Path path, ShapePath sp, float alpha, Paint p) {
        int argb = shapeArgb(sp.fillColor.peek(), alpha);
        if (argb == 0) return;
        p.setColor(argb);
        p.setMode(PaintMode.FILL);
        canvas.drawPath(path, p);
    }

    private void strokePath(Path path, ShapePath sp, float alpha, Paint p) {
        float sw = sp.strokeWidth.peekFloat();
        if (sw <= 0f) return;
        int argb = shapeArgb(sp.strokeColor.peek(), alpha);
        if (argb == 0) return;
        p.setColor(argb);
        p.setMode(PaintMode.STROKE);
        p.setStrokeWidth(sw);
        p.setStrokeCap(mapCap(sp.capStyle.peek()));
        p.setStrokeJoin(mapJoin(sp.joinStyle.peek()));
        canvas.drawPath(path, p);
    }

    private static int shapeArgb(String colorStr, float alpha) {
        if (colorStr == null || "transparent".equals(colorStr)) return 0;
        int rgb = Renderer.parseColor(colorStr);
        int a = (int) (((rgb >>> 24) & 0xFF) * alpha);
        if (a <= 0) return 0;
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static PaintStrokeCap mapCap(String cap) {
        if ("RoundCap".equals(cap)) return PaintStrokeCap.ROUND;
        if ("FlatCap".equals(cap)) return PaintStrokeCap.BUTT;
        return PaintStrokeCap.SQUARE;
    }

    private static PaintStrokeJoin mapJoin(String join) {
        if ("RoundJoin".equals(join)) return PaintStrokeJoin.ROUND;
        if ("MiterJoin".equals(join)) return PaintStrokeJoin.MITER;
        return PaintStrokeJoin.BEVEL;
    }

    // MultiEffect: paint the source subtree, optionally masked. The source is
    // normally an invisible sibling, so we draw it through the renderer here.
    public void drawMultiEffect(MultiEffect me, float w, float h, float alpha) {
        Object src = me.source.peek();
        if (!(src instanceof Item)) return;
        Item source = (Item) src;

        // Drop shadow: render the source through a drop-shadow image filter.
        if (Boolean.TRUE.equals(me.shadowEnabled.peek())) {
            float op = (float) (alpha * me.shadowOpacity.peekDouble());
            int sc = Renderer.applyAlpha(Renderer.parseColor(me.shadowColor.peek()), op);
            float dy = me.shadowVerticalOffset.peekFloat();
            float dx = me.shadowHorizontalOffset.peekFloat();
            float sg = Renderer.sigma(me.shadowBlur.peekFloat() * 32f); // Qt blur is 0..1
            Paint sp = new Paint();
            sp.setImageFilter(ImageFilter.makeDropShadow(dx, dy, sg, sg, sc));
            float mg = sg * 3f + Math.abs(dx) + Math.abs(dy) + 8f;
            int save = canvas.saveLayer(Rect.makeXYWH(-mg, -mg, w + 2 * mg, h + 2 * mg), sp);
            try { drawSourceAtEffectOrigin(source, alpha); }
            finally { canvas.restoreToCount(save); sp.close(); }
            return;
        }

        Object maskSrc = me.maskSource.peek();
        if (Boolean.TRUE.equals(me.maskEnabled.peek()) && maskSrc instanceof Item) {
            drawMaskedSource(source, (Item) maskSrc, Boolean.TRUE.equals(me.maskInverted.peek()), w, h, alpha);
            return;
        }

        int save = canvas.save();
        try { drawSourceAtEffectOrigin(source, alpha); }
        finally { canvas.restoreToCount(save); }
    }

    // True per-pixel alpha masking: render the source into an offscreen layer, then
    // composite the mask subtree's own rendered pixels onto it with DST_IN (or DST_OUT
    // when inverted). DST_IN multiplies the destination's alpha by the source's alpha at
    // every pixel -- so a mask painted with a gradient fades the source exactly where the
    // gradient fades, instead of only approximating a solid mask's outline as a clip.
    private void drawMaskedSource(Item source, Item maskSource, boolean inverted, float w, float h, float alpha) {
        Rect bounds = Rect.makeXYWH(0, 0, Math.max(0f, w), Math.max(0f, h));
        int save = canvas.saveLayer(bounds, null);
        try {
            drawSourceAtEffectOrigin(source, alpha);
            try (Paint maskPaint = new Paint().setBlendMode(inverted ? BlendMode.DST_OUT : BlendMode.DST_IN)) {
                int maskSave = canvas.saveLayer(bounds, maskPaint);
                try { drawSourceAtEffectOrigin(maskSource, 1f); }
                finally { canvas.restoreToCount(maskSave); }
            }
        } finally { canvas.restoreToCount(save); }
    }

    // The effect renders its source at the effect's own origin (the clip/mask is in
    // effect-local space), but drawForced re-applies the source's own x/y — which is
    // relative to the source's parent, not the effect. Neutralise that offset so the
    // source content lands at (0,0); a no-op when source and effect are co-located
    // siblings (the Ripple case), correct when they are not.
    private void drawSourceAtEffectOrigin(Item source, float alpha) {
        int s = canvas.save();
        canvas.translate(-source.x.peekFloat(), -source.y.peekFloat());
        try { renderer.drawForced(canvas, source, alpha); }
        finally { canvas.restoreToCount(s); }
    }

    private static final long CARET_BLINK_MS = 500;

    private static boolean caretBlinkOn() {
        return (System.currentTimeMillis() / CARET_BLINK_MS) % 2 == 0;
    }

    public void drawTextField(TextField tf, float w, float h, float alpha) {
        float radius = Math.max(0f, tf.radius.peekFloat());
        Paint p = renderer.paint();
        p.setShader(null);
        p.setMode(PaintMode.FILL);
        p.setColor(Renderer.applyAlpha(Renderer.parseColor(tf.backgroundColor.peek()), alpha));
        if (radius > 0f) {
            canvas.drawRRect(RRect.makeXYWH(0, 0, w, h, radius), p);
        } else {
            canvas.drawRect(Rect.makeXYWH(0, 0, w, h), p);
        }
        float bw = Math.max(0f, tf.borderWidth.peekFloat());
        if (bw > 0f) {
            boolean focused = Boolean.TRUE.equals(tf.activeFocus.peek());
            int bc = Renderer.parseColor(focused ? tf.focusBorderColor.peek() : tf.borderColor.peek());
            p.setMode(PaintMode.STROKE);
            p.setStrokeWidth(bw);
            p.setColor(Renderer.applyAlpha(bc, alpha));
            float inset = bw / 2f;
            float iw = Math.max(0f, w - bw);
            float ih = Math.max(0f, h - bw);
            if (radius > 0f) {
                canvas.drawRRect(RRect.makeXYWH(inset, inset, iw, ih, Math.max(0f, radius - inset)), p);
            } else {
                canvas.drawRect(Rect.makeXYWH(inset, inset, iw, ih), p);
            }
            p.setMode(PaintMode.FILL);
        }
        float size = tf.fontSize.peekFloat();
        float pad = tf.padding.peekFloat();
        int tfSave = canvas.save();
        try {
            canvas.translate(pad, 0);
            String s = tf.text.peek();
            if (s == null || s.isEmpty()) {
                String ph = tf.placeholderText.peek();
                if (ph != null && !ph.isEmpty()) {
                    { Font font = renderer.fonts().fontFor(size, ph);
                        p.setColor(Renderer.applyAlpha(Renderer.parseColor(tf.placeholderTextColor.peek()), alpha));
                        drawTextLine(textLine(font, ph), 0, TextLayout.centeredBaseline(font, h), p);
                    }
                }
            }
            drawTextInput(tf, w, h, alpha);
        } finally {
            canvas.restoreToCount(tfSave);
        }
    }

    // The string shown for a TextInput honouring echoMode. Reads the mode through
    // TextInput.echo() so that what is displayed and what may be copied are decided from
    // one interpretation of the property: a field whose glyphs are masked is never
    // copyable, and an unrecognised mode masks rather than falling through to plaintext.
    private static String echoDisplay(TextInput ti) {
        String raw = ti.text.peek();
        if (raw == null) raw = "";
        int mode = ti.echo();
        if (mode == TextInput.ECHO_NORMAL) return raw;
        if (mode == TextInput.ECHO_NO_ECHO) return "";
        // Qt reveals PasswordEchoOnEdit only once the user has typed since gaining focus.
        if (mode == TextInput.ECHO_PASSWORD_ON_EDIT && ti.isEchoEditing()) return raw;
        return mask(raw, ti.passwordCharacter.peek());
    }

    private static String mask(String raw, String passwordCharacter) {
        char c = passwordCharacter == null || passwordCharacter.isEmpty()
            ? '•' : passwordCharacter.charAt(0);
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) b.append(c);
        return b.toString();
    }

    // echoDisplay is @NotNull, but keep the null-guard defensively (QML can feed odd values).
    @SuppressWarnings({"unused", "ConstantValue"})
    public void drawTextInput(TextInput ti, float w, float h, float alpha) {
        String s = echoDisplay(ti);
        if (s == null) s = "";
        float size = ti.fontSize.peekFloat();
        { Font font = renderer.fonts().fontFor(size, s);
            float baseline = TextLayout.centeredBaseline(font, h);
            float glyphTop = baseline + TextLayout.glyphTopOffset(font);
            float glyphHeight = TextLayout.glyphExtent(font);
            paintSelectionRect(ti, s, font, glyphTop, glyphHeight, alpha);
            if (!s.isEmpty()) {
                Paint p = renderer.paint();
                p.setColor(Renderer.applyAlpha(Renderer.parseColor(ti.color.peek()), alpha));
                drawTextLine(textLine(font, s), 0, baseline, p);
            }
            if (Boolean.TRUE.equals(ti.activeFocus.peek()) && caretBlinkOn()) {
                int pos = Math.max(0, Math.min(ti.cursorPosition.peekInt(), s.length()));
                float cx = textWidth(font, s.substring(0, pos));
                Paint p = renderer.paint();
                p.setMode(PaintMode.FILL);
                p.setColor(Renderer.applyAlpha(Renderer.parseColor(ti.color.peek()), alpha));
                float cw = Math.max(1f, size / 16f);
                canvas.drawRect(Rect.makeXYWH(cx, glyphTop, cw, glyphHeight), p);
            }
        }
    }

    private void paintSelectionRect(TextInput ti, String s, Font font,
                                    float glyphTop, float glyphHeight, float alpha) {
        int len = s.length();
        int selS = Math.max(0, Math.min(ti.selectionStart.peekInt(), len));
        int selE = Math.max(selS, Math.min(ti.selectionEnd.peekInt(), len));
        if (selE <= selS) return;
        float x0 = textWidth(font, s.substring(0, selS));
        float x1 = textWidth(font, s.substring(0, selE));
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setColor(Renderer.applyAlpha(Renderer.parseColor(ti.selectionColor.peek()), alpha));
        canvas.drawRect(Rect.makeXYWH(x0, glyphTop, x1 - x0, glyphHeight), p);
    }

    public void drawTextEdit(TextEdit te, float w, float h, float alpha) {
        String s = te.text.peek();
        if (s == null) s = "";
        float size = te.fontSize.peekFloat();
        { Font font = renderer.fonts().fontFor(size, s);
            TextWrap.Result wrapped = renderer.textLayout().wrapFor(te, s, w, size, font);
            te.lineCount.set(wrapped.lines.size());
            float lineH = TextLayout.lineHeight(font);
            float total = lineH * wrapped.lines.size();
            float yOffset = renderer.textLayout().topOffset(te.verticalAlignment.peek(), h, total);
            paintSelectionMultiline(te, wrapped, font, yOffset, lineH, alpha);
            Paint p = renderer.paint();
            p.setColor(Renderer.applyAlpha(Renderer.parseColor(te.color.peek()), alpha));
            for (int i = 0; i < wrapped.lines.size(); i++) {
                String line = wrapped.lines.get(i);
                if (!line.isEmpty()) {
                    float baseline = yOffset + i * lineH + TextLayout.baselineInLine(font);
                    drawTextLine(textLine(font, line), 0, baseline, p);
                }
            }
            if (Boolean.TRUE.equals(te.activeFocus.peek()) && caretBlinkOn()) {
                drawCaretMultiline(te, wrapped, font, yOffset, lineH, size, alpha);
            }
        }
    }

    private void paintSelectionMultiline(TextEdit te, TextWrap.Result wrapped,
                                         Font font, float yOffset, float lineH, float alpha) {
        int len = te.cachedText == null ? 0 : te.cachedText.length();
        int selS = Math.max(0, Math.min(te.selectionStart.peekInt(), len));
        int selE = Math.max(selS, Math.min(te.selectionEnd.peekInt(), len));
        if (selE <= selS) return;
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setColor(Renderer.applyAlpha(Renderer.parseColor(te.selectionColor.peek()), alpha));
        float glyphTop = TextLayout.baselineInLine(font) + TextLayout.glyphTopOffset(font);
        float glyphHeight = TextLayout.glyphExtent(font);
        for (int i = 0; i < wrapped.lines.size(); i++) {
            int ls = wrapped.starts[i];
            String line = wrapped.lines.get(i);
            int le = ls + line.length();
            if (selE <= ls || selS >= le) continue;
            int a = Math.max(selS, ls) - ls;
            int b = Math.min(selE, le) - ls;
            float x0 = a == 0 ? 0 : textWidth(font, line.substring(0, a));
            float x1 = textWidth(font, line.substring(0, b));
            float y = yOffset + i * lineH + glyphTop;
            canvas.drawRect(Rect.makeXYWH(x0, y, x1 - x0, glyphHeight), p);
        }
    }

    private void drawCaretMultiline(TextEdit te, TextWrap.Result wrapped,
                                    Font font, float yOffset, float lineH, float size, float alpha) {
        int len = te.cachedText == null ? 0 : te.cachedText.length();
        int pos = Math.max(0, Math.min(te.cursorPosition.peekInt(), len));
        int lineIdx = TextWrap.lineForCaret(wrapped, pos);
        String line = wrapped.lines.get(lineIdx);
        int col = Math.max(0, Math.min(pos - wrapped.starts[lineIdx], line.length()));
        float cx = col == 0 ? 0 : textWidth(font, line.substring(0, col));
        float glyphTop = TextLayout.baselineInLine(font) + TextLayout.glyphTopOffset(font);
        float glyphHeight = TextLayout.glyphExtent(font);
        Paint p = renderer.paint();
        p.setMode(PaintMode.FILL);
        p.setColor(Renderer.applyAlpha(Renderer.parseColor(te.color.peek()), alpha));
        float cw = Math.max(1f, size / 16f);
        canvas.drawRect(Rect.makeXYWH(cx, yOffset + lineIdx * lineH + glyphTop, cw, glyphHeight), p);
    }
}
