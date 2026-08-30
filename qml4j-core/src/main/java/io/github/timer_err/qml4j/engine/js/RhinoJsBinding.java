package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.engine.binding.Binding;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

// The Binding produced by Qt.binding(() => expr) on the Rhino backend. It holds the
// arrow as a live JS function closed over its QmlScope; each evaluate() re-runs it, so
// reads of qml4j Properties register reactive dependencies through the normal
// machinery -- the same reactivity an ASM Qt.binding gets, but defined from JS.
//
// Only the arrow form reaches here: the bare-expression form `Qt.binding(expr)` would
// have been eagerly evaluated by Rhino before the call, losing its laziness, so the
// compiler keeps that form on the ASM backend.
public final class RhinoJsBinding extends Binding {

    private final Function fn;
    private final Scriptable scope;

    public RhinoJsBinding(Function fn, Scriptable scope) {
        this.fn = fn;
        this.scope = scope;
    }

    @Override
    public Object evaluate() {
        Context cx = JsRuntime.enter(scope);
        try {
            return JsWrap.toJava(fn.call(cx, scope, scope, ScriptRuntime.emptyArgs));
        } catch (EcmaError e) {
            return null;
        } finally {
            JsRuntime.exit();
        }
    }
}
