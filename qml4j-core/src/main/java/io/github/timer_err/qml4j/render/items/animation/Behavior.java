package io.github.timer_err.qml4j.render.items.animation;
import io.github.timer_err.qml4j.render.items.core.Item;

import io.github.timer_err.qml4j.engine.binding.Property;

import java.lang.reflect.Field;
import java.util.Objects;

public class Behavior extends Item implements Animatable, Property.WriteInterceptor<Object> {

    private Property<Object> bound;
    private PropertyAnimation template;
    // Snapshotted from the template each time an animation starts (Qt semantics), not at
    // attach: a non-literal `duration`/`easing.type` (Easing.OutCubic, a token binding) is
    // a deferred binding that has not evaluated yet when attach runs during construction.
    private long durationMs;
    private int easingType;

    private boolean running;
    private long startNanos = -1L;
    private Object preparedFrom;
    private Object preparedTo;
    private Object lastDisplayed;
    private boolean writing;
    // Qt semantics: a Behavior is inactive during component construction -- it does not
    // animate the initial value assignment, only genuine changes after the object is
    // complete. We enable it from the construction-complete tree walk (initStateBindings),
    // run at load and for each dynamically-instantiated subtree. Without this an MD3
    // NavigationRail item's pill flashes its colour up from the default white and fades.
    private boolean enabled;
    // An implicit/preferred size is layout-driven: its value keeps settling across the
    // first frames AFTER construction completes, so suppress those too (the rail items
    // would otherwise grow from 0 and the column would slide in). Direct properties
    // settle during construction and need no frame window.
    private boolean suppressStartup;
    private int frames;
    private static final int SETTLE_FRAMES = 3;

    public Behavior() {
        visible.set(Boolean.FALSE);
    }

    @SuppressWarnings("unused")
    public void attach(Object owner, String propName) {
        Property<?> p = findProperty(owner, propName);
        if (p == null) return;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Property<Object> raw = (Property) p;
        bound = raw;
        lastDisplayed = raw.peek();
        suppressStartup = "implicitWidth".equals(propName) || "implicitHeight".equals(propName)
            || propName.endsWith("preferredWidth") || propName.endsWith("preferredHeight");
        findTemplate();
        raw.setInterceptor(this);
    }

    // Called by the construction-complete tree walk (Item.initStateBindings); a Behavior
    // has no states, so it just arms itself here.
    @Override
    public void initStateBindings() {
        super.initStateBindings();
        enabled = true;
    }

    private void findTemplate() {
        for (Item c : children) {
            if (!(c instanceof PropertyAnimation)) continue;
            template = (PropertyAnimation) c;
            return;
        }
    }

    @Override
    public void write(Property<Object> property, Object newValue) {
        if (writing) {
            property.setBypassInterceptor(newValue);
            return;
        }
        // Inactive until construction completes (the initial binding assignment), and
        // for layout-driven sizes a few frames longer while they settle.
        if (!enabled || (suppressStartup && frames < SETTLE_FRAMES)) {
            writeBack(newValue);
            return;
        }
        // No prior value to tween from (e.g. anchors.*Margin defaults to NaN as "unset"):
        // assign directly, like Qt's un-animated initial assignment. Interpolating from
        // NaN yields NaN, which writeBack would then drop -- freezing the property.
        if (isUnset(lastDisplayed)) {
            lastDisplayed = null;
            writeBack(newValue);
            return;
        }
        if (!canTween(lastDisplayed, newValue)) {
            writeBack(newValue);
            return;
        }
        Object pFrom = template.coerceFrom(lastDisplayed);
        Object pTo = template.coerceTo(newValue);
        template.preparedFrom = pFrom;
        template.preparedTo = pTo;
        template.onPrepared();
        pFrom = template.preparedFrom;
        pTo = template.preparedTo;
        if (!running && Objects.equals(pFrom, pTo)) return;
        // A bound property re-delivers its value every time a dependency settles; if we
        // are already animating to this exact target, keep going instead of restarting
        // (which would re-write the start value and freeze progress).
        if (running && Objects.equals(preparedTo, pTo)) return;
        preparedFrom = pFrom;
        preparedTo = pTo;
        running = true;
        Number d = template.duration.peek();
        durationMs = d != null ? d.longValue() : 250L;
        easingType = template.easing.type.peekInt();
        startNanos = -1L;
        writeBack(template.interpolate(pFrom, pTo, 0.0));
    }

    private static boolean isUnset(Object v) {
        return v == null || (v instanceof Double && ((Double) v).isNaN())
            || (v instanceof Float && ((Float) v).isNaN());
    }

    @Override
    public void tick(long nowNanos) {
        // Safety net: a Behavior that reaches a tick is past construction even if no
        // construction-complete walk reached it (an uncommon dynamic-instantiation path).
        // Its initial writes already happened (and were suppressed) before any tick.
        enabled = true;
        if (suppressStartup && frames < SETTLE_FRAMES) frames++;
        if (!running || bound == null) return;
        if (startNanos < 0L) startNanos = nowNanos;
        double frac = durationMs <= 0
            ? 1.0
            : Math.min(1.0, (nowNanos - startNanos) / 1_000_000.0 / durationMs);
        double eased = Easings.apply(easingType, frac);
        Object out = template != null
            ? template.interpolate(preparedFrom, preparedTo, eased)
            : preparedTo;
        writeBack(out);
        if (frac >= 1.0) {
            running = false;
            startNanos = -1L;
        }
    }

    private boolean canTween(Object before, Object after) {
        if (template == null) return false;
        return template.acceptsTransition(before, after);
    }

    private void writeBack(Object out) {
        if (Objects.equals(lastDisplayed, out)) return;
        writing = true;
        try {
            bound.setBypassInterceptor(out);
        } finally {
            writing = false;
        }
        lastDisplayed = out;
    }

    // Resolves a possibly-dotted property path: "color" or a grouped
    // "border.color" / "font.pixelSize" walking the intermediate group objects.
    private static Property<?> findProperty(Object obj, String path) {
        try {
            int dot = path.indexOf('.');
            if (dot < 0) {
                Field f = obj.getClass().getField(path);
                return Property.class.isAssignableFrom(f.getType()) ? (Property<?>) f.get(obj) : null;
            }
            Object group = obj.getClass().getField(path.substring(0, dot)).get(obj);
            return group == null ? null : findProperty(group, path.substring(dot + 1));
        } catch (NoSuchFieldException | IllegalAccessException ignore) {
            return null;
        }
    }
}
