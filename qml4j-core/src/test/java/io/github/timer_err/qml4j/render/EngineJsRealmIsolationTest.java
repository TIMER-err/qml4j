package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.QmlSafeBridge;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.Loader;
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
    void loaderComponentKeepsItsOwningEngineContext() {
        QmlView first = QmlView.withStockTypes(new QmlEngine()).context("probe", 11);
        QmlView second = QmlView.withStockTypes(new QmlEngine()).context("probe", 22);

        Item firstRoot = first.load(dynamicComponentSource("probe"));
        Item secondRoot = second.load(dynamicComponentSource("probe"));
        settle(first, firstRoot);
        settle(second, secondRoot);

        assertEquals(11, loadedItem(firstRoot).implicitHeight.peek().intValue());
        assertEquals(22, loadedItem(secondRoot).implicitHeight.peek().intValue());
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
    void loaderComponentKeepsSafeRealmAndExplicitBridge() {
        QmlView view = new QmlView(
                new QmlEngine(new io.github.timer_err.qml4j.engine.classloader.JvmClassLoaderBackend(), true),
                StockTypes.safeRegistry()).context("bridge", new FixtureBridge());

        Item root = view.load(dynamicComponentSource(
                "bridge.call() + (bridge.getClass ? 1000 : 0)"));
        settle(view, root);

        assertEquals(23, loadedItem(root).implicitHeight.peek().intValue());
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

    private static String dynamicComponentSource(String heightExpression) {
        return "Item { Loader { sourceComponent: Component { "
                + "Item { implicitHeight: " + heightExpression + " } } } }";
    }

    private static Item loadedItem(Item root) {
        return ((Loader) root.children.get(0)).loadedItem;
    }

    private static void settle(QmlView view, Item root) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            new Renderer().layoutOnly(root);
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    public static final class FixtureBridge implements QmlSafeBridge {
        public int call() { return 23; }
        @Override public boolean allowsQmlMethod(String name) { return "call".equals(name); }
    }
}
