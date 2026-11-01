package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.QmlSafeBridge;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.js.JsRuntime;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.Loader;
import org.junit.jupiter.api.Test;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void concurrentCallersPublishOneFullyRegisteredSafeGlobalScope() throws Exception {
        JsRuntime.Realm realm = JsRuntime.newRealm(true);
        int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Scriptable>> scopes = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                scopes.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    Scriptable scope = JsRuntime.globals(realm);
                    assertTrue(JsRuntime.isSafeScope(scope),
                            "the scope must be registered before other threads can observe it");
                    return scope;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Scriptable initialized = scopes.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Scriptable> scope : scopes) {
                assertSame(initialized, scope.get(5, TimeUnit.SECONDS));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRealmsKeepDistinctGlobalScopes() throws Exception {
        JsRuntime.Realm first = JsRuntime.newRealm(true);
        JsRuntime.Realm second = JsRuntime.newRealm(false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Scriptable> firstScope = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return JsRuntime.globals(first);
            });
            Future<Scriptable> secondScope = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return JsRuntime.globals(second);
            });
            start.countDown();
            Scriptable safeScope = firstScope.get(5, TimeUnit.SECONDS);
            Scriptable normalScope = secondScope.get(5, TimeUnit.SECONDS);
            assertNotSame(safeScope, normalScope);
            assertTrue(JsRuntime.isSafeScope(safeScope));
            assertFalse(JsRuntime.isSafeScope(normalScope));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
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
