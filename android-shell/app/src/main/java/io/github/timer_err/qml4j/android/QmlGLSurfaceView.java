package io.github.timer_err.qml4j.android;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import io.github.humbleui.skija.BackendRenderTarget;
import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ColorSpace;
import io.github.humbleui.skija.ColorType;
import io.github.humbleui.skija.DirectContext;
import io.github.humbleui.skija.FramebufferFormat;
import io.github.humbleui.skija.Surface;
import io.github.humbleui.skija.SurfaceOrigin;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.render.ResourceLoader;
import io.github.timer_err.qml4j.render.SurfaceBackend;
import io.github.timer_err.qml4j.render.items.input.TextEditable;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public final class QmlGLSurfaceView extends GLSurfaceView {

    private final String qmlSource;
    private final QmlEngine engine;
    private final ResourceLoader resources;
    private QmlView view;
    private SkijaGlSurface surface;
    // Logical-pixel scale: QML is authored in dp; render at the screen density so
    // Material components are physically sized (and the canvas drawn larger).
    private final float uiScale;
    // UI-thread state: secondary fingers never replace the stream's first pointer.
    private int activeTouchPointerId = MotionEvent.INVALID_POINTER_ID;

    public QmlGLSurfaceView(Context ctx, QmlEngine engine, String qmlSource, ResourceLoader resources,
                            float uiScale) {
        super(ctx);
        this.engine = engine;
        this.qmlSource = qmlSource;
        this.resources = resources;
        this.uiScale = uiScale > 0 ? uiScale : 1f;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 8);
        setRenderer(new GlRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activeTouchPointerId = ev.getPointerId(ev.getActionIndex());
            requestFocus();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            return true;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (ev.getPointerId(ev.getActionIndex()) != activeTouchPointerId) return true;
            action = MotionEvent.ACTION_UP;
        } else if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP
                && action != MotionEvent.ACTION_CANCEL) {
            return false;
        }
        if (activeTouchPointerId == MotionEvent.INVALID_POINTER_ID) return true;
        final int pointerIndex = ev.findPointerIndex(activeTouchPointerId);
        if (pointerIndex < 0) action = MotionEvent.ACTION_CANCEL;
        final int dispatchedAction = action;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activeTouchPointerId = MotionEvent.INVALID_POINTER_ID;
        }
        // Own the complete batch until the GL thread finishes; never retain the
        // framework event or allocate a runnable/coordinate array per sample.
        final MotionEvent event = MotionEvent.obtain(ev);
        queueEvent(new Runnable() {
            @Override public void run() {
                try {
                    if (view == null) return;
                    DirtyQueue dq = view.dirtyQueue();
                    boolean ownBatch = DirtyQueue.current() != dq;
                    if (ownBatch) dq.install();
                    try {
                        // Replay velocity history without reevaluating bindings
                        // for every historical sample in the same MotionEvent.
                        dispatchTouchEvent(event, pointerIndex, dispatchedAction);
                    } finally {
                        if (ownBatch) {
                            try { dq.flush(); }
                            finally { dq.uninstall(); }
                        }
                    }
                } finally {
                    event.recycle();
                }
            }
        });
        return true;
    }

    private void dispatchTouchEvent(MotionEvent event, int pointerIndex, int action) {
        if (view == null) return;
        if (action == MotionEvent.ACTION_CANCEL) {
            view.dispatchPointerCancel();
            return;
        }
        float x = event.getX(pointerIndex) / uiScale;
        float y = event.getY(pointerIndex) / uiScale;
        long eventTimeNanos = event.getEventTime() * 1_000_000L;
        if (action == MotionEvent.ACTION_DOWN) {
            boolean hitTextEditable = view.pickTextEditable(x, y) != null;
            view.dispatchPointerDown(x, y, eventTimeNanos);
            if (hitTextEditable) showImeOnUiThread();
            return;
        }
        for (int i = 0; i < event.getHistorySize(); i++) {
            view.dispatchPointerMove(event.getHistoricalX(pointerIndex, i) / uiScale,
                    event.getHistoricalY(pointerIndex, i) / uiScale,
                    event.getHistoricalEventTime(i) * 1_000_000L);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            view.dispatchPointerMove(x, y, eventTimeNanos);
        } else if (action == MotionEvent.ACTION_UP) {
            view.dispatchPointerUp(x, y, eventTimeNanos);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return view != null && view.focused() instanceof TextEditable;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (view != null && view.focused() instanceof TextEditable) {
            TextEditable ti = (TextEditable) view.focused();
            int pos = ti.cursorPosition();
            outAttrs.initialSelStart = pos;
            outAttrs.initialSelEnd = pos;
        }
        return new QmlInputConnection(this, true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (dispatchKeyEvent(event, true)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (dispatchKeyEvent(event, false)) return true;
        return super.onKeyUp(keyCode, event);
    }

    boolean dispatchKeyEvent(KeyEvent event, boolean down) {
        if (view == null) return false;
        if (!(view.focused() instanceof TextEditable)) return false;
        if (down && handleClipboardShortcut(event)) return true;
        final int mapped = mapKeyCode(event.getKeyCode());
        final String text;
        if (mapped == 0) {
            int unicode = event.getUnicodeChar();
            if (unicode == 0) return false;
            text = new String(Character.toChars(unicode));
        } else {
            text = null;
        }
        final boolean isDown = down;
        final boolean shift = event.isShiftPressed();
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(mapped, text, isDown, shift);
            }
        });
        return true;
    }

    private boolean handleClipboardShortcut(KeyEvent event) {
        if (!event.isCtrlPressed()) return false;
        int kc = event.getKeyCode();
        if (kc != KeyEvent.KEYCODE_C && kc != KeyEvent.KEYCODE_X && kc != KeyEvent.KEYCODE_V) return false;
        final int action = kc;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                if (action == KeyEvent.KEYCODE_C) view.copy();
                else if (action == KeyEvent.KEYCODE_X) view.cut();
                else view.paste();
            }
        });
        return true;
    }

    void commitTextFromIme(final CharSequence text) {
        if (text == null) return;
        final String s = text.toString();
        if (s.isEmpty()) return;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(0, s, true);
            }
        });
    }

    QmlView qmlView() {
        return view;
    }

    void sendSyntheticKey(final int code, final String text) {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                view.dispatchKey(code, text, true, false);
                view.dispatchKey(code, text, false, false);
            }
        });
    }

    void deleteFromIme(final int beforeLength) {
        if (beforeLength <= 0) return;
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                for (int i = 0; i < beforeLength; i++) {
                    view.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
                }
            }
        });
    }

    void performImeEnter() {
        queueEvent(new Runnable() {
            @Override public void run() {
                if (view != null) view.dispatchKey(QmlView.KEY_ENTER, null, true);
            }
        });
    }

    private static int mapKeyCode(int kc) {
        if (kc == KeyEvent.KEYCODE_DEL) return QmlView.KEY_BACKSPACE;
        if (kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) return QmlView.KEY_ENTER;
        if (kc == KeyEvent.KEYCODE_DPAD_LEFT) return QmlView.KEY_LEFT;
        if (kc == KeyEvent.KEYCODE_DPAD_RIGHT) return QmlView.KEY_RIGHT;
        if (kc == KeyEvent.KEYCODE_DPAD_UP) return QmlView.KEY_UP;
        if (kc == KeyEvent.KEYCODE_DPAD_DOWN) return QmlView.KEY_DOWN;
        if (kc == KeyEvent.KEYCODE_MOVE_HOME) return QmlView.KEY_HOME;
        if (kc == KeyEvent.KEYCODE_MOVE_END) return QmlView.KEY_END;
        return 0;
    }

    private void hideImeOnUiThread() {
        runOnUi(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = imm();
                if (imm == null) return;
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        });
    }

    private void showImeOnUiThread() {
        runOnUi(new Runnable() {
            @Override public void run() {
                InputMethodManager imm = imm();
                if (imm == null) return;
                requestFocus();
                imm.restartInput(QmlGLSurfaceView.this);
                imm.showSoftInput(QmlGLSurfaceView.this, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private InputMethodManager imm() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    private void runOnUi(Runnable r) {
        Context ctx = getContext();
        if (ctx instanceof Activity) ((Activity) ctx).runOnUiThread(r);
    }

    private final class GlRenderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            surface = new SkijaGlSurface();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            surface.resize(width, height);
            if (view == null) {
                view = QmlView.withStockTypes(engine).resources(resources);
                view.setClipboard(new AndroidClipboard(getContext()));
                view.setFocusListener((nf, of) -> {
                    if (of instanceof TextEditable && !(nf instanceof TextEditable)) {
                        hideImeOnUiThread();
                    }
                });
                view.load(qmlSource);
            }
            if (view.root() != null) {
                view.root().width.set(width / uiScale);
                view.root().height.set(height / uiScale);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (view == null) return;
            DirtyQueue dq = view.dirtyQueue();
            dq.install();
            try {
                view.tickAnimations(System.nanoTime());
                dq.flush();
                Canvas canvas = surface.acquireCanvas();
                int sc = canvas.save();
                canvas.scale(uiScale, uiScale);
                // The view's own renderer is wired with the component factory + resource
                // loader (via resources()); reuse it rather than a bare Renderer.
                io.github.timer_err.qml4j.render.Renderer renderer = view.renderer();
                renderer.setGpuContext(surface.recordingContext());
                renderer.render(canvas, view.root());
                canvas.restoreToCount(sc);
                surface.present();
            } finally {
                dq.uninstall();
            }
        }
    }

    private static final class SkijaGlSurface implements SurfaceBackend {
        private DirectContext context;
        private BackendRenderTarget target;
        private Surface surface;
        private int width, height;

        @Override
        public void init(int w, int h) {
            this.width = w;
            this.height = h;
            context = DirectContext.makeGL();
            rebuild();
        }

        @Override
        public Canvas acquireCanvas() {
            return surface.getCanvas();
        }

        @Override
        public void present() {
            context.flush();
        }

        @Override
        public void resize(int w, int h) {
            if (context == null) {
                init(w, h);
                return;
            }
            if (w == width && h == height) return;
            this.width = w;
            this.height = h;
            rebuild();
        }

        @Override
        public int width() { return width; }

        @Override
        public int height() { return height; }

        @Override
        public DirectContext recordingContext() { return context; }

        @Override
        public void dispose() {
            if (surface != null) { surface.close(); surface = null; }
            if (target != null) { target.close(); target = null; }
            if (context != null) { context.close(); context = null; }
        }

        private void rebuild() {
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
}
