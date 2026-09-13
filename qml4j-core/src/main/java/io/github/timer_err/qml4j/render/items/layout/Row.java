package io.github.timer_err.qml4j.render.items.layout;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.binding.Property;

public class Row extends Item {
    public final Property<Number> spacing = new Property<>(0);


    @Override
    public void layout() {
        double x = 0;
        double s = spacing.peekDouble();
        double maxH = 0;
        for (Item c : children) {
            if (!c.isVisible()) continue;
            c.x.set(x);
            double w = c.width.peekDouble();
            x += w + s;
            double h = c.height.peekDouble();
            if (h > maxH) maxH = h;
        }
        if (x > 0) x -= s;
        // Explicit dimensions may bind to a container that is still settling.
        // Writing width/height here clears those bindings and freezes the row.
        PositionerSizing.update(this, x, maxH);
    }
}
