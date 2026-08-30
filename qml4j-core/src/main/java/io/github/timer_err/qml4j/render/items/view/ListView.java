package io.github.timer_err.qml4j.render.items.view;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.DelegateFactory;
import io.github.timer_err.qml4j.engine.DelegateHost;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.SignalHandler;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.js.JsRuntime;

import java.util.ArrayList;
import java.util.List;

public class ListView extends Flickable implements DelegateHost {

    public final Property<Object> model = new Property<>(0);
    public final Property<Number> spacing = new Property<>(0);
    // String ("Vertical"/"Horizontal") or the ListView.Horizontal/Vertical enum (0/1);
    // Object so a Long enum value doesn't fail the typed listener's bridge cast.
    public final Property<Object> orientation = new Property<>("Vertical");
    @SuppressWarnings("unused")
    public final Property<Number> cacheBuffer = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> currentIndex = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> highlightMoveDuration = new Property<>(0);
    // Snap/highlight-range are accepted for layout compatibility; the snap-scroll
    // behavior itself is not yet modeled.
    @SuppressWarnings("unused")
    public final Property<Object> snapMode = new Property<>(0L);
    @SuppressWarnings("unused")
    public final Property<Object> highlightRangeMode = new Property<>(0L);
    @SuppressWarnings("unused")
    public final Property<Number> preferredHighlightBegin = new Property<>(0);
    @SuppressWarnings("unused")
    public final Property<Number> preferredHighlightEnd = new Property<>(0);
    public final Property<Number> count = new Property<>(0);

    private DelegateFactory factory;
    private final List<Item> instances = new ArrayList<>();
    private ListModel boundModel;
    private SignalHandler modelListener;

    public ListView() {
        clip.set(Boolean.TRUE);
        model.addListener(v -> {
            attachModelSignals(v);
            rebuild();
        });
        spacing.addListener(v -> relayout());
        orientation.addListener(v -> relayout());
        width.addListener(v -> relayout());
        height.addListener(v -> relayout());
    }

    @Override
    public void setDelegate(DelegateFactory factory) {
        this.factory = JsRuntime.bindFactory(factory);
        rebuild();
    }

    public List<Item> instances() {
        return instances;
    }

    // Scroll so the delegate at `index` is at the top of the viewport. `mode` (Qt's
    // PositionMode) is treated as Beginning for now.
    @SuppressWarnings("unused")
    public void positionViewAtIndex(int index, int mode) {
        if (index < 0 || index >= instances.size()) return;
        contentY.set(instances.get(index).y.peek());
    }

    private void attachModelSignals(Object m) {
        if (boundModel != null && modelListener != null) {
            boundModel.rowsInserted.disconnect(modelListener);
            boundModel.rowsRemoved.disconnect(modelListener);
            boundModel.rowsChanged.disconnect(modelListener);
        }
        boundModel = null;
        modelListener = null;
        if (m instanceof ListModel) {
            boundModel = (ListModel) m;
            modelListener = args -> rebuild();
            boundModel.rowsInserted.connect(modelListener);
            boundModel.rowsRemoved.connect(modelListener);
            boundModel.rowsChanged.connect(modelListener);
        }
    }

    private void rebuild() {
        if (factory == null) return;
        for (Item it : instances) children.remove(it);
        instances.clear();
        Object m = model.peek();
        int n = sizeOf(m);
        for (int i = 0; i < n; i++) {
            Object data = dataAt(m, i);
            QObject created = factory.create(i, data, this);
            if (!(created instanceof Item)) {
                throw new IllegalStateException("ListView delegate must produce an Item");
            }
            Item item = (Item) created;
            children.add(item);
            instances.add(item);
        }
        count.set(instances.size());
        relayout();
    }

    private void relayout() {
        float sp = spacing.peekFloat();
        // orientation may be the string "Horizontal" or the ListView.Horizontal enum (0).
        Object o = orientation.peek();
        boolean horizontal = "Horizontal".equals(o)
            || (o instanceof Number && ((Number) o).intValue() == 0);
        float cursor = 0f;
        for (Item it : instances) {
            if (horizontal) {
                it.x.set(cursor);
                it.y.set(0);
                cursor += it.width.peekFloat() + sp;
            } else {
                it.x.set(0);
                it.y.set(cursor);
                cursor += it.height.peekFloat() + sp;
            }
        }
        if (horizontal) {
            contentWidth.set(cursor > 0 ? cursor - sp : 0);
            contentHeight.set(height.peek());
        } else {
            contentHeight.set(cursor > 0 ? cursor - sp : 0);
            contentWidth.set(width.peek());
        }
    }

    private static int sizeOf(Object m) {
        if (m instanceof ListModel) return ((ListModel) m).rows.size();
        if (m instanceof Number) {
            int n = ((Number) m).intValue();
            return Math.max(0, n);
        }
        if (m instanceof List) return ((List<?>) m).size();
        return 0;
    }

    private static Object dataAt(Object m, int i) {
        if (m instanceof ListModel) {
            List<ListElement> rows = ((ListModel) m).rows;
            return i < rows.size() ? rows.get(i) : null;
        }
        if (m instanceof List) {
            List<?> list = (List<?>) m;
            return i < list.size() ? list.get(i) : null;
        }
        return i;
    }
}
