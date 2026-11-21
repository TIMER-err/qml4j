package io.github.timer_err.qml4j.render.items.core;
import io.github.timer_err.qml4j.render.items.input.Keys;
import io.github.timer_err.qml4j.render.items.effect.Layer;
import io.github.timer_err.qml4j.render.items.layout.LayoutAttached;
import io.github.timer_err.qml4j.render.items.animation.State;
import io.github.timer_err.qml4j.render.items.animation.StateController;
import io.github.timer_err.qml4j.render.items.animation.Transition;
import io.github.timer_err.qml4j.render.items.transform.Transform;

import io.github.timer_err.qml4j.render.AnchorLine;
import io.github.timer_err.qml4j.render.Anchors;
import io.github.timer_err.qml4j.render.Painter;
import io.github.timer_err.qml4j.render.TextLayout;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.binding.ObservableList;

import io.github.humbleui.skija.Picture;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class Item extends QObject {
    public final Property<Number> x = new Property<>(0);
    public final Property<Number> y = new Property<>(0);
    public final Property<Number> width = new Property<>(0);
    public final Property<Number> height = new Property<>(0);
    public final Property<Number> implicitWidth = new Property<>(0);
    public final Property<Number> implicitHeight = new Property<>(0);
    public double lastImplicitWidth = Double.NaN;
    public double lastImplicitHeight = Double.NaN;
    public final Property<Boolean> visible = new Property<>(Boolean.TRUE);
    public final Property<Boolean> enabled = new Property<>(Boolean.TRUE);
    public final Property<Number> opacity = new Property<>(1.0);
    public final Property<Number> rotation = new Property<>(0);
    public final Property<Number> scale = new Property<>(1.0);
    // Item.TransformOrigin: which point scale/rotation pivot around (default Center).
    public final Property<Number> transformOrigin = new Property<>(4);
    public final Property<Number> z = new Property<>(0);
    public final Property<Boolean> clip = new Property<>(Boolean.FALSE);
    @SuppressWarnings("unused")
    public final Property<Boolean> antialiasing = new Property<>(Boolean.FALSE);
    // Opt-in static-subtree hint. When true, the layout pass skips re-measuring this
    // item's children as long as the item's own box and child count are unchanged --
    // for a container whose children's geometry is fixed once laid out (e.g. a full
    // song list), so an unrelated version bump (the 5 Hz play clock) doesn't
    // re-measure every off-screen row. See Renderer.measure.
    public final Property<Boolean> cachedLayout = new Property<>(Boolean.FALSE);
    public boolean cachedLayoutValid;
    public float cachedLayoutW;
    public float cachedLayoutH;
    public int cachedLayoutCount;
    public long cachedLayoutChildVersion = -1;
    public long cachedLayoutSettleId = -1;
    // Checksum of the direct children's sizes at cache time. A child whose width comes
    // from a binding (a list row at `width: view.width`) can settle a frame after the
    // first measure, while this container's own box is already final -- so the box +
    // count check above stays valid and the stale child never gets re-measured. Folding
    // the children's dimensions in catches that.
    public long cachedLayoutChildDims;
    public final Property<Item> parent = new Property<>(null);
    // Declared List (the compiler emits children accesses with a java/util/List descriptor)
    // but an ObservableList so structural changes re-evaluate dependent bindings.
    public final List<Item> children = new ObservableList<>();
    // Non-visual children (Behavior, ...) -- in the QML tree for the construction-complete
    // arming walk, but never measured/laid-out/drawn, and not in `children` so a binding
    // like `container.children[0]` resolves to the first real visual child (Qt: a Behavior
    // is a property modifier, not a child).
    public final List<Item> resources = new ObservableList<>();
    public final Anchors anchors = new Anchors();
    public final ChildrenRect childrenRect = new ChildrenRect();
    // QtQuick.Layouts attached props (Layout.fillWidth, Layout.leftMargin, ...).
    public final LayoutAttached Layout = new LayoutAttached();

    @SuppressWarnings("unused")
    public final Property<AnchorLine> left = new Property<>(new AnchorLine(this, AnchorLine.Edge.LEFT));
    public final Property<AnchorLine> right = new Property<>(new AnchorLine(this, AnchorLine.Edge.RIGHT));
    @SuppressWarnings("unused")
    public final Property<AnchorLine> top = new Property<>(new AnchorLine(this, AnchorLine.Edge.TOP));
    @SuppressWarnings("unused")
    public final Property<AnchorLine> bottom = new Property<>(new AnchorLine(this, AnchorLine.Edge.BOTTOM));
    @SuppressWarnings("unused")
    public final Property<AnchorLine> horizontalCenter =
        new Property<>(new AnchorLine(this, AnchorLine.Edge.HORIZONTAL_CENTER));
    @SuppressWarnings("unused")
    public final Property<AnchorLine> verticalCenter =
        new Property<>(new AnchorLine(this, AnchorLine.Edge.VERTICAL_CENTER));

    public final Property<String> state = new Property<>(null);
    // Qt's objectName: an opt-in tag so a host can locate a specific item in the tree
    // (e.g. to render one subtree separately). Null unless QML sets it.
    public final Property<String> objectName = new Property<>(null);
    public final List<State> states = new ArrayList<>();
    public final List<Transition> transitions = new ArrayList<>();
    public final List<Transform> transform = new ArrayList<>();

    public final Property<Boolean> focus = new Property<>(Boolean.FALSE);
    public final Property<Boolean> activeFocus = new Property<>(Boolean.FALSE);
    public final Property<Boolean> activeFocusOnTab = new Property<>(Boolean.FALSE);

    public final Layer layer = new Layer();

    private final StateController stateController = new StateController(this);
    private boolean stateBindingsInited;
    private Keys keys;
    private Consumer<Item> focusHook;

    // The previous value of `parent`, so a change can move this between children lists.
    private Item lastParent;

    // --- Draw-phase content caching (Qt-style ContentUpdateMask over an SkPicture) --------
    // A cache boundary owns a recorded SkPicture of its whole subtree drawn in its own local
    // space. The renderer replays it (drawPicture) with a fresh translate/transform each frame
    // instead of re-recording, unless the content changed. Set by the renderer for the nodes
    // it caches (the MVP: root's direct children); false for everything else.
    public boolean cacheBoundary;
    // The recorded subtree, or null before the first record. Owned by this item; the renderer
    // closes + replaces it on re-record and closes it when this stops being a boundary.
    public Picture cachedPicture;
    // The effective alpha baked into cachedPicture, so an opacity change on this boundary (or an
    // inherited alpha change from an ancestor) forces a re-record even if nothing else is dirty.
    public float cachedAlpha = Float.NaN;
    // The device scale baked into cachedPicture: the subtree is recorded at device resolution
    // (rc.scale(sf)) so a Canvas/layer/shadow raster backing is allocated at the on-screen pixel
    // size rather than logical size and then upscaled (blurry at high DPI). Replay cancels it with
    // scale(1/sf). A GUI-scale / DPI change (sf differs) forces a re-record.
    public float cachedScale = Float.NaN;
    // Set when the recorded pixels are stale: a pixel-affecting property changed somewhere in
    // this boundary's subtree. Pure position/scale/rotation of the boundary ITSELF does not set
    // it -- that only changes the replay matrix (see markTransformDirty). New boundaries start
    // dirty (cachedPicture == null forces the first record regardless).
    public volatile boolean contentDirty = true;
    // Background producers (currently ImageLoader) can finish while a boundary is being
    // recorded. A plain boolean mark can then be lost when recordBoundary clears contentDirty
    // at the end of the recording. This generation is advanced under the boundary monitor for
    // asynchronous marks; the renderer only clears the flag when no such mark arrived during
    // the draw. Normal Property invalidations stay on the render thread and keep the cheap path.
    private long asyncContentGeneration;
    // Diagnostic: how many times this boundary re-recorded its picture (the record-count guard
    // in the tests: a pure-geometry animation records 0 times, a local change records only the
    // affected boundary).
    public int recordCount;
    // Consecutive frames this boundary's content changed. A subtree animating every frame is a
    // hot spot: recording a picture only to replay it once costs more than drawing straight to
    // the canvas, so past a threshold the renderer draws it directly until it settles (a clean
    // frame resets this). See Renderer.drawCachedBoundary.
    public int dirtyStreak;

    // Feature gate: the draw-phase content cache is opt-in per process (the renderer flips this
    // when -Dqml4j.pictureCache is on). While off, markContentDirty/markTransformDirty are no-ops
    // so a scene that never caches pays nothing for the extra invalidation wiring.
    private static boolean contentCacheEnabled;

    // QmlView keeps a flat table of Animatable nodes instead of recursively walking the
    // complete resident scene on every frame. Any visual/non-visual child mutation bumps
    // this process-wide generation so the table is rebuilt before the next animation tick.
    // A process normally owns one view; a second view merely causes a harmless extra rebuild.
    private static long animationStructureVersion;

    public static long animationStructureVersion() {
        return animationStructureVersion;
    }

    public static void setContentCacheEnabled(boolean on) {
        contentCacheEnabled = on;
    }

    public Item() {
        state.addListener(stateController::apply);
        // Reparenting on a `parent` change (Qt): `parent: overlay` moves an item into the
        // overlay's children so it renders there (z:9999), not at its declaration site.
        parent.addListener(this::onParentChanged);
        // QML grants and drops focus by writing the property (`textInput.focus = false`), so
        // that write has to reach the view's focus manager -- otherwise activeFocus and the
        // key-event target keep pointing at the previously focused item.
        focus.addListener(this::onFocusWritten);
        ((ObservableList<Item>) children).addStructuralListener(Item::bumpAnimationStructure);
        ((ObservableList<Item>) resources).addStructuralListener(Item::bumpAnimationStructure);
        wireLayoutInvalidation();
    }

    private static void bumpAnimationStructure() {
        animationStructureVersion++;
    }

    // Connect the geometry + draw properties to the draw-phase content cache: a change to a
    // descendant invalidates the enclosing cache boundary so it re-records (no-op while the cache
    // is disabled). Layout itself is a whole-tree measure each settle, so no per-item layout
    // marking is needed here -- only the cache cares about which subtree changed.
    private void wireLayoutInvalidation() {
        // Position is replay-only for a boundary itself (its translate is re-applied every frame);
        // a descendant move bakes into the ancestor boundary's picture -> markTransformDirty.
        x.addInvalidationListener(this::markTransformDirty);
        y.addInvalidationListener(this::markTransformDirty);
        width.addInvalidationListener(this::markContentDirty);
        height.addInvalidationListener(this::markContentDirty);
        implicitWidth.addInvalidationListener(this::markContentDirty);
        implicitHeight.addInvalidationListener(this::markContentDirty);
        visible.addInvalidationListener(this::markContentDirty);
        ((ObservableList<Item>) children).addStructuralListener(this::markContentDirty);
        anchors.fill.addInvalidationListener(this::markContentDirty);
        anchors.centerIn.addInvalidationListener(this::markContentDirty);
        anchors.margins.addInvalidationListener(this::markContentDirty);
        anchors.leftMargin.addInvalidationListener(this::markContentDirty);
        anchors.rightMargin.addInvalidationListener(this::markContentDirty);
        anchors.topMargin.addInvalidationListener(this::markContentDirty);
        anchors.bottomMargin.addInvalidationListener(this::markContentDirty);
        anchors.left.addInvalidationListener(this::markContentDirty);
        anchors.right.addInvalidationListener(this::markContentDirty);
        anchors.top.addInvalidationListener(this::markContentDirty);
        anchors.bottom.addInvalidationListener(this::markContentDirty);
        anchors.horizontalCenter.addInvalidationListener(this::markContentDirty);
        anchors.verticalCenter.addInvalidationListener(this::markContentDirty);
        anchors.horizontalCenterOffset.addInvalidationListener(this::markContentDirty);
        anchors.verticalCenterOffset.addInvalidationListener(this::markContentDirty);
        // opacity/clip alter the boundary's own content; scale/rotation/z are replay-only for
        // the boundary itself but bake into an ancestor boundary when a descendant changes.
        opacity.addInvalidationListener(this::markContentDirty);
        clip.addInvalidationListener(this::markContentDirty);
        scale.addInvalidationListener(this::markTransformDirty);
        rotation.addInvalidationListener(this::markTransformDirty);
        z.addInvalidationListener(this::markTransformDirty);
    }

    // Mark the nearest enclosing cache boundary (this item or an ancestor) as needing its
    // picture re-recorded: a pixel-affecting property changed in its subtree. No-op while the
    // content cache is disabled, or when no ancestor is a boundary (an uncached region).
    public void markContentDirty() {
        if (!contentCacheEnabled) return;
        Item b = nearestBoundary(this);
        if (b != null) b.contentDirty = true;
    }

    /** Thread-safe cache invalidation for content published by a background worker. */
    public void markContentDirtyAsync() {
        if (!contentCacheEnabled) return;
        Item b = nearestBoundary(this);
        if (b == null) return;
        synchronized (b) {
            b.asyncContentGeneration++;
            b.contentDirty = true;
        }
    }

    /** Snapshot used by the renderer to avoid losing an asynchronous mark mid-recording. */
    public synchronized long asyncContentGeneration() {
        return asyncContentGeneration;
    }

    /** Clear a rendered boundary only if no background result arrived during that draw. */
    public synchronized void clearContentDirtyIfAsyncGeneration(long expected) {
        if (asyncContentGeneration == expected) contentDirty = false;
    }

    // Mark for a change (x/y/scale/rotation/z) that is replay-only for the item on which it
    // occurs: if THIS item is a boundary, its transform is re-applied at replay and needs no
    // re-record, so we look strictly above it. A descendant's transform is baked into the
    // ancestor boundary's picture, so that boundary must re-record.
    public void markTransformDirty() {
        if (!contentCacheEnabled) return;
        Item b = nearestBoundary(parent.peek());
        if (b != null) b.contentDirty = true;
    }

    private static Item nearestBoundary(Item start) {
        for (Item n = start; n != null; n = n.parent.peek()) {
            if (n.cacheBoundary) return n;
        }
        return null;
    }

    // Wire a set of paint-affecting properties (color, radius, text, font, image source, ...)
    // to invalidate the enclosing cache boundary. Subclasses call this from their constructor
    // for their own drawable properties. Listeners are cheap no-ops while caching is disabled.
    protected final void wireContentInvalidation(Property<?>... props) {
        for (Property<?> p : props) p.addInvalidationListener(this::markContentDirty);
    }

    // A stable this::markContentDirty reference for listeners we attach to EXTERNAL value holders
    // (a Gradient's stops, a ShapePath's path elements) so we can detach them on dispose -- those
    // holders can outlive this item (e.g. a shared theme gradient), and a lingering listener would
    // pin the whole subtree in the holder's listener list (the same leak dispose() guards against).
    private Runnable contentDirtyRef;
    private List<Property<?>> wiredHolderProps;

    private Runnable contentDirtyRef() {
        if (contentDirtyRef == null) contentDirtyRef = this::markContentDirty;
        return contentDirtyRef;
    }

    // Wire every Property reachable from a non-Item value holder (its own Property fields, and one
    // recursion into nested holders + list elements) to invalidate this item's cached picture.
    // These holders are QObjects, not Items, so their inner changes never reach markContentDirty on
    // their own -- an animated Gradient stop colour or Shape path coordinate would otherwise not
    // re-record. Wired once (post-construction, via wireDeferredContentInvalidation) so no
    // duplicate listeners accumulate; detached on dispose.
    protected final void wireHolderContent(Object holder) {
        if (holder == null) return;
        wireHolderContent(holder, new java.util.IdentityHashMap<>());
    }

    private void wireHolderContent(Object obj, Map<Object, Boolean> seen) {
        if (obj == null || obj instanceof Item || seen.put(obj, Boolean.TRUE) != null) return;
        for (Field f : fieldsOf(obj.getClass())) {
            Object v;
            try {
                v = f.get(obj);
            } catch (IllegalAccessException ignore) {
                continue;
            }
            if (v instanceof Property) {
                Property<?> p = (Property<?>) v;
                p.addInvalidationListener(contentDirtyRef());
                if (wiredHolderProps == null) wiredHolderProps = new ArrayList<>();
                wiredHolderProps.add(p);
            } else if (v instanceof List) {
                for (Object e : (List<?>) v) wireHolderContent(e, seen);
            } else if (isEngineHolder(v)) {
                wireHolderContent(v, seen);
            }
        }
    }

    private void unwireHolderContent() {
        if (wiredHolderProps == null) return;
        for (Property<?> p : wiredHolderProps) p.removeInvalidationListener(contentDirtyRef());
        wiredHolderProps = null;
    }

    // Wire non-Item value holders owned by this item (Gradient stops, Shape path elements) after
    // the QML tree is built and those lists are populated. Default no-op; Rectangle/Shape override.
    protected void wireDeferredContentInvalidation() {}

    private void onParentChanged(Item np) {
        Item old = lastParent;
        lastParent = np;
        if (old == np || old == null) return;
        // Move only a visual child: if the old parent's children held it (a resources-parked
        // Behavior, or an object assigned to a property, never was) move it to the new
        // parent's children. The construction-time first set has old == null, so the item is
        // simply recorded; a later `parent:` change then relocates it.
        if (old.children.remove(this) && np != null && !np.children.contains(this)) {
            np.children.add(this);
        }
    }

    @SuppressWarnings("unused")
    public Keys keys() {
        if (keys == null) keys = new Keys();
        return keys;
    }

    public Keys keysOrNull() {
        return keys;
    }

    public void installFocusHook(Consumer<Item> hook) {
        focusHook = hook;
    }

    // Activate declarative State.when bindings once the tree is built. Idempotent:
    // dynamically-instantiated subtrees (Loader pages, Repeater delegates) are walked
    // when attached, and a node already inited during the top-level walk is skipped.
    public void initStateBindings() {
        if (stateBindingsInited) return;
        stateBindingsInited = true;
        if (!states.isEmpty()) stateController.initWhen();
        // The QML tree is now built; wire any owned value holders (Gradient stops, Shape paths).
        wireDeferredContentInvalidation();
    }

    // Activate state bindings for a freshly-attached subtree. Loader/Repeater create
    // their content during a render pass, after the top-level QmlView.load walk, so
    // their `when`-driven states would otherwise never engage.
    public void initStateBindingsTree() {
        initStateBindings();
        for (Item c : children) c.initStateBindingsTree();
        for (Item r : resources) r.initStateBindingsTree();
    }

    // A `visible:` binding may evaluate to undefined (peek() == null); treat that as the
    // default (visible) so layout/hit-test/paint don't NPE on the unboxed boolean.
    public boolean isVisible() {
        return !Boolean.FALSE.equals(visible.peek());
    }

    // Container layout hook, invoked by the Renderer layout pass. Default no-op;
    // layout containers (Row/Column/*Layout) override to position their children.
    public void layout() {}

    // Paint hook, invoked by the Renderer paint pass once geometry/clip/transform
    // are set up. Default no-op; drawable items override to render themselves via
    // Painter primitives (so item subclasses never import skija directly).
    public void paint(Painter p, float w, float h, float alpha) {}

    // Measure hook, invoked by the Renderer layout pre-pass. Default no-op; items
    // with intrinsic content size (Text, Button) override to publish implicit size
    // via TextLayout (so item subclasses never touch font metrics directly).
    public void measure(TextLayout t) {}

    // QML Item.mapFromItem(source, x, y): map a point from source's coordinate
    // system into this item's. Returns a point ({x, y}); positions only (no
    // rotation/scale), which is what hit-math like Slider's drag needs.
    public Map<String, Object> mapFromItem(Object source, double x, double y) {
        double[] from = source instanceof Item ? ((Item) source).scenePosition() : new double[]{0, 0};
        double[] self = scenePosition();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("x", from[0] + x - self[0]);
        p.put("y", from[1] + y - self[1]);
        return p;
    }

    private double[] scenePosition() {
        double sx = 0, sy = 0;
        for (Item it = this; it != null; it = it.parent.peek()) {
            sx += it.x.peekDouble();
            sy += it.y.peekDouble();
            // A Flickable renders its content translated by (-contentX, -contentY), so a
            // descendant's on-screen position is offset by the scroll of each ancestor
            // Flickable (else mapFromItem/mapToItem return content, not screen, coords --
            // a popup anchored to the scene then lands off-screen).
            Item p = it.parent.peek();
            if (p instanceof Flickable) {
                sx -= ((Flickable) p).contentX.peekDouble();
                sy -= ((Flickable) p).contentY.peekDouble();
            }
        }
        return new double[]{sx, sy};
    }

    public void forceActiveFocus() {
        Consumer<Item> hook = rootFocusHook();
        if (hook != null) hook.accept(this);
    }

    // Dropping focus only means "nobody is focused" when this item is the one holding it;
    // clearing an unfocused item's flag must not steal focus from whoever has it. The manager
    // clears activeFocus before focus on the item it is leaving, so its own writes land here
    // as no-ops rather than recursing.
    private void onFocusWritten(Boolean wanted) {
        Consumer<Item> hook = rootFocusHook();
        if (hook == null) return;
        if (Boolean.TRUE.equals(wanted)) hook.accept(this);
        else if (Boolean.TRUE.equals(activeFocus.peek())) hook.accept(null);
    }

    private Consumer<Item> rootFocusHook() {
        Item r = this;
        while (r.parent.peek() != null) r = r.parent.peek();
        return r.focusHook;
    }

    // QML Object.destroy(): detach from the scene. Safe to call from inside an
    // animation tick because the tick walk iterates children in reverse by index.
    @SuppressWarnings("unused")
    public void destroy() {
        dispose();
    }

    // Release native resources this item owns (decoded images, offscreen
    // surfaces). Default no-op; Image/Canvas override. Called per item when a
    // subtree is discarded.
    protected void releaseResources() {}

    // Discard this subtree. Unbinds every property's binding so external,
    // longer-lived properties (e.g. a row's `width: parent.width` or
    // `highlighted: index === player.index`) stop retaining these items through
    // their listener lists, releases native resources (cover images, canvas
    // backings), then detaches. Without this a Repeater/Loader that throws away
    // delegates leaks the whole delegate subtree AND its decoded images — opening
    // several big playlists then OOMs.
    public void dispose() {
        tearDown();
        Item p = parent.peek();
        if (p != null) p.children.remove(this);
        parent.set(null);
    }

    private void tearDown() {
        unbindAll();
        unwireHolderContent();
        // Release the cached boundary picture (native memory) if this item was a cache boundary
        // discarded without a render pass to clean it up (e.g. a panel removed from the tree).
        if (cachedPicture != null) {
            cachedPicture.close();
            cachedPicture = null;
        }
        cacheBoundary = false;
        releaseResources();
        for (int i = 0; i < children.size(); i++) children.get(i).tearDown();
        for (int i = 0; i < resources.size(); i++) resources.get(i).tearDown();
    }

    private void unbindAll() {
        unbindFields(this, true);
    }

    // Unbind every Property reachable from `obj`: its own Property fields, and (one
    // level, when descendHolders) the Property fields of its nested holders (font,
    // anchors, Layout, ...). The holders matter because bindings like
    // `font.family: Theme.iconFont.name` live on a holder's Property, not a
    // top-level field — and Theme/StyleManager are singletons, so without clearing
    // them every discarded row stays pinned in their listener lists (the leak).
    private static void unbindFields(Object obj, boolean descendHolders) {
        for (Field f : fieldsOf(obj.getClass())) {
            Object v;
            try {
                v = f.get(obj);
            } catch (IllegalAccessException ignore) {
                continue; // public fields only; never thrown
            }
            if (v instanceof Property) {
                ((Property<?>) v).unbind();
                unbindDeclaredValue(obj, ((Property<?>) v).peek());
            } else if (descendHolders && isEngineHolder(v)) {
                unbindFields(v, false);
            }
            unbindDeclaredValue(obj, v);
        }
    }

    // ShapePath/GradientStop are not visual children. Their bindings still retain the
    // declaring component through QmlScope. Follow only compiler-stamped ownership:
    // a referenced theme gradient/model must keep its own bindings when this item dies.
    private static void unbindDeclaredValue(Object owner, Object value) {
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            for (int i = 0, size = values.size(); i < size; i++) {
                unbindDeclaredObject(owner, values.get(i));
            }
        } else {
            unbindDeclaredObject(owner, value);
        }
    }

    private static void unbindDeclaredObject(Object owner, Object value) {
        if (value instanceof QObject && !(value instanceof Item)
                && ((QObject) value).__getQmlParent() == owner) {
            unbindFields(value, true);
        }
    }

    // A non-Item, non-Property engine object that may hold bound Properties (Font,
    // Anchors, LayoutAttached, ChildrenRect, ...). Items are excluded — they're
    // torn down via the children/resources recursion, not as holders.
    private static boolean isEngineHolder(Object v) {
        if (v == null || v instanceof Item || v instanceof Property) return false;
        return v.getClass().getName().startsWith("io.github.timer_err.qml4j.");
    }

    // Cache the public fields per concrete class: dispose runs over every row of a
    // list on a model swap, and reflecting getFields() each time is a real cost at
    // hundreds of delegates.
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();

    /** Drop cached fields for classes defined by {@code cl} (a disposed view's loader). */
    public static void purgeFieldCache(ClassLoader cl) {
        if (cl == null) return;
        synchronized (FIELDS) {
            FIELDS.keySet().removeIf(k -> k.getClassLoader() == cl);
        }
    }

    private static Field[] fieldsOf(Class<?> cls) {
        Field[] cached = FIELDS.get(cls);
        if (cached == null) {
            cached = cls.getFields();
            FIELDS.put(cls, cached);
        }
        return cached;
    }
}
