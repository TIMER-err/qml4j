package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.compiler.CompiledUnit;
import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;
import io.github.timer_err.qml4j.compiler.TypeRegistry;
import io.github.timer_err.qml4j.compiler.bytecode.CompileScope;
import io.github.timer_err.qml4j.compiler.bytecode.QmlCompiler;
import io.github.timer_err.qml4j.compiler.bytecode.rhino.RhinoScope;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;
import io.github.timer_err.qml4j.engine.js.JsRuntime;
import io.github.timer_err.qml4j.parser.Qml4j;
import io.github.timer_err.qml4j.parser.ast.Ast;
import io.github.timer_err.qml4j.render.items.core.Item;

import org.mozilla.javascript.Scriptable;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Compiles a QML document to bytecode and instantiates its root object,
// resolving compound (file-based) types and singletons across imported modules.
final class Loader implements ComponentFactory {

    private final QmlEngine engine;
    private final TypeRegistry types;
    private final QmlCompiler compiler = new QmlCompiler();
    // Keyed by resolved path (prefix/file), not bare type name: two modules may
    // each export a `Theme`, and they must not collide in the cache.
    private final Map<String, Class<? extends QObject>> importedTypes = new HashMap<>();
    // prefix -> (type name -> singleton class). Singletons are scoped to the
    // import that brought them in, so a doc only sees its own modules' Themes.
    private final Map<String, Map<String, Class<? extends QObject>>> singletonsByPrefix = new HashMap<>();
    private final Map<String, Map<String, QmldirEntry>> qmldirCache = new HashMap<>();
    private final Set<String> compilingNow = new HashSet<>();
    private ResourceLoader resources;
    private CompiledSceneCache compilationCache;
    private String compilationCacheKey;
    private boolean initialLoad = true;
    private CompilationRecorder recorder;
    // Fired as each compound (file-based) component is compiled+defined during
    // load, so a host can drive a splash/progress UI; compiling+dexing the QML
    // tree on first launch is the slow part on Android.
    private QmlView.CompileProgressListener progress;
    private int compiledCount;

    Loader(QmlEngine engine, TypeRegistry types) {
        this.engine = engine;
        this.types = types;
    }

    void setResources(ResourceLoader resources) {
        this.resources = resources;
    }

    void setProgressListener(QmlView.CompileProgressListener l) {
        this.progress = l;
    }

    void setCompilationCache(CompiledSceneCache cache, String key) {
        this.compilationCache = cache;
        this.compilationCacheKey = key;
    }

    @SuppressWarnings("unused")
    Item instantiate(String qml) {
        return instantiate(qml, "");
    }

    // baseDir is the importing document's directory (relative to the resource root);
    // relative file imports (import "../widgets") resolve against it.
    Item instantiate(String qml, String baseDir) {
        try (JsRuntime.Activation ignored = JsRuntime.activate(engine.jsRealm())) {
            if (initialLoad) {
                initialLoad = false;
                return instantiateInitial(qml, baseDir);
            }
            return compileRoot(qml, baseDir);
        }
    }

    private Item instantiateInitial(String qml, String baseDir) {
        if (compilationCache == null || compilationCacheKey == null) {
            return compileRoot(qml, baseDir);
        }
        CompiledScene cached = compilationCache.load(compilationCacheKey);
        if (cached != null) return newRoot(restore(cached));
        recorder = new CompilationRecorder();
        try {
            Ast.QmlDocument doc = Qml4j.parse(qml);
            Class<? extends QObject> rootClass = compileAndDefine(doc, baseDir);
            compilationCache.store(compilationCacheKey, recorder.finish(rootClass));
            return newRoot(rootClass);
        } finally {
            recorder = null;
        }
    }

    private Item compileRoot(String qml, String baseDir) {
        Ast.QmlDocument doc = Qml4j.parse(qml);
        return newRoot(compileAndDefine(doc, baseDir));
    }

    private Class<? extends QObject> restore(CompiledScene scene) {
        for (CompiledScene.JsImport jsImport : scene.jsImports()) {
            evaluateJsImport(jsImport.alias(), jsImport.path());
        }
        Map<String, Class<?>> defined = engine.backend().defineClasses(scene.classes());
        restoreImportedTypes(scene, defined);
        restoreSingletons(scene, defined);
        return requireQObjectClass(defined, scene.rootClassName());
    }

    private void restoreImportedTypes(CompiledScene scene, Map<String, Class<?>> defined) {
        for (Map.Entry<String, String> entry : scene.importedTypes().entrySet()) {
            importedTypes.put(entry.getKey(), requireQObjectClass(defined, entry.getValue()));
        }
    }

    private void restoreSingletons(CompiledScene scene, Map<String, Class<?>> defined) {
        for (Map.Entry<String, Map<String, String>> prefix : scene.singletons().entrySet()) {
            Map<String, Class<? extends QObject>> restored = new HashMap<>();
            for (Map.Entry<String, String> entry : prefix.getValue().entrySet()) {
                restored.put(entry.getKey(), requireQObjectClass(defined, entry.getValue()));
            }
            singletonsByPrefix.put(prefix.getKey(), restored);
        }
    }

    private static Class<? extends QObject> requireQObjectClass(
            Map<String, Class<?>> defined, String className) {
        Class<?> type = defined.get(className);
        if (type == null) throw new IllegalStateException("compiled scene missing class " + className);
        if (!QObject.class.isAssignableFrom(type)) {
            throw new IllegalStateException("compiled scene class is not a QObject: " + className);
        }
        @SuppressWarnings("unchecked")
        Class<? extends QObject> qobjectType = (Class<? extends QObject>) type;
        return qobjectType;
    }

    @Override
    public Item create(String qml, String baseDir) {
        return instantiate(qml, baseDir);
    }

    // A Loader `source` (resource path). A relative path first resolves against the
    // declaring document's directory (Qt semantics — MD3 Menu recurses with
    // `source: "Menu.qml"` from md3/Core/), then falls back to the resource root for
    // legacy full paths. If the winning path is already compiled as a compound type
    // (its class is cached under that same path key), reuse it — recompiling the
    // file as a fresh document would mint a new class set the AOT capture never saw,
    // which native-image cannot define at runtime. Only an unregistered path falls back
    // to compiling the file (JVM-only territory).
    @Override
    public Item createFromSource(String sourcePath, String documentDir) {
        String resolved = relativeCandidate(sourcePath, documentDir);
        if (resolved != null) {
            Class<? extends QObject> viaDoc = importedTypes.get(resolved);
            if (viaDoc != null) return newRoot(viaDoc);
        }
        Class<? extends QObject> cached = importedTypes.get(sourcePath);
        if (cached != null) return newRoot(cached);
        if (resources == null) return null;
        String path = sourcePath;
        byte[] bytes = null;
        if (resolved != null) {
            bytes = resources.load(resolved);
            if (bytes != null) path = resolved;
        }
        if (bytes == null) bytes = resources.load(sourcePath);
        if (bytes == null) return null;
        int slash = path.lastIndexOf('/');
        return instantiate(new String(bytes, StandardCharsets.UTF_8),
                slash < 0 ? "" : path.substring(0, slash));
    }

    // The document-relative form of `sourcePath`, or null when that resolution is
    // impossible or a no-op (no declaring dir, absolute path, URL scheme) — the caller
    // then uses the resource-root lookup alone.
    private static String relativeCandidate(String sourcePath, String documentDir) {
        if (documentDir == null || documentDir.isEmpty()) return null;
        if (sourcePath.startsWith("/") || sourcePath.contains(":")) return null;
        String joined = joinPath(documentDir, sourcePath);
        return joined.equals(sourcePath) ? null : joined;
    }

    private Item newRoot(Class<? extends QObject> rootClass) {
        Object inst;
        try {
            inst = rootClass.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException ex) {
            // Unwrap: the opaque InvocationTargetException hides the real failure (its
            // getMessage() is null). Surface the actual cause — class, message AND chain —
            // so a QML root whose constructor throws (a bad binding, a missing context
            // object, a child type that fails to build) is diagnosable instead of "null".
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            throw new RuntimeException("QML root '" + rootClass.getName() + "' constructor threw "
                + cause.getClass().getName() + ": " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("QML root '" + rootClass.getName() + "' instantiation failed: "
                + ex.getClass().getName() + ": " + ex.getMessage(), ex);
        }
        if (!(inst instanceof Item)) {
            throw new IllegalArgumentException(
                "root QML type must extend Item, got " + inst.getClass().getName());
        }
        return (Item) inst;
    }

    private Class<? extends QObject> compileAndDefine(Ast.QmlDocument doc, String baseDir) {
        processJsImports(doc, baseDir);
        List<String> prefixes = stringImportPrefixes(doc, baseDir);
        Set<String> moduleProvided = new HashSet<>();
        for (String p : prefixes) moduleProvided.addAll(loadQmldir(p).keySet());
        TypeRegistry docTypes = types.copy()
            .withResolver(name -> resolveCompound(name, prefixes))
            .withAliases(importAliases(doc))
            .withModuleProvided(moduleProvided);
        registerKnownSingletons(docTypes, prefixes);
        defineInlineComponents(doc, docTypes, baseDir);
        CompiledUnit unit = compiler.compile(doc, docTypes, baseDir);
        return defineUnit(unit);
    }

    private Class<? extends QObject> defineUnit(CompiledUnit unit) {
        if (recorder != null) recorder.recordUnit(unit);
        ClassLoaderBackend backend = engine.backend();
        Map<String, Class<?>> defined = backend.defineClasses(unit.classes());
        return requireQObjectClass(defined, unit.rootClassName());
    }

    // `component Name: Base { ... }`: compile each inline component into its own class and
    // register it as a document-level type so any object can instantiate `Name { }`. They
    // are document-scoped in Qt regardless of where they nest (ColorPage declares them
    // inside a Flickable) and may reference the file's root-level ids/properties.
    private void defineInlineComponents(Ast.QmlDocument doc, TypeRegistry docTypes, String baseDir) {
        List<Ast.InlineComponent> inlines = new ArrayList<>();
        collectInlineDecls(doc.root, inlines);
        if (inlines.isEmpty()) return;
        // Pass 1: forward-declare each inline as its base type so collectIds can resolve
        // their usages while gathering the file's root-level scene ids/properties (visible
        // inside the inline components, resolved at runtime via the parent chain).
        for (Ast.InlineComponent ic : inlines) {
            docTypes.register(ic.name, docTypes.resolve(ic.body.typeName));
        }
        Map<String, Class<? extends QObject>> sceneIds = new LinkedHashMap<>();
        io.github.timer_err.qml4j.compiler.bytecode.ast.Ids.collectIds(doc.root, docTypes, sceneIds, false);
        for (Ast.ObjectMember m : doc.root.members) {
            if (m instanceof Ast.PropertyDeclaration) {
                sceneIds.putIfAbsent(((Ast.PropertyDeclaration) m).name, QObject.class);
            }
        }
        // Pass 2: compile each against the doc scope (+ those scene ids) and register the
        // real class; a later inline (SchemeColumn) may instantiate an earlier one.
        for (Ast.InlineComponent ic : inlines) {
            CompiledUnit unit = compiler.compile(
                new Ast.QmlDocument(doc.imports, ic.body), docTypes, sceneIds, baseDir);
            docTypes.register(ic.name, defineUnit(unit));
        }
    }

    private static void collectInlineDecls(Ast.ObjectNode obj, List<Ast.InlineComponent> out) {
        for (Ast.ObjectMember m : obj.members) {
            if (m instanceof Ast.InlineComponent) {
                out.add((Ast.InlineComponent) m);
            } else if (m instanceof Ast.ChildObject) {
                collectInlineDecls(((Ast.ChildObject) m).object, out);
            }
        }
    }

    private void registerKnownSingletons(TypeRegistry docTypes, List<String> prefixes) {
        for (String p : prefixes) {
            Map<String, Class<? extends QObject>> m = singletonsByPrefix.get(p);
            if (m == null) continue;
            for (Map.Entry<String, Class<? extends QObject>> e : m.entrySet()) {
                docTypes.registerSingleton(e.getKey(), e.getValue());
            }
        }
    }

    private Class<? extends QObject> resolveCompound(String name, List<String> prefixes) {
        if (resources == null) return null;
        for (String p : prefixes) {
            QmldirEntry entry = loadQmldir(p).get(name);
            String relFile = entry != null ? entry.file : name + ".qml";
            String path = p.isEmpty() ? relFile : p + "/" + relFile;
            Class<? extends QObject> cached = importedTypes.get(path);
            if (cached != null) {
                // Re-register a cached singleton into the doc currently compiling:
                // the first resolver registered it, but later docs hit the cache
                // and would otherwise not know it's a singleton.
                registerCachedSingleton(p, name, cached);
                return cached;
            }
            byte[] bytes = resources.load(path);
            if (bytes == null) continue;
            if (!compilingNow.add(path)) {
                throw new IllegalStateException("cyclic import: " + path);
            }
            try {
                Ast.QmlDocument subDoc = Qml4j.parse(new String(bytes, StandardCharsets.UTF_8));
                boolean singleton = (entry != null && entry.singleton) || subDoc.hasPragma("Singleton");
                if (singleton) subDoc.addPragma("Singleton");
                // The imported file's own directory is this prefix; its relative imports
                // resolve against it.
                Class<? extends QObject> rootClass = compileAndDefine(subDoc, p);
                importedTypes.put(path, rootClass);
                if (recorder != null) recorder.recordImportedType(path, rootClass);
                if (progress != null) progress.onComponentCompiled(name, ++compiledCount);
                if (singleton) {
                    singletonsByPrefix.computeIfAbsent(p, k -> new HashMap<>()).put(name, rootClass);
                    if (recorder != null) recorder.recordSingleton(p, name, rootClass);
                    TypeRegistry current = CompileScope.currentRegistry();
                    if (current != null) current.registerSingleton(name, rootClass);
                }
                return rootClass;
            } finally {
                compilingNow.remove(path);
            }
        }
        return null;
    }

    private void registerCachedSingleton(String prefix, String name, Class<? extends QObject> cached) {
        Map<String, Class<? extends QObject>> m = singletonsByPrefix.get(prefix);
        if (m == null || m.get(name) != cached) return;
        TypeRegistry current = CompileScope.currentRegistry();
        if (current != null) current.registerSingleton(name, cached);
    }

    private Map<String, QmldirEntry> loadQmldir(String prefix) {
        Map<String, QmldirEntry> cached = qmldirCache.get(prefix);
        if (cached != null) return cached;
        Map<String, QmldirEntry> map = Collections.emptyMap();
        if (resources != null) {
            String path = prefix.isEmpty() ? "qmldir" : prefix + "/qmldir";
            byte[] bytes = resources.load(path);
            if (bytes != null) {
                map = parseQmldir(new String(bytes, StandardCharsets.UTF_8));
            }
        }
        qmldirCache.put(prefix, map);
        return map;
    }

    private static Map<String, QmldirEntry> parseQmldir(String text) {
        Map<String, QmldirEntry> out = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            int i = 0;
            boolean singleton = false;
            if ("singleton".equals(parts[i])) { singleton = true; i++; }
            if (parts.length - i < 2) continue;
            String typeName = parts[i++];
            if (parts[i].matches("\\d+(\\.\\d+)?")) i++;
            if (i >= parts.length) continue;
            out.put(typeName, new QmldirEntry(parts[i], singleton));
        }
        return out;
    }

    private static final class QmldirEntry {
        final String file;
        final boolean singleton;
        QmldirEntry(String file, boolean singleton) {
            this.file = file;
            this.singleton = singleton;
        }
    }

    private static Set<String> importAliases(Ast.QmlDocument doc) {
        Set<String> out = new LinkedHashSet<>();
        for (Ast.ImportNode imp : doc.imports) {
            if (imp.alias != null && !isJsImport(imp)) out.add(imp.alias);
        }
        return out;
    }

    private static boolean isJsImport(Ast.ImportNode imp) {
        return imp.isStringPath && imp.moduleOrPath != null && imp.moduleOrPath.endsWith(".js");
    }

    // A `.js` library imported as a namespace (`import "IconData.js" as IconData`): evaluate
    // it once, expose its scope as a shared global under the alias, and register the alias so
    // bindings reading `IconData.icons` compile. The path resolves like any QML resource (qrc
    // scheme stripped to a module-relative path; a bare relative path against baseDir).
    private void processJsImports(Ast.QmlDocument doc, String baseDir) {
        for (Ast.ImportNode imp : doc.imports) {
            if (!isJsImport(imp) || imp.alias == null) continue;
            String path = resolveJsPath(imp.moduleOrPath, baseDir);
            evaluateJsImport(imp.alias, path);
            if (recorder != null) recorder.recordJsImport(imp.alias, path);
        }
    }

    private void evaluateJsImport(String alias, String path) {
        byte[] bytes = resources != null ? resources.load(path) : null;
        if (bytes == null) throw new IllegalArgumentException("JS import not found: " + path);
        Scriptable ns = JsRuntime.evalModule(new String(bytes, StandardCharsets.UTF_8));
        JsRuntime.putGlobal(engine.jsRealm(), alias, ns);
        RhinoScope.registerContextProperty(alias);
    }

    private static String resolveJsPath(String path, String baseDir) {
        if (path.startsWith("qrc:/qt/qml/")) return path.substring("qrc:/qt/qml/".length());
        if (path.startsWith("qrc:/")) return path.substring("qrc:/".length());
        if (path.startsWith("/")) return path.substring(1);
        return joinPath(baseDir, path);
    }

    private static List<String> stringImportPrefixes(Ast.QmlDocument doc, String baseDir) {
        List<String> out = new ArrayList<>();
        for (Ast.ImportNode imp : doc.imports) {
            String p = imp.moduleOrPath;
            if (p == null || isJsImport(imp)) continue;
            if (imp.isStringPath) {
                // A string import is a file path relative to the importing document
                // (import "../widgets"); resolve it against baseDir so `../` escapes the
                // importing file's directory rather than the resource root.
                String rel = ".".equals(p) ? "" : p;
                out.add(joinPath(baseDir, rel));
            } else {
                // Module URI (import md3.Core) maps to a resource dir md3/Core.
                // Built-in modules (QtQuick) just won't resolve any file.
                out.add(p.replace('.', '/'));
            }
        }
        return out;
    }

    // Normalise `base/rel`, collapsing `.`/`..` segments. A leading `..` is kept (the
    // resource loader resolves it against its own root), matching Qt's file imports.
    static String joinPath(String base, String rel) {
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String seg : (base + "/" + rel).split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg) && !stack.isEmpty() && !"..".equals(stack.peekLast())) {
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        return String.join("/", stack);
    }
}
