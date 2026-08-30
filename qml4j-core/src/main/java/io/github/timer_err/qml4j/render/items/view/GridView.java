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

public class GridView extends Flickable implements DelegateHost {

    public final Property<Object> model = new Property<>(0);
    public final Property<Number> cellWidth = new Property<>(100);
    public final Property<Number> cellHeight = new Property<>(100);
    public final Property<String> flow = new Property<>("FlowLeftToRight");

    private DelegateFactory factory;
    private final List<Item> instances = new ArrayList<>();
    private ListModel boundModel;
    private SignalHandler modelListener;

    public GridView() {
        clip.set(Boolean.TRUE);
        model.addListener(v -> { attachModelSignals(v); rebuild(); });
        cellWidth.addListener(v -> relayout());
        cellHeight.addListener(v -> relayout());
        flow.addListener(v -> relayout());
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
                throw new IllegalStateException("GridView delegate must produce an Item");
            }
            Item item = (Item) created;
            children.add(item);
            instances.add(item);
        }
        relayout();
    }

    private void relayout() {
        float cw = cellWidth.peekFloat();
        float ch = cellHeight.peekFloat();
        if (cw <= 0 || ch <= 0) return;
        boolean topToBottom = "FlowTopToBottom".equals(flow.peek());
        float viewW = width.peekFloat();
        float viewH = height.peekFloat();
        int perRow = Math.max(1, (int) (viewW / cw));
        int perCol = Math.max(1, (int) (viewH / ch));
        int maxRow = 0;
        int maxCol = 0;
        for (int i = 0; i < instances.size(); i++) {
            Item it = instances.get(i);
            int col, row;
            if (topToBottom) {
                col = i / perCol;
                row = i % perCol;
            } else {
                col = i % perRow;
                row = i / perRow;
            }
            it.x.set(col * cw);
            it.y.set(row * ch);
            if (row > maxRow) maxRow = row;
            if (col > maxCol) maxCol = col;
        }
        contentWidth.set((maxCol + 1) * cw);
        contentHeight.set((maxRow + 1) * ch);
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
