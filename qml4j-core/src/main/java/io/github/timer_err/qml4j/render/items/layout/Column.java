package io.github.timer_err.qml4j.render.items.layout;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.binding.Property;

public class Column extends Item {
    public final Property<Number> spacing = new Property<>(0);


    @Override
    public void layout() {
        double y = 0;
        double s = spacing.peekDouble();
        double maxW = 0;
        for (Item c : children) {
            if (!c.isVisible()) continue;
            c.y.set(y);
            double h = c.height.peekDouble();
            y += h + s;
            double w = c.width.peekDouble();
            if (w > maxW) maxW = w;
        }
        if (y > 0) y -= s;
        PositionerSizing.update(this, maxW, y);
    }
}
