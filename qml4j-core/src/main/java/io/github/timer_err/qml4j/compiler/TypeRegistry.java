package io.github.timer_err.qml4j.compiler;

import io.github.timer_err.qml4j.engine.QObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class TypeRegistry {

    public interface TypeResolver {
        Class<? extends QObject> resolve(String qmlName);
    }

    private final Map<String, Class<? extends QObject>> types = new HashMap<>();
    private final Map<String, Class<? extends QObject>> singletons = new HashMap<>();
    private TypeResolver resolver;
    private Set<String> aliases = Collections.emptySet();
    // Names a doc's imported modules explicitly export (qmldir entries). These
    // shadow built-in stock types of the same name (e.g. md3.Core's Button vs the
    // stock Button), matching QML: an explicit module import wins over built-ins.
    private Set<String> moduleProvided = Collections.emptySet();

    public TypeRegistry register(String qmlName, Class<? extends QObject> klass) {
        types.put(qmlName, klass);
        return this;
    }

    public TypeRegistry registerSingleton(String qmlName, Class<? extends QObject> klass) {
        types.put(qmlName, klass);
        singletons.put(qmlName, klass);
        return this;
    }

    public TypeRegistry unregister(String qmlName) {
        types.remove(qmlName);
        singletons.remove(qmlName);
        return this;
    }

    public Class<? extends QObject> singletonClass(String qmlName) {
        return singletons.get(qmlName);
    }

    public boolean isSingleton(String qmlName) {
        return singletons.containsKey(qmlName);
    }

    public TypeRegistry copy() {
        TypeRegistry t = new TypeRegistry();
        t.types.putAll(this.types);
        t.singletons.putAll(this.singletons);
        return t;
    }

    public TypeRegistry withResolver(TypeResolver r) {
        this.resolver = r;
        return this;
    }

    public TypeRegistry withAliases(Set<String> aliases) {
        this.aliases = aliases == null ? Collections.emptySet() : aliases;
        return this;
    }

    public TypeRegistry withModuleProvided(Set<String> names) {
        this.moduleProvided = names == null ? Collections.emptySet() : names;
        return this;
    }

    public Class<? extends QObject> resolve(String qmlName) {
        Class<? extends QObject> c = lookup(qmlName);
        if (c != null) return c;
        if (!aliases.isEmpty()) {
            int dot = qmlName.indexOf('.');
            if (dot > 0 && aliases.contains(qmlName.substring(0, dot))) {
                c = lookup(qmlName.substring(dot + 1));
                if (c != null) return c;
            }
        }
        throw new IllegalArgumentException("unknown QML type: " + qmlName);
    }

    private Class<? extends QObject> lookup(String qmlName) {
        // An explicitly imported module type shadows a same-named stock type.
        if (moduleProvided.contains(qmlName) && resolver != null) {
            Class<? extends QObject> rc = resolver.resolve(qmlName);
            if (rc != null) { types.put(qmlName, rc); return rc; }
        }
        Class<? extends QObject> c = types.get(qmlName);
        if (c != null) return c;
        if (resolver != null) {
            c = resolver.resolve(qmlName);
            if (c != null) {
                types.put(qmlName, c);
                return c;
            }
        }
        return null;
    }

    public boolean isRegistered(String qmlName) {
        return types.containsKey(qmlName);
    }
}
