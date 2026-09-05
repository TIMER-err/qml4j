package io.github.timer_err.qml4j.render.items.core;

import io.github.timer_err.qml4j.render.Renderer;

public final class ColorMath {

    public static int parse(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) return Renderer.parseColor((String) v);
        return 0xFF000000;
    }

    public static String formatHex(int argb) {
        return String.format("#%02x%02x%02x%02x",
            (argb >>> 24) & 0xFF,
            (argb >>> 16) & 0xFF,
            (argb >>> 8) & 0xFF,
            argb & 0xFF);
    }

    public static int lerpHsv(int a, int b, double t) {
        float[] ha = rgbToHsv(a);
        float[] hb = rgbToHsv(b);
        float aA = ((a >>> 24) & 0xFF) / 255f;
        float aB = ((b >>> 24) & 0xFF) / 255f;
        // Achromatic colors have no meaningful hue; keep the chromatic endpoint's
        // hue so a color fading to gray does not detour through magenta.
        float h;
        if (ha[1] == 0f) h = hb[0];
        else if (hb[1] == 0f) h = ha[0];
        else h = lerpHue(ha[0], hb[0], (float) t);
        float s = ha[1] + (hb[1] - ha[1]) * (float) t;
        float v = ha[2] + (hb[2] - ha[2]) * (float) t;
        float alpha = aA + (aB - aA) * (float) t;
        int rgb = hsvToRgb(h, s, v);
        int aOut = Math.round(clamp01(alpha) * 255f) & 0xFF;
        return (aOut << 24) | (rgb & 0x00FFFFFF);
    }

    private static float lerpHue(float a, float b, float t) {
        float d = b - a;
        if (d > 180f) d -= 360f;
        else if (d < -180f) d += 360f;
        float h = a + d * t;
        if (h < 0f) h += 360f;
        if (h >= 360f) h -= 360f;
        return h;
    }

    private static float[] rgbToHsv(int argb) {
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d == 0f) h = 0f;
        else if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * (((b - r) / d) + 2f);
        else h = 60f * (((r - g) / d) + 4f);
        if (h < 0f) h += 360f;
        float s = max == 0f ? 0f : d / max;
        return new float[]{h, s, max};
    }

    private static int hsvToRgb(float h, float s, float v) {
        s = clamp01(s);
        v = clamp01(v);
        float c = v * s;
        float hh = (h / 60f) % 6f;
        if (hh < 0f) hh += 6f;
        float x = c * (1f - Math.abs((hh % 2f) - 1f));
        float r1, g1, b1;
        if (hh < 1f) { r1 = c; g1 = x; b1 = 0f; }
        else if (hh < 2f) { r1 = x; g1 = c; b1 = 0f; }
        else if (hh < 3f) { r1 = 0f; g1 = c; b1 = x; }
        else if (hh < 4f) { r1 = 0f; g1 = x; b1 = c; }
        else if (hh < 5f) { r1 = x; g1 = 0f; b1 = c; }
        else { r1 = c; g1 = 0f; b1 = x; }
        float m = v - c;
        int r = Math.round((r1 + m) * 255f) & 0xFF;
        int g = Math.round((g1 + m) * 255f) & 0xFF;
        int b = Math.round((b1 + m) * 255f) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(v, 1f));
    }

    private ColorMath() {}
}
