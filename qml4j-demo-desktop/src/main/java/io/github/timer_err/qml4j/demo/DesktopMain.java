package io.github.timer_err.qml4j.demo;

import io.github.timer_err.qml4j.render.Clipboard;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.runtime.color.StyleManager;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class DesktopMain {

    private static final int INITIAL_W = 720;
    private static final int INITIAL_H = 720;

    private long window;
    private boolean glfwInitialized;
    private GlfwSurfaceBackend backend;
    private DesktopHost host;

    // glfwGetCursorPos reports window (screen) coordinates; the QML root is sized in
    // framebuffer pixels. On HiDPI those differ, so scale every pointer coordinate.
    private float scaleX = 1f;
    private float scaleY = 1f;
    private double cursorX;
    private double cursorY;
    // Latest cursor position, dispatched once per frame. GLFW delivers many cursor-move
    // events per poll; firing pointerMove on each runs the full hit-test + handler chain
    // (e.g. a ColorPicker slider's onMoved regenerates the whole MD3 scheme, ~6 ms) several
    // times before a single repaint -- so coalesce to one move per frame, as Qt does with
    // AA_CompressHighFrequencyEvents.
    private boolean cursorMoved;

    public static void main(String[] args) {
        new DesktopMain().run(args);
    }

    // `<projectDir> <entry.qml>` runs that QML from disk (quickshell-style); `app`
    // runs the bundled upstream MD3 app; `mock <projectDir> <entry.qml>` runs a document
    // that expects a `client` context object, supplying a MockClient (Haedus ClickGui).
    private void run(String[] args) {
        boolean app = args.length >= 1 && "app".equals(args[0]);
        boolean mock = args.length >= 3 && "mock".equals(args[0]);
        if (!app && !mock && args.length < 2) {
            System.err.println("usage:  <projectDir> <entry.qml>   |   app   |   mock <projectDir> <entry.qml>");
            return;
        }
        GlfwPlatform.configure();
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            freeErrorCallback();
            throw new IllegalStateException("glfwInit failed");
        }
        glfwInitialized = true;

        // Past this point a GL context may exist (on Linux the NVIDIA driver spins its
        // exit-fault worker), so every path -- normal close, init failure, render error --
        // must run the ordered cleanup and then pick the platform exit. Capture the failure,
        // release in finally, then decide.
        Throwable failure = null;
        try {
            createWindow();

            int[] fw = new int[1];
            int[] fh = new int[1];
            GLFW.glfwGetFramebufferSize(window, fw, fh);

            backend = new GlfwSurfaceBackend(window, fw[0], fh[0]);
            backend.init(fw[0], fh[0]);
            updateScale(fw[0], fh[0]);

            boolean dark = !"false".equals(System.getProperty("qml4j.dark", "true"));
            ((StyleManager) StyleManager.__instance()).isDarkTheme.set(dark);

            Clipboard clipboard = new GlfwClipboard(window);
            if (app) {
                host = new DesktopHost(new AppResourceLoader(), fw[0], fh[0], clipboard);
                host.startApp();
            } else if (mock) {
                host = new DesktopHost(new DirResourceLoader(Paths.get(args[1])), fw[0], fh[0], clipboard);
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("client", new MockClient());
                host.run(args[2], ctx);
            } else {
                host = new DesktopHost(new DirResourceLoader(Paths.get(args[0])), fw[0], fh[0], clipboard);
                host.run(args[1]);
            }

            installCallbacks();

            while (!GLFW.glfwWindowShouldClose(window)) {
                host.renderFrame(backend);
                GLFW.glfwPollEvents();
                if (cursorMoved) {
                    cursorMoved = false;
                    host.pointerMove((float) cursorX, (float) cursorY);
                }
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        } finally {
            try {
                shutdown();
            } catch (RuntimeException | Error cleanupError) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else {
                    failure = cleanupError;
                }
            }
        }

        // Exit only after the release above. Linux keeps the SIGKILL driver workaround (see
        // killSelf); every other host returns, so a real leftover non-daemon thread surfaces
        // as a hang rather than being masked, and a normal close exits 0. A failure re-throws
        // for a non-zero status with its stack trace; on Linux, where killSelf pre-empts the
        // JVM's own report, print it first.
        //
        // Consequence on Linux: a failure exits 137 like a normal close does, so the exit
        // code alone cannot tell them apart -- read the stack trace above it. The workaround
        // cannot be narrowed to the success path: a GL context exists by this point either
        // way, and the driver's exit-fault worker is exactly what a half-torn-down failure
        // path would trip.
        if (isLinux()) {
            if (failure != null) {
                failure.printStackTrace(System.err);
            }
            killSelf();
        }
        // Only RuntimeException and Error are ever caught above, so anything left here is an
        // Error; rethrow unwrapped so the JVM reports the original type and stack.
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure != null) {
            throw (Error) failure;
        }
    }

    private void createWindow() {
        GlfwContext.configure();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

        String platform = GlfwPlatform.currentName();
        String title = Boolean.getBoolean("qml4j.frameStamp")
            ? "qml4j frame diagnostics [" + platform + "]" : "qml4j showcases";
        window = GLFW.glfwCreateWindow(INITIAL_W, INITIAL_H, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            // run()'s finally drives glfwTerminate through shutdown(); don't tear down here.
            throw new IllegalStateException("glfwCreateWindow failed");
        }
        GLFW.glfwMakeContextCurrent(window);
        // vsync on by default; -Dqml4j.vsync=false uncaps the loop to measure real FPS.
        boolean vsync = !"false".equals(System.getProperty("qml4j.vsync", "true"));
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
        System.err.println("[host] GLFW platform=" + platform + " context=" + GlfwContext.currentName(window) + " vsync=" + vsync
            + " gpuWait=" + Boolean.getBoolean("qml4j.gpuWait"));
    }

    private void updateScale(int fbW, int fbH) {
        int[] ww = new int[1];
        int[] wh = new int[1];
        GLFW.glfwGetWindowSize(window, ww, wh);
        scaleX = ww[0] > 0 ? (float) fbW / ww[0] : 1f;
        scaleY = wh[0] > 0 ? (float) fbH / wh[0] : 1f;
    }

    private void installCallbacks() {
        GLFW.glfwSetFramebufferSizeCallback(window, new GLFWFramebufferSizeCallback() {
            @Override public void invoke(long win, int w, int h) {
                if (w <= 0 || h <= 0) return;
                backend.resize(w, h);
                host.resize(w, h);
                updateScale(w, h);
            }
        });
        GLFW.glfwSetCursorPosCallback(window, new GLFWCursorPosCallback() {
            @Override public void invoke(long win, double x, double y) {
                cursorX = x * scaleX;
                cursorY = y * scaleY;
                cursorMoved = true;
            }
        });
        GLFW.glfwSetMouseButtonCallback(window, new GLFWMouseButtonCallback() {
            @Override public void invoke(long win, int button, int action, int mods) {
                if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
                if (action == GLFW.GLFW_PRESS) host.pointerDown((float) cursorX, (float) cursorY);
                else if (action == GLFW.GLFW_RELEASE) host.pointerUp((float) cursorX, (float) cursorY);
            }
        });
        GLFW.glfwSetKeyCallback(window, new GLFWKeyCallback() {
            @Override public void invoke(long win, int key, int scancode, int action, int mods) {
                dispatchKey(key, mods, action != GLFW.GLFW_RELEASE);
            }
        });
        GLFW.glfwSetCharCallback(window, new GLFWCharCallback() {
            @Override public void invoke(long win, int codepoint) {
                host.text(new String(Character.toChars(codepoint)));
            }
        });
        GLFW.glfwSetScrollCallback(window, new GLFWScrollCallback() {
            @Override public void invoke(long win, double xoffset, double yoffset) {
                host.wheel((float) cursorX, (float) cursorY, (float) xoffset, (float) yoffset);
            }
        });
    }

    private void dispatchKey(int glfwKey, int mods, boolean down) {
        int code = mapKey(glfwKey, mods);
        if (code == 0) return;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        host.key(code, null, down, shift);
    }

    // Printable characters arrive via the char callback; this maps only the control
    // keys QmlView understands. 0 means "not a control key" -> ignored here.
    private static int mapKey(int key, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE: return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER: return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT: return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP: return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN: return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME: return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END: return QmlView.KEY_END;
            case GLFW.GLFW_KEY_ESCAPE: return QmlView.KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB:
                return (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? QmlView.KEY_BACKTAB : QmlView.KEY_TAB;
            default: return 0;
        }
    }

    // Ordered teardown: scene and GPU resources first (while the GL context is still
    // current), then unbind, free callbacks, destroy the window, and terminate GLFW. Each
    // step is attempted independently so one failure cannot strand a later native release --
    // the first error becomes primary and the rest are attached as suppressed. Guards keep it
    // safe after a partial init, and each handle is cleared once its own step succeeds (host,
    // backend, and the window only after destroy), so completed work is not redone; a step
    // that throws keeps its guard, so this is not unconditionally idempotent after a failed
    // cleanup. Does not exit the process; run() owns the platform exit.
    private void shutdown() {
        Throwable error = step(null, () -> { if (host != null) { host.dispose(); host = null; } });
        error = step(error, () -> { if (backend != null) { backend.dispose(); backend = null; } });
        error = step(error, () -> { if (window != MemoryUtil.NULL) GLFW.glfwMakeContextCurrent(MemoryUtil.NULL); });
        error = step(error, () -> { if (window != MemoryUtil.NULL) Callbacks.glfwFreeCallbacks(window); });
        error = step(error, () -> {
            if (window != MemoryUtil.NULL) {
                GLFW.glfwDestroyWindow(window);
                window = MemoryUtil.NULL;
            }
        });
        error = step(error, () -> { if (glfwInitialized) { GLFW.glfwTerminate(); glfwInitialized = false; } });
        error = step(error, DesktopMain::freeErrorCallback);
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
    }

    // Run one cleanup action, folding any failure into the running aggregate: the first
    // becomes primary, later ones are suppressed on it, and every step still runs.
    private static Throwable step(Throwable primary, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            if (primary == null) {
                return e;
            }
            primary.addSuppressed(e);
        }
        return primary;
    }

    // Released manually, not via try-with-resources; a second call is a no-op because
    // setErrorCallback(null) then returns null.
    @SuppressWarnings("resource")
    private static void freeErrorCallback() {
        GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    // The NVIDIA EGL driver spins a worker thread that SIGSEGVs the instant this process
    // begins to exit. Every in-process exit path reproduces it -- return, System.exit,
    // Runtime.halt -- even with zero GL teardown, so it cannot be fixed by ordering the
    // cleanup above. SIGKILL terminates the whole process atomically in the kernel, before
    // the worker can fault, so there is no JVM fatal-error log or core dump. Reached only on
    // Linux; other hosts return normally after shutdown() releases everything. Last resort
    // for this driver bug.
    private static void killSelf() {
        // Java 8 has no ProcessHandle; the RuntimeMXBean name is "<pid>@<host>".
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        long pid = Long.parseLong(runtimeName.substring(0, runtimeName.indexOf('@')));
        try {
            new ProcessBuilder("kill", "-9", Long.toString(pid)).start();
            Thread.sleep(10_000);
        } catch (IOException | InterruptedException e) {
            Runtime.getRuntime().halt(0);
        }
    }
}
