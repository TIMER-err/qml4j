package io.github.timer_err.qml4j.render;

/** Called for every remote QML resource URL, including every redirect hop. */
@FunctionalInterface
public interface NetworkResourcePolicy {
    boolean allow(String url);
}
