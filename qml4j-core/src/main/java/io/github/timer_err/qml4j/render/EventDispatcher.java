package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.Signal;
import io.github.timer_err.qml4j.render.items.core.Drag;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import io.github.timer_err.qml4j.render.items.core.MouseEvent;
import io.github.timer_err.qml4j.render.items.input.KeyEvent;
import io.github.timer_err.qml4j.render.items.input.Keys;
import io.github.timer_err.qml4j.render.items.input.TextEditable;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.window.AbstractButton;

import java.util.ArrayList;
import java.util.List;

import static io.github.timer_err.qml4j.render.Renderer.zOrdered;

// Translates raw pointer/key events into item-level interactions: hit-testing,
// press/move/release capture, drag and flick scrolling, hover tracking, caret
// movement and text editing/selection, and clipboard cut/copy/paste. Reads and
// mutates active focus through the injected FocusManager.
final class EventDispatcher {

    private final FocusManager focus;
    private final Renderer renderer;
    private Item root;

    private MouseArea captured;
    private MouseArea hovered;
    private AbstractButton capturedButton;
    private float captureRootX;
    private float captureRootY;
    private Item dragTarget;
    private float dragStartX;
    private float dragStartY;
    private Flickable scrolling;
    private float scrollStartContentX;
    private float scrollStartContentY;
    // The Flickables under the press, innermost first, eligible to take the gesture
    // for scrolling (Qt's flick-steals-from-child behaviour: a press on a clickable
    // row still scrolls the list when dragged). The whole chain is kept, not just
    // the innermost, so a drag picks the nearest ancestor that scrolls on the
    // gesture's axis -- a vertical drag over a horizontal TabRow scrolls the page.
    private final List<Flickable> pendingChain = new ArrayList<Flickable>(4);
    // Windowed velocity tracker. The fling speed handed to the Flickable on release
    // is the displacement across a short recent window of pointer samples, not a
    // running EMA of per-move deltas: an EMA reversed direction when the finger
    // rolled back a pixel or two on lift (its last sample dominated) and
    // underestimated fast flicks when move events arrived in irregular bursts. A
    // window over the last ~VEL_WINDOW seconds is robust to both.
    private static final int VEL_SAMPLES = 8;
    private static final float VEL_WINDOW = 0.09f; // seconds of history used for velocity
    private final long[] sampleNanos = new long[VEL_SAMPLES];
    private final float[] sampleX = new float[VEL_SAMPLES];
    private final float[] sampleY = new float[VEL_SAMPLES];
    private int sampleCount;

    // Pixels of content moved per mouse-wheel notch (GLFW reports ~±1 per notch).
    private static final float WHEEL_STEP = 48f;
    // Drag distance (logical px) past which a Flickable steals from a child MouseArea.
    private static final float DRAG_THRESHOLD = 10f;
    private TextEditable textCapturing;
    private Clipboard clipboard;

    EventDispatcher(FocusManager focus, Renderer renderer) {
        this.focus = focus;
        this.renderer = renderer;
    }

    void setRoot(Item root) {
        this.root = root;
    }

    void setClipboard(Clipboard cb) {
        this.clipboard = cb;
    }

    boolean copy() {
        Item f = focus.focused();
        if (!(f instanceof TextEditable)) return false;
        return copyFromSelection((TextEditable) f, false);
    }

    boolean cut() {
        Item f = focus.focused();
        if (!(f instanceof TextEditable)) return false;
        TextEditable ti = (TextEditable) f;
        if (ti.readOnly()) return false;
        return copyFromSelection(ti, true);
    }

    boolean paste() {
        Item f = focus.focused();
        if (!(f instanceof TextEditable)) return false;
        TextEditable ti = (TextEditable) f;
        if (ti.readOnly()) return false;
        if (clipboard == null) return false;
        String text = clipboard.getText();
        if (text == null || text.isEmpty()) return false;
        return applyInsert(ti, text);
    }

    // The order here is the safety contract. The policy question is settled before any text
    // is handed to the backend, and a cut only deletes once the write has been read back and
    // matched: setText returns void, so a silently dropped write is otherwise invisible and a
    // cut would destroy the only copy. A plain copy does not read back -- see below.
    private boolean copyFromSelection(TextEditable ti, boolean alsoDelete) {
        if (!ti.allowsClipboardCopy()) return false;
        if (clipboard == null) return false;
        String cur = ti.text();
        if (cur == null) cur = "";
        int s = clampPos(ti.selectionStart(), cur.length());
        int e = clampPos(ti.selectionEnd(), cur.length());
        if (e <= s) return false;
        String selected = cur.substring(s, e);
        clipboard.setText(selected);
        // setText already happened and cannot be undone, so a read-back mismatch must not be
        // reported as "nothing was copied" -- the caller would be told the copy failed while
        // the user's previous clipboard is already gone. The read-back only decides whether
        // the write is confirmed well enough to authorise deleting the original.
        //
        // null means the backend cannot answer yet rather than having refused: Wayland reads
        // from the compositor's selection event, which has not been dispatched while we are
        // still inside the key callback. A mismatch means it stored something else, e.g. a
        // backend that normalises line endings.
        if (!alsoDelete) return true;
        String readBack = clipboard.getText();
        if (!selected.equals(readBack)) return false;
        deleteSelection(ti, cur);
        return true;
    }

    boolean dispatchKey(int keyCode, String text, boolean down, boolean shift) {
        Item f = focus.focused();
        if (f != null && deliverToKeys(f, keyCode, text, down, shift)) {
            return true;
        }
        if (down && (keyCode == QmlView.KEY_TAB || keyCode == QmlView.KEY_BACKTAB)) {
            if (focus.moveFocusByTab(keyCode == QmlView.KEY_BACKTAB)) return true;
        }
        if (!(f instanceof TextEditable)) return false;
        TextEditable ti = (TextEditable) f;
        if (ti.readOnly()) return false;
        if (!down) return true;
        if (keyCode == QmlView.KEY_ENTER) {
            if (ti.handleEnter()) return true;
            return applyInsert(ti, "\n");
        }
        if (keyCode == QmlView.KEY_BACKSPACE) {
            return applyBackspace(ti);
        }
        if (keyCode == QmlView.KEY_LEFT) {
            return moveCaret(ti, -1, shift);
        }
        if (keyCode == QmlView.KEY_RIGHT) {
            return moveCaret(ti, +1, shift);
        }
        if (keyCode == QmlView.KEY_UP) {
            return moveCaretVertical(ti, -1, shift);
        }
        if (keyCode == QmlView.KEY_DOWN) {
            return moveCaretVertical(ti, +1, shift);
        }
        if (keyCode == QmlView.KEY_HOME) {
            return setCaret(ti, 0, shift);
        }
        if (keyCode == QmlView.KEY_END) {
            String cur = ti.text();
            return setCaret(ti, cur == null ? 0 : cur.length(), shift);
        }
        if (text != null && !text.isEmpty()) {
            return typeText(ti, text);
        }
        return false;
    }

    // Qt reveals a PasswordEchoOnEdit field when the user types into it -- not when a paste
    // or a newline lands, and not on a keystroke the editor rejected. So the reveal hangs off
    // the typing path and only after the insert succeeded.
    private static boolean typeText(TextEditable ti, String text) {
        if (!applyInsert(ti, text)) return false;
        if (ti instanceof TextInput) ((TextInput) ti).beginEchoEditing();
        return true;
    }

    private boolean deliverToKeys(Item start, int keyCode, String text, boolean down, boolean shift) {
        int modifiers = shift ? KeyEvent.SHIFT : 0;
        KeyEvent event = new KeyEvent(keyCode, text, modifiers);
        for (Item it = start; it != null; it = it.parent.peek()) {
            Keys keys = it.keysOrNull();
            if (keys == null) continue;
            (down ? keys.pressed : keys.released).emit(event);
            if (down) {
                Signal specific = specificKeysSignal(keys, keyCode, text);
                if (specific != null) specific.emit(event);
            }
            if (event.accepted) return true;
        }
        return false;
    }

    private static Signal specificKeysSignal(Keys keys, int keyCode, String text) {
        switch (keyCode) {
            case QmlView.KEY_ENTER: return keys.returnPressed;
            case QmlView.KEY_ESCAPE: return keys.escapePressed;
            case QmlView.KEY_TAB: return keys.tabPressed;
            case QmlView.KEY_BACKTAB: return keys.backtabPressed;
            case QmlView.KEY_BACKSPACE: return keys.backPressed;
            case QmlView.KEY_UP: return keys.upPressed;
            case QmlView.KEY_DOWN: return keys.downPressed;
            case QmlView.KEY_LEFT: return keys.leftPressed;
            case QmlView.KEY_RIGHT: return keys.rightPressed;
            default:
                return " ".equals(text) ? keys.spacePressed : null;
        }
    }

    private static boolean moveCaret(TextEditable ti, int delta, boolean shift) {
        String cur = ti.text();
        int len = cur == null ? 0 : cur.length();
        int pos = clampPos(ti.cursorPosition(), len);
        int next = clampPos(pos + delta, len);
        if (next == pos) return false;
        return setCaret(ti, next, shift);
    }

    private boolean moveCaretVertical(TextEditable ti, int delta, boolean shift) {
        String cur = ti.text();
        int len = cur == null ? 0 : cur.length();
        int pos = clampPos(ti.cursorPosition(), len);
        int next = clampPos(ti.moveCaretVertical(pos, delta, renderer), len);
        if (next == pos) return false;
        return setCaret(ti, next, shift);
    }

    private static boolean setCaret(TextEditable ti, int pos, boolean shift) {
        String cur = ti.text();
        int len = cur == null ? 0 : cur.length();
        int target = clampPos(pos, len);
        if (shift) {
            if (ti.selectionAnchor() < 0) {
                ti.setSelectionAnchor(clampPos(ti.cursorPosition(), len));
            }
            int anchor = ti.selectionAnchor();
            setSelection(ti, Math.min(anchor, target), Math.max(anchor, target));
        } else {
            clearSelection(ti);
        }
        ti.setCursorPosition(target);
        return true;
    }

    private static boolean applyBackspace(TextEditable ti) {
        String cur = ti.text();
        if (cur == null) cur = "";
        if (deleteSelection(ti, cur)) return true;
        int pos = clampPos(ti.cursorPosition(), cur.length());
        if (pos == 0) return false;
        String next = cur.substring(0, pos - 1) + cur.substring(pos);
        ti.setText(next);
        ti.setCursorPosition(pos - 1);
        ti.emitTextChanged();
        return true;
    }

    private static boolean applyInsert(TextEditable ti, String text) {
        String cur = ti.text();
        if (cur == null) cur = "";
        int selS = clampPos(ti.selectionStart(), cur.length());
        int selE = clampPos(ti.selectionEnd(), cur.length());
        boolean hasSel = selE > selS;
        int caretBase = hasSel ? selS : clampPos(ti.cursorPosition(), cur.length());
        int reservedLen = hasSel ? cur.length() - (selE - selS) : cur.length();
        int max = ti.maximumLength();
        int room = Math.max(0, max - reservedLen);
        if (room == 0 && !hasSel) return false;
        String add = text.length() > room ? text.substring(0, room) : text;
        String head = cur.substring(0, hasSel ? selS : caretBase);
        String tail = cur.substring(hasSel ? selE : caretBase);
        ti.setText(head + add + tail);
        ti.setCursorPosition(caretBase + add.length());
        clearSelection(ti);
        ti.emitTextChanged();
        return true;
    }

    private static boolean deleteSelection(TextEditable ti, String cur) {
        int s = clampPos(ti.selectionStart(), cur.length());
        int e = clampPos(ti.selectionEnd(), cur.length());
        if (e <= s) return false;
        ti.setText(cur.substring(0, s) + cur.substring(e));
        ti.setCursorPosition(s);
        clearSelection(ti);
        ti.emitTextChanged();
        return true;
    }

    private static void setSelection(TextEditable ti, int start, int end) {
        ti.setSelectionRange(start, end);
    }

    private static void clearSelection(TextEditable ti) {
        ti.setSelectionAnchor(-1);
        ti.setSelectionRange(0, 0);
    }

    private static int clampPos(int p, int len) {
        return Math.max(0, Math.min(p, len));
    }

    boolean dispatchClick(float x, float y) {
        return dispatchPointerDown(x, y) && dispatchPointerUp(x, y);
    }

    // Qt.MouseButton of the press currently being tracked; released/clicked and
    // positionChanged payloads carry it so handlers can route on mouse.button.
    private int pressButton = MouseEvent.LEFT_BUTTON;

    boolean dispatchPointerDown(float x, float y) {
        return dispatchPointerDown(x, y, MouseEvent.LEFT_BUTTON);
    }

    // button is a Qt.MouseButton value (LeftButton=1, RightButton=2, MiddleButton=4).
    // Only the left button drives text editing, buttons and flick-scrolling (Qt
    // semantics); any other button sees MouseAreas alone, and a MouseArea whose
    // acceptedButtons doesn't include the button is transparent to the press.
    boolean dispatchPointerDown(float x, float y, int button) {
        if (root == null) return false;
        pressButton = button;
        if (button == MouseEvent.LEFT_BUTTON) {
            TextEditable ti = hitTestTextEditable(root, x, y);
            if (ti != null) {
                focus.setFocus((Item) ti);
                float[] local = localCoords((Item) ti, x, y);
                int idx = ti.caretIndexAt(local[0], local[1], renderer);
                clearSelection(ti);
                ti.setSelectionAnchor(idx);
                ti.setCursorPosition(idx);
                textCapturing = ti;
                return true;
            }
            if (focus.focused() instanceof TextEditable) focus.clearFocus();
            AbstractButton btn = hitTestButton(root, x, y);
            if (btn != null) {
                capturedButton = btn;
                captureRootX = x;
                captureRootY = y;
                if (!Boolean.FALSE.equals(btn.enabled.peek())) btn.press();
                return true;
            }
        }
        MouseArea hit = hitTestMouseArea(root, x, y, button);
        if (hit != null) {
            captured = hit;
            captureRootX = x;
            captureRootY = y;
            float[] local = localCoords(hit, x, y);
            hit.mouseX.set(local[0]);
            hit.mouseY.set(local[1]);
            hit.pressed.set(Boolean.TRUE);
            setContains(hit, true);
            hit.pressedSignal.emit(new MouseEvent(local[0], local[1], button, button, 0));
            beginDragIfRequested(hit);
            // If the MouseArea isn't dragging its own target, remember the
            // Flickables beneath it so a drag past threshold scrolls one of them.
            // Only a left press may hand off to a flick-scroll.
            pendingChain.clear();
            if (button == MouseEvent.LEFT_BUTTON && dragTarget == null) {
                hitTestFlickables(root, x, y, pendingChain);
                for (int i = 0; i < pendingChain.size(); i++) pendingChain.get(i).stopScroll();
            }
            return true;
        }
        if (button != MouseEvent.LEFT_BUTTON) return false;
        // Pressed straight onto a Flickable. With nested scrollers, which one moves
        // depends on the drag axis, so the choice waits for the first move; a lone
        // Flickable takes the gesture immediately (it starts `moving` at press).
        pendingChain.clear();
        hitTestFlickables(root, x, y, pendingChain);
        if (pendingChain.isEmpty()) return false;
        for (int i = 0; i < pendingChain.size(); i++) pendingChain.get(i).stopScroll();
        captureRootX = x;
        captureRootY = y;
        if (pendingChain.size() == 1) {
            beginScroll(pendingChain.get(0), x, y);
            return true;
        }
        beginScrollVelocity(x, y);
        return true;
    }

    // The nearest Flickable in the pending chain that scrolls along the gesture's
    // axis. The dominant axis is tried first, then the other one, so a mostly
    // vertical drag prefers a vertical scroller but a horizontal-only chain still
    // responds to the horizontal component.
    private Flickable chooseFlickable(float x, float y, float threshold) {
        float dx = x - captureRootX;
        float dy = y - captureRootY;
        boolean verticalFirst = Math.abs(dy) >= Math.abs(dx);
        for (int pass = 0; pass < 2; pass++) {
            boolean wantY = (pass == 0) == verticalFirst;
            if ((wantY ? Math.abs(dy) : Math.abs(dx)) <= threshold) continue;
            for (int i = 0; i < pendingChain.size(); i++) {
                Flickable f = pendingChain.get(i);
                if (wantY ? (allowY(f) && maxY(f) > 0f) : (allowX(f) && maxX(f) > 0f)) return f;
            }
        }
        return null;
    }

    private void beginScroll(Flickable f, float x, float y) {
        pendingChain.clear();
        scrolling = f;
        f.moving.set(Boolean.TRUE);
        captureRootX = x;
        captureRootY = y;
        scrollStartContentX = f.contentX.peekFloat();
        scrollStartContentY = f.contentY.peekFloat();
        beginScrollVelocity(x, y);
    }

    boolean dispatchPointerMove(float x, float y) {
        if (textCapturing != null) {
            extendTextSelection(x, y);
            return true;
        }
        if (captured != null) {
            if (!pendingChain.isEmpty() && dragTarget == null && stealsToFlick(x, y)) {
                return true;
            }
            float[] local = localCoords(captured, x, y);
            captured.mouseX.set(local[0]);
            captured.mouseY.set(local[1]);
            setContains(captured, within(captured, local));
            applyDrag(x, y);
            captured.positionChanged.emit(
                new MouseEvent(local[0], local[1], pressButton, pressButton, 0));
            return true;
        }
        if (scrolling != null) {
            applyScroll(x, y);
            return true;
        }
        if (!pendingChain.isEmpty()) {
            // A press that landed on the Flickables themselves: start scrolling the
            // first one that moves along this gesture's axis.
            Flickable f = chooseFlickable(x, y, 0f);
            if (f != null) beginScroll(f, x, y);
            return true;
        }
        return updateHover(x, y);
    }

    private boolean updateHover(float x, float y) {
        MouseArea hit = hitTestMouseArea(root, x, y, 0);
        MouseArea next = (hit != null && Boolean.TRUE.equals(hit.hoverEnabled.peek())) ? hit : null;
        if (next == hovered) return next != null;
        if (hovered != null) setContains(hovered, false);
        hovered = next;
        if (hovered != null) setContains(hovered, true);
        return hovered != null;
    }

    // Toggles containsMouse and fires entered/exited only on a real transition.
    private static void setContains(MouseArea ma, boolean inside) {
        if (Boolean.valueOf(inside).equals(ma.containsMouse.peek())) return;
        ma.containsMouse.set(inside);
        (inside ? ma.entered : ma.exited).emit();
    }

    private static boolean within(MouseArea ma, float[] local) {
        return local[0] >= 0 && local[1] >= 0
            && local[0] <= ma.width.peekFloat()
            && local[1] <= ma.height.peekFloat();
    }

    boolean dispatchPointerUp(float x, float y) {
        return dispatchPointerUp(x, y, pressButton);
    }

    boolean dispatchPointerUp(float x, float y, int button) {
        if (textCapturing != null) {
            extendTextSelection(x, y);
            textCapturing = null;
            return true;
        }
        if (capturedButton != null) {
            AbstractButton b = capturedButton;
            capturedButton = null;
            if (Boolean.FALSE.equals(b.enabled.peek())) return true;
            float[] local = localCoords(b, x, y);
            boolean inside = local[0] >= 0 && local[1] >= 0
                && local[0] <= b.width.peekFloat()
                && local[1] <= b.height.peekFloat();
            if (inside) b.releaseInside(); else b.releaseOutside();
            return true;
        }
        if (captured != null) {
            pendingChain.clear();
            MouseArea target = captured;
            float[] local = localCoords(target, x, y);
            target.mouseX.set(local[0]);
            target.mouseY.set(local[1]);
            target.pressed.set(Boolean.FALSE);
            setContains(target, false);
            endDrag(target);
            // Release payloads: button = the releasing button, buttons = still held (none).
            target.released.emit(new MouseEvent(local[0], local[1], button, 0, 0));
            boolean inside = local[0] >= 0 && local[1] >= 0
                && local[0] <= target.width.peekFloat()
                && local[1] <= target.height.peekFloat();
            if (inside) target.clicked.emit(new MouseEvent(local[0], local[1], button, 0, 0));
            captured = null;
            return true;
        }
        if (scrolling != null) {
            applyScroll(x, y);
            scrolling.startFling(flingVelX(), flingVelY());
            scrolling = null;
            return true;
        }
        return false;
    }

    private void extendTextSelection(float x, float y) {
        TextEditable ti = textCapturing;
        float[] local = localCoords((Item) ti, x, y);
        int idx = ti.caretIndexAt(local[0], local[1], renderer);
        if (ti.selectionAnchor() < 0) ti.setSelectionAnchor(idx);
        int anchor = ti.selectionAnchor();
        int s = Math.min(anchor, idx);
        int e = Math.max(anchor, idx);
        setSelection(ti, s, e);
        ti.setCursorPosition(idx);
    }

    private void beginDragIfRequested(MouseArea hit) {
        Item dt = hit.drag.target.peek();
        if (dt == null) return;
        dragTarget = dt;
        dragStartX = dt.x.peekFloat();
        dragStartY = dt.y.peekFloat();
    }

    private void applyDrag(float rootX, float rootY) {
        if (dragTarget == null) return;
        Drag drag = captured.drag;
        String axis = drag.axis.peek();
        boolean allowX = !"YAxis".equals(axis);
        boolean allowY = !"XAxis".equals(axis);
        float dx = rootX - captureRootX;
        float dy = rootY - captureRootY;
        if (allowX) {
            float nx = clamp(dragStartX + dx,
                             drag.minimumX.peekFloat(),
                             drag.maximumX.peekFloat());
            dragTarget.x.set(nx);
        }
        if (allowY) {
            float ny = clamp(dragStartY + dy,
                             drag.minimumY.peekFloat(),
                             drag.maximumY.peekFloat());
            dragTarget.y.set(ny);
        }
        drag.active.set(Boolean.TRUE);
    }

    private void endDrag(MouseArea hit) {
        if (dragTarget == null) return;
        hit.drag.active.set(Boolean.FALSE);
        dragTarget = null;
    }

    // True once a captured MouseArea's drag passes the threshold along the
    // Flickable's scroll axis: cancels the MouseArea press (no click) and hands
    // the live gesture to the Flickable, so list rows stay tappable yet scroll.
    private boolean stealsToFlick(float x, float y) {
        // Qt's MouseArea.preventStealing: a child that has claimed the gesture keeps
        // it for the rest of the press. Read live, so a control can open the door at
        // press time (letting a vertical flick scroll the page) and close it once it
        // recognises its own drag -- a horizontal slider drag then survives the
        // finger drifting off-axis.
        if (Boolean.TRUE.equals(captured.preventStealing.peek())) return false;
        Flickable target = chooseFlickable(x, y, DRAG_THRESHOLD);
        if (target == null) return false;
        MouseArea ma = captured;
        ma.pressed.set(Boolean.FALSE);
        setContains(ma, false);
        // The Flickable is taking over the gesture — tell the MouseArea its press
        // was canceled (Qt does the same). Without this, a child that tracks the
        // press (e.g. Ripple's wave) never learns it ended and stays stuck.
        ma.canceled.emit();
        captured = null;
        beginScroll(target, x, y);
        applyScroll(x, y);
        return true;
    }

    // Begin (or restart) velocity tracking for a fresh drag at (x, y).
    private void beginScrollVelocity(float x, float y) {
        sampleCount = 0;
        addScrollSample(x, y);
    }

    // Record a pointer sample (raw position + processing time) into the ring.
    // arraycopy within one array shifts the ring buffer down one slot (memmove semantics --
    // overlapping ranges are handled correctly); not a suspicious same-array copy.
    @SuppressWarnings("SuspiciousSystemArraycopy")
    private void addScrollSample(float x, float y) {
        if (sampleCount == VEL_SAMPLES) {
            System.arraycopy(sampleNanos, 1, sampleNanos, 0, VEL_SAMPLES - 1);
            System.arraycopy(sampleX, 1, sampleX, 0, VEL_SAMPLES - 1);
            System.arraycopy(sampleY, 1, sampleY, 0, VEL_SAMPLES - 1);
            sampleCount--;
        }
        sampleNanos[sampleCount] = System.nanoTime();
        sampleX[sampleCount] = x;
        sampleY[sampleCount] = y;
        sampleCount++;
    }

    private void trackScrollVelocity(float x, float y) {
        addScrollSample(x, y);
    }

    // Content velocity (px/sec) = -(finger displacement)/(elapsed) measured from the
    // newest sample back to the oldest sample still within VEL_WINDOW. Averaging over
    // the window ignores a jittery final sample (no spurious reversal) and reflects
    // the true flick speed even when individual moves were unevenly timed.
    private float flingVelX() { return flingVel(sampleX); }
    private float flingVelY() { return flingVel(sampleY); }

    private float flingVel(float[] axis) {
        if (sampleCount < 2) return 0f;
        long newest = sampleNanos[sampleCount - 1];
        int oldest = sampleCount - 1;
        for (int i = sampleCount - 1; i >= 0; i--) {
            if ((newest - sampleNanos[i]) / 1_000_000_000f > VEL_WINDOW) break;
            oldest = i;
        }
        float dt = (newest - sampleNanos[oldest]) / 1_000_000_000f;
        if (dt < 0.001f) return 0f;
        return -(axis[sampleCount - 1] - axis[oldest]) / dt;
    }

    private void applyScroll(float rootX, float rootY) {
        trackScrollVelocity(rootX, rootY);
        Flickable f = scrolling;
        String dir = f.flickableDirection.peek();
        boolean allowX = !"VerticalFlick".equals(dir);
        boolean allowY = !"HorizontalFlick".equals(dir);
        float w = f.width.peekFloat();
        float h = f.height.peekFloat();
        float cw = f.contentWidth.peekFloat();
        float ch = f.contentHeight.peekFloat();
        // Flickable.bottomMargin/rightMargin extend the scrollable range past the content
        // so trailing content (e.g. a page's last row under a 32px bottom margin) can be
        // brought fully into view.
        float maxX = Math.max(0f, cw + f.rightMargin.peekFloat() - w);
        float maxY = Math.max(0f, ch + f.bottomMargin.peekFloat() - h);
        // Drag tracks the finger 1:1 (responsive); the eased glide is only the
        // release inertia. Keep the fling target in step via syncTarget.
        if (allowX) {
            f.contentX.setPaintOnly(clamp(scrollStartContentX - (rootX - captureRootX), 0f, maxX));
        }
        if (allowY) {
            f.contentY.setPaintOnly(clamp(scrollStartContentY - (rootY - captureRootY), 0f, maxY));
        }
        f.syncTarget();
    }

    // Mouse wheel at (x, y): dx/dy are wheel offsets (GLFW convention, +y = scroll up).
    // The wheel walks the nested Flickables under the cursor from innermost outwards, so
    // a child that cannot move on the wheel's axis (a horizontal chip strip inside a
    // vertical list, or a list already at its end-stop) hands the notch to its ancestor
    // instead of swallowing it.
    boolean dispatchWheel(float x, float y, float dx, float dy) {
        List<Flickable> chain = new ArrayList<Flickable>(4);
        hitTestFlickables(root, x, y, chain);
        for (int i = 0; i < chain.size(); i++) {
            if (wheelOnAxis(chain.get(i), dx, dy)) return true;
        }
        // Nothing in the chain scrolls on the wheel's own axis, so let a horizontal-only
        // list take the vertical notch — the usual "wheel over a horizontal list scrolls
        // it sideways" behaviour, now a fallback rather than a first claim.
        for (int i = 0; i < chain.size(); i++) {
            if (wheelCrossAxis(chain.get(i), dy)) return true;
        }
        return false;
    }

    // Scrolls f on the wheel's own axes. False when it has no room there, which lets
    // dispatchWheel continue up the chain.
    private boolean wheelOnAxis(Flickable f, float dx, float dy) {
        boolean scrolled = false;
        if (dy != 0f && allowY(f) && maxY(f) > 0f) {
            f.contentY.setPaintOnly(clamp(f.contentY.peekFloat() - dy * WHEEL_STEP, 0f, maxY(f)));
            scrolled = true;
        }
        if (dx != 0f && allowX(f) && maxX(f) > 0f) {
            f.contentX.setPaintOnly(clamp(f.contentX.peekFloat() - dx * WHEEL_STEP, 0f, maxX(f)));
            scrolled = true;
        }
        if (scrolled) f.syncTarget();
        return scrolled;
    }

    // Maps a vertical notch onto a horizontal-only list's X axis.
    private boolean wheelCrossAxis(Flickable f, float dy) {
        if (dy == 0f || !allowX(f) || maxX(f) <= 0f) return false;
        f.contentX.setPaintOnly(clamp(f.contentX.peekFloat() - dy * WHEEL_STEP, 0f, maxX(f)));
        f.syncTarget();
        return true;
    }

    private static boolean allowX(Flickable f) {
        return !"VerticalFlick".equals(f.flickableDirection.peek());
    }

    private static boolean allowY(Flickable f) {
        return !"HorizontalFlick".equals(f.flickableDirection.peek());
    }

    private static float maxX(Flickable f) {
        return Math.max(0f, f.contentWidth.peekFloat() + f.rightMargin.peekFloat() - f.width.peekFloat());
    }

    private static float maxY(Flickable f) {
        return Math.max(0f, f.contentHeight.peekFloat() + f.bottomMargin.peekFloat() - f.height.peekFloat());
    }

    // Appends every interactive Flickable containing (x, y) to out, innermost first.
    private void hitTestFlickables(Item item, float x, float y, List<Flickable> out) {
        if (!item.isVisible()) return;
        float lx = x - item.x.peekFloat();
        float ly = y - item.y.peekFloat();
        if (lx < 0 || ly < 0 || lx > item.width.peekFloat() || ly > item.height.peekFloat()) return;
        float childLx = lx;
        float childLy = ly;
        if (item instanceof Flickable) {
            Flickable f = (Flickable) item;
            childLx += f.contentX.peekFloat();
            childLy += f.contentY.peekFloat();
        }
        List<Item> ordered = zOrdered(item.children);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            int before = out.size();
            hitTestFlickables(ordered.get(i), childLx, childLy, out);
            // The topmost child that yields a Flickable occludes its lower siblings; one
            // that yields none (a plain overlay such as a ScrollBar) is scrolled through.
            if (out.size() != before) break;
        }
        if (item instanceof Flickable) {
            Flickable f = (Flickable) item;
            if (Boolean.TRUE.equals(f.interactive.peek())) out.add(f);
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    TextEditable pickTextEditable(float x, float y) {
        return root == null ? null : hitTestTextEditable(root, x, y);
    }

    TextInput pickTextInput(float x, float y) {
        TextEditable te = pickTextEditable(x, y);
        return te instanceof TextInput ? (TextInput) te : null;
    }

    private TextEditable hitTestTextEditable(Item item, float x, float y) {
        if (!item.isVisible()) return null;
        float ix = item.x.peekFloat();
        float iy = item.y.peekFloat();
        float w = item.width.peekFloat();
        float h = item.height.peekFloat();
        float lx = x - ix;
        float ly = y - iy;
        if (lx < 0 || ly < 0 || lx > w || ly > h) return null;
        float childLx = lx;
        float childLy = ly;
        if (item instanceof Flickable) {
            Flickable f = (Flickable) item;
            childLx += f.contentX.peekFloat();
            childLy += f.contentY.peekFloat();
        }
        List<Item> ordered = zOrdered(item.children);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            TextEditable hit = hitTestTextEditable(ordered.get(i), childLx, childLy);
            if (hit != null) return hit;
        }
        return item instanceof TextEditable ? (TextEditable) item : null;
    }

    private AbstractButton hitTestButton(Item item, float x, float y) {
        if (!item.isVisible()) return null;
        float ix = item.x.peekFloat();
        float iy = item.y.peekFloat();
        float w = item.width.peekFloat();
        float h = item.height.peekFloat();
        float lx = x - ix;
        float ly = y - iy;
        if (lx < 0 || ly < 0 || lx > w || ly > h) return null;
        float childLx = lx;
        float childLy = ly;
        if (item instanceof Flickable) {
            Flickable f = (Flickable) item;
            childLx += f.contentX.peekFloat();
            childLy += f.contentY.peekFloat();
        }
        List<Item> ordered = zOrdered(item.children);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            AbstractButton hit = hitTestButton(ordered.get(i), childLx, childLy);
            if (hit != null) return hit;
        }
        return item instanceof AbstractButton ? (AbstractButton) item : null;
    }

    // buttonMask: the Qt.MouseButton bit of the press, or 0 for hover (no filter).
    private MouseArea hitTestMouseArea(Item item, float x, float y, int buttonMask) {
        if (!item.isVisible()) return null;
        float ix = item.x.peekFloat();
        float iy = item.y.peekFloat();
        float w = item.width.peekFloat();
        float h = item.height.peekFloat();
        float lx = x - ix;
        float ly = y - iy;
        if (lx < 0 || ly < 0 || lx > w || ly > h) return null;
        float childLx = lx;
        float childLy = ly;
        if (item instanceof Flickable) {
            Flickable f = (Flickable) item;
            childLx += f.contentX.peekFloat();
            childLy += f.contentY.peekFloat();
        }
        List<Item> ordered = zOrdered(item.children);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            MouseArea hit = hitTestMouseArea(ordered.get(i), childLx, childLy, buttonMask);
            if (hit != null) return hit;
        }
        // A MouseArea is transparent to presses when disabled, accepting no buttons
        // (Qt: acceptedButtons: Qt.NoButton), or not accepting the pressed button —
        // the press falls through to whatever is beneath (Qt semantics).
        if (item instanceof MouseArea) {
            MouseArea ma = (MouseArea) item;
            if (Boolean.FALSE.equals(ma.enabled.peek())) return null;
            int accepted = ma.acceptedButtons.peekInt();
            if (accepted == 0) return null;
            if (buttonMask != 0 && (accepted & buttonMask) == 0) return null;
            return ma;
        }
        return null;
    }

    private float[] localCoords(Item target, float rootX, float rootY) {
        float ox = 0, oy = 0;
        Item cur = target;
        while (cur != null) {
            ox += cur.x.peekFloat();
            oy += cur.y.peekFloat();
            Item p = cur.parent.peek();
            if (p instanceof Flickable) {
                Flickable f = (Flickable) p;
                ox -= f.contentX.peekFloat();
                oy -= f.contentY.peekFloat();
            }
            cur = p;
        }
        return new float[]{rootX - ox, rootY - oy};
    }
}
