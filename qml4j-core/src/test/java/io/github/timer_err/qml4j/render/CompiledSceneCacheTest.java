package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.compiler.CompiledScene;
import io.github.timer_err.qml4j.compiler.CompiledSceneCache;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CompiledSceneCacheTest {

    @Test
    void warmLoadRestoresGeneratedSceneWithoutParsingSource() {
        MemoryCache cache = new MemoryCache();
        Map<String, byte[]> files = resources();
        QmlView cold = QmlView.withStockTypes(new QmlEngine())
            .resources(files::get)
            .compilationCache(cache, "bundle-a:Main.qml");
        Item coldRoot = cold.load(source(31));
        assertEquals(31L, ((Rectangle) coldRoot.children.get(0)).width.peek().longValue());
        assertNotNull(cache.entries.get("bundle-a:Main.qml"));
        assertEquals(1, cache.stores);

        QmlView warm = QmlView.withStockTypes(new QmlEngine())
            .resources(path -> {
                throw new AssertionError("warm scene unexpectedly loaded " + path);
            })
            .compilationCache(cache, "bundle-a:Main.qml");
        Item warmRoot = warm.load("this is deliberately not valid QML");
        assertEquals(31L, ((Rectangle) warmRoot.children.get(0)).width.peek().longValue());
        assertEquals(1, cache.stores);
        assertEquals(2, cache.loads);
    }

    @Test
    void differentHostFingerprintCompilesAndStoresAnotherScene() {
        MemoryCache cache = new MemoryCache();
        Map<String, byte[]> files = resources();
        QmlView first = QmlView.withStockTypes(new QmlEngine())
            .resources(files::get)
            .compilationCache(cache, "bundle-a:Main.qml");
        first.load(source(31));

        files.put("widgets/Card.qml", bytes("Rectangle { width: 47; height: 9 }"));
        QmlView changed = QmlView.withStockTypes(new QmlEngine())
            .resources(files::get)
            .compilationCache(cache, "bundle-b:Main.qml");
        Item changedRoot = changed.load(source(47));

        assertEquals(47L, ((Rectangle) changedRoot.children.get(0)).width.peek().longValue());
        assertEquals(2, cache.stores);
        assertEquals(2, cache.entries.size());
    }

    @Test
    void warmLoadRestoresSingletonClasses() {
        MemoryCache cache = new MemoryCache();
        Map<String, byte[]> files = new HashMap<>();
        files.put("theme/qmldir", bytes("singleton Theme 1.0 Theme.qml\n"));
        files.put("theme/Theme.qml", bytes("Rectangle { property int answer: 42 }"));
        String qml = "import QtQuick\n"
            + "import \"theme\"\n"
            + "Item { property int answer: Theme.answer }";
        QmlView.withStockTypes(new QmlEngine())
            .resources(files::get)
            .compilationCache(cache, "bundle-a:Main.qml")
            .load(qml);

        QmlView warm = QmlView.withStockTypes(new QmlEngine())
            .resources(path -> {
                throw new AssertionError("warm scene unexpectedly loaded " + path);
            })
            .compilationCache(cache, "bundle-a:Main.qml");
        Item root = warm.load("invalid QML that must not be parsed");

        assertEquals(42L, readProperty(root, "answer"));
    }

    @Test
    void warmLoadReplaysJavaScriptImports() {
        MemoryCache cache = new MemoryCache();
        Map<String, byte[]> files = new HashMap<>();
        files.put("Logic.js", bytes("var answer = 17;"));
        String qml = "import QtQuick\n"
            + "import \"Logic.js\" as Logic\n"
            + "Item { property int answer: Logic.answer }";
        QmlView.withStockTypes(new QmlEngine())
            .resources(files::get)
            .compilationCache(cache, "bundle-a:Main.qml")
            .load(qml);

        QmlView warm = QmlView.withStockTypes(new QmlEngine())
            .resources(path -> files.get(path))
            .compilationCache(cache, "bundle-a:Main.qml");
        Item root = warm.load("invalid QML that must not be parsed");

        assertEquals(17L, readProperty(root, "answer"));
    }

    private static Map<String, byte[]> resources() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("widgets/Card.qml", bytes("Rectangle { width: 31; height: 9 }"));
        return files;
    }

    private static String source(int expectedWidth) {
        return "import QtQuick\n"
            + "import \"widgets\"\n"
            + "Item { Card { } property int expected: " + expectedWidth + " }";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Object readProperty(Object object, String name) {
        try {
            Field field = object.getClass().getField(name);
            return ((Property<?>) field.get(object)).peek();
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static final class MemoryCache implements CompiledSceneCache {
        final Map<String, CompiledScene> entries = new HashMap<>();
        int loads;
        int stores;

        @Override
        public CompiledScene load(String key) {
            loads++;
            return entries.get(key);
        }

        @Override
        public void store(String key, CompiledScene scene) {
            stores++;
            entries.put(key, scene);
        }
    }
}
