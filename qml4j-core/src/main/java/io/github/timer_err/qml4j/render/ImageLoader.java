package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.core.Image;

import io.github.humbleui.skija.FilterMipmap;
import io.github.humbleui.skija.FilterMode;
import io.github.humbleui.skija.ImageInfo;
import io.github.humbleui.skija.MipmapMode;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.types.Rect;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Background load + decode of an Image source (local or remote). A daemon thread loads
// the bytes and rasterizes them, so Skija's makeFromEncoded/downscale never runs on the
// render thread (which would stall the frame a cover switches or a thumbnail scrolls in).
final class ImageLoader {
    private static final int MAX_REMOTE_BYTES = 16 * 1024 * 1024;

    private ImageLoader() {}

    static boolean isRemote(String src) {
        return src.startsWith("http://") || src.startsWith("https://");
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "qml4j-image-fetch");
        t.setDaemon(true);
        return t;
    });

    // Load (local or remote) AND decode the source off the render thread into a raster
    // image the render thread just adopts. `gen` guards a superseded source: if the node's
    // decodeGen moved on (a newer source, or the item was released) the result is dropped.
    static void decode(Image node, String src, long gen, ResourceLoader resources,
                       NetworkResourcePolicy networkPolicy) {
        POOL.submit(() -> {
            byte[] bytes = null;
            try {
                if (isRemote(src)) bytes = get(src, 5, networkPolicy);
                else if (resources != null) bytes = resources.load(src);
            } catch (Throwable ignore) {
                // bytes stays null -> treated as a failed load below
            }
            io.github.humbleui.skija.Image img = null;
            int w = 0, h = 0;
            if (bytes != null) {
                try {
                    img = decodeRaster(bytes,
                            node.sourceSize.width.peek().intValue(),
                            node.sourceSize.height.peek().intValue());
                    w = img.getWidth();
                    h = img.getHeight();
                } catch (Throwable t) {
                    img = null;
                }
            }
            if (node.decodeGen != gen) {   // superseded -> drop
                if (img != null) img.close();
                return;
            }
            node.pendW = w;
            node.pendH = h;
            node.pendingImage = img;
            node.decodeReadyGen = gen;
            // A cache boundary may be replaying a picture recorded while this image was still
            // loading. Completion itself must invalidate it; otherwise adoption waits for an
            // unrelated event (typically a list scroll) to dirty the scene.
            node.markContentDirtyAsync();
        });
    }

    // Decode encoded bytes into a RASTER image, forcing the real decode here (off the
    // render thread) by drawing into a raster surface -- makeFromEncoded alone is lazy and
    // would otherwise decode on first draw, back on the render thread. Honours sourceSize
    // like Qt: shrink to ~display size so a multi-megapixel photo isn't sampled every frame.
    private static io.github.humbleui.skija.Image decodeRaster(byte[] bytes, int sw, int sh) {
        io.github.humbleui.skija.Image full = io.github.humbleui.skija.Image.makeDeferredFromEncodedBytes(bytes);
        int iw = full.getWidth(), ih = full.getHeight();
        float f;
        if (sw > 0 && sh > 0) f = Math.max((float) sw / iw, (float) sh / ih);
        else if (sw > 0) f = (float) sw / iw;
        else if (sh > 0) f = (float) sh / ih;
        else f = 1f;
        int tw = f < 1f ? Math.max(1, Math.round(iw * f)) : iw;
        int th = f < 1f ? Math.max(1, Math.round(ih * f)) : ih;
        try (Surface surf = Surface.makeRaster(ImageInfo.makeN32Premul(tw, th))) {
            surf.getCanvas().drawImageRect(full,
                    Rect.makeXYWH(0, 0, iw, ih), Rect.makeXYWH(0, 0, tw, th),
                    new FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true);
            io.github.humbleui.skija.Image raster = surf.makeImageSnapshot();
            full.close();
            return raster;
        }
    }

    // Honour the standard HTTPS_PROXY/HTTP_PROXY env vars (curl/wget convention) -- the
    // JVM ignores them by default, so a remote image would just time out behind a proxy.
    private static final Proxy PROXY = detectProxy();

    private static Proxy detectProxy() {
        String p = env("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");
        if (p == null) return Proxy.NO_PROXY;
        try {
            URL u = new URL(p);
            int port = u.getPort() < 0 ? 8080 : u.getPort();
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(u.getHost(), port));
        } catch (Exception e) {
            return Proxy.NO_PROXY;
        }
    }

    private static String env(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    // HttpURLConnection only auto-follows same-scheme redirects; GitHub raw -> CDN often
    // crosses http<->https, so follow Location manually with a hop limit.
    private static byte[] get(String url, int redirects, NetworkResourcePolicy policy) throws Exception {
        if (redirects < 0) return null;
        if (policy != null && !policy.allow(url)) return null;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(PROXY);
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "qml4j");
        int code = conn.getResponseCode();
        if (code >= 300 && code < 400) {
            String loc = conn.getHeaderField("Location");
            return loc == null ? null : get(new URL(new URL(url), loc).toString(), redirects - 1, policy);
        }
        if (code != 200) return null;
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > MAX_REMOTE_BYTES) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
