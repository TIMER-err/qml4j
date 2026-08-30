package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.QmlSafeBridge;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineJsRealmIsolationTest {

    @Test
    void contextPropertiesStayInsideTheirOwningEngine() {
        QmlView first = QmlView.withStockTypes(new QmlEngine()).context("probe", 11);
        QmlView second = QmlView.withStockTypes(new QmlEngine()).context("probe", 22);

        Item firstRoot = first.load("Item { width: probe }");
        Item secondRoot = second.load("Item { width: probe }");

        assertEquals(11, firstRoot.width.peek().intValue());
        assertEquals(22, secondRoot.width.peek().intValue());
    }

    @Test
    void safeRealmAllowsOnlyExplicitHostBridgeMethods() {
        QmlView view = new QmlView(
                new QmlEngine(new io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend(), true),
                StockTypes.safeRegistry()).context("bridge", new FixtureBridge());

        Item root = view.load("Item { width: bridge.call(); height: bridge.getClass ? 0 : 1 }");

        assertEquals(23, root.width.peek().intValue());
        assertEquals(1, root.height.peek().intValue());
    }

    @Test
    void safeTypeRegistryExcludesNativeFileDialog() {
        QmlView view = new QmlView(
                new QmlEngine(new io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend(), true),
                StockTypes.safeRegistry());
        assertThrows(IllegalArgumentException.class, () -> view.load("FileDialog {}"));
    }

    @Test
    void safeRealmInterruptsRunawayJavascript() {
        QmlView view = new QmlView(
                new QmlEngine(new io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend(), true),
                StockTypes.safeRegistry());
        assertThrows(RuntimeException.class, () -> view.load(
                "Item { width: (function() { while (true) {} })() }"));
    }

    public static final class FixtureBridge implements QmlSafeBridge {
        public int call() { return 23; }
        @Override public boolean allowsQmlMethod(String name) { return "call".equals(name); }
    }
}
