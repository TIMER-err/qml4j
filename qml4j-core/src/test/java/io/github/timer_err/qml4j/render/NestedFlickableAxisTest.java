package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A horizontally scrolling strip (miuix TabRow) inside a vertically scrolling page:
// the gesture has to pick the Flickable that scrolls along its own axis, otherwise
// dragging up over the strip scrolls nothing at all.
class NestedFlickableAxisTest {

    private static final String SRC =
        "import QtQuick\n" +
        "Item { width: 200; height: 400\n" +
        "  Flickable { id: page; anchors.fill: parent\n" +
        "    contentWidth: 200; contentHeight: 2000\n" +
        "    flickableDirection: \"VerticalFlick\"\n" +
        "    Flickable { id: strip; x: 0; y: 0; width: 200; height: 60\n" +
        "      contentWidth: 800; contentHeight: 60\n" +
        "      flickableDirection: \"HorizontalFlick\"\n" +
        "      Rectangle { width: 800; height: 60; color: \"#3482ff\" }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    private static QmlView scene() {
        QmlView v = QmlView.withStockTypes(new QmlEngine());
        Item root = v.load(SRC);
        DirtyQueue dq = v.dirtyQueue();
        dq.install();
        try { dq.flush(); v.renderer().layoutOnly(root); dq.flush(); } finally { dq.uninstall(); }
        return v;
    }

    private static Flickable page(QmlView v) {
        return (Flickable) v.root().children.get(0);
    }

    private static Flickable strip(QmlView v) {
        return (Flickable) page(v).children.get(0);
    }

    @Test
    void verticalDragOverAHorizontalStripScrollsThePage() {
        QmlView v = scene();
        v.dispatchPointerDown(100, 30);
        v.dispatchPointerMove(100, 10);
        v.dispatchPointerMove(100, -20);
        assertTrue(page(v).contentY.peekFloat() > 0f, "page scrolled");
        assertEquals(0f, strip(v).contentX.peekFloat(), "strip untouched");
        v.dispatchPointerUp(100, -20);
    }

    @Test
    void horizontalDragOverTheStripScrollsTheStrip() {
        QmlView v = scene();
        v.dispatchPointerDown(150, 30);
        v.dispatchPointerMove(120, 30);
        v.dispatchPointerMove(60, 30);
        assertTrue(strip(v).contentX.peekFloat() > 0f, "strip scrolled");
        assertEquals(0f, page(v).contentY.peekFloat(), "page untouched");
        v.dispatchPointerUp(60, 30);
    }
}
