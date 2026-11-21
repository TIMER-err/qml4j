package io.github.timer_err.qml4j.render.items.view;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.Signal;
import io.github.timer_err.qml4j.engine.SignalHandler;
import io.github.timer_err.qml4j.engine.SignalRelay;
import io.github.timer_err.qml4j.engine.binding.Property;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public class Connections extends Item implements SignalRelay {
    public final Property<QObject> target = new Property<>(null);
    // We already skip handlers whose signal is absent, so this is accepted but unused.
    @SuppressWarnings("unused")
    public final Property<Object> ignoreUnknownSignals = new Property<>(Boolean.FALSE);
    private final Map<String, SignalHandler> handlers = new LinkedHashMap<>();
    private QObject boundTarget;

    public Connections() {
        target.addListener(t -> rebind());
    }

    @Override
    public void connectSignal(String name, SignalHandler handler) {
        handlers.put(name, handler);
        QObject t = target.peek();
        if (t == boundTarget && t != null) {
            Signal s = findSignal(t, name);
            if (s != null) s.connect(handler);
        } else {
            rebind();
        }
    }

    private void rebind() {
        QObject next = target.peek();
        disconnectTarget();
        boundTarget = next;
        if (next == null) return;
        for (Map.Entry<String, SignalHandler> e : handlers.entrySet()) {
            Signal s = findSignal(next, e.getKey());
            if (s != null) s.connect(e.getValue());
        }
    }

    @Override
    protected void releaseResources() {
        disconnectTarget();
        handlers.clear();
    }

    private void disconnectTarget() {
        if (boundTarget == null) return;
        for (Map.Entry<String, SignalHandler> entry : handlers.entrySet()) {
            Signal signal = findSignal(boundTarget, entry.getKey());
            if (signal != null) signal.disconnect(entry.getValue());
        }
        boundTarget = null;
    }

    private static Signal findSignal(QObject obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            Object v = f.get(obj);
            return v instanceof Signal ? (Signal) v : null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
