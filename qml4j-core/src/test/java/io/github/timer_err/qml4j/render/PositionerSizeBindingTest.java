package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Item;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionerSizeBindingTest {
    private static void settle(QmlView view) {
        DirtyQueue queue = view.dirtyQueue();
        queue.install();
        try {
            queue.flush();
            view.renderer().layoutOnly(view.root());
            queue.flush();
        } finally {
            queue.uninstall();
        }
    }

    @Test
    void rowKeepsWidthBindingWhenParentResizes() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        try {
            Item root = view.load("import QtQuick\nItem { width: 400; height: 100\n"
                + " Row { width: parent.width; spacing: 12\n"
                + "  Rectangle { width: (parent.width - 12) / 2; height: 40 }\n"
                + "  Rectangle { width: (parent.width - 12) / 2; height: 40 }\n"
                + " } }");
            settle(view);
            Item row = root.children.get(0);
            assertEquals(194, row.children.get(0).width.peekDouble(), 0.01);
            root.width.set(600);
            settle(view);
            assertEquals(600, row.width.peekDouble(), 0.01);
            assertEquals(294, row.children.get(0).width.peekDouble(), 0.01);
        } finally {
            view.dispose();
        }
    }

    @Test
    void columnKeepsNarrowWidthWhileTextWraps() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        try {
            Item root = view.load("import QtQuick\nItem { width: 300; height: 200\n"
                + " Column { width: parent.width - 32\n"
                + "  Text { width: parent.width; wrapMode: Text.WordWrap; text: \"A long description that wraps as the window gets narrower\" }\n"
                + " } }");
            settle(view);
            Item column = root.children.get(0);
            double previousHeight = column.height.peekDouble();
            root.width.set(150);
            settle(view);
            assertEquals(118, column.width.peekDouble(), 0.01);
            assertTrue(column.height.peekDouble() > previousHeight);
        } finally {
            view.dispose();
        }
    }

    @Test
    void unconstrainedPositionerShrinksWhenChildIsHidden() {
        QmlView view = QmlView.withStockTypes(new QmlEngine());
        try {
            Item root = view.load("import QtQuick\nItem { width: 200; height: 200; Column { spacing: 8\n"
                + " Rectangle { width: 90; height: 30 }\n"
                + " Rectangle { width: 140; height: 50 }\n} }");
            root = root.children.get(0);
            settle(view);
            assertEquals(88, root.height.peekDouble(), 0.01);
            root.children.get(1).visible.set(false);
            settle(view);
            assertEquals(30, root.height.peekDouble(), 0.01);
            assertEquals(90, root.width.peekDouble(), 0.01);
        } finally {
            view.dispose();
        }
    }
}
