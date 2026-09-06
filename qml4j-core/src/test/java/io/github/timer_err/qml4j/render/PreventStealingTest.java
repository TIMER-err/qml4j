package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

// A Flickable steals a press from a child MouseArea once the drag passes the
// threshold on its scroll axis -- unless the child sets preventStealing, which is
// how a horizontal control (Slider, ColorSlider) keeps a drag whose finger drifts
// vertically instead of losing it to the page scroll.
class PreventStealingTest {

    private static Object prop(Object o, String name) {
        try {
            Field f = o.getClass().getField(name);
            return ((Property<?>) f.get(o)).peek();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static QmlView scene(String preventStealing) {
        QmlView v = QmlView.withStockTypes(new QmlEngine());
        Item root = v.load(
            "import QtQuick\n" +
            "Item { width: 200; height: 400\n" +
            "  property int moves: 0\n" +
            "  property bool canceled: false\n" +
            "  Flickable { id: page; anchors.fill: parent\n" +
            "    contentWidth: 200; contentHeight: 2000\n" +
            "    flickableDirection: \"VerticalFlick\"\n" +
            "    MouseArea { id: track; width: 200; height: 40\n" +
            "      preventStealing: " + preventStealing + "\n" +
            "      onPositionChanged: parent.parent.moves = parent.parent.moves + 1\n" +
            "      onCanceled: parent.parent.canceled = true\n" +
            "    }\n" +
            "  }\n" +
            "}");
        DirtyQueue dq = v.dirtyQueue();
        dq.install();
        try { dq.flush(); v.renderer().layoutOnly(root); dq.flush(); } finally { dq.uninstall(); }
        return v;
    }

    @Test
    void flickableStealsAVerticalDragFromAPlainMouseArea() {
        QmlView v = scene("false");
        Item root = v.root();
        v.dispatchPointerDown(100, 20);
        v.dispatchPointerMove(100, 60);
        assertEquals(Boolean.TRUE, prop(root, "canceled"), "press handed to the Flickable");
        v.dispatchPointerUp(100, 60);
    }

    @Test
    void preventStealingKeepsTheDragOnTheChild() {
        QmlView v = scene("true");
        Item root = v.root();
        v.dispatchPointerDown(100, 20);
        v.dispatchPointerMove(100, 60);
        assertEquals(Boolean.FALSE, prop(root, "canceled"), "child keeps the gesture");
        assertEquals(1, ((Number) prop(root, "moves")).intValue(), "child still receives moves");
        v.dispatchPointerUp(100, 60);
    }
}
