package io.github.timer_err.qml4j.demo;

import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Explicit window-system selection for presentation comparisons on Linux. */
final class GlfwPlatform {
    private GlfwPlatform() {}

    static void configure() {
        String requested = System.getProperty("qml4j.platform", "auto").toLowerCase(Locale.ROOT);
        int platform;
        switch (requested) {
            case "auto": platform = GLFW.GLFW_ANY_PLATFORM; break;
            case "x11": platform = GLFW.GLFW_PLATFORM_X11; break;
            case "wayland": platform = GLFW.GLFW_PLATFORM_WAYLAND; break;
            default: throw new IllegalArgumentException("qml4j.platform must be auto, x11, or wayland: " + requested);
        }
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, platform);
    }

    static String currentName() {
        switch (GLFW.glfwGetPlatform()) {
            case GLFW.GLFW_PLATFORM_X11: return "x11";
            case GLFW.GLFW_PLATFORM_WAYLAND: return "wayland";
            case GLFW.GLFW_PLATFORM_WIN32: return "win32";
            case GLFW.GLFW_PLATFORM_COCOA: return "cocoa";
            case GLFW.GLFW_PLATFORM_NULL: return "null";
            default: throw new IllegalStateException("Unknown GLFW platform");
        }
    }
}
