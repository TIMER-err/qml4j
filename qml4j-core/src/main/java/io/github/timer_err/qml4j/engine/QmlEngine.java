package io.github.timer_err.qml4j.engine;

import io.github.timer_err.qml4j.engine.classloader.ClassLoaderBackend;
import io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend;
import io.github.timer_err.qml4j.engine.js.JsRuntime;

public final class QmlEngine {

    private final ClassLoaderBackend backend;
    private final Context rootContext = new Context();
    private final JsRuntime.Realm jsRealm;

    public QmlEngine() {
        this(new JvmClassLoaderBackend(), false);
    }

    public QmlEngine(ClassLoaderBackend backend) {
        this(backend, false);
    }

    /** Create an engine with safe Rhino standard objects. Host applications
     * should use this for QML supplied by a third-party plugin. */
    public QmlEngine(ClassLoaderBackend backend, boolean safeJavaScript) {
        this.backend = backend;
        this.jsRealm = JsRuntime.newRealm(safeJavaScript);
    }

    public ClassLoaderBackend backend() {
        return backend;
    }

    public Context rootContext() {
        return rootContext;
    }

    public JsRuntime.Realm jsRealm() {
        return jsRealm;
    }
}
