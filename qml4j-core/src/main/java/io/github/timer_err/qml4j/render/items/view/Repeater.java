package io.github.timer_err.qml4j.render.items.view;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.DelegateFactory;
import io.github.timer_err.qml4j.engine.DelegateHost;
import io.github.timer_err.qml4j.engine.QObject;
import io.github.timer_err.qml4j.engine.SignalHandler;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.engine.binding.Property;
import io.github.timer_err.qml4j.engine.js.JsRuntime;

import java.util.ArrayList;
import java.util.List;

public class Repeater extends Item implements DelegateHost {
    public final Property<Object> model = new Property<>(0);

    // Optional windowing. When windowCount is a number, the Repeater instantiates
    // only `windowCount` delegates representing the GLOBAL index range
    // [windowStart, windowStart+windowCount) instead of one delegate per model row.
    // Each delegate's `index` is its global index (so a delegate positioned by
    // `y: index*rowH` lands at its absolute content position), and sliding the
    // window (windowStart change, same count) reuses the existing delegates via the
    // in-place update path -- no dispose/recreate. This is what lets a several-
    // thousand-row list cost ~20 live delegates. windowCount == null (the default)
    // means "no windowing": every Repeater that doesn't opt in builds the full list
    // exactly as before.
    public final Property<Object> windowStart = new Property<>(null);
    public final Property<Object> windowCount = new Property<>(null);

    private DelegateFactory factory;
    private final List<Item> instances = new ArrayList<>();
    // assigned.get(i) is the GLOBAL model index that instances.get(i) currently
    // renders. For a window slide (same model, moved start) only the delegates whose
    // global index left the window are repointed to the indices that entered it --
    // the rest keep their content/geometry untouched, so a one-row scroll rewrites
    // one row, not the whole window (the difference between smooth and a per-row
    // relayout + cover reload storm).
    private final List<Integer> assigned = new ArrayList<>();
    // Identity of the model the live delegates were last filled from. Same object =>
    // the data at each global index is unchanged, so a rebuild that only moved the
    // window can recycle in place; a different object means the content itself
    // changed (e.g. a selection-model swap) and every delegate must refresh.
    private Object lastModel;
    private ListModel boundModel;
    private SignalHandler modelListener;
    // A stable task instance so repeated enqueues within one DirtyQueue flush
    // collapse to a single rebuild (the queue is a LinkedHashSet keyed on identity).
    // A window slide bumps windowStart, and opening a page bumps model + windowCount
    // together; coalescing avoids rebuilding two or three times that frame.
    private final Runnable rebuildTask = this::rebuild;

    public Repeater() {
        // A Repeater is non-visual (Qt): it inserts its delegates as siblings in its
        // parent and takes no space itself, so a layout must not give it a slot + spacing
        // between the items before and after it. Invisible -> the layout's visible-child
        // filter skips it; its delegates lay out normally as separate children.
        visible.set(Boolean.FALSE);
        model.addListener(v -> {
            attachModelSignals(v);
            scheduleRebuild();
        });
        parent.addListener(v -> scheduleRebuild());
        windowStart.addListener(v -> scheduleRebuild());
        windowCount.addListener(v -> scheduleRebuild());
    }

    @Override
    public void setDelegate(DelegateFactory factory) {
        this.factory = JsRuntime.bindFactory(factory);
        rebuild();
    }

    public List<Item> instances() {
        return instances;
    }

    // Qt Repeater.itemAt(index): the delegate instance at index, or null. MD3 Tabs uses
    // it to read the current tab's geometry when positioning the sliding indicator.
    @SuppressWarnings("unused")
    public Item itemAt(int index) {
        return index >= 0 && index < instances.size() ? instances.get(index) : null;
    }

    @SuppressWarnings("unused")
    public int count() {
        return instances.size();
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
            modelListener = args -> scheduleRebuild();
            boundModel.rowsInserted.connect(modelListener);
            boundModel.rowsRemoved.connect(modelListener);
            boundModel.rowsChanged.connect(modelListener);
        }
    }

    private void scheduleRebuild() {
        DirtyQueue dq = DirtyQueue.current();
        if (dq != null) dq.enqueue(rebuildTask);
        else rebuild();
    }

    private void rebuild() {
        if (factory == null) return;
        Item visualParent = parent.peek();
        if (visualParent == null) return;
        Object m = model.peek();
        int total = sizeOf(m);

        // Resolve the [start, start+desired) global range. Without windowing this is
        // the whole model. With windowing, clamp so the window never runs past the
        // ends and never shrinks at the tail (start pinned to total-count), which
        // keeps `desired` constant as it slides -> the in-place fast path stays hot.
        int start = 0;
        int desired = total;
        Object wcObj = windowCount.peek();
        if (wcObj instanceof Number) {
            int count = ((Number) wcObj).intValue();
            if (count < 0) count = 0;
            if (count > total) count = total;
            Object wsObj = windowStart.peek();
            int wStart = wsObj instanceof Number ? ((Number) wsObj).intValue() : 0;
            if (wStart < 0) wStart = 0;
            int maxStart = total - count;
            if (wStart > maxStart) wStart = maxStart;
            start = wStart;
            desired = count;
        }

        // Same count: update delegates in place instead of recreating them. The
        // delegate (and any live state like an animating Ripple) survives. Two modes:
        // a pure window slide over an unchanged model recycles only the delegates that
        // left the window; anything else (model swapped, e.g. a SegmentedButton/
        // NavigationBar selection model) refreshes every delegate positionally.
        if (desired == instances.size() && desired > 0) {
            if (wcObj instanceof Number && m == lastModel) {
                slideWindow(m, start, desired);
            } else {
                for (int i = 0; i < desired; i++) {
                    int global = start + i;
                    Item d = instances.get(i);
                    MemberAccess.writeMember(d, "index", (long) global);
                    MemberAccess.writeMember(d, "modelData", dataAt(m, global));
                    assigned.set(i, global);
                }
            }
            lastModel = m;
            return;
        }

        // dispose() (not just children.remove) so each discarded delegate's bindings
        // unsubscribe from external properties and its native images/backings close --
        // otherwise swapping a several-hundred-row model leaks the whole delegate set.
        for (Item it : instances) it.dispose();
        instances.clear();
        assigned.clear();
        // Insert delegates at the Repeater's own position in the parent (Qt), so siblings
        // declared after the Repeater (e.g. a trailing spacer) stay after the rows -- not
        // appended to the end, which would reorder them before the rows.
        int at = visualParent.children.indexOf(this);
        if (at < 0) at = visualParent.children.size();
        else at++;
        for (int i = 0; i < desired; i++) {
            int global = start + i;
            Object data = dataAt(m, global);
            QObject created = factory.create(global, data, visualParent);
            if (!(created instanceof Item)) {
                throw new IllegalStateException("Repeater delegate must produce an Item, got "
                    + (created == null ? "null" : created.getClass().getName()));
            }
            Item item = (Item) created;
            // parent was already set inside create() (before the delegate's bindings
            // were flushed, so outer-scope names resolve); just attach to the scene.
            visualParent.children.add(at + i, item);
            instances.add(item);
            assigned.add(global);
            item.initStateBindingsTree();
        }
        lastModel = m;
    }

    // Recycle in place for a same-count window slide over an unchanged model: keep
    // every delegate whose global index is still inside [start, start+count), and
    // repoint each delegate that fell outside to a freshly-exposed index. A one-row
    // scroll frees one delegate and rewrites one row; the rest are not touched, so no
    // spurious binding recompute / cover reload on the rows that merely stayed put.
    private void slideWindow(Object m, int start, int count) {
        int n = instances.size();
        boolean[] covered = new boolean[count];
        for (int i = 0; i < n; i++) {
            int slot = assigned.get(i) - start;
            if (slot >= 0 && slot < count) covered[slot] = true;
            else assigned.set(i, -1);
        }
        int slot = 0;
        for (int i = 0; i < n; i++) {
            if (assigned.get(i) != -1) continue;
            while (slot < count && covered[slot]) slot++;
            int global = start + slot;
            covered[slot] = true;
            Item d = instances.get(i);
            MemberAccess.writeMember(d, "index", (long) global);
            MemberAccess.writeMember(d, "modelData", dataAt(m, global));
            assigned.set(i, global);
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
