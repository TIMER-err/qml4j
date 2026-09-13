package io.github.timer_err.qml4j.demo;

import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Shared font setup for the desktop window and raster previews. */
final class HostFonts {
    private HostFonts() {}

    static void configure(QmlView view, ResourceLoader resources) {
        view.uiTypefaces(load(resources, "Roboto-Regular.ttf"), load(resources, "Roboto-Medium.ttf"));
        view.iconTypeface(load(resources, "MaterialSymbolsRounded.ttf"));
    }

    private static byte[] load(ResourceLoader resources, String name) {
        String path = "fonts/" + name;
        byte[] supplied = resources.load(path);
        if (supplied != null) return supplied;
        try (InputStream in = HostFonts.class.getResourceAsStream("/" + path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) return null;
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled font " + path, e);
        }
    }
}
