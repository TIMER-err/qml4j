package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Surface;
import io.github.timer_err.qml4j.engine.binding.Binding;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.Component;
import io.github.timer_err.qml4j.render.items.view.Loader;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderResourceLifecycleTest {
    @Test
    void sourceReplacementAndClearReleaseDiscardedBindingsAndImages() {
        Fixture fixture = new Fixture();
        try {
            fixture.loader.source.set("first.qml");
            fixture.resolve();
            Image first = (Image) fixture.loader.loadedItem;
            try (Surface surface = Surface.makeRasterN32Premul(16, 16)) {
                first.skiaImage = surface.makeImageSnapshot();
            }
            var decoded = first.skiaImage;

            fixture.loader.source.set("second.qml");
            fixture.resolve();
            fixture.external.set(23);
            assertEquals(1, fixture.released.get());
            assertEquals(17, first.width.peek().intValue(), "discarded bindings must unsubscribe");
            assertNull(first.parent.peek());
            assertTrue(decoded.isClosed(), "replacement must close the old native image");
            assertEquals(23, fixture.loader.loadedItem.width.peek().intValue());

            fixture.loader.source.set("");
            fixture.resolve();
            assertEquals(2, fixture.released.get());
            assertNull(fixture.loader.loadedItem);
            assertNull(fixture.loader.item.peek());
            assertTrue(fixture.loader.children.isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void componentReplacementDeactivationAndSourceSwitchReleaseEachOldItem() {
        Fixture fixture = new Fixture();
        try {
            fixture.loader.sourceComponent.set(fixture.component());
            fixture.resolve();
            Item first = fixture.loader.loadedItem;
            fixture.loader.sourceComponent.set(fixture.component());
            fixture.resolve();
            assertEquals(1, fixture.released.get());
            assertNull(first.parent.peek());

            fixture.loader.active.set(false);
            fixture.resolve();
            assertEquals(2, fixture.released.get());
            assertNull(fixture.loader.item.peek());

            fixture.loader.active.set(true);
            fixture.resolve();
            fixture.loader.sourceComponent.set(null);
            fixture.loader.source.set("page.qml");
            fixture.resolve();
            assertEquals(3, fixture.released.get());
            assertEquals(1, fixture.loader.children.size());
        } finally {
            fixture.close();
        }
        assertEquals(4, fixture.released.get());
    }

    @Test
    void repeatedLoaderCyclesKeepExactlyOneOwnedSubtree() {
        Fixture fixture = new Fixture();
        try {
            fixture.loader.sourceComponent.set(fixture.component());
            for (int cycle = 0; cycle < 100; cycle++) {
                fixture.loader.active.set(true);
                fixture.resolve();
                assertEquals(1, fixture.loader.children.size());
                fixture.loader.active.set(false);
                fixture.resolve();
                assertEquals(cycle + 1, fixture.released.get());
                assertTrue(fixture.loader.children.isEmpty());
            }
        } finally {
            fixture.close();
        }
        assertEquals(100, fixture.released.get());
    }

    private static final class Fixture {
        final Renderer renderer = new Renderer();
        final Loader loader = new Loader();
        final Property<Number> external = new Property<>(17);
        final AtomicInteger released = new AtomicInteger();

        Fixture() {
            renderer.setComponentFactory(new ComponentFactory() {
                @Override
                public Item create(String qmlSource, String baseDir) {
                    return createItem();
                }

                @Override
                public Item createFromSource(String sourcePath, String documentDir) {
                    return createItem();
                }
            });
        }

        Image createItem() {
            Image image = new Image() {
                @Override
                protected synchronized void releaseResources() {
                    super.releaseResources();
                    released.incrementAndGet();
                }
            };
            image.width.bind(new Binding() {
                @Override
                public Object evaluate() {
                    return external.get();
                }
            });
            return image;
        }

        Component component() {
            Component component = new Component();
            component.setDelegate((index, data, parent) -> createItem());
            return component;
        }

        void resolve() {
            renderer.resolveLoader(loader);
        }

        void close() {
            loader.dispose();
            renderer.dispose();
        }
    }
}
