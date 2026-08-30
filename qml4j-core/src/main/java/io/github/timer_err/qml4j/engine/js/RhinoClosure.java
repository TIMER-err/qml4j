package io.github.timer_err.qml4j.engine.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Script;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

// Shared runtime for the Rhino-backed handler and function: a QML handler/function
// body wrapped as a JS function expression so `return` and parameters work as real
// JS, closed over a QmlScope. The wrapper compiles once (cached by source); the
// Function instance is materialised lazily and reused, and each call binds the
// arguments and resolves bare names through the activation -> QmlScope -> globals
// chain.
//
// The body is wrapped rather than executed directly because a QML function body is a
// brace block that may contain a top-level `return` -- illegal in a bare script but
// fine inside a function. An evaluation error is swallowed (null), matching the
// binding backend's QML-null tolerance.
public final class RhinoClosure {

    private final Script script;
    private final QmlScope scope;
    private Function fn;

    public RhinoClosure(String body, List<String> params, Object outer, Object root,
                        String[] ids, boolean delegate,
                        String[] singletonNames, Class<?>[] singletonClasses, String[] aliasSpecs) {
        this.script = JsRuntime.compile(wrap(body, params), JsRuntime.loaderOf(root, outer));
        this.scope = new QmlScope(outer, root, JsRuntime.globals(),
                                  new HashSet<>(Arrays.asList(ids)), delegate,
                                  QmlScope.singletonMap(singletonNames, singletonClasses),
                                  QmlScope.aliasMap(aliasSpecs));
    }

    // The function-expression form fed to Rhino. Shared with the compiler so its
    // eager parity check validates exactly what runs. The body is always braced: a
    // statement-block body (`{ ... }`) becomes a harmless inner block, while a bare
    // body (`if (x) y`, `foo()`) needs the braces to be a legal function body.
    public static String wrap(String body, List<String> params) {
        return "(function(" + String.join(",", params) + "){" + body + "})";
    }

    @SuppressWarnings("deprecation") // Script.exec is the compiled-script entry point in Rhino 1.9
    public Object invoke(Object[] args) {
        Context cx = JsRuntime.enter(scope);
        try {
            Function f = fn;
            if (f == null) {
                f = (Function) script.exec(cx, scope);
                fn = f;
            }
            Object[] js = new Object[args.length];
            for (int i = 0; i < args.length; i++) js[i] = JsWrap.toJs(args[i], scope);
            return JsWrap.toJava(f.call(cx, scope, scope, js));
        } catch (EcmaError e) {
            return null;
        } finally {
            JsRuntime.exit();
        }
    }
}
