package io.github.timer_err.qml4j.demo;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.Font;
import io.github.humbleui.skija.FontMgr;
import io.github.humbleui.skija.FontStyle;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.Typeface;
import io.github.humbleui.types.Rect;

/** Submission order marker, independent of the QML tree and its paint caches. */
final class FrameStamp implements AutoCloseable {
    private final Typeface typeface = FontMgr.getDefault().matchFamilyStyle("monospace", FontStyle.NORMAL);
    private final Font font = new Font(typeface, 14f);
    private final Paint paint = new Paint();
    private final String mode;
    private long sequence;

    FrameStamp(boolean gpuWait) {
        mode = " " + GlfwPlatform.currentName() + (gpuWait ? " wait" : " async");
    }

    void draw(Canvas canvas, int width) {
        long frame = ++sequence;
        float x = Math.max(0, width - 256);
        canvas.save();
        canvas.resetMatrix();
        paint.setColor(0xFF000000);
        canvas.drawRect(Rect.makeXYWH(x, 0, 256, 36), paint);
        paint.setColor(0xFF00E676);
        canvas.drawString("Frame " + frame + mode, x + 8, 16, font, paint);
        // Fixed cells allow decoding a recording even when text has motion/compression blur.
        // Least significant bit is on the left; the text retains the full sequence number.
        for (int bit = 0; bit < 24; bit++) {
            paint.setColor((frame & (1L << bit)) == 0 ? 0xFF000000 : 0xFFFFFFFF);
            canvas.drawRect(Rect.makeXYWH(x + 8 + bit * 10, 22, 10, 10), paint);
        }
        canvas.restore();
    }

    @Override
    public void close() {
        paint.close();
        font.close();
        if (typeface != null) typeface.close();
    }
}
