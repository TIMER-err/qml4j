package io.github.timer_err.qml4j.demo;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.Clipboard;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.SurfaceBackend;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// Owns the live QmlView and routes already-framebuffer-scaled input into it. A fresh
// QmlEngine/QmlView is built per loaded document (mirroring the Android shell). All
// coordinates are framebuffer pixels, matching root width/height.
final class DesktopHost {

    private final ResourceLoader loader;
    private final Clipboard clipboard;
    private QmlView view;
    private int fbW;
    private int fbH;

    DesktopHost(ResourceLoader loader, int fbW, int fbH, Clipboard clipboard) {
        this.loader = loader;
        this.fbW = fbW;
        this.fbH = fbH;
        this.clipboard = clipboard;
    }

    // Load and render an entry .qml resolved by the loader. Untrusted content: a
    // compile/load failure shows an error page instead of taking the host down.
    void run(String entry) {
        run(entry, null);
    }

    // Load `entry` with optional host context properties (e.g. a mock `client` model), so a
    // document that reads a context object can run in this demo host.
    void run(String entry, Map<String, Object> context) {
        byte[] bytes = loader.load(entry);
        if (bytes == null) {
            setView(loader, errorQml(entry, new IllegalStateException("not found: " + entry)), null, "");
            return;
        }
        // The document's directory (relative to the resource root) so its `import "."`
        // sibling types (ClickGui's ValueRow/ModeDropdown) resolve.
        int slash = entry.lastIndexOf('/');
        String baseDir = slash < 0 ? "" : entry.substring(0, slash);
        try {
            setView(loader, new String(bytes, StandardCharsets.UTF_8), context, baseDir);
            System.out.println("[host] loaded " + entry + " root="
                + (view.root() == null ? "null" : view.root().getClass().getSimpleName())
                + " children=" + (view.root() == null ? 0 : view.root().children.size()));
            System.out.flush();
        } catch (RuntimeException e) {
            System.out.println("[host] load FAILED: " + entry + " -> " + e);
            System.out.flush();
            setView(loader, errorQml(entry, e), null, "");
        }
    }

    // Run the bundled upstream MD3 app (Main.qml) with its own resource loader and
    // the host context properties the app expects.
    void startApp() {
        AppResourceLoader appLoader = new AppResourceLoader();
        byte[] bytes = appLoader.load("Main.qml");
        if (bytes == null) {
            setView(loader, errorQml("App", new IllegalStateException("Main.qml not found (no mcq clone / bundle)")), null, "");
            return;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("AppFeatures", AppFeaturesMap.all());
        ctx.put("HotReloadEnabled", Boolean.FALSE);
        ctx.put("ProjectSourceDir", "");
        try {
            setView(appLoader, new String(bytes, StandardCharsets.UTF_8), ctx, "");
        } catch (RuntimeException e) {
            setView(loader, errorQml("App", e), null, "");
        }
    }

    private void setView(ResourceLoader rl, String qml, Map<String, Object> context, String baseDir) {
        if (view != null) view.dispose();
        QmlEngine engine = new QmlEngine();
        view = QmlView.withStockTypes(engine).resources(rl);
        HostFonts.configure(view, rl);
        view.setClipboard(clipboard);
        if (context != null) {
            for (Map.Entry<String, Object> e : context.entrySet()) view.context(e.getKey(), e.getValue());
        }
        view.load(qml, baseDir);
        sizeRoot();
    }

    // No \n escapes in the generated string: the error page must never itself fail
    // to compile, so it sticks to plain literals and a wrapped Text for the message.
    private static String errorQml(String title, Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        msg = msg.split("\n")[0].replace("\"", "'").replace("\\", "/");
        return "import QtQuick\n"
            + "Rectangle { x: 0; y: 0; color: \"#3b1f1f\"\n"
            + "  Text { x: 24; y: 24; color: \"#ffd0d0\"; fontSize: 20;\n"
            + "    text: \"" + title + " failed to load:\" }\n"
            + "  Text { x: 24; y: 64; width: parent.width - 48; wrapMode: Text.WordWrap;\n"
            + "    color: \"#ffb0b0\"; fontSize: 15; text: \"" + msg + "\" }\n"
            + "}\n";
    }

    private void sizeRoot() {
        if (view.root() == null) return;
        view.root().x.set(0);
        view.root().y.set(0);
        view.root().width.set(fbW);
        view.root().height.set(fbH);
    }

    void resize(int w, int h) {
        fbW = w;
        fbH = h;
        if (view != null) sizeRoot();
    }

    void renderFrame(SurfaceBackend backend) {
        if (view != null) view.renderFrame(backend);
    }

    void pointerDown(float x, float y) {
        if (view != null) view.dispatchPointerDown(x, y);
    }

    void pointerMove(float x, float y) {
        if (view != null) view.dispatchPointerMove(x, y);
    }

    void pointerUp(float x, float y) {
        if (view != null) view.dispatchPointerUp(x, y);
    }

    void wheel(float x, float y, float dx, float dy) {
        if (view != null) view.dispatchWheel(x, y, dx, dy);
    }

    void key(int code, String text, boolean down, boolean shift) {
        if (view != null) view.dispatchKey(code, text, down, shift);
    }

    void text(String s) {
        if (view != null && s != null && !s.isEmpty()) view.dispatchKey(0, s, true);
    }

    void dispose() {
        if (view != null) view.dispose();
    }
}
