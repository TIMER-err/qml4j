package io.github.timer_err.qml4j.render.items.view;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.DelegateFactory;
import io.github.timer_err.qml4j.engine.DelegateHost;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.js.JsRuntime;

import java.util.Map;

public class Component extends Item implements DelegateHost {
    private DelegateFactory factory;

    @Override
    public void setDelegate(DelegateFactory factory) {
        this.factory = JsRuntime.bindFactory(factory);
    }

    public DelegateFactory factory() {
        return factory;
    }

    // QML Component.createObject(parent, properties): instantiate the delegate,
    // parent it into the scene, and apply the optional property map.
    public QObject createObject(Object parentObj, Object props) {
        if (factory == null) return null;
        QObject created = factory.create(0, null, parentObj);
        if (created instanceof Item && parentObj instanceof Item) {
            ((Item) parentObj).children.add((Item) created);
        }
        if (props instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) props).entrySet()) {
                MemberAccess.writeMember(created, String.valueOf(e.getKey()), e.getValue());
            }
        }
        return created;
    }

    @SuppressWarnings("unused")
    public QObject createObject(Object parentObj) {
        return createObject(parentObj, null);
    }
}
