package io.github.timer_err.qml4j.engine;

/**
 * Explicit method allowlist for a host object exposed to third-party QML.
 *
 * <p>Safe QML realms may read public data fields, but may invoke methods on a
 * non-qml4j host object only when it implements this interface and approves the
 * exact method name. This prevents inherited reflection methods such as
 * {@code getClass()} from becoming a sandbox escape.
 */
public interface QmlSafeBridge {
    boolean allowsQmlMethod(String name);
}
