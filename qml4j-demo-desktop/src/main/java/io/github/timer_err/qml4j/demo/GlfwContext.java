package io.github.timer_err.qml4j.demo;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** EGL avoids the observed GLX presentation reversal on NVIDIA/XWayland. */
final class GlfwContext {
    private GlfwContext() {}

    static void configure() {
        String requested = System.getProperty("qml4j.glContext", "auto").toLowerCase(Locale.ROOT);
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        boolean xwayland = GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_X11
                && waylandDisplay != null && !waylandDisplay.isEmpty();
        int api;
        switch (requested) {
            case "auto": api = xwayland ? GLFW.GLFW_EGL_CONTEXT_API : GLFW.GLFW_NATIVE_CONTEXT_API; break;
            case "egl": api = GLFW.GLFW_EGL_CONTEXT_API; break;
            case "native": api = GLFW.GLFW_NATIVE_CONTEXT_API; break;
            default: throw new IllegalArgumentException("qml4j.glContext must be auto, egl, or native");
        }
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, api);
    }

    static String currentName(long window) {
        return GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_CONTEXT_CREATION_API) == GLFW.GLFW_EGL_CONTEXT_API
                ? "egl" : "native";
    }
}
