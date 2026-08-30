package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import io.github.timer_err.qml4j.runtime.invoke.MethodInvocation;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.Callable;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.QmlSafeBridge;
import io.github.timer_err.qml4j.engine.Signal;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Symbol;
import org.mozilla.javascript.SymbolKey;
import org.mozilla.javascript.SymbolScriptable;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

// Bridges values across the Java <-> Rhino boundary for the embedded-JS backend.
// Primitives pass through; Java objects (QObject/QColor/Map/List) are exposed to JS
// as a JavaMember scriptable whose member/index access routes through MemberAccess
// (so item.width reads the Property -- and records the dependency -- color.r resolves
// a channel, list[0] indexes, etc.).
public final class JsWrap {

    private JsWrap() {}

    public static Object toJs(Object v, Scriptable scope) {
        if (v == null) return null;
        if (v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof CharSequence) {
            String s = v.toString();
            // A color value is a "#rrggbb" string, but QML exposes .r/.g/.b/.a channels on
            // it. Wrap so member access resolves the channel while every string use (concat,
            // template, assignment back to a color property) coerces to the hex string.
            if (isColorHex(s)) return new JsColor(s, scope);
            return s;
        }
        // A function value crossing back into JS must stay callable. A JS function stored
        // in a `var` property round-trips as RhinoFunctionValue; unwrap it to the live
        // Rhino Function (preserving its captured scope) so `obj.action()` / `mk()` work
        // instead of resolving to a non-callable JavaMember. A plain io.github.timer_err.qml4j Callable
        // (a Java-side function value) is adapted to a BaseFunction.
        if (v instanceof Function) return v;
        if (v instanceof RhinoFunctionValue) return ((RhinoFunctionValue) v).unwrap();
        if (v instanceof Callable) return callableToFunction((Callable) v);
        // A natively-stored var object/array (see toJava) is a live Rhino object; return
        // it as itself so JS sees the same identity and reaches it through Rhino directly
        // -- no per-read copy, no JavaMember proxy -- and mutations (arr.push, p.x = ...)
        // persist as QML var semantics require.
        if (v instanceof Scriptable) return v;
        // A Java-produced list (a model, a Java API result) becomes a real JS Array so
        // Array.isArray / .map / .indexOf work, not a JavaMember proxy.
        if (v instanceof List) {
            Scriptable arr = toNativeArray((List<?>) v, scope);
            if (arr != null) return arr;
        }
        return new JavaMember(v, scope);
    }

    private static Scriptable toNativeArray(List<?> list, Scriptable scope) {
        Context cx = Context.getCurrentContext();
        if (cx == null) return null;
        Object[] elems = new Object[list.size()];
        for (int i = 0; i < elems.length; i++) elems[i] = toJs(list.get(i), scope);
        return cx.newArray(scope, elems);
    }

    private static Function callableToFunction(Callable c) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object[] ja = new Object[args == null ? 0 : args.length];
                for (int i = 0; i < ja.length; i++) ja[i] = toJava(args[i]);
                return toJs(c.call(ja), scope);
            }
        };
    }

    private static boolean isColorHex(String s) {
        int n = s.length();
        if (n == 0 || s.charAt(0) != '#') return false;
        int hex = n - 1;
        if (hex != 3 && hex != 4 && hex != 6 && hex != 8) return false;
        for (int i = 1; i < n; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    public static Object toJava(Object v) {
        if (v == null || v == Undefined.instance) return null;
        if (v instanceof Wrapper) return ((Wrapper) v).unwrap();
        if (v instanceof CharSequence) return v.toString();
        if (v instanceof Boolean) return v;
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)
                    && Math.abs(d) < 9.007199254740992e15) {
                return (long) d;
            }
            return d;
        }
        // Rhino 1.9 represents small integers as java.lang.Integer; the engine's
        // canonical integer type is Long, so widen every integral Number to it.
        if (v instanceof Number) return ((Number) v).longValue();
        // A JS array/object assigned to a `var` property stays the live Rhino object:
        // NativeArray is a java.util.List and NativeObject is a java.util.Map, so Java
        // consumers (Repeater/ListView models, MemberAccess) still see collections, while
        // reads round-trip without a deep copy and in-place mutation persists. Element
        // values stay JS-typed; that is fine -- they convert at their own use boundary.
        if (v instanceof NativeArray || v instanceof NativeObject) {
            return v;
        }
        // A JS function escaping into Java (e.g. `property var fn: x => x * 2`) becomes a
        // Callable so QML/Java callers can invoke it like any other function value.
        if (v instanceof Function) {
            return new RhinoFunctionValue((Function) v);
        }
        return v;
    }

    // A Rhino function value held on the Java side, invokable through io.github.timer_err.qml4j's Callable.
    // Each call re-enters a Rhino context and runs the function against its captured scope.
    static final class RhinoFunctionValue implements Callable, Wrapper {
        private final Function fn;

        RhinoFunctionValue(Function fn) { this.fn = fn; }

        @Override public Object unwrap() { return fn; }

        @Override public Object call(Object[] args) {
            Scriptable home = fn.getParentScope();
            Context cx = JsRuntime.enter(home);
            try {
                Object[] ja = new Object[args == null ? 0 : args.length];
                for (int i = 0; i < ja.length; i++) ja[i] = toJs(args[i], home);
                return toJava(fn.call(cx, home, home, ja));
            } finally {
                JsRuntime.exit();
            }
        }
    }

    // A Rhino view over an arbitrary Java object, delegating member/index reads and
    // writes to MemberAccess (same semantics as the ASM backend's readMember etc).
    static final class JavaMember implements Scriptable, SymbolScriptable, Wrapper {
        private final Object target;
        private final Scriptable parent;

        JavaMember(Object target, Scriptable parent) {
            this.target = target;
            this.parent = parent;
        }

        // So a binding that yields a wrapped Java object (e.g. `parent: foo`) unwraps
        // back to the real object on the way out, not the JavaMember view.
        @Override public Object unwrap() { return target; }

        @Override public Object get(String name, Scriptable start) {
            if (resolves(name)) {
                Object v = MemberAccess.readMember(target, name);
                // A signal member supports both forms QML uses: a direct emit
                // (`control.clicked()`) and an explicit `.emit(...)` / `.connect(...)`.
                if (v instanceof Signal) return new SignalRef((Signal) v, parent);
                return toJs(v, parent);
            }
            if (isCallable(target, name, parent)) {
                return new BoundMethod(target, name, parent);
            }
            return NOT_FOUND;
        }

        @Override public Object[] getIds() {
            if (target instanceof Map) return ((Map<?, ?>) target).keySet().toArray();
            if (target instanceof List) {
                int n = ((List<?>) target).size();
                Object[] ids = new Object[n];
                for (int i = 0; i < n; i++) ids[i] = i;
                return ids;
            }
            return new Object[0];
        }

        @Override public Object get(int index, Scriptable start) {
            return toJs(MemberAccess.readIndex(target, (long) index), parent);
        }

        @Override public void put(String name, Scriptable start, Object value) {
            MemberAccess.writeMember(target, name, toJava(value));
        }

        @Override public boolean has(String name, Scriptable start) {
            return resolves(name) || isCallable(target, name, parent);
        }

        @Override public boolean has(int index, Scriptable start) { return true; }

        // Spread / for-of over a wrapped Java iterable: expose Symbol.iterator as a JS
        // iterator backed by the Java Iterator, wrapping each element through toJs (so a
        // nested map/object keeps the same JavaMember view). Member and index access still
        // route through us unchanged.
        @Override public Object get(Symbol key, Scriptable start) {
            if (key == SymbolKey.ITERATOR && target instanceof Iterable) {
                return new BaseFunction() {
                    @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                        Iterator<?> it = ((Iterable<?>) target).iterator();
                        NativeObject iter = new NativeObject();
                        iter.put("next", iter, new BaseFunction() {
                            @Override public Object call(Context c, Scriptable s, Scriptable t, Object[] a) {
                                NativeObject r = new NativeObject();
                                boolean more = it.hasNext();
                                r.put("value", r, more ? toJs(it.next(), parent) : Undefined.instance);
                                r.put("done", r, !more);
                                return r;
                            }
                        });
                        return iter;
                    }
                };
            }
            return NOT_FOUND;
        }

        @Override public boolean has(Symbol key, Scriptable start) {
            return key == SymbolKey.ITERATOR && target instanceof Iterable;
        }

        @Override public void put(Symbol key, Scriptable start, Object value) {}
        @Override public void delete(Symbol key) {}

        // Whether `name` reads as a data member -- a map key, or a reflective/virtual
        // member MemberAccess.readMember resolves. Map keys are not reflective
        // fields, so they must be checked against the map directly or for-in / `in`
        // would miss them.
        private boolean resolves(String name) {
            if (target instanceof Map) return ((Map<?, ?>) target).containsKey(name);
            return MemberAccess.hasMember(target, name) || isVirtual(name);
        }

        // length/r/g/b/a aren't reflective fields but MemberAccess.readMember resolves them.
        private boolean isVirtual(String name) {
            return "length".equals(name) || (name.length() == 1 && "rgba".contains(name));
        }

        @Override public String getClassName() { return "JavaMember"; }
        @Override public Object getDefaultValue(Class<?> hint) { return target.toString(); }
        @Override public Scriptable getParentScope() { return parent; }
        @Override public void setParentScope(Scriptable s) {}
        // Expose the standard Object prototype so generic JS methods QML uses on objects
        // -- hasOwnProperty, toString, isPrototypeOf -- resolve. hasOwnProperty in turn
        // calls back through has(name), which already reflects our members/map keys.
        @Override public Scriptable getPrototype() {
            return parent != null ? ScriptableObject.getObjectPrototype(parent) : null;
        }
        @Override public void setPrototype(Scriptable s) {}
        @Override public void put(int index, Scriptable start, Object value) {}
        @Override public void delete(String name) {}
        @Override public void delete(int index) {}
        @Override public boolean hasInstance(Scriptable instance) { return false; }
    }

    // A color value ("#rrggbb") exposed to JS: `.r/.g/.b/.a` resolve the channel (0..1),
    // and every other use coerces to the hex string (getDefaultValue / unwrap), so a color
    // read from a property both exposes channels and assigns back as a plain color string.
    static final class JsColor implements Scriptable, Wrapper {
        private final String hex;
        private Scriptable parent;

        JsColor(String hex, Scriptable parent) { this.hex = hex; this.parent = parent; }

        @Override public Object unwrap() { return hex; }

        @Override public Object get(String name, Scriptable start) {
            if (name.length() == 1 && "rgba".contains(name)) {
                return MemberAccess.readMember(hex, name);
            }
            // A color-hex value is ALSO a string: fall back to String semantics for any
            // non-channel member (charAt/substring/indexOf/length/...), so parsing a color-like
            // string doesn't silently lose every string method. String.prototype methods coerce
            // `this` via getDefaultValue -> hex, so they operate on the hex text.
            Scriptable s = stringScriptable(start);
            return s != null ? ScriptableObject.getProperty(s, name) : NOT_FOUND;
        }

        @Override public boolean has(String name, Scriptable start) {
            if (name.length() == 1 && "rgba".contains(name)) return true;
            Scriptable s = stringScriptable(start);
            return s != null && ScriptableObject.hasProperty(s, name);
        }

        // The hex text as a JS String object, so member/index lookups resolve String.prototype.
        private Scriptable stringScriptable(Scriptable start) {
            Context cx = Context.getCurrentContext();
            if (cx == null) return null;
            return ScriptRuntime.toObject(cx, parent != null ? parent : start, hex);
        }

        @Override public Object getDefaultValue(Class<?> hint) { return hex; }
        @Override public String getClassName() { return "JsColor"; }
        @Override public Object get(int index, Scriptable start) {
            Scriptable s = stringScriptable(start);
            return s != null ? ScriptableObject.getProperty(s, index) : NOT_FOUND;
        }
        @Override public boolean has(int index, Scriptable start) {
            Scriptable s = stringScriptable(start);
            return s != null && ScriptableObject.hasProperty(s, index);
        }
        @Override public void put(String name, Scriptable start, Object value) {}
        @Override public void put(int index, Scriptable start, Object value) {}
        @Override public void delete(String name) {}
        @Override public void delete(int index) {}
        @Override public Scriptable getParentScope() { return parent; }
        @Override public void setParentScope(Scriptable s) { this.parent = s; }
        @Override public Scriptable getPrototype() { return null; }
        @Override public void setPrototype(Scriptable s) {}
        @Override public Object[] getIds() { return new Object[0]; }
        @Override public boolean hasInstance(Scriptable instance) { return false; }
    }

    // Whether `target.name` is invokable: a QML function on a QObject, or a public
    // Java method. Used by both the JavaMember member-access path and QmlScope's
    // bare-call resolution.
    static boolean isCallable(Object target, String name) {
        return isCallable(target, name, null);
    }

    static boolean isCallable(Object target, String name, Scriptable scope) {
        if (target == null) return false;
        if (scope != null && JsRuntime.isSafeScope(scope)) {
            if ("getClass".equals(name) || "wait".equals(name)
                    || "notify".equals(name) || "notifyAll".equals(name)
                    || "clone".equals(name) || "finalize".equals(name)) {
                return false;
            }
            String className = target.getClass().getName();
            boolean engineObject = target instanceof QObject
                    || className.startsWith("io.github.timer_err.qml4j.");
            if (!engineObject && (!(target instanceof QmlSafeBridge)
                    || !((QmlSafeBridge) target).allowsQmlMethod(name))) {
                return false;
            }
        }
        if (target instanceof QObject && ((QObject) target).__getFunction(name) != null) return true;
        return MethodInvocation.hasMethod(target.getClass(), name);
    }

    // A method reference obtained from a JavaMember (obj.method); calling it routes to
    // MethodInvocation.callMethod, which dispatches to a Java method or a QML function.
    static final class BoundMethod extends BaseFunction {
        private final Object target;
        private final String name;
        private final Scriptable parent;

        BoundMethod(Object target, String name, Scriptable parent) {
            this.target = target;
            this.name = name;
            this.parent = parent;
        }

        @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            Object[] ja = new Object[args.length];
            for (int i = 0; i < args.length; i++) ja[i] = toJava(args[i]);
            return toJs(MethodInvocation.callMethod(target, name, ja), parent);
        }
    }

    // The ListView/GridView attached object seen inside a delegate: `.view` is the hosting
    // view, every other member (Center, isCurrentItem, ...) falls through to the type's enum
    // namespace so `ListView.Center` still resolves.
    static Scriptable viewAttached(Object host, Scriptable enumObj, Scriptable parent) {
        return new BaseFunction() {
            @Override public Object get(String name, Scriptable start) {
                if ("view".equals(name)) return toJs(host, parent);
                return enumObj != null ? enumObj.get(name, enumObj) : NOT_FOUND;
            }
            @Override public boolean has(String name, Scriptable start) {
                return "view".equals(name) || (enumObj != null && enumObj.has(name, enumObj));
            }
        };
    }

    // A no-arg JS function that emits Qt's implicit <prop>Changed() signal: calling it
    // re-fires the property's dependents (bindings/onXChanged handlers) without altering
    // the value, matching a manual `propChanged()` emit in QML.
    static Function changedNotifier(Property<?> p) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                p.notifyChanged();
                return Undefined.instance;
            }
        };
    }

    // A QML signal exposed to JS so both forms work: calling it emits
    // (`control.clicked(args)`), and its `.emit`/`.connect`/... members resolve to the
    // Signal's Java methods (`r.pinged.emit(args)`).
    static final class SignalRef extends BaseFunction implements Wrapper {
        private final Signal signal;
        private final Scriptable parent;

        SignalRef(Signal signal, Scriptable parent) {
            this.signal = signal;
            this.parent = parent;
        }

        @Override public Object unwrap() { return signal; }

        @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
            Object[] ja = new Object[args.length];
            for (int i = 0; i < args.length; i++) ja[i] = toJava(args[i]);
            signal.emit(ja);
            return Undefined.instance;
        }

        @Override public Object get(String name, Scriptable start) {
            if (isCallable(signal, name, parent)) return new BoundMethod(signal, name, parent);
            return super.get(name, start);
        }

        @Override public boolean has(String name, Scriptable start) {
            return isCallable(signal, name, parent) || super.has(name, start);
        }
    }
}
