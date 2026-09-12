package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Data;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.Typeface;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses injected font identities across views without sharing closeable wrappers. */
final class SharedTypefaceCache {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 8;
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(8, 0.75f, true);
    private static long retainedBytes;

    private SharedTypefaceCache() { }

    static synchronized Typeface acquire(FontMgr manager, byte[] bytes) {
        String key = digest(bytes);
        Entry entry = ENTRIES.get(key);
        if (entry != null) return entry.font.getTypeface();

        Typeface face;
        try (Data data = Data.makeFromBytes(bytes)) {
            face = manager.makeFromData(data);
        }
        if (face == null || bytes.length > MAX_BYTES) return face;
        // Font owns a native reference; getTypeface() returns another independently
        // owned wrapper. A view can close its handle while other views keep using
        // the same native font ID. Repeated CJK loads would otherwise create new
        // IDs whose complete data buffers linger in HarfBuzz's face cache.
        try {
            entry = new Entry(new Font(face), bytes.length);
            ENTRIES.put(key, entry);
            retainedBytes += bytes.length;
            evict();
            return entry.font.getTypeface();
        } finally {
            face.close();
        }
    }

    private static void evict() {
        Iterator<Entry> entries = ENTRIES.values().iterator();
        while (retainedBytes > MAX_BYTES || ENTRIES.size() > MAX_ENTRIES) {
            Entry entry = entries.next();
            entries.remove();
            retainedBytes -= entry.bytes;
            entry.font.close();
        }
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder key = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                key.append(Character.forDigit((value >> 4) & 15, 16));
                key.append(Character.forDigit(value & 15, 16));
            }
            return key.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Entry {
        final Font font;
        final int bytes;

        Entry(Font font, int bytes) {
            this.font = font;
            this.bytes = bytes;
        }
    }
}
