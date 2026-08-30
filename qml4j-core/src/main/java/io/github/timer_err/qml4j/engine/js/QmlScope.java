package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.runtime.member.DelegateScope;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import io.github.timer_err.qml4j.engine.Signal;
import io.github.timer_err.qml4j.engine.binding.Property;
import org.mozilla.javascript.Scriptable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// The top scope a binding's JS runs in. Bare identifiers resolve against the QML
// scope: first the this-object's members, then the component root's (ids + declared
// props). Member reads route through MemberAccess.readMember, so Property reads
// register the reactive dependency just like the ASM backend. Names not found here
// return NOT_FOUND, so Rhino falls through to the shared globals (Math, etc.) on the
// parent scope.
//
// First-cut scope: ids + this/root properties only. Singletons, Qt, enums, signal
// params and JS locals come in later migration phases (the compiler's canHandle
// predicate keeps those off this path for now).
public final class QmlScope implements Scriptable {

    private final Object outer;
    private final Object root;
    private final Set<String> sceneIds;
    private final boolean delegate;
    // The singletons this binding's JS may reference, keyed by name -> the SPECIFIC
    // generated class (threaded per-binding from the compiler, never a global name
    // lookup, so two components' same-named singletons never collide). Instances are
    // materialised lazily via __instance().
    private final Map<String, Class<?>> singletonClasses;
    private Map<String, Object> singletonInstances;
    private Scriptable parent;
    private Scriptable prototype;

    // Property aliases in scope, name -> [targetId, targetProperty]; an alias expands to
    // a read/write of the target id's property (ExpressionCodegen expands `x` to
    // `targetId.targetProperty`). Threaded per-binding, empty in delegate scope.
    private final Map<String, String[]> aliases;

    @SuppressWarnings("unused")
    public QmlScope(Object outer, Object root, Scriptable globals, Set<String> sceneIds,
                    boolean delegate, Map<String, Class<?>> singletonClasses,
                    Map<String, String[]> aliases) {
        this.outer = outer;
        this.root = root;
        this.sceneIds = sceneIds;
        this.delegate = delegate;
        this.singletonClasses = singletonClasses;
        this.aliases = aliases;
        this.parent = globals;
    }

    // Build the name -> class map from the parallel arrays the compiler pushes.
    public static Map<String, Class<?>> singletonMap(String[] names, Class<?>[] classes) {
        if (names.length == 0) return Collections.emptyMap();
        Map<String, Class<?>> m = new HashMap<>();
        for (int i = 0; i < names.length; i++) m.put(names[i], classes[i]);
        return m;
    }

    // Build the alias map from specs encoded as "name\u0000targetId\u0000targetProperty".
    public static Map<String, String[]> aliasMap(String[] specs) {
        if (specs.length == 0) return Collections.emptyMap();
        Map<String, String[]> m = new HashMap<>();
        for (String s : specs) {
            String[] parts = s.split("\u0000");
            m.put(parts[0], new String[]{parts[1], parts[2]});
        }
        return m;
    }

    // The object an alias's target id resolves to, then its target property. The id read
    // is a plain reference (no dependency); the property read records the dependency.
    private Object aliasValue(String name) {
        String[] t = aliases.get(name);
        Object o = owner(t[0]);
        if (o == null) return null;
        Object target = MemberAccess.readMember(o, t[0]);
        if (target == null) return null;
        return MemberAccess.readMember(target, t[1]);
    }

    private Object singleton(String name) {
        Class<?> c = singletonClasses.get(name);
        if (c == null) return null;
        if (singletonInstances == null) singletonInstances = new HashMap<>();
        Object inst = singletonInstances.get(name);
        if (inst != null) return inst;
        try {
            inst = c.getMethod("__instance").invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate singleton " + name, e);
        }
        singletonInstances.put(name, inst);
        return inst;
    }

    private Object wrap(Object v) {
        // A signal read as a bare identifier supports both a direct emit (`clicked(i)`)
        // and an explicit `.emit(...)` / `.connect(...)`.
        if (v instanceof Signal) return new JsWrap.SignalRef((Signal) v, this);
        return JsWrap.toJs(v, this);
    }

    // Delegate-scope resolution mirrors ExpressionCodegen's delegate path: the binding's
    // own item first, then DelegateScope.delegateLookup (the DelegateRoot's index/
    // modelData/local ids, then the enclosing scene). An absent name falls through to
    // the globals, and an unresolved reference yields undefined -- QML-tolerant.
    private Object getDelegate(String name) {
        // Own *property* only -- an own id field (a compound child's leaked `id: root`)
        // must not shadow the enclosing component's id; that resolves via delegateLookup.
        if (MemberAccess.hasProperty(outer, name)) {
            return wrap(MemberAccess.readMember(outer, name));
        }
        Object v = DelegateScope.delegateLookup(outer, name);
        if (v != DelegateScope.DELEGATE_ABSENT) return wrap(v);
        // Scene ids are lexical: resolve them off the captured component root even when
        // the runtime parent chain can't reach it. A reparented subtree (the MD3 popup
        // reparents its overlay onto the scene root to render on top) detaches the
        // delegate items from the enclosing component, so delegateLookup's parent walk
        // never reaches the component root -- but the id still names that root.
        if (sceneIds.contains(name)) return wrap(MemberAccess.readMember(root, name));
        // A `property` declared on the enclosing component root, read from a delegate
        // whose parent walk can't reach that root -- an anonymous `Component {}` /
        // Repeater delegate, or a reparented popup overlay (Menu/DatePicker pop onto
        // the scene root). sceneIds only carries the root's *ids*, not its property
        // declarations, so `_colors.x` there would otherwise read as undefined. Mirrors
        // the non-delegate owner() path's hasMember(root) fallback; guarded so it only
        // fires when root genuinely owns the name (else fall through to globals).
        if (root != null && root != outer && MemberAccess.hasMember(root, name)) {
            return wrap(MemberAccess.readMember(root, name));
        }
        Object co = DelegateScope.delegateCallableOwner(outer, name);
        if (co != null) return new JsWrap.BoundMethod(co, name, this);
        // A free function call (`isSameDay(...)`) is lexical like a scene id: fall back to
        // the captured component root when the parent walk can't reach it -- a reparented
        // overlay (DatePicker/Menu pop onto the scene root) detaches the delegate from the
        // component, so the parent chain no longer finds the root's functions.
        if (root != null && JsWrap.isCallable(root, name, this)) {
            return new JsWrap.BoundMethod(root, name, this);
        }
        Object s = singleton(name);
        if (s != null) return JsWrap.toJs(s, this);
        Object ch = changedSignal(name);
        if (ch != null) return ch;
        Object va = viewAttached(name);
        if (va != null) return va;
        return NOT_FOUND;
    }

    // ListView.view / GridView.view inside a delegate: resolve to the hosting view, with
    // other members (Center, ...) falling through to the type's enum namespace.
    private Object viewAttached(String name) {
        if (!"ListView".equals(name) && !"GridView".equals(name)) return null;
        Object host = DelegateScope.hostView(outer);
        if (host == null) return null;
        Object e = parent != null ? parent.get(name, parent) : NOT_FOUND;
        Scriptable enumObj = e instanceof Scriptable ? (Scriptable) e : null;
        return JsWrap.viewAttached(host, enumObj, this);
    }

    // Qt's implicit per-property <prop>Changed() emit, resolved as a bare callable last
    // -- a real signal/field/function of the same name always wins above. Returns a
    // notifier bound to the property on outer (then root), or null if `name` is not an
    // <prop>Changed for any property in scope.
    private Object changedSignal(String name) {
        if (!name.endsWith("Changed") || name.length() == "Changed".length()) return null;
        String base = name.substring(0, name.length() - "Changed".length());
        Property<?> p = MemberAccess.propertyOf(outer, base);
        if (p == null && root != outer) p = MemberAccess.propertyOf(root, base);
        return p == null ? null : JsWrap.changedNotifier(p);
    }

    private Object owner(String name) {
        // Scene ids are lexically scoped to the enclosing component and live as fields
        // on its root, matching ExpressionCodegen.emitIdentifier's idTypes step. They
        // must win over outer's reflective members: a compound child (e.g. an MD3 Card
        // whose own `id: root` leaks as a field) would otherwise shadow the enclosing
        // component's same-named id, turning `root.width` into a self-reference.
        if (sceneIds.contains(name)) return root;
        if (MemberAccess.hasMember(outer, name)) return outer;
        if (root != outer && MemberAccess.hasMember(root, name)) return root;
        return null;
    }

    // A bare callable identifier resolves to a root/outer QML function or method
    // (handler bodies invoke them directly: `foo()`, not `this.foo()`).
    private Object callableOwner(String name) {
        if (JsWrap.isCallable(outer, name, this)) return outer;
        if (root != outer && JsWrap.isCallable(root, name, this)) return root;
        return null;
    }

    @Override public Object get(String name, Scriptable start) {
        if (delegate) return getDelegate(name);
        if (aliases.containsKey(name)) return wrap(aliasValue(name));
        Object o = owner(name);
        if (o != null) return wrap(MemberAccess.readMember(o, name));
        Object c = callableOwner(name);
        if (c != null) return new JsWrap.BoundMethod(c, name, this);
        Object s = singleton(name);
        if (s != null) return JsWrap.toJs(s, this);
        Object ch = changedSignal(name);
        if (ch != null) return ch;
        return NOT_FOUND;
    }

    @Override public boolean has(String name, Scriptable start) {
        if (delegate) {
            return MemberAccess.hasProperty(outer, name)
                || DelegateScope.delegateLookup(outer, name) != DelegateScope.DELEGATE_ABSENT
                || sceneIds.contains(name)
                || (root != null && root != outer && MemberAccess.hasMember(root, name))
                || DelegateScope.delegateCallableOwner(outer, name) != null
                || (root != null && JsWrap.isCallable(root, name, this))
                || singletonClasses.containsKey(name)
                || changedSignal(name) != null;
        }
        return aliases.containsKey(name) || owner(name) != null || callableOwner(name) != null
            || singletonClasses.containsKey(name) || changedSignal(name) != null;
    }

    @Override public void put(String name, Scriptable start, Object value) {
        if (delegate) {
            if (MemberAccess.hasProperty(outer, name)) {
                MemberAccess.writeMember(outer, name, JsWrap.toJava(value));
                return;
            }
            // A bare-identifier write must reach the enclosing scope the read came from
            // (delegateLookup), not vanish: `isRail = !isRail` in a Loader/Repeater
            // header writes the enclosing component's property.
            Object o = DelegateScope.delegateOwner(outer, name);
            if (o != null) MemberAccess.writeMember(o, name, JsWrap.toJava(value));
            return;
        }
        if (aliases.containsKey(name)) {
            String[] t = aliases.get(name);
            Object o = owner(t[0]);
            if (o != null) {
                Object target = MemberAccess.readMember(o, t[0]);
                if (target != null) MemberAccess.writeMember(target, t[1], JsWrap.toJava(value));
            }
            return;
        }
        Object o = owner(name);
        if (o != null) MemberAccess.writeMember(o, name, JsWrap.toJava(value));
    }

    @Override public Object get(int index, Scriptable start) { return NOT_FOUND; }
    @Override public boolean has(int index, Scriptable start) { return false; }
    @Override public void put(int index, Scriptable start, Object value) {}
    @Override public void delete(String name) {}
    @Override public void delete(int index) {}

    @Override public String getClassName() { return "QmlScope"; }
    @Override public Object getDefaultValue(Class<?> hint) { return toString(); }
    @Override public Scriptable getParentScope() { return parent; }
    @Override public void setParentScope(Scriptable s) { this.parent = s; }
    @Override public Scriptable getPrototype() { return prototype; }
    @Override public void setPrototype(Scriptable s) { this.prototype = s; }
    @Override public Object[] getIds() { return new Object[0]; }
    @Override public boolean hasInstance(Scriptable instance) { return false; }
}
