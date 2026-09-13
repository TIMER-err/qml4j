package io.github.timer_err.qml4j.demo;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;
import io.github.timer_err.qml4j.render.SurfaceBackend;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public final class GlfwSurfaceBackend implements SurfaceBackend {

    private final long window;
    private final boolean gpuWait = Boolean.getBoolean("qml4j.gpuWait");
    private int width;
    private int height;
    private DirectContext context;
    private BackendRenderTarget target;
    private Surface surface;
    private FrameStamp frameStamp;

    public GlfwSurfaceBackend(long window, int width, int height) {
        this.window = window;
        this.width = width;
        this.height = height;
    }

    @Override
    public void init(int w, int h) {
        this.width = w;
        this.height = h;
        GL.createCapabilities();
        context = DirectContext.makeGL();
        rebuildSurface();
        if (Boolean.getBoolean("qml4j.frameStamp")) frameStamp = new FrameStamp(gpuWait);
    }

    @Override
    public Canvas acquireCanvas() {
        // Clear through Skija, not a raw GL11.glClear: a bare glClear is invisible
        // to Skija's DirectContext and drops the frame's first draw (the root's
        // full-surface fill), leaving the background black. canvas.clear() enters
        // Skija's own command stream so ordering is correct.
        Canvas canvas = surface.getCanvas();
        canvas.clear(0xFF000000);
        return canvas;
    }

    @Override
    public DirectContext recordingContext() {
        return context;
    }

    @Override
    public void present() {
        if (frameStamp != null) frameStamp.draw(surface.getCanvas(), width);
        // Hand the surface to the window system only after Skia has submitted its
        // drawing commands. flush() alone does not complete that handoff. Keep
        // CPU/GPU execution asynchronous; the swap interval controls presentation.
        context.flushAndSubmit(surface);
        // Diagnostic A/B: finish this context's GPU work before the window handoff.
        // Disabled normally because it serializes CPU/GPU execution; it does not
        // wait for the compositor or prove that the frame reached the display.
        if (gpuWait) GL11.glFinish();
        GLFW.glfwSwapBuffers(window);
    }

    @Override
    public void resize(int w, int h) {
        if (w == width && h == height) return;
        this.width = w;
        this.height = h;
        rebuildSurface();
    }

    @Override
    public int width() { return width; }

    @Override
    public int height() { return height; }

    @Override
    public void dispose() {
        if (frameStamp != null) { frameStamp.close(); frameStamp = null; }
        if (surface != null) { surface.close(); surface = null; }
        if (target != null) { target.close(); target = null; }
        if (context != null) { context.close(); context = null; }
    }

    // makeFromBackendRenderTarget is deprecated in this skija build but is the working GL path.
    @SuppressWarnings("deprecation")
    private void rebuildSurface() {
        if (surface != null) surface.close();
        if (target != null) target.close();
        target = BackendRenderTarget.makeGL(width, height, 0, 8, 0,
                                            FramebufferFormat.GR_GL_RGBA8);
        surface = Surface.makeFromBackendRenderTarget(
            context, target,
            SurfaceOrigin.BOTTOM_LEFT,
            ColorType.RGBA_8888,
            ColorSpace.getSRGB());
    }
}
