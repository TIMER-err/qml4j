package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Gradient;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.shape.PathLine;
import io.github.timer_err.qml4j.render.items.shape.Shape;
import io.github.timer_err.qml4j.render.items.shape.ShapePath;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclaredHolderLifecycleTest {
    @Test
    void repeatedShapeDisposalRemovesExternalPathAndElementSubscriptions() throws Exception {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        Item root = view.load("import QtQuick\nimport QtQuick.Shapes\n"
                + "Item { id: scene; property int rows: 0; property color accent: \"#112233\"; property real point: 20\n"
                + " Repeater { model: scene.rows; Shape {\n"
                + "  ShapePath { strokeColor: scene.accent; PathLine { x: scene.point; y: 1 } }\n"
                + " } }\n}");
        try {
            Property<?> accent = property(root, "accent");
            Property<?> point = property(root, "point");
            for (int cycle = 0; cycle < 100; cycle++) {
                setRows(view, root, 1);
                Shape shape = child(root, Shape.class);
                ShapePath path = shape.elements.get(0);
                PathLine line = (PathLine) path.pathElements.get(0);
                assertTrue(path.strokeColor.isBound());
                assertTrue(line.x.isBound());
                assertEquals(1, listeners(accent));
                assertEquals(1, listeners(point));

                setRows(view, root, 0);
                assertFalse(path.strokeColor.isBound());
                assertFalse(line.x.isBound());
                assertEquals(0, listeners(accent), "discarded paths must not retain their component through the theme");
                assertEquals(0, listeners(point));
            }
        } finally {
            view.dispose();
        }
    }

    @Test
    void declaredGradientStopsUnsubscribeWhenRectangleIsDiscarded() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        Item root = view.load("import QtQuick\n"
                + "Item { id: scene; property int rows: 1; property color accent: \"#112233\"\n"
                + " Repeater { model: scene.rows; Rectangle {\n"
                + "  gradient: Gradient { GradientStop { position: 0; color: scene.accent } }\n"
                + " } }\n}");
        try {
            Gradient gradient = child(root, Rectangle.class).gradient.peek();
            assertTrue(gradient.stops.get(0).color.isBound());
            setRows(view, root, 0);
            assertFalse(gradient.stops.get(0).color.isBound());
        } finally {
            view.dispose();
        }
    }

    @Test
    void borrowingRectangleDisposalDoesNotUnbindSharedDeclaredGradient() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        Item root = view.load("import QtQuick\n"
                + "Item { id: scene; property int rows: 1; property color accent: \"#112233\"\n"
                + " Rectangle { id: owner; gradient: Gradient {\n"
                + "  GradientStop { position: 0; color: scene.accent }\n"
                + " } }\n"
                + " Repeater { model: scene.rows; Rectangle { gradient: owner.gradient } }\n}");
        try {
            Gradient gradient = child(root, Rectangle.class).gradient.peek();
            setRows(view, root, 0);
            assertTrue(gradient.stops.get(0).color.isBound(), "a borrower cannot tear down the owner's bindings");
            MemberAccess.writeMember(root, "accent", "#445566");
            assertEquals("#445566", gradient.stops.get(0).color.peek());
        } finally {
            view.dispose();
        }
    }

    private static void setRows(QmlView view, Item root, int count) {
        view.dirtyQueue().install();
        try {
            MemberAccess.writeMember(root, "rows", count);
            view.dirtyQueue().flush();
        } finally {
            view.dirtyQueue().uninstall();
        }
    }

    private static <T extends Item> T child(Item root, Class<T> type) {
        for (Item child : root.children) if (type.isInstance(child)) return type.cast(child);
        assertNotNull(null, "missing " + type.getSimpleName());
        throw new AssertionError("missing child");
    }

    private static Property<?> property(Item root, String name) throws Exception {
        return (Property<?>) root.getClass().getField(name).get(root);
    }

    private static int listeners(Property<?> property) throws Exception {
        Field field = Property.class.getDeclaredField("invalidationListeners");
        field.setAccessible(true);
        Object value = field.get(property);
        return value == null ? 0 : value instanceof List ? ((List<?>) value).size() : 1;
    }
}
