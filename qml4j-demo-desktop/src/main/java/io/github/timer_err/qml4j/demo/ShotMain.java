package io.github.timer_err.qml4j.demo;

import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.EncodedImageFormat;
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.SurfaceBackend;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// Headless raster of a project dir's entry .qml to /tmp/shot-<tag>.png, for visual
// verification without a display. Sibling of AppPng (which renders the mcq app).
//   args: <dir> <entry.qml> [w] [h] [tag] [frames] [dark|light] [seedColor]
//   e.g. ShotMain shared-qml showcases/FisProxyShowcase.qml 1280 872 _x 30 dark "#2196F3"
final class ShotMain {
    // Dev screenshot tool: raster Surfaces live for the process lifetime, and the skija encode
    // APIs are deprecated-but-functional -- not worth migrating a throwaway tool.
    @SuppressWarnings({"deprecation", "resource"})
    public static void main(String[] a) throws Exception {
        if (a.length < 2) { System.out.println("usage: <dir> <entry.qml> [w h tag frames dark|light seed]"); return; }
        String dir = a[0];
        String entry = a[1];
        int w = a.length > 2 ? Integer.parseInt(a[2]) : 1280;
        int h = a.length > 3 ? Integer.parseInt(a[3]) : 872;
        String tag = a.length > 4 ? a[4] : "";
        int frames = a.length > 5 ? Integer.parseInt(a[5]) : 30;
        boolean dark = a.length <= 6 || a[6].equals("dark");
        String seed = a.length > 7 ? a[7] : null;
        io.github.timer_err.qml4j.runtime.color.StyleManager sm =
            (io.github.timer_err.qml4j.runtime.color.StyleManager) io.github.timer_err.qml4j.runtime.color.StyleManager.__instance();
        sm.isDarkTheme.set(dark);
        if (seed != null) sm.seedColor.set(seed);
        DirResourceLoader loader = new DirResourceLoader(Paths.get(dir));
        QmlView v = QmlView.withStockTypes(new QmlEngine()).resources(loader);
        HostFonts.configure(v, loader);
        byte[] b = loader.load(entry);
        if (b == null) { System.out.println("entry not found: " + entry); return; }
        try { v.load(new String(b, StandardCharsets.UTF_8)); }
        catch (Throwable t) { System.out.println("LOAD FAIL"); t.printStackTrace(System.out); return; }
        v.root().x.set(0); v.root().y.set(0); v.root().width.set(w); v.root().height.set(h);
        Surface s = Surface.makeRasterN32Premul(Math.max(1, w), Math.max(1, h));
        ShotBackend bk = new ShotBackend(s, Math.max(1, w), Math.max(1, h));
        for (int i = 0; i < frames; i++) { s.getCanvas().clear(0x00000000); v.renderFrame(bk); Thread.sleep(10); }
        Surface flat = Surface.makeRasterN32Premul(Math.max(1, w), Math.max(1, h));
        flat.getCanvas().clear(dark ? 0xFF141218 : 0xFFFEF7FF);
        s.draw(flat.getCanvas(), 0, 0, null);
        Path out = Paths.get("/tmp/shot" + tag + ".png");
        Files.write(out, flat.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG).getBytes());
        System.out.println("wrote " + out);
    }
    static final class ShotBackend implements SurfaceBackend {
        final Surface s; int w, h;
        ShotBackend(Surface s, int w, int h) { this.s = s; this.w = w; this.h = h; }
        public void init(int w, int h) {}
        public Canvas acquireCanvas() { return s.getCanvas(); }
        public void present() {}
        public void resize(int w, int h) { this.w = w; this.h = h; }
        public void dispose() {}
        public int width() { return w; }
        public int height() { return h; }
    }
}
