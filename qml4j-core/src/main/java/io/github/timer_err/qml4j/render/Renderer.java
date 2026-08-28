package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.DelegateFactory;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.ObservableList;
import io.github.timer_err.qml4j.render.items.view.Component;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.transform.Rotation;
import io.github.timer_err.qml4j.render.items.transform.Scale;
import io.github.timer_err.qml4j.render.items.transform.Transform;
import io.github.timer_err.qml4j.render.items.transform.Translate;
import io.github.timer_err.qml4j.render.items.view.Loader;
import io.github.timer_err.qml4j.render.items.effect.ColorOverlay;
import io.github.timer_err.qml4j.render.items.effect.DropShadow;
import io.github.timer_err.qml4j.render.items.effect.MultiEffect;
import io.github.timer_err.qml4j.render.items.effect.Glow;
import io.github.timer_err.qml4j.render.items.window.ApplicationWindow;
import io.github.timer_err.qml4j.render.items.input.TextField;
import io.github.timer_err.qml4j.render.items.input.TextEdit;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.core.TextWrap;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.BlendMode;
import io.github.humbleui.skija.ColorFilter;
import io.github.humbleui.skija.ImageFilter;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathOp;
import io.github.humbleui.skija.Picture;
import io.github.humbleui.skija.PictureRecorder;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Renderer {

    private Paint paint;
    private ResourceLoader resources;
    private ComponentFactory factory;
    private final FontResolver fonts = new FontResolver();
    private final IconResolver icons = new IconResolver(fonts);
    private final TextLayout text = new TextLayout(fonts, icons);
    private final Painter painter = new Painter(this);

    public void setResourceLoader(ResourceLoader loader) {
        this.resources = loader;
    }

    /** Override the UI font (regular + medium) from app-provided bytes; either may be
     *  null. The regular face also drives CJK unless {@link #setCjkTypeface} is set. */
    public void setUiTypefaces(byte[] regular, byte[] medium) {
        fonts.setUiTypefaces(regular, medium);
    }

    /** Provide a dedicated CJK face (optional). */
    public void setCjkTypeface(byte[] bytes) {
        fonts.setCjkTypeface(bytes);
    }

    /** Provide the icon face (e.g. Material Symbols). */
    public void setIconTypeface(byte[] bytes) {
        fonts.setIconTypeface(bytes);
    }

    public void setComponentFactory(ComponentFactory factory) {
        this.factory = factory;
    }

    ResourceLoader resources() {
        return resources;
    }

    FontResolver fonts() {
        return fonts;
    }

    IconResolver icons() {
        return icons;
    }

    TextLayout textLayout() {
        return text;
    }

    Paint paint() {
        if (paint == null) paint = new Paint();
        return paint;
    }

    // Max layout-settle iterations per frame. Converges multi-level implicit-size
    // chains; capped so a pathological oscillating layout can't spin forever.
    private static final int MAX_LAYOUT_PASSES = 8;

    // The GPU context of the surface being rendered, threaded to Painter so a Canvas item's
    // offscreen backing is made on the same context (a raster offscreen won't blit on GPU).
    private io.github.humbleui.skija.DirectContext gpuContext;

    public void setGpuContext(io.github.humbleui.skija.DirectContext ctx) {
        this.gpuContext = ctx;
    }

    io.github.humbleui.skija.DirectContext gpuContext() {
        return gpuContext;
    }

    @SuppressWarnings("unused")
    public void render(Canvas canvas, Item root) {
        render(canvas, root, false);
    }

    // skipLayout: the scene is unchanged since the last frame, so skip the layout pass and
    // the per-node measure/layout in draw, repainting with cached geometry. The host's game
    // loop calls renderFrame every frame; this keeps an idle UI to paint-only cost.
    public void render(Canvas canvas, Item root, boolean skipLayout) {
        if (root == null) return;
        painter.bind(canvas);
        long tLayout0 = System.nanoTime();
        if (!skipLayout) settleLayout(root);
        else { measuredThisFrame = 0; settlePasses = 0; }
        lastMeasureNanos = System.nanoTime() - tLayout0;
        // Establish the top-level viewport clip for the main scene. renderSubtree()
        // narrows clipL/T/R/B to its subtree's size and does NOT restore them (draw()
        // only saves/restores around each node), so once a host has composited a
        // subtree pass the next render() would cull the whole scene against that stale
        // clip — e.g. a host that draws a tagged overlay subtree at the window size,
        // then grows the window, loses every item past the old bounds. Reset to the
        // unbounded default here so the main scene is never culled against leaked state.
        clipL = -Float.MAX_VALUE; clipT = -Float.MAX_VALUE;
        clipR = Float.MAX_VALUE;  clipB = Float.MAX_VALUE;
        pictureRecordsThisFrame = 0;
        // Designate the boundaries (root's direct children) before drawing so a boundary flag is
        // in place for marks that arrive between this frame and the next. Cheap (few children).
        if (pictureCacheEnabled) updateBoundaries(root);
        long tDraw0 = System.nanoTime();
        draw(canvas, root, 1f);
        lastDrawNanos = System.nanoTime() - tDraw0;
    }

    // Draw a single already-laid-out subtree (layout must have run via a prior render()
    // this frame, or be stable). The viewport clip is reset to the given logical size so
    // the node's children aren't culled against a stale clip. Used to composite one
    // tagged subtree on top of host-drawn content in a separate pass.
    @SuppressWarnings("unused") // called by the host shell, not from within qml4j
    public void renderSubtree(Canvas canvas, Item node, float w, float h) {
        if (node == null) return;
        painter.bind(canvas);
        clipL = 0f;
        clipT = 0f;
        clipR = w;
        clipB = h;
        drawForced(canvas, node, 1f);
    }

    // Opt-in dev FPS overlay (-Dqml4j.fps=true), drawn top-right over the scene.
    private static final boolean FPS_OVERLAY = Boolean.getBoolean("qml4j.fps");

    public boolean fpsOverlayEnabled() {
        return FPS_OVERLAY;
    }

    public void drawFpsOverlay(Canvas canvas, float w, double fps) {
        String s = Math.round(fps) + " FPS  m" + measuredThisFrame + " p" + settlePasses;
        // fontFor returns a cached Font shared across frames -- must NOT be closed.
        Font font = fonts.fontFor(14f, s, true);
        float tw = font.measureTextWidth(s);
        float pad = 6f, bw = tw + 2 * pad, bh = 22f, x = w - bw - 8f, y = 8f;
        Paint p = paint();
        p.setShader(null);
        p.setMode(PaintMode.FILL);
        p.setColor(0xCC000000);
        canvas.drawRRect(RRect.makeXYWH(x, y, bw, bh, 6f), p);
        p.setColor(0xFF00E676);
        canvas.drawString(s, x + pad, y + 16f, font, p);
    }

    // Diagnostic/test hook: run the whole-tree layout pre-pass without painting.
    public void layoutOnly(Item root) {
        if (root != null) settleLayout(root);
    }

    // Bumped once per settleLayout call. cachedLayout skips re-measuring a static subtree only
    // across DIFFERENT settle passes (i.e. a later frame); within ONE settleLayout the multi-pass
    // loop must keep recursing so anchor/size chains that need 2+ passes (a card's Column whose
    // width comes from its parent) still settle.
    private long settleId;

    // Instrumentation: nodes measured and settle passes run in the LAST settleLayout, and the
    // wall time of the layout vs draw phases of the last render (for the FPS log).
    private int measuredThisFrame;
    private int settlePasses;
    private long lastMeasureNanos;
    private long lastDrawNanos;

    public long lastMeasureNanos() {
        return lastMeasureNanos;
    }

    public long lastDrawNanos() {
        return lastDrawNanos;
    }

    // --- Draw-phase content cache (opt-in via QmlView / -Dqml4j.pictureCache) --------------
    // When on, each of the root's direct children is a cache boundary: its whole subtree is
    // recorded once into an SkPicture and replayed with a fresh translate/transform every frame,
    // re-recorded only when its content is marked dirty. A pure move/scale/opacity of a whole
    // panel then costs a drawPicture, and one animating panel doesn't force the others to re-record.
    private boolean pictureCacheEnabled;
    // The nodes currently designated as boundaries (root's direct children), tracked so a node
    // that stops being a boundary has its flag + picture cleared.
    private final List<Item> boundaries = new ArrayList<>();
    // Instrumentation: pictures recorded in the last render, and over the renderer's lifetime.
    private int pictureRecordsThisFrame;
    private long pictureRecordsTotal;

    public void setPictureCache(boolean on) {
        pictureCacheEnabled = on;
        Item.setContentCacheEnabled(on);
        if (!on) clearBoundaries();
    }

    @SuppressWarnings("unused") // public accessor for host/diagnostics
    public boolean pictureCacheEnabled() {
        return pictureCacheEnabled;
    }

    public int pictureRecordsThisFrame() {
        return pictureRecordsThisFrame;
    }

    public long pictureRecordsTotal() {
        return pictureRecordsTotal;
    }

    // Nodes measured in the last settleLayout (diagnostic).
    public int measuredNodeCount() {
        return measuredThisFrame;
    }

    public int settlePassCount() {
        return settlePasses;
    }

    // Whole-tree layout: measure the entire tree, flushing size-driven bindings until it stops
    // changing (or the pass cap is hit), so first-appearance layout is correct on the very frame
    // a node becomes visible. The static-subtree fast path (opt-in Item.cachedLayout, checked in
    // measure()) keeps this cheap for large lists whose rows don't reflow.
    private void settleLayout(Item root) {
        settleId++;
        measuredThisFrame = 0;
        settlePasses = 0;
        DirtyQueue dq = DirtyQueue.current();
        for (int i = 0; i < MAX_LAYOUT_PASSES; i++) {
            settlePasses++;
            measure(root);
            if (dq == null || dq.isEmpty()) break;
            dq.flush();
        }
    }

    // Layout pre-pass: populate implicitWidth/Height (text/control measurement),
    // run implicit-size following, container layout and anchors across the whole
    // tree so size-driven bindings can settle BEFORE painting.
    private void measure(Item node) {
        // Invisible subtrees are still laid out (Qt computes geometry regardless of
        // visibility) -- only painting is skipped. A SideSheet that parks its panel at
        // `x: parent.width` while hidden needs parent.width resolved, else it parks at 0
        // and the first open slides in from the left.
        if (node == null) return;
        measuredThisFrame++;
        // Resolve a Loader before measuring its children so the loaded item is in the
        // tree + measured, and the Loader can size to it in this same layout pass.
        if (node instanceof Loader) resolveLoader((Loader) node);
        node.measure(text);
        followImplicitSize(node);
        // Static-subtree fast path (opt-in via cachedLayout). A container whose
        // children's geometry is fixed once laid out -- e.g. a full song list, whose
        // rows sit at y = index*rowH and never reflow -- doesn't need its children
        // re-measured just because an unrelated property bumped the change version
        // (the 5 Hz play clock re-runs the whole layout pass). Skip recursing while
        // the container's own box and child count are unchanged; a resize (width/
        // height) or a model change (child count) differs and forces a full re-measure.
        if (Boolean.TRUE.equals(node.cachedLayout.peek())) {
            float cw = node.width.peekFloat();
            float ch = node.height.peekFloat();
            int cc = node.children.size();
            // Structural version distinguishes a rebuilt child set (Repeater re-creating
            // delegates) from a stable one even when the size is unchanged -- e.g.
            // reopening the same playlist swaps every row for a fresh instance that has
            // never been measured, yet the box and count match the cached values.
            long cv = node.children instanceof ObservableList
                ? ((ObservableList<?>) node.children).structuralVersion() : 0L;
            // Skip only on a later settle (settleId differs) with an unchanged box +
            // child count + child set. Same-settle re-passes always recurse so a card's
            // children (whose width derives from this container) converge over the loop.
            // Children's sizes are read as they stand now -- a late-settling child
            // binding (a row at `width: view.width`) has already updated its Property,
            // so a mismatch here forces the re-measure that re-anchors the stale
            // subtree. The stored value is refreshed at the end of measure(), after the
            // children have been (re-)measured to their final sizes.
            if (node.cachedLayoutValid && node.cachedLayoutSettleId != settleId
                    && node.cachedLayoutW == cw && node.cachedLayoutH == ch
                    && node.cachedLayoutCount == cc && node.cachedLayoutChildVersion == cv
                    && node.cachedLayoutChildDims == childDimsChecksum(node)) {
                runLayout(node);
                applyAnchors(node);
                return;
            }
            node.cachedLayoutValid = true;
            node.cachedLayoutW = cw;
            node.cachedLayoutH = ch;
            node.cachedLayoutCount = cc;
            node.cachedLayoutChildVersion = cv;
            node.cachedLayoutSettleId = settleId;
        }
        // Children first so a container can size itself from their measured sizes.
        // An invisible child's whole subtree (e.g. the off-screen pages of a
        // StackLayout) is never drawn; measuring it every frame is pure waste. Still
        // resolve the hidden child's OWN size + anchors so a parked-while-hidden item
        // (a SideSheet panel at x: parent.width) lands correctly -- just don't recurse
        // into its descendants. They get a full measure the frame it becomes visible.
        // Indexed loops (not for-each) across the whole tree — an Iterator per node
        // per settle pass, at the 5 Hz play-clock re-settle rate, was steady GC churn.
        List<Item> kids = node.children;
        for (int i = 0, sz = kids.size(); i < sz; i++) {
            Item child = kids.get(i);
            if (child.isVisible()) {
                measure(child);
            } else {
                measuredThisFrame++;
                if (child instanceof Loader) resolveLoader((Loader) child);
                child.measure(text);
                followImplicitSize(child);
                runLayout(child);
                applyAnchors(child);
            }
        }
        runLayout(node);
        float ownW = node.width.peekFloat();
        float ownH = node.height.peekFloat();
        applyAnchors(node);
        // This is where an anchored node's own box lands: anchors resolve against the parent,
        // which is only final once we return. So the layout() above distributed a box the node
        // did not have yet -- a RowLayout at `anchors.fill: parent` handed its fillWidth
        // children the stale (usually 0) width. Redistribute now that the box is real.
        boolean resized = node.width.peekFloat() != ownW || node.height.peekFloat() != ownH;
        if (resized) runLayout(node);
        // A child's anchors (centerIn/fill/...) resolve against the parent's geometry, but
        // children are measured+anchored BEFORE the parent's own size is finalised above -- so
        // a child centred/filled in a parent whose size comes from anchors was positioned
        // against the parent's stale size. Re-resolve the direct children's anchors now.
        for (int i = 0, sz = kids.size(); i < sz; i++) {
            Item child = kids.get(i);
            if (!child.isVisible()) continue;
            float cw = child.width.peekFloat();
            float ch = child.height.peekFloat();
            applyAnchors(child);
            // The child ended up in a different box than the one its own subtree was measured
            // against, so re-measure it. Carrying the correction down here, rather than leaving
            // it to the next settle pass, is what makes a chain of anchored layouts converge:
            // a pass only ever moved it one level, so anything deeper stayed wrong once the
            // pass cap was reached (and a static scene never got a second pass at all).
            if (resized || child.width.peekFloat() != cw || child.height.peekFloat() != ch) {
                measure(child);
            }
        }
        // Snapshot the now-final child sizes so the next settle's fast-path check can
        // tell a genuinely static subtree from one whose row widths just settled.
        if (Boolean.TRUE.equals(node.cachedLayout.peek())) {
            node.cachedLayoutChildDims = childDimsChecksum(node);
        }
        updateChildrenRect(node);
    }

    // Order-sensitive checksum of the direct children's sizes — cheap (no per-child
    // allocation) and only computed for cachedLayout containers, which are few.
    private static long childDimsChecksum(Item node) {
        List<Item> kids = node.children;
        long h = 1L;
        for (int i = 0, sz = kids.size(); i < sz; i++) {
            Item c = kids.get(i);
            h = h * 31L + Float.floatToRawIntBits(c.width.peekFloat());
            h = h * 31L + Float.floatToRawIntBits(c.height.peekFloat());
        }
        return h;
    }

    private static void updateChildrenRect(Item node) {
        List<Item> kids = node.children;
        if (kids.isEmpty()) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (int i = 0, sz = kids.size(); i < sz; i++) {
            Item c = kids.get(i);
            // A `visible:` binding can evaluate to undefined (null here); treat it as the
            // default (visible) rather than NPE on the unboxed boolean.
            if (!c.isVisible()) continue;
            float cx = c.x.peekFloat(), cy = c.y.peekFloat();
            float cw = c.width.peekFloat(), ch = c.height.peekFloat();
            minX = Math.min(minX, cx); minY = Math.min(minY, cy);
            maxX = Math.max(maxX, cx + cw); maxY = Math.max(maxY, cy + ch);
            any = true;
        }
        if (!any) return;
        node.childrenRect.x.set(minX);
        node.childrenRect.y.set(minY);
        node.childrenRect.width.set(maxX - minX);
        node.childrenRect.height.set(maxY - minY);
    }

    private static void runLayout(Item node) {
        node.layout();
    }

    // Visible clip rect in the coordinate space of the children currently being
    // drawn (refreshed once per parent, before its child loop). Culling tests each
    // child against these floats instead of allocating a Rect + a JNI quickReject
    // per child -- a long list quick-rejects hundreds of off-screen rows EVERY
    // frame while scrolling, and Rect-per-row churned enough garbage to stutter
    // playback with periodic GC pauses (Haedus rule: no uncontrolled allocation on
    // the per-frame render path).
    private float clipL = -Float.MAX_VALUE, clipT = -Float.MAX_VALUE;
    private float clipR = Float.MAX_VALUE, clipB = Float.MAX_VALUE;

    private void draw(Canvas canvas, Item node, float inheritedAlpha) {
        if (!node.isVisible()) return;
        if (culled(node)) return;
        if (pictureCacheEnabled && node.cacheBoundary) {
            drawCachedBoundary(canvas, node, inheritedAlpha);
            return;
        }
        drawForced(canvas, node, inheritedAlpha);
    }

    // Designate the root's direct children as cache boundaries. A node that dropped out of the
    // set (reparented away, or the root was replaced) has its flag + picture cleared so it draws
    // normally and doesn't leak native memory.
    private void updateBoundaries(Item root) {
        for (int i = 0, n = boundaries.size(); i < n; i++) {
            Item b = boundaries.get(i);
            if (b.parent.peek() != root) releaseBoundary(b);
        }
        boundaries.clear();
        List<Item> kids = root.children;
        for (int i = 0, n = kids.size(); i < n; i++) {
            Item c = kids.get(i);
            c.cacheBoundary = true;
            boundaries.add(c);
        }
    }

    private void clearBoundaries() {
        for (int i = 0, n = boundaries.size(); i < n; i++) releaseBoundary(boundaries.get(i));
        boundaries.clear();
    }

    private static void releaseBoundary(Item b) {
        b.cacheBoundary = false;
        b.contentDirty = true;
        b.cachedAlpha = Float.NaN;
        b.cachedScale = Float.NaN;
        b.dirtyStreak = 0;
        if (b.cachedPicture != null) {
            b.cachedPicture.close();
            b.cachedPicture = null;
        }
    }

    // A boundary whose content changed for this many consecutive frames is treated as a hot
    // spot and drawn directly (no record) until it settles: recording a picture to replay it
    // once is strictly more work than drawing it, so an animating panel must not pay for both.
    private static final int DIRECT_DRAW_STREAK = 3;

    // Draw a cache boundary: replay its recorded picture with the boundary's live translate,
    // transform and z-independent placement, re-recording first only when the content is dirty,
    // the picture is missing, or the effective alpha changed (opacity baked into the record).
    // Continuously-dirty boundaries fall back to a direct draw (see DIRECT_DRAW_STREAK).
    private void drawCachedBoundary(Canvas canvas, Item node, float inheritedAlpha) {
        float w = node.width.peekFloat();
        float h = node.height.peekFloat();
        float alpha = inheritedAlpha * node.opacity.peekFloat();
        if (alpha <= 0f) return;
        // Device scale from the target canvas' CTM: the subtree is recorded at this resolution so
        // raster backings (Canvas/layer/shadow) are crisp at high DPI, and a scale change forces a
        // re-record. Read here (before recording) so record + replay agree on the same sf.
        float sf = deviceScale(canvas);
        // A content change is a dirty mark or an effective-alpha change. A missing picture alone
        // (just settled out of direct-draw mode) is NOT a content change, so it doesn't keep the
        // hot-spot streak alive -- otherwise a settled panel could never return to caching. A
        // scale change is likewise not a "content" change (doesn't feed the hot-spot streak).
        boolean contentChanged = node.contentDirty || node.cachedAlpha != alpha;
        node.dirtyStreak = contentChanged ? node.dirtyStreak + 1 : 0;
        if (node.dirtyStreak >= DIRECT_DRAW_STREAK) {
            // Hot spot: draw straight to the canvas and drop the stale picture. Clear the dirty
            // flag and remember the drawn alpha so the frame it stops changing reads clean and
            // the streak resets, resuming the cache.
            if (node.cachedPicture != null) {
                node.cachedPicture.close();
                node.cachedPicture = null;
            }
            long asyncGeneration = node.asyncContentGeneration();
            node.cachedAlpha = alpha;
            node.cachedScale = Float.NaN;
            drawForced(canvas, node, inheritedAlpha);
            node.clearContentDirtyIfAsyncGeneration(asyncGeneration);
            return;
        }
        if (node.cachedPicture == null || contentChanged || node.cachedScale != sf) {
            recordBoundary(node, w, h, alpha, sf);
        }
        float x = node.x.peekFloat();
        float y = node.y.peekFloat();
        float rot = node.rotation.peekFloat();
        float sc = node.scale.peekFloat();
        int saved = canvas.save();
        try {
            canvas.translate(x, y);
            applyTransform(canvas, node, w, h, rot, sc);
            // Cancel the device scale baked into the picture; the target canvas' own device
            // transform re-applies it, so the net on-screen size/position is unchanged but the
            // recorded raster content is at device resolution.
            float inv = node.cachedScale > 0f ? 1f / node.cachedScale : 1f;
            if (inv != 1f) canvas.scale(inv, inv);
            canvas.drawPicture(node.cachedPicture);
        } finally {
            canvas.restoreToCount(saved);
        }
    }

    // The uniform device scale of a canvas' current transform (its local-to-device matrix'
    // x-scale). Used to record boundary pictures at on-screen resolution. Defensive: a
    // non-positive/degenerate value (or an unexpected build) falls back to 1.
    private static float deviceScale(Canvas canvas) {
        try {
            float sx = canvas.getLocalToDeviceAsMatrix33()._mat[0];
            // sx > 0f already excludes NaN (NaN > 0 is false); only guard against +Infinity.
            return (sx > 0f && !Float.isInfinite(sx)) ? sx : 1f;
        } catch (Throwable t) {
            return 1f;
        }
    }

    // Record a boundary's whole subtree into an SkPicture in the node's LOCAL space (no
    // translate/transform baked -- those are re-applied at replay). The effective alpha IS baked,
    // so an opacity change re-records. Culling is disabled during the record (unbounded clip) so
    // the picture holds the full subtree regardless of the current viewport.
    private void recordBoundary(Item node, float w, float h, float alpha, float sf) {
        long asyncGeneration = node.asyncContentGeneration();
        PictureRecorder recorder = new PictureRecorder();
        // A generous cull rect: the picture is replayed at arbitrary offsets, so don't clip its
        // contents here (Skia treats drawing outside the cull rect as undefined).
        Canvas rc = recorder.beginRecording(Rect.makeLTRB(-1e5f, -1e5f, 1e5f, 1e5f));
        Canvas prev = painter.canvas();
        float sl = clipL, st = clipT, sr = clipR, sb = clipB;
        clipL = -Float.MAX_VALUE; clipT = -Float.MAX_VALUE;
        clipR = Float.MAX_VALUE;  clipB = Float.MAX_VALUE;
        painter.bind(rc);
        try {
            // Pre-multiply the device scale so raster backings (a Canvas item's FBO, a saveLayer /
            // layer.effect offscreen, a blur) rasterize at on-screen resolution instead of logical
            // size. Vector commands are resolution-independent, so this is lossless for them.
            if (sf != 1f) rc.scale(sf, sf);
            drawBody(rc, node, w, h, alpha);
        } finally {
            painter.bind(prev);
            clipL = sl; clipT = st; clipR = sr; clipB = sb;
        }
        Picture pic = recorder.finishRecordingAsPicture();
        recorder.close();
        if (node.cachedPicture != null) node.cachedPicture.close();
        node.cachedPicture = pic;
        node.cachedAlpha = alpha;
        node.cachedScale = sf;
        node.clearContentDirtyIfAsyncGeneration(asyncGeneration);
        node.recordCount++;
        pictureRecordsThisFrame++;
        pictureRecordsTotal++;
    }

    // Viewport culling: skip a subtree whose bounds (its own box unioned with its
    // children's overflow) fall entirely outside the visible clip (clipL/T/R/B,
    // set by the parent in drawForced). The renderer otherwise records draw
    // commands for everything off-screen (a long list, a page parked off a
    // StackLayout). Skip the check for transformed or layer-effected nodes, whose
    // drawn extent isn't this axis-aligned box.
    private boolean culled(Item node) {
        if (node.rotation.peekFloat() != 0f || node.scale.peekFloat() != 1f) return false;
        if (!node.transform.isEmpty()) return false;
        // layerEffectPaint allocates a Paint; close it here (we only need its presence) so a
        // layer-effected node's cull test doesn't leak a native Paint every frame.
        Paint lep = layerEffectPaint(node);
        if (lep != null) { lep.close(); return false; }
        float x = node.x.peekFloat(), y = node.y.peekFloat();
        float w = node.width.peekFloat(), h = node.height.peekFloat();
        float crx = node.childrenRect.x.peekFloat(), cry = node.childrenRect.y.peekFloat();
        float crw = node.childrenRect.width.peekFloat(), crh = node.childrenRect.height.peekFloat();
        float minX = x + Math.min(0f, crx);
        float minY = y + Math.min(0f, cry);
        float maxX = x + Math.max(w, crx + crw);
        float maxY = y + Math.max(h, cry + crh);
        if (maxX <= minX || maxY <= minY) return false;
        return maxX <= clipL || minX >= clipR || maxY <= clipT || minY >= clipB;
    }

    // Draw a node ignoring its own `visible` flag (used to render a MultiEffect
    // source or mask, both normally invisible siblings rendered only via the effect).
    void drawForced(Canvas canvas, Item node, float inheritedAlpha) {
        // (Layout is done once in settleLayout; no per-node re-measure in draw.)
        float x = node.x.peekFloat();
        float y = node.y.peekFloat();
        float w = node.width.peekFloat();
        float h = node.height.peekFloat();
        float alpha = inheritedAlpha * node.opacity.peekFloat();
        if (alpha <= 0f) return;
        float rot = node.rotation.peekFloat();
        float sc = node.scale.peekFloat();
        int savedCount = canvas.save();
        try {
            canvas.translate(x, y);
            applyTransform(canvas, node, w, h, rot, sc);
            drawBody(canvas, node, w, h, alpha);
        } finally {
            canvas.restoreToCount(savedCount);
        }
    }

    // Draw a node's body -- its own paint plus its children -- assuming the canvas is already
    // translated to the node's local origin and its scale/rotation transform applied. Split out
    // of drawForced so a cache boundary can record exactly this (in local space) into a picture
    // and replay it with a fresh outer translate/transform. Self-contained: it saves/restores the
    // canvas and closes any layer paint it creates.
    private void drawBody(Canvas canvas, Item node, float w, float h, float alpha) {
        float x = node.x.peekFloat();
        float y = node.y.peekFloat();
        float rot = node.rotation.peekFloat();
        float sc = node.scale.peekFloat();
        boolean clip = Boolean.TRUE.equals(node.clip.peek());
        int savedCount = canvas.save();
        Paint layerPaint = layerEffectPaint(node);
        try {
            if (layerPaint != null) {
                float m = layerEffectMargin(node);
                // A reparent/resize frame can hand a node a transient negative size; Skija's
                // Rect throws on negative extents, so clamp at the native boundary (Qt no-ops).
                canvas.saveLayer(Rect.makeXYWH(-m, -m, Math.max(0f, w + 2 * m), Math.max(0f, h + 2 * m)), layerPaint);
            }
            // A layer.effect mask rounds the item by rendering its content into an offscreen
            // and erasing the corners with an antialiased path -- a drawn AA shape stays smooth
            // on the GPU backend, where an antialiased rounded clip can fall back to hard edges.
            float[] maskR = maskClipRadii(node);
            if (maskR != null) {
                canvas.saveLayer(Rect.makeXYWH(0, 0, Math.max(0f, w), Math.max(0f, h)), null);
            } else if (clip) {
                canvas.clipRect(Rect.makeXYWH(0, 0, Math.max(0f, w), Math.max(0f, h)));
            }
            // Track the visible clip analytically as the canvas ops are applied (this
            // Skija build has no getLocalClipBounds): translate(x,y) shifts the local
            // origin, clip intersects with the node box, a transformed node disables
            // culling for its subtree. Children are then culled against these floats --
            // zero allocation, vs a Rect + JNI quickReject per child. Save the incoming
            // bounds (parent's child-space) and restore at the end, since draw() recurses.
            float sl = clipL, st = clipT, sr = clipR, sb = clipB;
            float nl, nt, nr, nb;
            if (rot != 0f || sc != 1f || !node.transform.isEmpty()) {
                nl = nt = -Float.MAX_VALUE; nr = nb = Float.MAX_VALUE;
            } else {
                nl = clipL - x; nt = clipT - y; nr = clipR - x; nb = clipB - y;
                if (clip || maskR != null) {
                    if (nl < 0f) nl = 0f;
                    if (nt < 0f) nt = 0f;
                    if (nr > w) nr = w;
                    if (nb > h) nb = h;
                }
            }
            // Children with z < 0 render BEHIND the node's own content (Qt); the rest on top.
            List<Item> ordered = zOrdered(node.children);
            clipL = nl; clipT = nt; clipR = nr; clipB = nb;
            // Indexed loops, not for-each: this runs for every visible node every
            // frame, and an Iterator allocation per node adds up to GC pressure.
            for (int i = 0, n = ordered.size(); i < n; i++) {
                Item child = ordered.get(i);
                if (child.z.peekFloat() < 0f) draw(canvas, child, alpha);
            }
            node.paint(painter, w, h, alpha);
            if (node instanceof Flickable) {
                Flickable f = (Flickable) node;
                canvas.clipRect(Rect.makeXYWH(0, 0, Math.max(0f, w), Math.max(0f, h)));
                float cx = f.contentX.peekFloat();
                float cy = f.contentY.peekFloat();
                // Intersect with the viewport box [0,0,w,h], then shift into content
                // space (translate(-cx,-cy) maps a content point p to local p-c).
                float vl = Math.max(0f, nl), vt = Math.max(0f, nt);
                float vr = Math.min(w, nr), vb = Math.min(h, nb);
                canvas.translate(-cx, -cy);
                clipL = vl + cx; clipT = vt + cy; clipR = vr + cx; clipB = vb + cy;
            }
            if (node instanceof ApplicationWindow) {
                ApplicationWindow win = (ApplicationWindow) node;
                float top = win.contentTop();
                float bottom = win.contentBottom(h);
                int contentSave = canvas.save();
                try {
                    canvas.clipRect(Rect.makeXYWH(0, top, Math.max(0f, w), Math.max(0f, bottom - top)));
                    canvas.translate(0, top);
                    clipL = Math.max(0f, nl);
                    clipR = Math.min(w, nr);
                    clipT = Math.max(top, nt) - top;
                    clipB = Math.min(bottom, nb) - top;
                    for (int i = 0, n = ordered.size(); i < n; i++) {
                        Item child = ordered.get(i);
                        if (child.z.peekFloat() >= 0f) draw(canvas, child, alpha);
                    }
                } finally {
                    canvas.restoreToCount(contentSave);
                }
                drawChrome(canvas, win, w, h, alpha);
            } else {
                for (int i = 0, n = ordered.size(); i < n; i++) {
                    Item child = ordered.get(i);
                    if (child.z.peekFloat() >= 0f) draw(canvas, child, alpha);
                }
            }
            clipL = sl; clipT = st; clipR = sr; clipB = sb;
            if (maskR != null) eraseOutsideRoundRect(canvas, w, h, maskR);
        } finally {
            canvas.restoreToCount(savedCount);
            if (layerPaint != null) layerPaint.close();
        }
    }

    // Clear the four corners outside the rounded rect with an antialiased path, so the
    // offscreen layer composites back with smooth rounded edges (the layer.effect mask).
    private void eraseOutsideRoundRect(Canvas canvas, float w, float h, float[] r) {
        // A reparent/resize frame can hand a transient non-positive size; Skija's Rect
        // throws on negative extents (the saveLayer above already clamps with Math.max).
        if (w <= 0f || h <= 0f) return;
        try (Path bounds = Path.makeRect(Rect.makeXYWH(0, 0, w, h));
             Path rounded = Path.makeRRect(RRect.makeComplexXYWH(0, 0, w, h, r));
             Path corners = Path.makeCombining(bounds, rounded, PathOp.DIFFERENCE);
             Paint clear = new Paint().setBlendMode(BlendMode.CLEAR).setAntiAlias(true)) {
            canvas.drawPath(corners, clear);
        }
    }

    private static void applyTransform(Canvas canvas, Item node, float w, float h, float rot, float sc) {
        if (rot != 0f || sc != 1f) {
            // Item.TransformOrigin: 0..8 row-major (TopLeft..BottomRight); col=origin%3,
            // row=origin/3 each map 0/1/2 -> start/center/end. Default 4 = Center.
            int origin = node.transformOrigin.peek().intValue();
            float px = pivot(origin % 3, w);
            float py = pivot(origin / 3, h);
            canvas.translate(px, py);
            if (rot != 0f) canvas.rotate(rot);
            if (sc != 1f) canvas.scale(sc, sc);
            canvas.translate(-px, -py);
        }
        if (!node.transform.isEmpty()) applyTransformList(canvas, node);
    }

    // Item.transform: a list of Translate/Rotation/Scale applied in order. A Rotation about
    // the x/y axis is a 3D flip; the 2D renderer approximates it by foreshortening along the
    // perpendicular axis (cos angle), which collapses the item edge-on like the real flip.
    private static void applyTransformList(Canvas canvas, Item node) {
        for (Transform t : node.transform) {
            if (t instanceof Translate) {
                Translate tr = (Translate) t;
                canvas.translate(tr.x.peekFloat(), tr.y.peekFloat());
            } else if (t instanceof Scale) {
                Scale s = (Scale) t;
                float ox = s.origin.x.peekFloat();
                float oy = s.origin.y.peekFloat();
                canvas.translate(ox, oy);
                canvas.scale(s.xScale.peekFloat(), s.yScale.peekFloat());
                canvas.translate(-ox, -oy);
            } else if (t instanceof Rotation) {
                applyRotation(canvas, (Rotation) t);
            }
        }
    }

    private static void applyRotation(Canvas canvas, Rotation r) {
        float angle = r.angle.peekFloat();
        if (angle == 0f) return;
        float ox = r.origin.x.peekFloat();
        float oy = r.origin.y.peekFloat();
        float ax = r.axis.x.peekFloat();
        float ay = r.axis.y.peekFloat();
        float az = r.axis.z.peekFloat();
        canvas.translate(ox, oy);
        if (ax == 0f && ay == 0f) {
            canvas.rotate(angle);
        } else {
            double c = Math.abs(Math.cos(Math.toRadians(angle)));
            if (ay != 0f) canvas.scale((float) c, 1f); // flip about the vertical axis
            else canvas.scale(1f, (float) c);          // flip about the horizontal axis
        }
        if (az != 0f && (ax != 0f || ay != 0f)) canvas.rotate(angle * az);
        canvas.translate(-ox, -oy);
    }

    private static float pivot(int axis, float extent) {
        return axis == 0 ? 0f : axis == 1 ? extent / 2f : extent;
    }

    static List<Item> zOrdered(List<Item> children) {
        int n = children.size();
        if (n < 2) return children;
        boolean anyZ = false;
        for (int i = 0; i < n; i++) {
            if (children.get(i).z.peekFloat() != 0f) { anyZ = true; break; }
        }
        if (!anyZ) return children;
        List<Item> copy = new ArrayList<>(children);
        copy.sort(Comparator.comparingDouble(c -> c.z.peekDouble()));
        return copy;
    }

    // Qt: an Item's width follows implicitWidth until width is explicitly set.
    // We approximate "explicitly set" with an owns-check (current value equals
    // the last implicit value we wrote, or 0 if never written) plus the binding
    // flag, mirroring Text auto-measure. Not unit-testable (no headless trigger
    // beyond this pass); verified on device.
    private static void followImplicitSize(Item node) {
        double iw = node.implicitWidth.peekDouble();
        if (iw > 0 && !node.width.isBound() && ownsImplicitWidth(node)) {
            node.width.set(iw);
            node.lastImplicitWidth = iw;
        }
        double ih = node.implicitHeight.peekDouble();
        if (ih > 0 && !node.height.isBound() && ownsImplicitHeight(node)) {
            node.height.set(ih);
            node.lastImplicitHeight = ih;
        }
    }

    private static boolean ownsImplicitWidth(Item c) {
        if (Double.isNaN(c.lastImplicitWidth)) return c.width.peekDouble() == 0.0;
        return c.width.peekDouble() == c.lastImplicitWidth;
    }

    private static boolean ownsImplicitHeight(Item c) {
        if (Double.isNaN(c.lastImplicitHeight)) return c.height.peekDouble() == 0.0;
        return c.height.peekDouble() == c.lastImplicitHeight;
    }

    static void applyAnchors(Item node) {
        Anchors a = node.anchors;
        float baseM = a.margins.peekFloat();
        float lm = marginOr(a.leftMargin.peek(), baseM);
        float rm = marginOr(a.rightMargin.peek(), baseM);
        float tm = marginOr(a.topMargin.peek(), baseM);
        float bm = marginOr(a.bottomMargin.peek(), baseM);

        // Anchors live in the parent's coordinate space: filling/centering on a SIBLING
        // (not the parent) coincides with that sibling's box, so offset by its x/y. The
        // parent sits at the origin, so its offset is 0. Without this a Ripple
        // `anchors.fill: indicator` ignored the centred pill's x and sat at the left.
        Item parent = node.parent.peek();
        Item fill = a.fill.peek();
        if (fill != null) {
            float fx = fill == parent ? 0f : fill.x.peekFloat();
            float fy = fill == parent ? 0f : fill.y.peekFloat();
            node.x.set(fx + lm);
            node.y.set(fy + tm);
            node.width.set(fill.width.peekFloat() - lm - rm);
            node.height.set(fill.height.peekFloat() - tm - bm);
            return;
        }
        Item ci = a.centerIn.peek();
        if (ci != null) {
            float w = node.width.peekFloat();
            float h = node.height.peekFloat();
            float cx = ci == parent ? 0f : ci.x.peekFloat();
            float cy = ci == parent ? 0f : ci.y.peekFloat();
            // centerIn honours the centre offsets (Qt): a clock-face number centred in the
            // dial is pushed out to its position via horizontal/verticalCenterOffset.
            node.x.set(cx + (ci.width.peekFloat() - w) / 2f + a.horizontalCenterOffset.peekFloat());
            node.y.set(cy + (ci.height.peekFloat() - h) / 2f + a.verticalCenterOffset.peekFloat());
            return;
        }
        applyHorizontalAnchors(node, lm, rm, a);
        applyVerticalAnchors(node, tm, bm, a);
    }

    private static void applyHorizontalAnchors(Item node, float lm, float rm, Anchors a) {
        AnchorLine left = a.left.peek();
        AnchorLine right = a.right.peek();
        AnchorLine hcenter = a.horizontalCenter.peek();
        if (left != null && right != null) {
            float l = resolveX(left, node) + lm;
            float r = resolveX(right, node) - rm;
            node.x.set(l);
            node.width.set(r - l);
        } else if (left != null) {
            node.x.set(resolveX(left, node) + lm);
        } else if (right != null) {
            float w = node.width.peekFloat();
            node.x.set(resolveX(right, node) - rm - w);
        } else if (hcenter != null) {
            float w = node.width.peekFloat();
            float off = a.horizontalCenterOffset.peekFloat();
            node.x.set(resolveX(hcenter, node) - w / 2f + off);
        }
    }

    private static void applyVerticalAnchors(Item node, float tm, float bm, Anchors a) {
        AnchorLine top = a.top.peek();
        AnchorLine bottom = a.bottom.peek();
        AnchorLine vcenter = a.verticalCenter.peek();
        if (top != null && bottom != null) {
            float t = resolveY(top, node) + tm;
            float b = resolveY(bottom, node) - bm;
            node.y.set(t);
            node.height.set(b - t);
        } else if (top != null) {
            node.y.set(resolveY(top, node) + tm);
        } else if (bottom != null) {
            float h = node.height.peekFloat();
            node.y.set(resolveY(bottom, node) - bm - h);
        } else if (vcenter != null) {
            float h = node.height.peekFloat();
            float off = a.verticalCenterOffset.peekFloat();
            node.y.set(resolveY(vcenter, node) - h / 2f + off);
        }
    }

    private static float resolveX(AnchorLine line, Item node) {
        Item src = line.source;
        boolean srcIsParent = src == node.parent.peek();
        float base = srcIsParent ? 0f : src.x.peekFloat();
        float w = src.width.peekFloat();
        switch (line.edge) {
            case LEFT: return base;
            case RIGHT: return base + w;
            case HORIZONTAL_CENTER: return base + w / 2f;
            default: throw new IllegalStateException("not a horizontal edge: " + line.edge);
        }
    }

    private static float resolveY(AnchorLine line, Item node) {
        Item src = line.source;
        boolean srcIsParent = src == node.parent.peek();
        float base = srcIsParent ? 0f : src.y.peekFloat();
        float h = src.height.peekFloat();
        switch (line.edge) {
            case TOP: return base;
            case BOTTOM: return base + h;
            case VERTICAL_CENTER: return base + h / 2f;
            default: throw new IllegalStateException("not a vertical edge: " + line.edge);
        }
    }

    private static float marginOr(Number margin, float fallback) {
        if (margin == null) return fallback;
        double d = margin.doubleValue();
        if (Double.isNaN(d)) return fallback;
        return (float) d;
    }

    private void drawChrome(Canvas canvas, ApplicationWindow win, float w, float h, float alpha) {
        win.layoutChrome(w, h);
        Item m = win.menuBar.peek();
        Item hdr = win.header.peek();
        Item ftr = win.footer.peek();
        if (m != null) draw(canvas, m, alpha);
        if (hdr != null) draw(canvas, hdr, alpha);
        if (ftr != null) draw(canvas, ftr, alpha);
    }

    @SuppressWarnings("resource") // fonts.fontFor returns a cached, shared Font
    public int moveCaretVerticalForTextEdit(TextEdit te, int caret, int delta) {
        String s = te.text.peek();
        if (s == null) s = "";
        float size = te.fontSize.peekFloat();
        Font font = fonts.fontFor(size, s);   // cached, shared -- do not close
        try {
            float w = te.width.peekFloat();
            TextWrap.Result wrapped = text.wrapFor(te, s, w, size, font);
            return TextWrap.moveCaretVertical(wrapped, caret, delta,
                seg -> font.measureTextWidth(seg));
        } catch (Throwable ignored) {
            return caret;
        }
    }

    @SuppressWarnings("resource") // fonts.fontFor returns a cached, shared Font
    public int caretIndexForTextEdit(TextEdit te, float localX, float localY) {
        String s = te.text.peek();
        if (s == null) s = "";
        float size = te.fontSize.peekFloat();
        Font font = fonts.fontFor(size, s);   // cached, shared -- do not close
        try {
            float w = te.width.peekFloat();
            float h = te.height.peekFloat();
            TextWrap.Result wrapped = text.wrapFor(te, s, w, size, font);
            float lineH = TextLayout.lineHeight(font);
            float total = lineH * wrapped.lines.size();
            float yOffset = text.topOffset(te.verticalAlignment.peek(), h, total);
            int lineIdx = (int) Math.floor((localY - yOffset) / lineH);
            if (lineIdx < 0) lineIdx = 0;
            if (lineIdx >= wrapped.lines.size()) lineIdx = wrapped.lines.size() - 1;
            String line = wrapped.lines.get(lineIdx);
            int col = TextWrap.caretInLine(line, localX, seg -> font.measureTextWidth(seg));
            return wrapped.starts[lineIdx] + col;
        } catch (Throwable ignored) {
            return s.length();
        }
    }

    @SuppressWarnings("resource") // fonts.fontFor returns a cached, shared Font
    public int caretIndexFor(TextInput ti, float localX) {
        String s = ti.text.peek();
        if (ti instanceof TextField) {
            localX -= ((TextField) ti).padding.peekFloat();
        }
        if (s == null || s.isEmpty() || localX <= 0) return 0;
        float size = ti.fontSize.peekFloat();
        Font font = fonts.fontFor(size, s);   // cached, shared -- do not close
        try {
            float prev = 0f;
            int n = s.length();
            for (int i = 1; i <= n; i++) {
                float w = font.measureTextWidth(s.substring(0, i));
                if (w >= localX) {
                    float mid = (prev + w) / 2f;
                    return localX < mid ? i - 1 : i;
                }
                prev = w;
            }
            return n;
        } catch (Throwable ignored) {
            return s.length();
        }
    }

    void resolveLoader(Loader node) {
        // An inactive Loader holds no item (Qt frees it). Unload so an off-screen tab's
        // content -- and its animations -- stops, instead of running every frame and keeping
        // the whole scene dirty. Reactivating reloads (loadedSource/Component cleared).
        if (!Boolean.TRUE.equals(node.active.peek())) {
            if (node.loadedItem != null) clearLoadedItem(node);
            node.loadedComponent = null;
            node.loadedSource = null;
            return;
        }
        Component sc = node.sourceComponent.peek();
        if (sc != null) {
            resolveLoaderComponent(node, sc);
            return;
        }
        if (node.loadedComponent != null) {
            clearLoadedItem(node);
            node.loadedComponent = null;
        }
        resolveLoaderSource(node);
    }

    private void resolveLoaderSource(Loader node) {
        String src = node.source.peek();
        if (src == null || src.isEmpty()) {
            if (node.loadedItem != null) {
                clearLoadedItem(node);
                node.loadedSource = null;
            }
            return;
        }
        if (src.equals(node.loadedSource)) return;
        if (factory == null) return;
        Item child;
        try {
            // Reuse the compiled compound-type class when `src` names one (native-image
            // safe); the factory loads + compiles the file only for an unregistered path.
            child = factory.createFromSource(src, node.documentDir);
        } catch (Throwable t) {
            return;
        }
        if (child == null) return;
        attachLoadedItem(node, child);
        node.loadedSource = src;
    }

    private void resolveLoaderComponent(Loader node, Component sc) {
        if (sc == node.loadedComponent && node.loadedItem != null) return;
        DelegateFactory df = sc.factory();
        if (df == null) return;
        QObject created = df.create(0, null, node);
        if (!(created instanceof Item)) {
            throw new IllegalStateException("Loader sourceComponent must produce an Item, got "
                + (created == null ? "null" : created.getClass().getName()));
        }
        attachLoadedItem(node, (Item) created);
        node.loadedComponent = sc;
        node.loadedSource = null;
    }

    private void attachLoadedItem(Loader node, Item child) {
        if (node.loadedItem != null) {
            node.children.remove(node.loadedItem);
        }
        node.loadedItem = child;
        child.parent.set(node);
        node.children.add(child);
        node.item.set(child);
        child.initStateBindingsTree();
        // Qt fires Loader.onLoaded once the item exists; the MD3 app hangs its page
        // enter animation (slide-up + fade-in) off it. Emit after item/state are set
        // so the handler sees a fully-formed item.
        node.loaded.emit();
    }

    private void clearLoadedItem(Loader node) {
        if (node.loadedItem != null) {
            node.children.remove(node.loadedItem);
            node.loadedItem = null;
        }
        node.item.set(null);
    }

    public void dispose() {
        clearBoundaries();   // close any cached boundary pictures (native memory)
        if (paint != null) {
            paint.close();
            paint = null;
        }
        painter.dispose();   // close cached native TextLine handles
        text.dispose();
        fonts.close();
    }

    // Common CSS/QML named colors. "transparent" is the critical one (MD3 uses it
    // heavily); without it parseColor returned opaque black and painted over.
    private static final Map<String, Integer> NAMED_COLORS = buildNamedColors();

    private static Map<String, Integer> buildNamedColors() {
        Map<String, Integer> m = new HashMap<>();
        m.put("transparent", 0x00000000);
        m.put("black", 0xFF000000);
        m.put("white", 0xFFFFFFFF);
        m.put("red", 0xFFFF0000);
        m.put("green", 0xFF008000);
        m.put("blue", 0xFF0000FF);
        m.put("gray", 0xFF808080);
        m.put("grey", 0xFF808080);
        m.put("yellow", 0xFFFFFF00);
        m.put("orange", 0xFFFFA500);
        return m;
    }

    public static int parseColor(String s) {
        if (s == null) return 0xFF000000;
        s = s.trim();
        if (s.isEmpty()) return 0xFF000000;
        if (s.charAt(0) != '#') {
            Integer named = NAMED_COLORS.get(s.toLowerCase());
            return named != null ? named : 0xFF000000;
        }
        String hex = s.substring(1);
        long v;
        try { v = Long.parseLong(hex, 16); }
        catch (NumberFormatException e) { return 0xFF000000; }
        switch (hex.length()) {
            case 3: {
                int r = (int) ((v >> 8) & 0xF);
                int g = (int) ((v >> 4) & 0xF);
                int b = (int) (v & 0xF);
                return 0xFF000000 | (r * 0x11 << 16) | (g * 0x11 << 8) | (b * 0x11);
            }
            case 6:
                return 0xFF000000 | (int) (v & 0xFFFFFFL);
            case 8:
                return (int) v;
            default:
                return 0xFF000000;
        }
    }

    static int applyAlpha(int color, float alpha) {
        if (alpha >= 1f) return color;
        if (alpha <= 0f) return color & 0x00FFFFFF;
        int a = (color >>> 24) & 0xFF;
        int na = Math.round(a * alpha);
        return (na << 24) | (color & 0x00FFFFFF);
    }

    static float sigma(float radius) {
        return radius <= 0f ? 0f : radius / 2f;
    }

    // A layer.effect MultiEffect with maskEnabled rounds the item to its mask's shape
    // (the carousel masks its image to the card's corner radius this way). Returns the
    // four corner radii (tl,tr,br,bl) for an antialiased rounded clip, or null when
    // there is no rounded mask.
    private static float[] maskClipRadii(Item node) {
        if (!Boolean.TRUE.equals(node.layer.enabled.peek())) return null;
        Object effect = node.layer.effect.peek();
        if (!(effect instanceof MultiEffect)) return null;
        MultiEffect me = (MultiEffect) effect;
        if (!Boolean.TRUE.equals(me.maskEnabled.peek())) return null;
        Rectangle mr = firstRectangle(me.maskSource.peek());
        if (mr == null) return null;
        float tl = mr.cornerRadius(mr.topLeftRadius.peekFloat());
        float tr = mr.cornerRadius(mr.topRightRadius.peekFloat());
        float br = mr.cornerRadius(mr.bottomRightRadius.peekFloat());
        float bl = mr.cornerRadius(mr.bottomLeftRadius.peekFloat());
        if (tl <= 0 && tr <= 0 && br <= 0 && bl <= 0) return null;
        return new float[]{tl, tr, br, bl};
    }

    private static Rectangle firstRectangle(Object maskSource) {
        if (!(maskSource instanceof Item)) return null;
        if (maskSource instanceof Rectangle) return (Rectangle) maskSource;
        for (Item n : ((Item) maskSource).children) {
            Rectangle r = firstRectangle(n);
            if (r != null) return r;
        }
        return null;
    }

    private Paint layerEffectPaint(Item node) {
        if (!Boolean.TRUE.equals(node.layer.enabled.peek())) return null;
        Object effect = node.layer.effect.peek();
        if (effect == null) return null;
        Paint p = new Paint();
        if (effect instanceof DropShadow) {
            DropShadow d = (DropShadow) effect;
            p.setImageFilter(ImageFilter.makeDropShadow(
                d.offsetX.peekFloat(), d.offsetY.peekFloat(),
                sigma(d.radius.peekFloat()), sigma(d.radius.peekFloat()),
                parseColor(d.color.peek())));
        } else if (effect instanceof Glow) {
            Glow g = (Glow) effect;
            p.setImageFilter(ImageFilter.makeDropShadow(
                0f, 0f, sigma(g.radius.peekFloat()), sigma(g.radius.peekFloat()),
                parseColor(g.color.peek())));
        } else if (effect instanceof ColorOverlay) {
            ColorOverlay c = (ColorOverlay) effect;
            p.setColorFilter(ColorFilter.makeBlend(parseColor(c.color.peek()), BlendMode.SRC_IN));
        } else if (effect instanceof MultiEffect && Boolean.TRUE.equals(((MultiEffect) effect).shadowEnabled.peek())) {
            // A MultiEffect used as layer.effect for its drop shadow (MD3 SideSheet casts
            // a shadow to its left over the page). Same shadow knobs as drawMultiEffect.
            MultiEffect me = (MultiEffect) effect;
            int sc = applyAlpha(parseColor(me.shadowColor.peek()), (float) me.shadowOpacity.peekDouble());
            float sg = sigma(me.shadowBlur.peekFloat() * 32f); // Qt blur is 0..1
            p.setImageFilter(ImageFilter.makeDropShadow(
                me.shadowHorizontalOffset.peekFloat(), me.shadowVerticalOffset.peekFloat(), sg, sg, sc));
        } else {
            p.close();
            return null;
        }
        return p;
    }

    private static float layerEffectMargin(Item node) {
        Object effect = node.layer.effect.peek();
        if (effect instanceof DropShadow) {
            DropShadow d = (DropShadow) effect;
            float r = d.radius.peekFloat();
            float ox = Math.abs(d.offsetX.peekFloat());
            float oy = Math.abs(d.offsetY.peekFloat());
            return r + Math.max(ox, oy) + 4f;
        }
        if (effect instanceof Glow) {
            return ((Glow) effect).radius.peekFloat() + 4f;
        }
        if (effect instanceof MultiEffect && Boolean.TRUE.equals(((MultiEffect) effect).shadowEnabled.peek())) {
            MultiEffect me = (MultiEffect) effect;
            float r = me.shadowBlur.peekFloat() * 32f;
            float ox = Math.abs(me.shadowHorizontalOffset.peekFloat());
            float oy = Math.abs(me.shadowVerticalOffset.peekFloat());
            return r + Math.max(ox, oy) + 4f;
        }
        return 0f;
    }
}
