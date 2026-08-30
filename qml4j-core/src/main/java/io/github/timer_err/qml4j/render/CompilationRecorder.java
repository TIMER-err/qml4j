package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledUnit;
import io.github.timer_err.qml4j.engine.QObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures one cold compilation in definition order for later restoration. */
final class CompilationRecorder {

    private final Map<String, byte[]> classes = new LinkedHashMap<>();
    private final Map<String, String> importedTypes = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> singletons = new LinkedHashMap<>();
    private final List<CompiledScene.JsImport> jsImports = new ArrayList<>();

    void recordUnit(CompiledUnit unit) {
        classes.putAll(unit.classes());
    }

    void recordImportedType(String path, Class<? extends QObject> type) {
        importedTypes.put(path, type.getName());
    }

    void recordSingleton(String prefix, String name, Class<? extends QObject> type) {
        singletons.computeIfAbsent(prefix, ignored -> new LinkedHashMap<>())
            .put(name, type.getName());
    }

    void recordJsImport(String alias, String path) {
        jsImports.add(new CompiledScene.JsImport(alias, path));
    }

    CompiledScene finish(Class<? extends QObject> rootClass) {
        return new CompiledScene(rootClass.getName(), classes, importedTypes,
            singletons, jsImports);
    }
}
