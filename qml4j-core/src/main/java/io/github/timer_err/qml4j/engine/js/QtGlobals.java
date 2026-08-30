package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.runtime.qt.QtColorFactory;
import io.github.timer_err.qml4j.runtime.qt.QtDateFormat;
import io.github.timer_err.qml4j.runtime.invoke.Scheduler;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

// The shared JS global scope for the Rhino backend: standard objects (Math, JSON,
// parseInt, ...) plus QML's Qt namespace (enum constants + Qt.rgba/hsla/color
// factories bridged to QtColorFactory) and the Text/Font/Easing enum namespaces and
// console. Built once and shared as the parent scope of every QmlScope.
//
// Enum tables are duplicated from the compiler's ExpressionCodegen.ENUMS for now;
// they'll be unified when the ASM backend is deleted (Phase 6).
public final class QtGlobals {

    private QtGlobals() {}

    public static Scriptable build(Context cx) {
        return build(cx, false);
    }

    public static Scriptable build(Context cx, boolean safeStandardObjects) {
        ScriptableObject scope = safeStandardObjects
                ? cx.initSafeStandardObjects()
                : cx.initStandardObjects();

        NativeObject qt = enumObject(QT);
        qt.put("rgba", qt, fn("rgba", 4, a -> QtColorFactory.qtRgba(arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3))));
        qt.put("hsla", qt, fn("hsla", 4, a -> QtColorFactory.qtHsla(arg(a, 0), arg(a, 1), arg(a, 2), arg(a, 3))));
        qt.put("color", qt, fn("color", 1, a -> QtColorFactory.qtColor(arg(a, 0))));
        qt.put("callLater", qt, callLater());
        qt.put("binding", qt, qtBinding());
        qt.put("fontFamilies", qt, fn("fontFamilies", 0, a -> new ArrayList<>()));
        qt.put("application", qt, application());
        qt.put("formatDateTime", qt, dateFormat("yyyy-MM-dd hh:mm:ss"));
        qt.put("formatDate", qt, dateFormat("yyyy-MM-dd"));
        qt.put("formatTime", qt, dateFormat("hh:mm:ss"));
        scope.put("Qt", scope, qt);

        scope.put("Text", scope, enumObject(TEXT));
        scope.put("TextInput", scope, enumObject(TEXT_INPUT));
        scope.put("TextEdit", scope, enumObject(TEXT_INPUT));
        scope.put("Font", scope, enumObject(FONT));
        scope.put("Easing", scope, enumObject(EASING));
        scope.put("Item", scope, enumObject(ITEM));
        scope.put("Flickable", scope, enumObject(FLICKABLE));
        scope.put("Animation", scope, enumObject(ANIMATION));
        scope.put("Canvas", scope, enumObject(CANVAS));
        scope.put("ListView", scope, enumObject(LISTVIEW));
        scope.put("GridView", scope, enumObject(LISTVIEW));
        scope.put("RotationAnimation", scope, enumObject(ROTATION_ANIMATION));
        scope.put("Gradient", scope, enumObject(GRADIENT));
        scope.put("TapHandler", scope, enumObject(TAP_HANDLER));
        scope.put("PointerHandler", scope, enumObject(POINTER_HANDLER));
        scope.put("Image", scope, enumObject(IMAGE));
        scope.put("Drag", scope, enumObject(DRAG));

        // Window attached type: only Window.window (the containing window) is read, and
        // only to walk the live scene graph for a debug overlay we don't materialise.
        // A single-window engine has no separate window object, so it stays null --
        // consumers guard on it (`if (!win) return`).
        NativeObject window = new NativeObject();
        window.put("window", window, null);
        scope.put("Window", scope, window);

        NativeObject console = new NativeObject();
        console.put("log", console, fn("log", 1, a -> { System.out.println(join(a)); return null; }));
        console.put("warn", console, fn("warn", 1, a -> { System.out.println(join(a)); return null; }));
        console.put("error", console, fn("error", 1, a -> { System.out.println(join(a)); return null; }));
        scope.put("console", scope, console);

        // Component.status enum, for code that probes Qt.createComponent(...).status.
        scope.put("Component", scope, enumObject(COMPONENT));

        return scope;
    }

    // Qt.callLater(fn): defer the JS function to the next DirtyQueue flush. The
    // function is re-entered under a fresh Rhino context against its captured scope.
    // (org.mozilla.javascript.Function is FQN'd here -- it clashes with the imported
    // java.util.function.Function used by fn().)
    private static BaseFunction callLater() {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object f = args.length > 0 ? args[0] : null;
                if (f instanceof org.mozilla.javascript.Function) {
                    org.mozilla.javascript.Function jf = (org.mozilla.javascript.Function) f;
                    Scriptable home = jf.getParentScope() != null ? jf.getParentScope() : scope;
                    Scheduler.qtCallLater((Runnable) () -> {
                        Context c = JsRuntime.enter(home);
                        try {
                            jf.call(c, home, home, ScriptRuntime.emptyArgs);
                        } finally {
                            JsRuntime.exit();
                        }
                    });
                }
                return null;
            }
            @Override public int getArity() { return 1; }
            @Override public String getFunctionName() { return "callLater"; }
        };
    }

    // Qt.application.font: the application's default font. Only `.family` is read by
    // current consumers (the Canvas charts' font fallback); the resolver maps an unknown
    // family to its sans-serif default.
    // Qt.formatDate/formatTime/formatDateTime(date[, fmt]): format a date value (coerced to
    // epoch millis, so a JS Date works) with a Qt format string, defaulting to `defaultFmt`.
    private static BaseFunction dateFormat(String defaultFmt) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                if (args.length == 0) return "";
                double millis = ScriptRuntime.toNumber(args[0]);
                String fmt = args.length > 1 && args[1] != null
                    ? ScriptRuntime.toString(args[1]) : defaultFmt;
                return QtDateFormat.format(millis, fmt);
            }
            @Override public int getArity() { return 2; }
            @Override public String getFunctionName() { return "formatDateTime"; }
        };
    }

    private static NativeObject application() {
        NativeObject font = new NativeObject();
        font.put("family", font, "sans-serif");
        font.put("pixelSize", font, 14);
        NativeObject application = new NativeObject();
        application.put("font", application, font);
        return application;
    }

    // Qt.binding(() => expr): wrap the arrow as a RhinoJsBinding. Assigning the result
    // to a property establishes a reactive binding (Property.set detects a Binding).
    private static BaseFunction qtBinding() {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                Object f = args.length > 0 ? args[0] : null;
                if (f instanceof org.mozilla.javascript.Function) {
                    org.mozilla.javascript.Function jf = (org.mozilla.javascript.Function) f;
                    Scriptable home = jf.getParentScope() != null ? jf.getParentScope() : scope;
                    return JsWrap.toJs(new RhinoJsBinding(jf, home), scope);
                }
                return null;
            }
            @Override public int getArity() { return 1; }
            @Override public String getFunctionName() { return "binding"; }
        };
    }

    private static NativeObject enumObject(Map<String, Long> members) {
        NativeObject o = new NativeObject();
        for (Map.Entry<String, Long> e : members.entrySet()) {
            o.put(e.getKey(), o, e.getValue());
        }
        return o;
    }

    private static BaseFunction fn(String name, int arity, Function<Object[], Object> impl) {
        return new BaseFunction() {
            @Override public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return JsWrap.toJs(impl.apply(args), scope);
            }
            @Override public int getArity() { return arity; }
            @Override public String getFunctionName() { return name; }
        };
    }

    private static Object arg(Object[] args, int i) {
        return i < args.length ? JsWrap.toJava(args[i]) : null;
    }

    private static String join(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) b.append(' ');
            b.append(JsWrap.toJava(args[i]));
        }
        return b.toString();
    }

    private static Map<String, Long> map(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Long) kv[i + 1]);
        return m;
    }

    private static final Map<String, Long> QT = map(
        "Horizontal", 1L, "Vertical", 2L,
        "NoButton", 0L, "LeftButton", 1L, "RightButton", 2L, "MiddleButton", 4L,
        "AlignLeft", 1L, "AlignRight", 2L, "AlignHCenter", 4L, "AlignJustify", 8L,
        "AlignTop", 32L, "AlignBottom", 64L, "AlignVCenter", 128L, "AlignBaseline", 256L,
        "AlignCenter", 132L,
        "ArrowCursor", 0L, "IBeamCursor", 4L, "PointingHandCursor", 13L);

    private static final Map<String, Long> TEXT = map(
        "AlignLeft", 1L, "AlignRight", 2L, "AlignHCenter", 4L, "AlignJustify", 8L,
        "AlignTop", 32L, "AlignBottom", 64L, "AlignVCenter", 128L,
        "NoWrap", 0L, "WordWrap", 1L, "WrapAnywhere", 3L, "Wrap", 4L,
        "ElideNone", 0L, "ElideLeft", 1L, "ElideMiddle", 2L, "ElideRight", 3L,
        "Normal", 0L, "Outline", 1L, "Raised", 2L, "Sunken", 3L);

    private static final Map<String, Long> FONT = map(
        "Thin", 0L, "ExtraLight", 12L, "Light", 25L, "Normal", 50L, "Medium", 57L,
        "DemiBold", 63L, "Bold", 75L, "ExtraBold", 81L, "Black", 87L,
        "MixedCase", 0L, "AllUppercase", 1L, "AllLowercase", 2L, "SmallCaps", 3L, "Capitalize", 4L);

    // TextInput/TextEdit: alignment (shared with Text) + EchoMode + WrapMode.
    private static final Map<String, Long> TEXT_INPUT = map(
        "AlignLeft", 1L, "AlignRight", 2L, "AlignHCenter", 4L, "AlignJustify", 8L,
        "AlignTop", 32L, "AlignBottom", 64L, "AlignVCenter", 128L,
        "NoWrap", 0L, "WordWrap", 1L, "WrapAnywhere", 3L, "Wrap", 4L,
        "Normal", 0L, "NoEcho", 1L, "Password", 2L, "PasswordEchoOnEdit", 3L);

    // Item.TransformOrigin: pivot for scale/rotation.
    private static final Map<String, Long> ITEM = map(
        "TopLeft", 0L, "Top", 1L, "TopRight", 2L, "Left", 3L, "Center", 4L,
        "Right", 5L, "BottomLeft", 6L, "Bottom", 7L, "BottomRight", 8L);

    // Animation.loops sentinel.
    private static final Map<String, Long> ANIMATION = map("Infinite", -1L);

    // ListView/GridView.positionViewAtIndex PositionMode + snapMode.
    private static final Map<String, Long> LISTVIEW = map(
        "Beginning", 0L, "Center", 1L, "End", 2L, "Visible", 3L, "Contain", 4L, "SnapPosition", 5L,
        "NoSnap", 0L, "SnapToItem", 1L, "SnapOneItem", 2L,
        // ListView.orientation
        "Horizontal", 0L, "Vertical", 1L);

    // Image.status + fillMode.
    private static final Map<String, Long> IMAGE = map(
        "Null", 0L, "Ready", 1L, "Loading", 2L, "Error", 3L,
        "Stretch", 0L, "PreserveAspectFit", 1L, "PreserveAspectCrop", 2L,
        "Tile", 3L, "TileVertically", 4L, "TileHorizontally", 5L, "Pad", 6L,
        "AlignLeft", 1L, "AlignRight", 2L, "AlignHCenter", 4L,
        "AlignTop", 32L, "AlignBottom", 64L, "AlignVCenter", 128L);

    // Gradient.orientation.
    private static final Map<String, Long> GRADIENT = map("Vertical", 0L, "Horizontal", 1L);

    // Drag.axis (drag-and-drop axis constraint).
    private static final Map<String, Long> DRAG = map("XAxis", 1L, "YAxis", 2L, "XAndYAxis", 3L);

    // QQmlComponent::Status: Null, Ready, Loading, Error.
    private static final Map<String, Long> COMPONENT = map(
        "Null", 0L, "Ready", 1L, "Loading", 2L, "Error", 3L);

    // TapHandler.gesturePolicy.
    private static final Map<String, Long> TAP_HANDLER = map(
        "DragThreshold", 0L, "WithinBounds", 1L, "ReleaseWithinBounds", 2L, "DragWithinBounds", 3L);

    // PointerHandler.grabPermissions (a bit-flag set).
    private static final Map<String, Long> POINTER_HANDLER = map(
        "TakeOverForbidden", 0L, "CanTakeOverFromHandlersOfSameType", 1L,
        "CanTakeOverFromHandlersOfDifferentType", 2L, "CanTakeOverFromItems", 4L,
        "CanTakeOverFromAnything", 15L, "ApprovesTakeOverByHandlersOfSameType", 16L,
        "ApprovesTakeOverByHandlersOfDifferentType", 32L, "ApprovesTakeOverByItems", 64L,
        "ApprovesCancellation", 128L, "ApprovesTakeOverByAnything", 240L);

    // RotationAnimation.direction.
    private static final Map<String, Long> ROTATION_ANIMATION = map(
        "Numerical", 0L, "Shortest", 1L, "Clockwise", 2L, "Counterclockwise", 3L);

    // Canvas.renderTarget / renderStrategy.
    private static final Map<String, Long> CANVAS = map(
        "Image", 0L, "FramebufferObject", 1L,
        "Immediate", 0L, "Threaded", 1L, "Cooperative", 2L);

    // Flickable.BoundsBehavior.
    private static final Map<String, Long> FLICKABLE = map(
        "StopAtBounds", 0L, "DragOverBounds", 1L, "OvershootBounds", 2L,
        "DragAndOvershootBounds", 3L);

    private static final Map<String, Long> EASING = map(
        "Linear", 0L, "InQuad", 1L, "OutQuad", 2L, "InOutQuad", 3L, "OutInQuad", 4L,
        "InCubic", 5L, "OutCubic", 6L, "InOutCubic", 7L, "OutInCubic", 8L,
        "InQuart", 9L, "OutQuart", 10L, "InOutQuart", 11L,
        "InSine", 13L, "OutSine", 14L, "InOutSine", 15L,
        "InBack", 29L, "OutBack", 30L, "InOutBack", 31L);
}
