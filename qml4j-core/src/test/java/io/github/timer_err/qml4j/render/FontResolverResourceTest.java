package io.github.timer_err.qml4j.render;

import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontResolverResourceTest {

    @Test
    void recreatedViewsReuseFontIdentityWithIndependentLifetimes() throws Exception {
        byte[] bytes = resource("/fonts/Roboto-Regular.ttf");
        FontResolver first = new FontResolver();
        FontResolver second = new FontResolver();
        FontResolver recreated = new FontResolver();
        try {
            first.setUiTypefaces(bytes, null);
            second.setUiTypefaces(Arrays.copyOf(bytes, bytes.length), null);
            try (Typeface a = first.fontFor(16f, "QPlayer").getTypeface();
                 Typeface b = second.fontFor(16f, "QPlayer").getTypeface()) {
                assertEquals(a.getUniqueId(), b.getUniqueId());
                assertNotSame(a, b);
                first.close();
                assertFalse(b.isClosed());
                assertTrue(second.fontFor(16f, "QPlayer").measureTextWidth("QPlayer") > 0);
                second.close();
                recreated.setUiTypefaces(bytes, null);
                try (Typeface c = recreated.fontFor(16f, "QPlayer").getTypeface()) {
                    assertEquals(a.getUniqueId(), c.getUniqueId());
                }
            }
        } finally {
            first.close();
            second.close();
            recreated.close();
        }
    }

    @Test
    void replacingUiTypefaceClosesFontsCachedAgainstOldFace() throws Exception {
        FontResolver resolver = new FontResolver();
        Font old = resolver.fontFor(16f, "QPlayer");
        assertFalse(old.isClosed());

        resolver.setUiTypefaces(resource("/fonts/Roboto-Regular.ttf"), null);

        assertTrue(old.isClosed());
        Font replacement = resolver.fontFor(16f, "QPlayer");
        assertNotSame(old, replacement);
        resolver.close();
        assertTrue(replacement.isClosed());
    }

    @Test
    void replacingIconTypefaceClosesOldCachedIconFont() throws Exception {
        FontResolver resolver = new FontResolver();
        byte[] icons = resource("/fonts/MaterialSymbolsRounded.ttf");
        resolver.setIconTypeface(icons);
        Font old = resolver.iconFont(20f);

        resolver.setIconTypeface(icons);

        assertTrue(old.isClosed());
        Font replacement = resolver.iconFont(20f);
        assertNotSame(old, replacement);
        resolver.close();
        assertTrue(replacement.isClosed());
    }

    private static byte[] resource(String path) throws Exception {
        try (InputStream in = FontResolverResourceTest.class.getResourceAsStream(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
}
