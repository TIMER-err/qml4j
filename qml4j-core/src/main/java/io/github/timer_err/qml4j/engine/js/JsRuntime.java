package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.engine.classloader.ScriptCache;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// Shared Rhino plumbing for the embedded-JS backend: a compiled-script cache (each
// binding's source compiles once), per-document when the document's class loader
// carries a ScriptCache (so its generated JS classes die with it), and one shared,
// sealed standard-objects scope (Math, JSON, etc.) reached via the binding scope's
// parent chain.
// Rhino's Context is entered/exited manually (Context.enter()/exit()), not via
// try-with-resources; the optimization-level knobs are deprecated in Rhino 1.9 but are
// still the supported way to force interpreted mode for our JIT-compiled bindings.
@SuppressWarnings({"deprecation", "resource"})
public final class JsRuntime {

    private JsRuntime() {}

    // Compiled (JS -> JVM bytecode) by default: bindings/handlers run far faster than
    // interpreted. Android must force the interpreter (no runtime bytecode gen) with
    // -Dqml4j.rhino.opt=-1. First step of the compiled-mode / hot-reload direction;
    // a later step defines the generated classes into each component's classloader.
    private static final int OPT_LEVEL = Integer.getInteger("qml4j.rhino.opt", 9);
    private static final long SAFE_EVAL_MS = Math.max(10L,
            Long.getLong("qml4j.safeEvalMs", 100L));
    private static final ThreadLocal<ArrayDeque<Long>> DEADLINES =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<ArrayDeque<Integer>> OPTIMIZATION_LEVELS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final ContextFactory FACTORY = new ContextFactory() {
        @Override protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setOptimizationLevel(OPT_LEVEL);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInstructionObserverThreshold(10_000);
            return cx;
        }

        @Override protected void observeInstructionCount(Context context, int instructionCount) {
            ArrayDeque<Long> values = DEADLINES.get();
            Long deadline = values.peek();
            if (deadline != null && deadline > 0L && System.nanoTime() > deadline) {
                throw new IllegalStateException("safe QML JavaScript exceeded its execution limit");
            }
        }
    };

    private static final ConcurrentHashMap<String, Script> SCRIPTS = new ConcurrentHashMap<>();
    private static final Set<Scriptable> SAFE_GLOBALS = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<Scriptable, Boolean>()));
    /**
     * A QML engine's JavaScript namespace.  Context properties and imported JS
     * modules must never leak into another window/engine (and, in particular,
     * into a third-party component hosted by the same process).
     */
    public static final class Realm {
        private final boolean safeStandardObjects;
        private volatile Scriptable globals;

        public Realm(boolean safeStandardObjects) {
            this.safeStandardObjects = safeStandardObjects;
        }
    }

    /** Scope activation is deliberately thread-local: QML classes are created
     * synchronously by Loader, and bindings capture the selected realm's global
     * object in their QmlScope. Later evaluations therefore do not depend on the
     * activation still being present. */
    public static final class Activation implements AutoCloseable {
        private final Realm previous;
        private boolean closed;

        private Activation(Realm realm) {
            previous = ACTIVE_REALM.get();
            ACTIVE_REALM.set(realm);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) ACTIVE_REALM.remove();
            else ACTIVE_REALM.set(previous);
        }
    }

    private static final Realm DEFAULT_REALM = new Realm(false);
    private static final ThreadLocal<Realm> ACTIVE_REALM = new ThreadLocal<>();

    public static Realm newRealm(boolean safeStandardObjects) {
        return new Realm(safeStandardObjects);
    }

    public static Activation activate(Realm realm) {
        if (realm == null) throw new IllegalArgumentException("realm == null");
        return new Activation(realm);
    }

    public static Context enter() {
        return enter(currentRealm().safeStandardObjects);
    }

    public static Context enter(Scriptable scope) {
        return enter(scope != null && isSafeScope(scope));
    }

    private static Context enter(boolean safe) {
        Context context = FACTORY.enterContext();
        OPTIMIZATION_LEVELS.get().push(context.getOptimizationLevel());
        if (safe) context.setOptimizationLevel(-1);
        DEADLINES.get().push(safe
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SAFE_EVAL_MS) : 0L);
        return context;
    }

    public static void exit() {
        ArrayDeque<Long> values = DEADLINES.get();
        if (!values.isEmpty()) values.pop();
        if (values.isEmpty()) DEADLINES.remove();
        ArrayDeque<Integer> levels = OPTIMIZATION_LEVELS.get();
        if (!levels.isEmpty()) Context.getCurrentContext().setOptimizationLevel(levels.pop());
        if (levels.isEmpty()) OPTIMIZATION_LEVELS.remove();
        Context.exit();
    }

    @SuppressWarnings("unused")
    public static Script compile(String source) {
        return compile(source, null);
    }

    // Compile `source`, caching on the document's class loader when it carries a
    // ScriptCache (so the compiled-JS class is a child of that loader and is freed
    // with the document), else in the shared global cache. The loader is also set as
    // the parent for Rhino's generated class so the JS can see the document's classes.
    public static Script compile(String source, ClassLoader cl) {
        boolean safe = currentRealm().safeStandardObjects;
        String cacheKey = (safe ? "safe:" : "normal:") + source;
        Map<String, Object> cache = cl instanceof ScriptCache ? ((ScriptCache) cl).jsScriptCache() : null;
        if (cache != null) {
            Script cached = (Script) cache.get(cacheKey);
            if (cached != null) return cached;
            Script s = doCompile(source, cl, safe);
            cache.put(cacheKey, s);
            return s;
        }
        return SCRIPTS.computeIfAbsent(cacheKey, ignored -> doCompile(source, cl, safe));
    }

    // The document's class loader for a binding/handler: prefer the root component's
    // (a ScriptCache-carrying DynamicClassLoader), else the binding owner's.
    public static ClassLoader loaderOf(Object root, Object outer) {
        ClassLoader cl = root != null ? root.getClass().getClassLoader() : null;
        if (cl instanceof ScriptCache) return cl;
        return outer != null ? outer.getClass().getClassLoader() : cl;
    }

    private static Script doCompile(String source, ClassLoader cl, boolean safe) {
        Context cx = enter(safe);
        try {
            if (cl != null) cx.setApplicationClassLoader(cl);
            return cx.compileString(JsConstRepair.toLet(source), "qml-binding", 1, null);
        } finally {
            exit();
        }
    }

    // Syntax-validate `source` without generating a class: compiled mode would emit
    // (and leak) a throwaway class per source, so validation runs interpreted. Throws
    // RhinoException on a syntax error.
    public static void validate(String source) {
        Context cx = enter();
        int prev = cx.getOptimizationLevel();
        try {
            cx.setOptimizationLevel(-1);
            cx.compileString(JsConstRepair.toLet(source), "qml-validate", 1, null);
        } finally {
            cx.setOptimizationLevel(prev);
            exit();
        }
    }

    // Install a host context property into the shared globals scope, where a binding's
    // QmlScope reaches it via the parent chain after its own lookups miss.
    public static void putGlobal(String name, Object value) {
        putGlobal(currentRealm(), name, value);
    }

    public static void putGlobal(Realm realm, String name, Object value) {
        Scriptable g = globals(realm);
        enter(realm.safeStandardObjects);
        try {
            g.put(name, g, JsWrap.toJs(value, g));
        } finally {
            exit();
        }
    }

    // Evaluate a `.js` library imported as a namespace (`import "x.js" as Foo`) and return
    // its scope, whose top-level `var`s are the module's members (`Foo.icons`). The scope's
    // prototype is the shared globals so the module sees Math/JSON/etc.
    public static Scriptable evalModule(String source) {
        Context cx = enter();
        try {
            Scriptable scope = cx.newObject(globals());
            scope.setPrototype(globals());
            scope.setParentScope(null);
            cx.evaluateString(scope, JsConstRepair.toLet(source), "qml-js-module", 1, null);
            return scope;
        } finally {
            exit();
        }
    }

    public static Scriptable globals() {
        return globals(currentRealm());
    }

    public static Scriptable globals(Realm realm) {
        Scriptable g = realm.globals;
        if (g != null) return g;
        synchronized (realm) {
            if (realm.globals == null) {
                Context cx = enter(realm.safeStandardObjects);
                try {
                    realm.globals = QtGlobals.build(cx, realm.safeStandardObjects);
                    if (realm.safeStandardObjects) SAFE_GLOBALS.add(realm.globals);
                } finally {
                    exit();
                }
            }
            return realm.globals;
        }
    }

    private static Realm currentRealm() {
        Realm realm = ACTIVE_REALM.get();
        return realm != null ? realm : DEFAULT_REALM;
    }

    /** Whether a scope belongs to a safe realm, following only scope/prototype links. */
    public static boolean isSafeScope(Scriptable scope) {
        Scriptable current = scope;
        for (int depth = 0; current != null && depth < 128; depth++) {
            if (SAFE_GLOBALS.contains(current)) return true;
            Scriptable parent = current.getParentScope();
            current = parent != null ? parent : current.getPrototype();
        }
        return false;
    }
}
