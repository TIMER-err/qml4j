package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Image;
import io.github.humbleui.types.IRect;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Decoded {@code Image} results of one renderer, keyed by source and decode size.
 *
 * <p>Like Qt's pixmap cache: a list delegate that is recycled or scrolled back into
 * view gets its image without loading and decoding the source again. Without it,
 * every row that entered the viewport downloaded its cover again and decoded the
 * full original before downscaling it to the row size.</p>
 *
 * <p>Each lookup returns a private copy, because an {@code Image} item closes the
 * image it adopts. The cache belongs to its renderer, so a hit cannot bypass another
 * view's resource loader or network policy.</p>
 */
final class DecodedImageCache {
    static final long BUDGET_BYTES = 16L << 20;
    // A full-screen cover would evict the whole working set of row thumbnails.
    static final long MAX_ENTRY_BYTES = BUDGET_BYTES / 8;

    private final LinkedHashMap<String, Image> entries = new LinkedHashMap<>(64, 0.75f, true);
    private long bytes;
    private boolean closed;

    static String key(String source, int width, int height) {
        return width + "x" + height + '\n' + source;
    }

    /** A copy the caller owns, or null on a miss. */
    synchronized Image copy(String key) {
        Image cached = entries.get(key);
        return cached == null ? null : duplicate(cached);
    }

    /** Keep a copy of {@code decoded}; the caller keeps ownership of {@code decoded}. */
    synchronized void put(String key, Image decoded) {
        long size = size(decoded);
        if (closed || size > MAX_ENTRY_BYTES) return;
        Image copy = duplicate(decoded);
        if (copy == null) return;
        Image previous = entries.put(key, copy);
        if (previous != null) {
            bytes -= size(previous);
            previous.close();
        }
        bytes += size;
        Iterator<Image> oldest = entries.values().iterator();
        while (bytes > BUDGET_BYTES && oldest.hasNext()) {
            Image evicted = oldest.next();
            bytes -= size(evicted);
            evicted.close();
            oldest.remove();
        }
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized void clear() {
        for (Image image : entries.values()) image.close();
        entries.clear();
        bytes = 0;
    }

    /** Release every entry and refuse decodes that finish after the renderer is gone. */
    synchronized void close() {
        clear();
        closed = true;
    }

    private static long size(Image image) {
        return 4L * image.getWidth() * image.getHeight();
    }

    private static Image duplicate(Image image) {
        return image.makeSubset(IRect.makeXYWH(0, 0, image.getWidth(), image.getHeight()));
    }
}
