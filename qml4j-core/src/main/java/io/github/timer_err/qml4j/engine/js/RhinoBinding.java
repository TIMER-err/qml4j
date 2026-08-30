package io.github.timer_err.qml4j.engine.js;

import io.github.timer_err.qml4j.engine.binding.Binding;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Script;

import java.util.Arrays;
import java.util.HashSet;

// A Property binding whose body is JavaScript run by Rhino against a QmlScope. The
// source compiles once (cached); each evaluate() runs it under the scope, so reads
// of qml4j Properties register reactive dependencies through the normal machinery.
public final class RhinoBinding extends Binding {

    private final Script script;
    private final QmlScope scope;

    @SuppressWarnings("unused")
    public RhinoBinding(String source, Object outer, Object root, String[] ids, boolean delegate,
                        String[] singletonNames, Class<?>[] singletonClasses, String[] aliasSpecs) {
        this.script = JsRuntime.compile(source, JsRuntime.loaderOf(root, outer));
        this.scope = new QmlScope(outer, root, JsRuntime.globals(),
                                  new HashSet<>(Arrays.asList(ids)), delegate,
                                  QmlScope.singletonMap(singletonNames, singletonClasses),
                                  QmlScope.aliasMap(aliasSpecs));
    }

    @Override
    @SuppressWarnings("deprecation") // Script.exec is the compiled-script entry point in Rhino 1.9
    public Object evaluate() {
        Context cx = JsRuntime.enter(scope);
        try {
            return JsWrap.toJava(script.exec(cx, scope));
        } catch (EcmaError e) {
            // QML binding semantics: an evaluation error (reading a member off a null/
            // undefined value, an undefined reference) is non-fatal -- the binding
            // yields undefined and recomputes when a dependency changes. Dependencies
            // read before the throw are already recorded, so e.g. `child.width + 1`
            // with child==null still subscribes to `child` and recomputes on set.
            return null;
        } finally {
            JsRuntime.exit();
        }
    }
}
