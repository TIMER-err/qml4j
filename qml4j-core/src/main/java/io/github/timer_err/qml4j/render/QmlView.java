package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.animation.Animatable;
import io.github.timer_err.qml4j.render.items.animation.GroupAnimation;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.animation.PropertyAnimation;
import io.github.timer_err.qml4j.render.items.input.TextEditable;
import io.github.timer_err.qml4j.render.items.input.TextInput;

import io.github.humbleui.skija.Canvas;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;
import io.github.timer_err.qml4j.compiler.TypeRegistry;
import io.github.timer_err.qml4j.compiler.bytecode.rhino.RhinoScope;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.js.JsRuntime;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.invoke.MethodInvocation;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;

public final class QmlView {

    // Draw-phase content cache (per-boundary SkPicture reuse). Off by default -- it changes the
    // paint path and is still an MVP (root's direct children only); opt in with
    // -Dqml4j.pictureCache=true to reuse recorded subtrees for static panels while one animates.
    private static final boolean PICTURE_CACHE =
        Boolean.parseBoolean(System.getProperty("qml4j.pictureCache", "false"));

    private final Renderer renderer = new Renderer();
    private final DirtyQueue dirty = new DirtyQueue();
    private final Loader loader;
    private final QmlEngine engine;
    private final FocusManager focus = new FocusManager();
    private final EventDispatcher events = new EventDispatcher(focus, renderer);
    private Item root;

    public QmlView(QmlEngine engine, TypeRegistry types) {
        this.engine = engine;
        this.loader = new Loader(engine, types);
        renderer.setComponentFactory(loader);
        renderer.setPictureCache(PICTURE_CACHE);
    }

    public static QmlView withStockTypes(QmlEngine engine) {
        return new QmlView(engine, StockTypes.registry());
    }

    public QmlView resources(ResourceLoader res) {
        loader.setResources(res);
        renderer.setResourceLoader(res);
        return this;
    }

    /**
     * Reuse a host-persisted generated scene for the first {@link #load} call.
     * The host owns {@code key}; it must change whenever any reachable QML, qmldir,
     * JavaScript import, context contract, or qml4j version changes.
     */
    public QmlView compilationCache(CompiledSceneCache cache, String key) {
        loader.setCompilationCache(cache, key);
        return this;
    }

    /** Restrict remote Image sources. Redirect hops are checked independently. */
    public QmlView networkPolicy(NetworkResourcePolicy policy) {
        renderer.setNetworkResourcePolicy(policy);
        return this;
    }

    /** Override the UI font (regular + medium) so the whole scene renders with an
     *  app-bundled face (Latin + CJK). Call before the first frame. */
    @SuppressWarnings("unused") // host configuration API
    public QmlView uiTypefaces(byte[] regular, byte[] medium) {
        renderer.setUiTypefaces(regular, medium);
        return this;
    }

    /** Provide a dedicated CJK face (optional; the default font covers CJK otherwise). */
    @SuppressWarnings("unused") // host configuration API
    public QmlView cjkTypeface(byte[] bytes) {
        renderer.setCjkTypeface(bytes);
        return this;
    }

    /** Provide the icon face (e.g. Material Symbols) the scene's icon glyphs need. */
    @SuppressWarnings("unused") // host configuration API
    public QmlView iconTypeface(byte[] bytes) {
        renderer.setIconTypeface(bytes);
        return this;
    }

    // Expose a host value to QML under `name` (QML's setContextProperty): bindings resolve
    // it as a free identifier. Register before load() so the compiler accepts it.
    public QmlView context(String name, Object value) {
        RhinoScope.registerContextProperty(name);
        JsRuntime.putGlobal(engine.jsRealm(), name, value);
        return this;
    }

    public Item load(String qml) {
        return load(qml, "");
    }

    // baseDir is the document's directory (relative to the resource root), so its
    // relative file imports resolve correctly -- e.g. loading "pages/X.qml" passes "pages".
    public Item load(String qml, String baseDir) {
        root = loader.instantiate(qml, baseDir);
        focus.setRoot(root);
        events.setRoot(root);
        root.installFocusHook(focus::setFocus);
        root.initStateBindingsTree();
        focus.scanInitialFocus(root);
        return root;
    }

    public Item root() {
        return root;
    }

    /** First item in the tree whose objectName equals {@code name}, or null. Lets a host
     *  locate a tagged subtree (e.g. to render it in a separate pass). */
    @SuppressWarnings("unused") // called by the host shell, not from within qml4j
    public Item findByObjectName(String name) {
        return findByObjectName(root, name);
    }

    private static Item findByObjectName(Item node, String name) {
        if (node == null) return null;
        if (name.equals(node.objectName.peek())) return node;
        for (int i = 0; i < node.children.size(); i++) {
            Item r = findByObjectName(node.children.get(i), name);
            if (r != null) return r;
        }
        return null;
    }

    public void setClipboard(Clipboard cb) {
        events.setClipboard(cb);
    }

    public boolean copy() {
        return events.copy();
    }

    public boolean cut() {
        return events.cut();
    }

    public boolean paste() {
        return events.paste();
    }

    public interface FocusListener {
        void onFocusChanged(Item newFocus, Item oldFocus);
    }

    public void setFocusListener(FocusListener l) {
        focus.setFocusListener(l);
    }

    /** Notified as each compound component is compiled during {@link #load} —
     *  drives a host splash/progress while the QML tree compiles. */
    public interface CompileProgressListener {
        @SuppressWarnings("unused")
        void onComponentCompiled(String name, int compiledCount);
    }

    @SuppressWarnings("unused")
    public void setCompileProgressListener(CompileProgressListener l) {
        loader.setProgressListener(l);
    }

    public Item focused() {
        return focus.focused();
    }

    public void setFocus(Item it) {
        focus.setFocus(it);
    }

    @SuppressWarnings("unused")
    public void clearFocus() {
        focus.clearFocus();
    }

    public boolean dispatchKey(int keyCode, String text, boolean down) {
        return events.dispatchKey(keyCode, text, down, false);
    }

    public boolean dispatchKey(int keyCode, String text, boolean down, boolean shift) {
        return events.dispatchKey(keyCode, text, down, shift);
    }

    public static final int KEY_BACKSPACE = -1;
    public static final int KEY_ENTER = -2;
    public static final int KEY_LEFT = -3;
    public static final int KEY_RIGHT = -4;
    public static final int KEY_HOME = -5;
    public static final int KEY_END = -6;
    public static final int KEY_UP = -7;
    public static final int KEY_DOWN = -8;
    public static final int KEY_ESCAPE = -9;
    public static final int KEY_TAB = -10;
    public static final int KEY_BACKTAB = -11;

    public boolean dispatchClick(float x, float y) {
        return events.dispatchClick(x, y);
    }

    public boolean dispatchPointerDown(float x, float y) {
        return events.dispatchPointerDown(x, y);
    }

    /** button is a Qt.MouseButton value: LeftButton=1, RightButton=2, MiddleButton=4. */
    @SuppressWarnings("unused") // host input API
    public boolean dispatchPointerDown(float x, float y, int button) {
        return events.dispatchPointerDown(x, y, button);
    }

    public boolean dispatchPointerMove(float x, float y) {
        return events.dispatchPointerMove(x, y);
    }

    public boolean dispatchPointerUp(float x, float y) {
        return events.dispatchPointerUp(x, y);
    }

    /** button is a Qt.MouseButton value: LeftButton=1, RightButton=2, MiddleButton=4. */
    @SuppressWarnings("unused") // host input API
    public boolean dispatchPointerUp(float x, float y, int button) {
        return events.dispatchPointerUp(x, y, button);
    }

    public boolean dispatchWheel(float x, float y, float dx, float dy) {
        return events.dispatchWheel(x, y, dx, dy);
    }

    @SuppressWarnings("unused")
    public TextEditable pickTextEditable(float x, float y) {
        return events.pickTextEditable(x, y);
    }

    @SuppressWarnings("unused")
    public TextInput pickTextInput(float x, float y) {
        return events.pickTextInput(x, y);
    }

    private long fpsLastNanos;
    private double fpsSmoothed;
    private long renderedVersion = -1;

    public void renderFrame(SurfaceBackend backend) {
        dirty.install();
        try {
            long now = System.nanoTime();
            tickAnimations(now);
            dirty.flush();
            // Idle-frame fast path: if nothing in the scene changed since the last full
            // layout (no animation/timer/input/binding touched a property), skip the layout
            // pass and repaint with cached geometry -- so an idle UI over a game loop costs
            // only its paint, not a full re-layout every frame.
            boolean skipLayout = Property.changeVersion() == renderedVersion;
            Canvas canvas = backend.acquireCanvas();
            renderer.setGpuContext(backend.recordingContext());
            renderer.render(canvas, root, skipLayout);
            renderedVersion = Property.changeVersion();
            if (renderer.fpsOverlayEnabled()) {
                drawFpsOverlay(canvas, now);
                logFrameStats(now, skipLayout);
            }
            backend.present();
        } finally {
            dirty.uninstall();
        }
    }

    // -Dqml4j.fps=true diagnostic: accumulate per-frame layout/draw timing and print a
    // once-per-second breakdown so a stutter (e.g. an animation) can be attributed to the
    // measure phase (this refactor's concern) vs the paint phase (shadows/blur/overdraw).
    private long statsWindowStart;
    private int statsFrames;
    private int statsLaidOut;
    private long statsMeasureNanos;
    private long statsDrawNanos;
    private int statsMaxNodes;
    private int statsMaxPasses;
    private int statsRecords;

    // Optional file sink (-Dqml4j.fpslog=path): appended + flushed per line so the numbers
    // survive the demo's hard halt() exit, which drops buffered stdout.
    private static final String FPS_LOG_PATH = System.getProperty("qml4j.fpslog");
    private java.io.Writer fpsLogWriter;

    private void logFrameStats(long now, boolean skipLayout) {
        statsFrames++;
        if (!skipLayout) statsLaidOut++;
        statsMeasureNanos += renderer.lastMeasureNanos();
        statsDrawNanos += renderer.lastDrawNanos();
        statsMaxNodes = Math.max(statsMaxNodes, renderer.measuredNodeCount());
        statsMaxPasses = Math.max(statsMaxPasses, renderer.settlePassCount());
        statsRecords += renderer.pictureRecordsThisFrame();
        if (statsWindowStart == 0) statsWindowStart = now;
        long elapsed = now - statsWindowStart;
        if (elapsed < 1_000_000_000L) return;
        double fps = statsFrames * 1e9 / elapsed;
        double measUs = statsMeasureNanos / 1e3 / statsFrames;
        double drawUs = statsDrawNanos / 1e3 / statsFrames;
        String line = String.format(
            "[qml4j.fps] %.0f fps | frames=%d laidOut=%d | measure=%.1fus draw=%.1fus | maxNodes=%d maxPasses=%d records=%d",
            fps, statsFrames, statsLaidOut, measUs, drawUs, statsMaxNodes, statsMaxPasses, statsRecords);
        emitFrameStats(line);
        statsWindowStart = now;
        statsFrames = 0;
        statsLaidOut = 0;
        statsMeasureNanos = 0;
        statsDrawNanos = 0;
        statsMaxNodes = 0;
        statsMaxPasses = 0;
        statsRecords = 0;
    }

    private void emitFrameStats(String line) {
        System.out.println(line);
        System.out.flush();
        if (FPS_LOG_PATH == null) return;
        try {
            if (fpsLogWriter == null) {
                fpsLogWriter = new java.io.FileWriter(FPS_LOG_PATH, true);
            }
            fpsLogWriter.write(line);
            fpsLogWriter.write('\n');
            fpsLogWriter.flush();
        } catch (java.io.IOException ignored) {
            // diagnostic only; never disrupt rendering
        }
    }

    // Smooth the inter-frame interval (EMA) into an FPS reading and draw it top-right.
    private void drawFpsOverlay(Canvas canvas, long now) {
        if (fpsLastNanos > 0) {
            double inst = 1e9 / Math.max(1, now - fpsLastNanos);
            fpsSmoothed = fpsSmoothed == 0 ? inst : fpsSmoothed * 0.9 + inst * 0.1;
        }
        fpsLastNanos = now;
        if (root != null) renderer.drawFpsOverlay(canvas, root.width.peekFloat(), fpsSmoothed);
    }

    public void tickAnimations(long nowNanos) {
        if (root == null) return;
        long structureVersion = Item.animationStructureVersion();
        if (animationRoot != root || animationStructureVersion != structureVersion) {
            animationEntries.clear();
            collectAnimations(root, null, false);
            animationRoot = root;
            animationStructureVersion = structureVersion;
        }
        for (int i = 0, size = animationEntries.size(); i < size; i++) {
            AnimationEntry entry = animationEntries.get(i);
            entry.animation.tick(nowNanos);
            if (entry.child && entry.item instanceof PropertyAnimation) {
                PropertyAnimation animation = (PropertyAnimation) entry.item;
                if (animation.ephemeral && !Boolean.TRUE.equals(animation.running.peek())) {
                    entry.parent.children.remove(entry.item);
                }
            }
        }
    }

    // Rebuilt only when the Item tree changes. GroupAnimation owns/ticks its child
    // animations, so its descendants intentionally stay out of this top-level table.
    private final java.util.ArrayList<AnimationEntry> animationEntries = new java.util.ArrayList<>();
    private Item animationRoot;
    private long animationStructureVersion = -1L;

    private static final class AnimationEntry {
        final Animatable animation;
        final Item item;
        final Item parent;
        final boolean child;

        AnimationEntry(Animatable animation, Item item, Item parent, boolean child) {
            this.animation = animation;
            this.item = item;
            this.parent = parent;
            this.child = child;
        }
    }

    private void collectAnimations(Item node, Item parent, boolean child) {
        if (node == null) return;
        if (node instanceof Animatable) {
            animationEntries.add(new AnimationEntry((Animatable) node, node, parent, child));
        }
        if (node instanceof GroupAnimation) return;
        for (int i = node.children.size() - 1; i >= 0; i--) {
            collectAnimations(node.children.get(i), node, true);
        }
        for (int i = 0, size = node.resources.size(); i < size; i++) {
            collectAnimations(node.resources.get(i), node, false);
        }
    }

    public DirtyQueue dirtyQueue() {
        return dirty;
    }

    public Renderer renderer() {
        return renderer;
    }

    public void dispose() {
        // The generated component classes for this view were defined by one per-document
        // ClassLoader (QmlEngine's backend). Static reflection caches key on those Class
        // objects with strong refs, which would pin the loader — and all its Metaspace
        // classes — forever across hot-reloads. Purge this loader's entries so it (and its
        // classes) can be collected. Stock-type entries (parent loader) survive.
        Item r = root();
        ClassLoader cl = (r != null) ? r.getClass().getClassLoader() : null;
        // Tear the whole scene tree down FIRST: unbind every Property (so bindings to
        // long-lived singletons -- Theme/StyleManager -- stop pinning these items in their
        // listener lists) and release each item's native resources (Canvas backings, decoded
        // Images, cached boundary Pictures). Without this a hot-reload orphans the entire old
        // tree, and since Skija's native memory never pressures the JVM heap it isn't GC'd --
        // each reload then adds hundreds of MB that never comes back.
        if (r != null) r.dispose();
        renderer.dispose();
        if (cl != null && cl != QmlView.class.getClassLoader()) {
            MemberAccess.purge(cl);
            MethodInvocation.purge(cl);
            Item.purgeFieldCache(cl);
        }
    }
}
