package io.github.timer_err.qml4j.render.items.layout;

import io.github.timer_err.qml4j.render.items.core.Item;

/** Keeps content-driven dimensions separate from explicit application geometry. */
final class PositionerSizing {
    private PositionerSizing() {}

    static void update(Item item, double width, double height) {
        item.implicitWidth.set(width);
        item.implicitHeight.set(height);
        if (!item.width.isBound() && owns(item.width.peekDouble(), item.lastImplicitWidth)) {
            item.width.set(width);
            item.lastImplicitWidth = width;
        }
        if (!item.height.isBound() && owns(item.height.peekDouble(), item.lastImplicitHeight)) {
            item.height.set(height);
            item.lastImplicitHeight = height;
        }
    }

    private static boolean owns(double current, double previous) {
        return current == (Double.isNaN(previous) ? 0 : previous);
    }
}
