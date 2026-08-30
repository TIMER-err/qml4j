<p align="center">
  <img src="docs/logo.png" width="132" alt="qml4j">
</p>

<h1 align="center">qml4j</h1>

<p align="center">
  <b>A pure-Java QML engine.</b> Parse <code>.qml</code> → JIT to JVM bytecode → render with Skia.
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.timer-err/qml4j-core"><img src="https://img.shields.io/maven-central/v/io.github.timer-err/qml4j-core?label=Maven%20Central&color=7C6CF0" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/Java-8%2B-7C6CF0" alt="Java 8+">
  <a href="LICENSE.md"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a>
</p>

---

`.qml` source → JIT-compiled object tree (ASM bytecode) → bindings/expressions on embedded Rhino → pixels via Skija. No Qt, no C++, no codegen step — a `.qml` file becomes live JVM classes in-process. Runs on x86-64 desktop today; Android (D8 → DEX → `InMemoryDexClassLoader`) is a milestone.

**Status — pre-alpha, but capable.** All 10 pages of the *unmodified* upstream MD3 (Material Design 3) showcase app render — Home, Color, Navigation, Settings, Typography, Icon, Pro, Components, Widgets, About — dozens of components, carousels, animated canvas widgets, charts. **585 tests green**, checkstyle CI guard. The engine was refactored to polymorphic dispatch + single-responsibility modules (conventions in `CLAUDE.md` § *Dispatch & polymorphism*).

## Quick start

```java
import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.QmlView;

QmlView view = QmlView.withStockTypes(new QmlEngine());
view.load(
    "Rectangle {\n" +
    "  width: 200; height: 100; color: \"#ff5050\"\n" +
    "  Text { x: 8; y: 8; text: \"hello qml4j\"; color: \"#ffffff\" }\n" +
    "}");
// then, inside your render loop:  view.renderFrame(surfaceBackend);
```

For third-party QML, create a safe JavaScript realm, use the restricted stock
type set, confine resources, and provide a network policy. Host bridges implement
`QmlSafeBridge` to allow only explicitly named methods; inherited reflection
methods and native file/window types remain unavailable. Safe-realm bindings,
handlers, imported scripts, and deferred callbacks run interpreted with an
instruction-observed deadline (100 ms by default, configurable with
`-Dqml4j.safeEvalMs=...`).

```java
QmlEngine engine = new QmlEngine(new JvmClassLoaderBackend(), true);
QmlView pluginView = new QmlView(engine, StockTypes.safeRegistry())
    .resources(confinedResources)
    .networkPolicy(url -> allowedDomain(url))
    .context("plugin", safeBridge);
```

Or run a whole QML project from disk, quickshell-style — point the desktop host at a directory and an entry file:

```sh
./run.sh shared-qml showcases/NavigationBarShowcase.qml
./run.sh app          # the bundled upstream MD3 showcase app
```

## Install

`qml4j-core` is on Maven Central under the `io.github.timer-err` namespace.

Maven:

```xml
<dependency>
    <groupId>io.github.timer-err</groupId>
    <artifactId>qml4j-core</artifactId>
    <version>0.1.1</version>
</dependency>
<!-- Skija is a `provided` dependency of the engine; add the native bundle for your platform. -->
<dependency>
    <groupId>io.github.humbleui</groupId>
    <artifactId>skija-linux-x64</artifactId>
    <version>0.143.17</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.timer-err:qml4j-core:0.1.1")
// pick your platform: skija-linux-x64 / skija-windows-x64 / skija-macos-x64 / skija-macos-arm64
runtimeOnly("io.github.humbleui:skija-linux-x64:0.143.17")
```

The engine keeps Skija `provided` so you choose the platform-native artifact yourself; it transitively pulls in the ANTLR runtime, Rhino and ASM. Java 8+.

## Why

Existing options have gaps:
- `paulovap/qmljava` — stalled, no renderer
- `jaqumal` / QtJambi — depend on Qt C++

qml4j aims to be a fully native-Java path from QML source to pixels — a drop-in engine that runs unmodified third-party QML libraries, not a hand-grown Controls clone.

<p align="center">
  <img src="https://img.shields.io/badge/parse-ANTLR4-70BF4F" alt="ANTLR4">
  <img src="https://img.shields.io/badge/compile-ASM%20bytecode-465BA6" alt="ASM">
  <img src="https://img.shields.io/badge/bindings-Rhino%20JS-7C6CF0" alt="Rhino">
  <img src="https://img.shields.io/badge/render-Skija%20%E2%80%A2%20Skia-E94037" alt="Skija">
  <img src="https://img.shields.io/badge/host-LWJGL%20%2F%20GLFW-E76F00" alt="LWJGL/GLFW">
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.timer-err/qml4j-core"><img src="https://img.shields.io/maven-central/v/io.github.timer-err/qml4j-core?label=Maven%20Central&color=7C6CF0" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/desktop-Linux%20%E2%80%A2%20Windows-2BD46E" alt="Desktop">
  <img src="https://img.shields.io/badge/Android-D8%20%E2%86%92%20DEX-A4C639" alt="Android">
</p>

## Architecture

```
.qml → Qml4j.parse() → AST → QmlCompiler.compile() → byte[]  (one Component$N per object)
                                     ↓
                          ClassLoaderBackend.defineClasses()
                                     ↓
                            Item tree (QObject + Property<T>)
                                     ↓
            bindings/handlers run as JavaScript on embedded Rhino (RhinoBinding)
            against a QmlScope; Property.get() registers reactive dependencies
                                     ↓
                            Renderer (Skija Canvas, polymorphic Item.paint)
                                     ↓
                              SurfaceBackend
```

### Modules

The four original `qml4j-{parser,engine,compiler,render}` modules were merged into a single `qml4j-core` (they remain Java *packages* inside it). The host is a separate module.

| Module | Role |
|---|---|
| `qml4j-core` | The whole engine. Packages: `parser` (ANTLR4 `Qml.g4` → POJO AST), `engine` (`QObject`, `Property<T>`, `Binding`, `DirtyQueue`, `ClassLoaderBackend` SPI, `js/` Rhino bridge), `runtime` (stateless `member`/`invoke`/`convert`/`qt` support utilities), `compiler` (ASM bytecode codegen + `emit/` member-emitter strategies), `render` (`QmlView` facade, `Renderer`, `Painter`, `items/` by feature) |
| `qml4j-demo-desktop` | LWJGL3 + GLFW host + `GlfwSurfaceBackend` + showcase launcher |
| `android-shell` | **Frozen.** Separate Gradle project: APK with `DexClassLoaderBackend` (D8 → DEX → `InMemoryDexClassLoader`). Kept for reference only; do not build. |

`shared-qml/` (repo root) is the single source of truth for the bundled MD3 component library and the showcases; both the tests and the desktop host load it from the classpath.

### Design choices

- **Runtime JIT, not source generation.** A `.qml` file becomes JVM classes inside the running process. On Android, the same `byte[]` is fed through D8 → DEX → `InMemoryDexClassLoader` (API 26+).
- **The object tree is compiled; bindings are interpreted JS.** Each QML object becomes a generated `Component$N` class wired up in its constructor. Each non-literal binding/handler is JavaScript captured from source and run by **embedded Rhino** (`RhinoBinding`) against a `QmlScope` — there is no separate JS bytecode backend (the old ASM `ExpressionCodegen`/`StatementCodegen` were removed). An unresolvable identifier in a binding is a compile error. So `Rectangle { width: parent.width / 2 }` becomes a `Component$N extends Rectangle` whose constructor binds `width` to a `RhinoBinding` carrying `"parent.width / 2"`; at evaluation Rhino resolves `parent` against the `QmlScope`, and reading `parent.width` registers the reactive dependency.
- **Dependency tracking is automatic.** `Property.get()` registers itself with the active `BindingEvaluationContext` thread-local, so re-evaluation only needs to re-run the binding to refresh its subscription set; `DirtyQueue` coalesces redundant re-evaluations per frame.
- **Polymorphic dispatch, not type switches.** Drawable items override `Item.paint(Painter)`; items with intrinsic size override `Item.measure(TextLayout)`; layout containers override `Item.layout()`. The compiler dispatches member emission through a `MemberEmitter` strategy map. See `CLAUDE.md` § *Dispatch & polymorphism*.
- **Generated types are erased to `Object`/`Number`.** No type inference; runtime `convert` utilities coerce.
- Compiled with `--release 8` (the published bytecode is genuinely Java 8 — no JDK 9+ API), to stay friendly to Android dexing without desugar.

## Build

Requires JDK 8+ (built with a JDK 21 toolchain), Maven 3.9+.

```sh
mvn verify      # compile + 585 tests + checkstyle guard, all modules
```

```sh
mvn -pl qml4j-core test                          # engine tests only
mvn -pl qml4j-core test -Dtest=DialogLoadTest    # one test
```

The build runs offline-friendly; iteration commonly uses `mvn -o install -DskipTests` then `mvn -o -pl qml4j-core test`. A checkstyle guard (`config/checkstyle/checkstyle.xml`) is bound to `verify` and fails on unused/redundant imports and unused locals.

### Run a QML project (desktop)

The desktop module is a generic QML runner, quickshell-style: point it at a project
directory and an entry `.qml`, and it loads everything — the entry, its `import md3.Core`,
icon fonts — from that directory on disk. Nothing is bundled into the jar.

```sh
./run.sh <projectDir> <entry.qml>
./run.sh shared-qml showcases/NavigationBarShowcase.qml   # example
./run.sh app                                              # bundled upstream MD3 app (dark)
./run.sh app light                                        # ... light scheme
```

Every import is resolved under that single project directory, so it must contain the
libraries the QML imports: `import md3.Core` → `<projectDir>/md3/Core/*.qml`, icon fonts →
`<projectDir>/fonts/`. The repo ships two usable roots:

- `shared-qml/` — has `md3/Core` (the engine-adapted component subset), `showcases/` and `fonts/`, so `./run.sh shared-qml showcases/ButtonShowcase.qml` just works.
- `md3/Core/` — the full md3.Core library extracted verbatim from upstream (our StyleManager-driven Theme, no C++); drop it (and a `fonts/`) next to your own `.qml`.

To run a `.qml` kept elsewhere, put or symlink `md3` and `fonts` beside it:

```sh
ln -sfn "$PWD/shared-qml/md3" ~/myproj/md3
ln -sfn "$PWD/shared-qml/fonts" ~/myproj/fonts
./run.sh ~/myproj Main.qml
```

`./run.sh app` runs the upstream [material-components-qml](https://github.com/sudoevolve/material-components-qml) app, expected at `../mcq` (override with `$MCQ_DIR`); clone it once: `git clone https://github.com/sudoevolve/material-components-qml ../mcq`. It uses a dedicated loader that maps `md3.Core` to our Theme plus the upstream Controls and injects the app's context properties, so the generic dir+entry mode can't run it directly.

Env-var options (set before the command, e.g. `QML4J_FPS=true ./run.sh app`):

| Variable | Default | Effect |
|---|---|---|
| `QML4J_FPS` | `false` | Draw a top-right FPS overlay over the scene. |
| `QML4J_VSYNC` | `true` | `false` uncaps the frame loop (`glfwSwapInterval(0)`) to measure the real frame rate; on, the loop is pinned to the monitor refresh (~60fps). |
| `QML4J_CANVAS_CACHE` | `true` | `false` re-runs every `Canvas` `onPaint` straight to the screen each frame instead of caching it to an offscreen surface (fallback if the cache misbehaves on a given GPU). |
| `MCQ_DIR` | `../mcq` | Path to the upstream MD3 app checkout. |

These map to the `-Dqml4j.fps` / `-Dqml4j.vsync` / `-Dqml4j.canvasCache` / `-Dqml4j.mcq` system properties, which an embedder reads the same way.

`run.sh` recompiles `qml4j-core` + the desktop module from source (`-am`) and launches `java` directly with the freshly-built `target/classes` ahead of any `~/.m2` jar on the classpath — no `mvn install` after editing the engine, and a stale `~/.m2/qml4j-core` can't shadow your changes. (Plain `mvn -pl qml4j-demo-desktop exec:java` resolves `qml4j-core` from `~/.m2` and silently runs a stale engine, e.g. "unknown QML type" for a freshly-registered item.)

On Darwin `run.sh` adds `-XstartOnFirstThread`, which GLFW requires in order to drive the
AppKit event loop on the process's first thread. The flag only takes effect at JVM start-up,
so `mvn exec:java` cannot supply it — Maven's JVM has already started off the first thread by
the time the plugin runs.

`run.sh` launches the JDK that `JAVA_HOME` points at, because that is the JDK Maven used to
pick the platform natives now on the classpath. On a machine with more than one JDK — an x64
`JAVA_HOME` with an arm64 `java` first on `PATH`, or the reverse — launching a different one
hands the wrong architecture's natives to the VM and LWJGL fails to load them. Leave
`JAVA_HOME` unset to keep using `java` from `PATH`.

On Linux, closing the window exits with code 137: the host SIGKILLs itself to dodge an NVIDIA libEGL teardown SIGSEGV. macOS and Windows release resources and exit 0 normally.

### Package a distributable jar

```sh
mvn -pl qml4j-demo-desktop -am -Pdist package
java -jar qml4j-demo-desktop/target/qml4j-app.jar                        # bundled MD3 app
java -jar qml4j-demo-desktop/target/qml4j-app.jar <projectDir> <entry.qml>  # run any project
```

`-Pdist` shades a self-contained, cross-platform fat jar (`qml4j-app.jar`): it carries both
Linux and Windows Skija/LWJGL natives (each loader picks its platform at runtime), is a
multi-release jar, and bundles the upstream MD3 app source under `/mcq/**`. With no args it
runs that bundled app; pass `<projectDir> <entry.qml>` and it's the same on-disk runner as
`run.sh` (those paths are resolved relative to your current directory). The bundled app source
is read from `${mcq.dir}` at build time (default `../mcq`); override with `-Dmcq.dir=/path/to/mcq`.

## Performance

The engine is built to sit inside a host render loop that calls `renderFrame` every frame (e.g. a game overlay):

- **Idle frames skip layout.** `Property` bumps a global change-version on every real value change; when a frame's version is unchanged (no animation/timer/input/binding touched anything), `renderFrame` skips the whole measure+layout pass and repaints with cached geometry. A static UI costs only its paint.
- **Inactive `Loader`s unload.** A `Loader` with `active: false` frees its item (Qt semantics), so off-screen tab/page content — and its animations — stops instead of running every frame and keeping the scene dirty.
- **`Canvas` `onPaint` is cached.** Each `Canvas` renders into an offscreen surface only when dirty (`requestPaint` / resize) and blits the cache otherwise, so an animated canvas runs its JS at its own fps and a static one runs once. (GPU-backed offscreen on the GL backend; the blit is snapped to integer device pixels for sharpness.)

Net effect on the desktop GL host (uncapped, `QML4J_VSYNC=false`): static pages run 1000–2000fps, the component-heavy "Core" page ~400–500fps; pages with a full-screen animated background are bound by that canvas (~60fps). With vsync on (default) everything is a smooth 60.

## Feature set

The engine hosts enough QML to run real component libraries. Supported (inventory from `StockTypes`, the compiler, and the renderer):

- Object trees, nested children, `id:` resolution in bindings (incl. forward refs via deferred bindings)
- Property declarations (`int/real/bool/string/color/var/url/alias/Item/list`), bindings + dependency tracking, grouped properties (`anchors.*`, `border.*`, `font.*`), member chains, `property alias`
- JS via Rhino: binary/unary/ternary, member/call/index, arrays, object literals, template strings, arrow functions, `function` declarations, statements (`if/for/while/return/let/var/const/block`)
- Signals (with typed args) + handlers + arrow handlers; custom `signal foo()` on root and child scopes; `on<Prop>Changed`; `Connections`
- `States` / `PropertyChanges` / `Transition`; `Behavior`; the full animation set (`Number/Color/Rotation/Opacity/Parallel/Sequential/Pause/ScriptAction`)
- `pragma Singleton`, `qmldir`, import aliases, `import "dir"`
- `Repeater` / `ListModel` / `ListElement` / `ListView` / `GridView` / `Component` / `Loader` / `Flickable`
- Positioners `Row` / `Column` / `Flow` and QtQuick.Layouts `RowLayout` / `ColumnLayout` / `StackLayout` / `GridLayout` with `Layout.*` attached (`fillWidth/Height`, `preferred/minimum/maximum*`, margins, `alignment`, `row/column/rowSpan/columnSpan`)
- `Keys` attached + `FocusScope`; `Window` / `ApplicationWindow`
- `Shape` / `ShapePath` / `Path*`; layer effects (`DropShadow` / `Glow` / `ColorOverlay`) and `MultiEffect`
- `Rectangle` (radius/border/linear gradient), `Text` (font group, wrap/align/elide, icon glyphs), `Image` (fill modes), `TextInput` / `TextEdit` / `TextField` (caret/selection/clipboard via a `Clipboard` SPI), `Control` / `AbstractButton` / `Button` / `Label`
- `QtObject` + nested object properties + multi-level dotted bindings (the MD3 `Theme` pattern)
- `MouseArea` (hover: `hoverEnabled`/`containsMouse`/`entered`/`exited`; `drag.target` with axis + bounds)
- `Qt.rgba/hsla/lighter/darker/binding/callLater`, enum families (`Easing.*`, `Font.*`, `Text.*`, `Qt.Align*`)

See `ROADMAP.md` for milestone history and `COMPAT_REPORT.md` for the original Qt-parity gap analysis (largely closed).

## Known limitations / tech debt

- **C++-backed QML modules can't load.** `import md3.Core` is normally a C++-registered module; we load the `Core/*.qml` files as a directory module via `qmldir` and stub the C++ `StyleManager` in Java. We cannot load arbitrary C++ QML plugins — each target library's C++ backend must be re-implemented in Java.
- **Type system is `Object` + `Number` everywhere.** No type inference; runtime coerces on each operation. Numeric precision can degrade through long bind chains.
- **No `LineNumberTable` on generated classes.** Stack traces from binding evaluation point at synthetic classes, not `.qml` lines.
- **No hot reload.** Source changes require a process restart (or, for `Loader`, mutating its `source`).
- **`Image` dimensions read from a header parse, not Skia.** Animated/multi-frame formats may report 0×0.
- **Renderer is not thread-safe.** All `render()` / dispatch calls must come from one thread (the GL thread).
- **`property` is a reserved keyword.** `NumberAnimation { property: "width" }` won't parse — set it from Java (`anim.property.set("width")`).
- **Skija-on-Android JNI is fragile** (the `android-shell` is frozen): several `_n*` natives crash from missing cached `jclass` refs; worked around case by case.

## Android

The `android-shell` module is a frozen separate Gradle project (AGP 8.5, Gradle 8.7, JDK 21). It is kept as a reference for the desktop host's input/IME wiring and the D8 dexing path; it is not built or shipped today. At runtime its `DexClassLoaderBackend.defineClasses(Map<String, byte[]>)` invoked D8 in-process to convert generated `.class` bytes into a single dex `byte[]`, loaded via `InMemoryDexClassLoader` (API 26+).

## License

[Apache License 2.0](LICENSE.md).
