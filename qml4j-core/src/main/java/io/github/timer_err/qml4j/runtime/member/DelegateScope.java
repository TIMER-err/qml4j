package io.github.timer_err.qml4j.runtime.member;

import io.github.timer_err.qml4j.engine.DelegateHost;
import io.github.timer_err.qml4j.engine.DelegateRoot;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.runtime.invoke.MethodInvocation;

import java.lang.reflect.Field;
import java.util.Map;

// Delegate-scope name resolution shared by the Rhino QmlScope: walk to the
// DelegateRoot for index/modelData/delegate-local ids, then above it to the
// enclosing scene for outer-scope members.
public final class DelegateScope {

    private DelegateScope() {}

    // Sentinel for delegateLookup when a name is nowhere in the delegate's chain.
    public static final Object DELEGATE_ABSENT = new Object();

    // The first object up the delegate's parent chain on which `name` is callable -- a
    // QML function or a Java method. Mirrors the lookup walk so a bare call `foo()` in a
    // delegate resolves to a delegate-local or enclosing function. Null if none.
    public static Object delegateCallableOwner(Object start, String name) {
        Object cur = start;
        while (cur != null) {
            if (isCallableMember(cur, name)) return cur;
            cur = parentOf(cur);
        }
        return null;
    }

    private static boolean isCallableMember(Object o, String name) {
        if (o instanceof QObject && ((QObject) o).__getFunction(name) != null) return true;
        // Cached per class: this runs for every free identifier in every binding, at
        // every level of the delegate's parent chain. An inline getMethods() scan
        // re-allocated (and, on Android, re-sorted and de-duplicated) the whole method
        // array per probe -- profiling a NumberPicker drag put 59% of all CPU here.
        return MethodInvocation.hasMethod(o.getClass(), name);
    }

    // The object up the delegate's parent chain that owns settable property `name`:
    // the delegate's DelegateRoot, then the enclosing scene. Mirrors delegateLookup so a
    // free-identifier WRITE lands on the same object a READ resolves to -- Qt's
    // `isRail = !isRail` in a Loader/Repeater-scoped handler writes the enclosing
    // component's property. Null if no ancestor declares it. Properties only: a write
    // never targets a method/id.
    public static Object delegateOwner(Object start, String name) {
        Object cur = start;
        Object delegateRoot = null;
        while (cur != null) {
            if (cur instanceof DelegateRoot) {
                delegateRoot = cur;
                if (MemberAccess.hasProperty(cur, name)) return cur;
                break;
            }
            cur = parentOf(cur);
        }
        Object outer = parentOf(delegateRoot != null ? delegateRoot : start);
        while (outer != null) {
            if (MemberAccess.hasProperty(outer, name)) return outer;
            outer = parentOf(outer);
        }
        return null;
    }

    // The view hosting this delegate: the nearest DelegateHost up the parent chain
    // (ListView/GridView/Repeater). Backs the `ListView.view`/`GridView.view` attached
    // property a delegate uses to size to its view (`width: ListView.view.width`).
    public static Object hostView(Object start) {
        for (Object cur = start; cur != null; cur = parentOf(cur)) {
            if (cur instanceof DelegateHost) return cur;
        }
        return null;
    }

    public static Object delegateLookup(Object start, String name) {
        Object cur = start;
        Object delegateRoot = null;
        while (cur != null) {
            if (cur instanceof DelegateRoot) {
                delegateRoot = cur;
                // `model` in a delegate is the row's model object (Qt), shadowing any `model`
                // property further up the chain (a ListView's own `model`): return modelData.
                if ("model".equals(name) && MemberAccess.hasMember(cur, "modelData")) {
                    return MemberAccess.readMember(cur, "modelData");
                }
                if (MemberAccess.hasMember(cur, name)) return MemberAccess.readMember(cur, name);
                // A ListModel/JS-object row exposes its roles by bare name in the delegate
                // (Qt's named-role context): `text: name` reads modelData["name"].
                Object role = roleFromModelData(cur, name);
                if (role != DELEGATE_ABSENT) return role;
                break;
            }
            cur = parentOf(cur);
        }
        Object outer = parentOf(delegateRoot != null ? delegateRoot : start);
        while (outer != null) {
            if (MemberAccess.hasMember(outer, name)) return MemberAccess.readMember(outer, name);
            outer = parentOf(outer);
        }
        return DELEGATE_ABSENT;
    }

    // A named model role read off the delegate root's modelData when it is a keyed row
    // (a ListModel ListElement or a JS object), or DELEGATE_ABSENT if modelData is not a
    // map or has no such key. A plain-value modelData (number/string) has no roles.
    private static Object roleFromModelData(Object delegateRoot, String name) {
        if (!MemberAccess.hasMember(delegateRoot, "modelData")) return DELEGATE_ABSENT;
        Object data = MemberAccess.readMember(delegateRoot, "modelData");
        if (data instanceof Map && ((Map<?, ?>) data).containsKey(name)) {
            return ((Map<?, ?>) data).get(name);
        }
        return DELEGATE_ABSENT;
    }

    private static Object parentOf(Object node) {
        if (node == null) return null;
        Field f;
        try {
            f = node.getClass().getField("parent");
        } catch (NoSuchFieldException e) {
            return null;
        }
        Object v;
        try {
            v = f.get(node);
        } catch (IllegalAccessException e) {
            return null;
        }
        if (v instanceof Property) return ((Property<?>) v).peek();
        return v;
    }
}
