package io.github.timer_err.qml4j.compiler;

/** Host-provided persistence for generated QML scene classes. */
public interface CompiledSceneCache {

    /** Returns a scene for the exact host fingerprint, or {@code null} on a miss. */
    CompiledScene load(String key);

    /** Stores a successfully compiled scene. Cache failures should remain best-effort. */
    void store(String key, CompiledScene scene);
}
