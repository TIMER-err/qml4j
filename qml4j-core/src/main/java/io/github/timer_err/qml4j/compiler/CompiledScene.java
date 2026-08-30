package io.github.timer_err.qml4j.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete generated representation of one root QML scene. Hosts may persist this
 * through {@link CompiledSceneCache}; the cache key remains host-owned because only
 * the host can fingerprint its complete resource bundle reliably.
 */
public final class CompiledScene {

    /** Bump when persisted fields or their runtime meaning changes. */
    public static final int FORMAT_VERSION = 1;

    private final String rootClassName;
    private final Map<String, byte[]> classes;
    private final Map<String, String> importedTypes;
    private final Map<String, Map<String, String>> singletons;
    private final List<JsImport> jsImports;

    public CompiledScene(String rootClassName,
                         Map<String, byte[]> classes,
                         Map<String, String> importedTypes,
                         Map<String, Map<String, String>> singletons,
                         List<JsImport> jsImports) {
        this.rootClassName = rootClassName;
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(classes));
        this.importedTypes = Collections.unmodifiableMap(new LinkedHashMap<>(importedTypes));
        Map<String, Map<String, String>> singletonCopy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : singletons.entrySet()) {
            singletonCopy.put(entry.getKey(),
                Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        this.singletons = Collections.unmodifiableMap(singletonCopy);
        this.jsImports = Collections.unmodifiableList(new ArrayList<>(jsImports));
    }

    public String rootClassName() {
        return rootClassName;
    }

    public Map<String, byte[]> classes() {
        return classes;
    }

    public Map<String, String> importedTypes() {
        return importedTypes;
    }

    public Map<String, Map<String, String>> singletons() {
        return singletons;
    }

    public List<JsImport> jsImports() {
        return jsImports;
    }

    public static final class JsImport {
        private final String alias;
        private final String path;

        public JsImport(String alias, String path) {
            this.alias = alias;
            this.path = path;
        }

        public String alias() {
            return alias;
        }

        public String path() {
            return path;
        }
    }
}
