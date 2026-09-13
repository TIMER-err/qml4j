#!/usr/bin/env bash
# Run a QML project, quickshell-style:  `./run.sh <projectDir> <entry.qml>`
#   e.g.  ./run.sh shared-qml showcases/FisProxyShowcase.qml
# `./run.sh app [light]` runs the bundled upstream MD3 app from $MCQ_DIR (default ../mcq;
# clone once: git clone https://github.com/sudoevolve/material-components-qml ../mcq).
#
# Builds + installs qml4j-core (reactor, `-am`) so dependency:build-classpath can
# resolve it, then launches java with the freshly-compiled target/classes placed
# AHEAD of the ~/.m2 jar on the classpath -- edits to the engine take effect
# immediately and a stale ~/.m2/qml4j-core jar can never shadow them.
set -euo pipefail
cd "$(dirname "$0")"

mvn -q -pl qml4j-demo-desktop -am install -DskipTests

CP_FILE="$PWD/qml4j-demo-desktop/target/run-cp.txt"
mvn -q -pl qml4j-demo-desktop dependency:build-classpath \
    -Dmdep.includeScope=runtime -Dmdep.outputFile="$CP_FILE" >/dev/null

CP="$PWD/qml4j-demo-desktop/target/classes:$PWD/qml4j-core/target/classes:$(cat "$CP_FILE")"
# Dark by default; `./run.sh app light` or QML4J_DARK=false picks the light scheme.
DARK="${QML4J_DARK:-true}"
[ "${2:-}" = "light" ] && DARK=false
# `QML4J_FPS=true ./run.sh app` shows a top-right FPS overlay; QML4J_VSYNC=false uncaps
# the frame loop (otherwise vsync pins it to the monitor refresh, ~60fps);
# QML4J_CANVAS_CACHE=false falls back to per-frame direct canvas draw (cache on by default).
# QML4J_FRAME_STAMP=true marks submitted frames for diagnosing old-frame reappearance.
# QML4J_GPU_WAIT=true waits for rendering before swap as a diagnostic (default false).
# QML4J_PLATFORM=auto, wayland, or x11 overrides window-system selection.
# QML4J_GL_CONTEXT=auto, egl, or native overrides context creation (auto uses EGL on XWayland).
# Prefer native Wayland in a Wayland session: the XWayland path reproduced old-frame
# reappearance on NVIDIA. Explicit `auto` delegates selection to GLFW as before.
WINDOW_PLATFORM="${QML4J_PLATFORM:-}"
if [ -z "$WINDOW_PLATFORM" ]; then
    WINDOW_PLATFORM=auto
    if [ "$(uname -s)" = Linux ] && [ "${XDG_SESSION_TYPE:-}" = wayland ] && [ -n "${WAYLAND_DISPLAY:-}" ]; then
        WINDOW_PLATFORM=wayland
    fi
fi
# NVIDIA's threaded EGL path can return EGL_BAD_DISPLAY and leave the window unmapped.
# This is process-local and respects an explicit driver setting; other GPU drivers ignore it.
GL_CONTEXT="${QML4J_GL_CONTEXT:-auto}"
USE_EGL=false
if [ "$WINDOW_PLATFORM" = wayland ]; then
    USE_EGL=true
elif [ "$(uname -s)" = Linux ]; then
    if [ "$GL_CONTEXT" = egl ] || { [ "$GL_CONTEXT" = auto ] && [ -n "${WAYLAND_DISPLAY:-}" ]; }; then
        USE_EGL=true
    fi
fi
if [ "$USE_EGL" = true ]; then
    export __GL_THREADED_OPTIMIZATIONS="${__GL_THREADED_OPTIMIZATIONS:-0}"
fi
#
# macOS/Cocoa must drive the GLFW/AppKit event loop on the process's first thread, so the
# JVM that runs DesktopMain has to start with -XstartOnFirstThread. It is a VM launch flag on
# this direct `java` call -- `mvn exec:java` can't add it (Maven's JVM already started off the
# first thread). The `[@]+` guard expands to nothing on other platforms under `set -u`.
JVM_OPTS=()
[ "$(uname -s)" = "Darwin" ] && JVM_OPTS+=(-XstartOnFirstThread)
# Maven above obeys JAVA_HOME, so it is JAVA_HOME that decided which platform's natives are on
# $CP. Launch that same JDK, or an x64 JAVA_HOME with an arm64 java first on PATH hands x64
# natives to an arm64 JVM and LWJGL dies on liblwjgl.dylib. Empty or unset keeps the bare `java`.
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" ${JVM_OPTS[@]+"${JVM_OPTS[@]}"} -cp "$CP" -Dqml4j.mcq="${MCQ_DIR:-$PWD/../mcq}" -Dqml4j.dark="$DARK" \
    -Dqml4j.fps="${QML4J_FPS:-false}" -Dqml4j.vsync="${QML4J_VSYNC:-true}" \
    -Dqml4j.canvasCache="${QML4J_CANVAS_CACHE:-true}" \
    -Dqml4j.frameStamp="${QML4J_FRAME_STAMP:-false}" \
    -Dqml4j.gpuWait="${QML4J_GPU_WAIT:-false}" \
    -Dqml4j.platform="$WINDOW_PLATFORM" \
    -Dqml4j.glContext="$GL_CONTEXT" \
    io.github.timer_err.qml4j.demo.DesktopMain "$@"
