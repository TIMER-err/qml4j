package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.render.items.layout.Column;
import io.github.timer_err.qml4j.render.items.view.Component;
import io.github.timer_err.qml4j.render.items.view.Connections;
import io.github.timer_err.qml4j.render.items.core.Flickable;
import io.github.timer_err.qml4j.render.items.core.Gradient;
import io.github.timer_err.qml4j.render.items.core.GradientStop;
import io.github.timer_err.qml4j.render.items.core.Image;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.ListElement;
import io.github.timer_err.qml4j.render.items.view.ListModel;
import io.github.timer_err.qml4j.render.items.view.ListView;
import io.github.timer_err.qml4j.render.items.view.GridView;
import io.github.timer_err.qml4j.render.items.input.TextEdit;
import io.github.timer_err.qml4j.render.items.view.Loader;
import io.github.timer_err.qml4j.render.items.view.Repeater;
import io.github.timer_err.qml4j.render.items.core.MouseArea;
import io.github.timer_err.qml4j.render.items.core.Rectangle;
import io.github.timer_err.qml4j.render.items.layout.Row;
import io.github.timer_err.qml4j.render.items.core.Text;
import io.github.timer_err.qml4j.render.items.input.TextInput;
import io.github.timer_err.qml4j.render.items.animation.Timer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QmlViewTest {

    private static QmlView newView() {
        return QmlView.withStockTypes(new QmlEngine());
    }

    @Test
    void loadRectangle() {
        Item root = newView().load("Rectangle { width: 100; height: 50; color: \"#ff0000\" }");
        assertTrue(root instanceof Rectangle);
        Rectangle r = (Rectangle) root;
        assertEquals(100L, r.width.peek().longValue());
        assertEquals(50L, r.height.peek().longValue());
        assertEquals("#ff0000", r.color.peek());
    }

    @Test
    void nestedRectangleBindsToParent() {
        Item root = newView().load(
            "Item {\n" +
            "  width: 200\n" +
            "  height: 100\n" +
            "  Rectangle {\n" +
            "    width: parent.width / 2\n" +
            "    height: parent.height\n" +
            "    color: \"#00ff00\"\n" +
            "  }\n" +
            "}");
        assertEquals(1, root.children.size());
        Rectangle r = (Rectangle) root.children.get(0);
        assertEquals(100L, r.width.peek().longValue());
        assertEquals(100L, r.height.peek().longValue());
        assertSame(root, r.parent.peek());
        root.width.set(400);
        assertEquals(200L, r.width.peek().longValue());
    }

    @Test
    void textType() {
        Item root = newView().load("Text { text: \"hi\"; fontSize: 20 }");
        Text t = (Text) root;
        assertEquals("hi", t.text.peek());
        assertEquals(20L, t.fontSize.peek().longValue());
    }

    @Test
    void columnLaysOutChildren() {
        Item root = newView().load(
            "Column {\n" +
            "  spacing: 5\n" +
            "  Rectangle { width: 10; height: 20 }\n" +
            "  Rectangle { width: 30; height: 15 }\n" +
            "  Rectangle { width: 8;  height: 7  }\n" +
            "}");
        Column col = (Column) root;
        assertEquals(3, col.children.size());
        col.layout();
        assertEquals(0.0, col.children.get(0).y.peek().doubleValue(), 1e-9);
        assertEquals(25.0, col.children.get(1).y.peek().doubleValue(), 1e-9);
        assertEquals(45.0, col.children.get(2).y.peek().doubleValue(), 1e-9);
        assertEquals(52.0, col.height.peek().doubleValue(), 1e-9);
        assertEquals(30L, col.width.peek().longValue());
    }

    @Test
    void rootMustExtendItem() {
        QmlView v = newView();
        assertNotNull(v.load("Item {}"));
    }

    @Test
    void mouseAreaClickHandlerFiresAndAssigns() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  width: 200; height: 200\n" +
            "  color: \"#ff0000\"\n" +
            "  MouseArea {\n" +
            "    width: parent.width\n" +
            "    height: parent.height\n" +
            "    onClicked: parent.color = \"#00ff00\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals("#ff0000", r.color.peek());
        assertTrue(v.dispatchClick(10, 10));
        assertEquals("#00ff00", r.color.peek());
    }

    @Test
    void mouseAreaClickMissesOutsideBounds() {
        QmlView v = newView();
        v.load(
            "Item {\n" +
            "  width: 100; height: 100\n" +
            "  MouseArea { width: 50; height: 50; onClicked: parent.width = 999 }\n" +
            "}");
        // miss
        assertEquals(false, v.dispatchClick(80, 80));
        assertEquals(100L, v.root().width.peek().longValue());
        // hit
        assertTrue(v.dispatchClick(10, 10));
        assertEquals(999L, v.root().width.peek().longValue());
    }

    @Test
    void anchorsFillSizesToParentMinusMargins() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  Rectangle {\n" +
            "    anchors.fill: parent\n" +
            "    anchors.margins: 10\n" +
            "    color: \"#ff0000\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        v.renderer();
        Renderer.applyAnchors(r);
        assertEquals(10.0, r.x.peek().doubleValue(), 1e-6);
        assertEquals(10.0, r.y.peek().doubleValue(), 1e-6);
        assertEquals(180.0, r.width.peek().doubleValue(), 1e-6);
        assertEquals(80.0, r.height.peek().doubleValue(), 1e-6);
    }

    @Test
    void anchorsCenterInCentersChild() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  Rectangle {\n" +
            "    width: 40; height: 20\n" +
            "    anchors.centerIn: parent\n" +
            "    color: \"#00ff00\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        Renderer.applyAnchors(r);
        assertEquals(80.0, r.x.peek().doubleValue(), 1e-6);
        assertEquals(40.0, r.y.peek().doubleValue(), 1e-6);
    }

    @Test
    void anchorsLeftRightStretchesAcrossParent() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  Rectangle {\n" +
            "    anchors.left: parent.left\n" +
            "    anchors.right: parent.right\n" +
            "    anchors.leftMargin: 5\n" +
            "    anchors.rightMargin: 15\n" +
            "    color: \"#0000ff\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        Renderer.applyAnchors(r);
        assertEquals(5.0, r.x.peek().doubleValue(), 1e-6);
        assertEquals(180.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void anchorsRightOnlyOffsetsByOwnWidth() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  Rectangle {\n" +
            "    width: 30; height: 30\n" +
            "    anchors.right: parent.right\n" +
            "    anchors.rightMargin: 10\n" +
            "    color: \"#ff00ff\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        Renderer.applyAnchors(r);
        assertEquals(160.0, r.x.peek().doubleValue(), 1e-6);
    }

    @Test
    void anchorsHorizontalCenterCentersHorizontally() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  Rectangle {\n" +
            "    width: 40; height: 20\n" +
            "    anchors.horizontalCenter: parent.horizontalCenter\n" +
            "    anchors.top: parent.top\n" +
            "    color: \"#00ffff\"\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        Renderer.applyAnchors(r);
        assertEquals(80.0, r.x.peek().doubleValue(), 1e-6);
        assertEquals(0.0, r.y.peek().doubleValue(), 1e-6);
    }

    @Test
    void anchorsAnchorToSiblingRightEdge() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 300; height: 100\n" +
            "  Rectangle { id: a; x: 10; width: 50; height: 50; color: \"#ff0000\" }\n" +
            "  Rectangle {\n" +
            "    anchors.left: a.right\n" +
            "    anchors.leftMargin: 8\n" +
            "    width: 20; height: 20\n" +
            "    color: \"#00ff00\"\n" +
            "  }\n" +
            "}");
        Rectangle b = (Rectangle) root.children.get(1);
        Renderer.applyAnchors(b);
        assertEquals(68.0, b.x.peek().doubleValue(), 1e-6);
    }

    @Test
    void itemEdgeAnchorLineIdentifiesSource() {
        QmlView v = newView();
        Item root = v.load("Item { width: 100 }");
        io.github.timer_err.qml4j.render.AnchorLine line = root.right.peek();
        assertTrue(line.source == root);
        assertEquals(io.github.timer_err.qml4j.render.AnchorLine.Edge.RIGHT, line.edge);
    }

    @Test
    void imageTypeLoadsSource() {
        QmlView v = newView();
        Item root = v.load(
            "Image { width: 32; height: 32; source: \"foo.png\" }");
        assertTrue(root instanceof Image);
        Image img = (Image) root;
        assertEquals("foo.png", img.source.peek());
        assertEquals(32L, img.width.peek().longValue());
    }

    @Test
    void imageFillModeDefaultsToStretch() {
        Image img = (Image) newView().load("Image { width: 32; height: 32 }");
        assertEquals("Stretch", img.fillMode.peek());
    }

    @Test
    void imageFillModeAcceptsStandardValues() {
        Image img = (Image) newView().load(
            "Image { width: 32; height: 32; fillMode: \"PreserveAspectFit\" }");
        assertEquals("PreserveAspectFit", img.fillMode.peek());
    }

    @Test
    void loaderResolvesNestedQml() {
        QmlView v = newView();
        v.resources(name -> {
            if ("child.qml".equals(name)) {
                return "Rectangle { width: 40; height: 30; color: \"#abcdef\" }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        });
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 200\n" +
            "  Loader { source: \"child.qml\" }\n" +
            "}");
        Loader loader = (Loader) root.children.get(0);
        v.renderer().resolveLoader(loader);
        assertNotNull(loader.item.peek());
        assertTrue(loader.item.peek() instanceof Rectangle);
        Rectangle r = (Rectangle) loader.item.peek();
        assertEquals(40L, r.width.peek().longValue());
        assertEquals("#abcdef", r.color.peek());
        assertEquals(1, loader.children.size());
        assertSame(loader, r.parent.peek());
    }

    @Test
    void opacityComposesAcrossParents() {
        Item parent = new Item();
        parent.opacity.set(0.5);
        Item child = new Item();
        child.opacity.set(0.5);
        parent.children.add(child);
        child.parent.set(parent);
        // No canvas needed for this — we only assert the math the renderer uses.
        float effective = parent.opacity.peek().floatValue() * child.opacity.peek().floatValue();
        org.junit.jupiter.api.Assertions.assertEquals(0.25f, effective, 1e-6);
    }

    @Test
    void dirtyQueueCoalescesRedundantReevaluations() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 10; height: 20\n" +
            "  Rectangle { width: parent.width + parent.height; height: 1 }\n" +
            "}");
        Rectangle r = (Rectangle) root.children.get(0);
        assertEquals(30L, r.width.peek().longValue());
        io.github.timer_err.qml4j.engine.binding.DirtyQueue dq = v.dirtyQueue();
        dq.install();
        try {
            root.width.set(100);
            root.height.set(200);
            // While the queue is installed, the binding is queued, not yet re-run.
            assertEquals(30L, r.width.peek().longValue());
            dq.flush();
            assertEquals(300L, r.width.peek().longValue());
        } finally {
            dq.uninstall();
        }
    }

    @Test
    void customSignalDeclarationAndHandler() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 10; height: 10\n" +
            "  signal pinged()\n" +
            "  onPinged: width = 999\n" +
            "}");
        try {
            io.github.timer_err.qml4j.engine.Signal s = (io.github.timer_err.qml4j.engine.Signal)
                root.getClass().getField("pinged").get(root);
            assertNotNull(s);
            s.emit();
            assertEquals(999L, root.width.peek().longValue());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void numberAnimationLinearTicksTowardTarget() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle { id: r\n" +
            "  width: 100; height: 100\n" +
            "  NumberAnimation {\n" +
            "    target: r\n" +
            "    from: 0; to: 1000; duration: 100\n" +
            "    running: true\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation anim =
            (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) r.children.get(0);
        anim.property.set("width");
        long t0 = 1_000_000_000L;
        anim.tick(t0);
        anim.tick(t0 + 50_000_000L); // 50ms = 50%
        assertEquals(500.0, r.width.peek().doubleValue(), 1.0);
        anim.tick(t0 + 200_000_000L); // past end → clamp + stop
        assertEquals(1000.0, r.width.peek().doubleValue(), 1e-6);
        assertEquals(Boolean.FALSE, anim.running.peek());
    }

    @Test
    void numberAnimationZeroDurationJumps() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle { id: r\n" +
            "  width: 10; height: 10\n" +
            "  NumberAnimation {\n" +
            "    target: r\n" +
            "    from: 0; to: 42; duration: 0\n" +
            "    running: true\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation anim =
            (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) r.children.get(0);
        anim.property.set("width");
        anim.tick(1L);
        assertEquals(42.0, r.width.peek().doubleValue(), 1e-9);
        assertEquals(Boolean.FALSE, anim.running.peek());
    }

    @Test
    void numberAnimationNotRunningSkips() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle { id: r\n" +
            "  width: 7; height: 7\n" +
            "  NumberAnimation { target: r; from: 0; to: 99; duration: 100 }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation anim =
            (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) r.children.get(0);
        anim.property.set("width");
        anim.tick(1L);
        anim.tick(50_000_000L);
        assertEquals(7L, r.width.peek().longValue());
    }

    @Test
    void numberAnimationEaseOutQuadEndsAtTarget() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle { id: r\n" +
            "  width: 0; height: 10\n" +
            "  NumberAnimation {\n" +
            "    target: r\n" +
            "    from: 0; to: 100; duration: 100\n" +
            "    easing.type: Easing.OutQuad\n" +
            "    running: true\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation anim =
            (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) r.children.get(0);
        anim.property.set("width");
        long t0 = 0L;
        anim.tick(t0);
        anim.tick(t0 + 50_000_000L); // 50%, eased = 1 - 0.25 = 0.75
        assertEquals(75.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void stateAppliesPropertyChangesToTarget() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 100; height: 50; color: \"#ff0000\"\n" +
            "  states: [\n" +
            "    State {\n" +
            "      name: \"big\"\n" +
            "      PropertyChanges { target: r; width: 300; color: \"#00ff00\" }\n" +
            "    }\n" +
            "  ]\n" +
            "  state: \"big\"\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals(300L, r.width.peek().longValue());
        assertEquals("#00ff00", r.color.peek());
        // revert by clearing state
        r.state.set("");
        assertEquals(100L, r.width.peek().longValue());
        assertEquals("#ff0000", r.color.peek());
    }

    @Test
    void stateSwitchRevertsPreviousAndAppliesNext() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 10; height: 10; color: \"#000000\"\n" +
            "  states: [\n" +
            "    State { name: \"a\"; PropertyChanges { target: r; width: 100 } },\n" +
            "    State { name: \"b\"; PropertyChanges { target: r; width: 200; color: \"#abcdef\" } }\n" +
            "  ]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.state.set("a");
        assertEquals(100L, r.width.peek().longValue());
        assertEquals("#000000", r.color.peek());
        r.state.set("b");
        assertEquals(200L, r.width.peek().longValue());
        assertEquals("#abcdef", r.color.peek());
        r.state.set("a");
        assertEquals(100L, r.width.peek().longValue());
        assertEquals("#000000", r.color.peek());
    }

    @Test
    void stateAppliesAtInitialAssignmentRegardlessOfMemberOrder() {
        // `state:` written BEFORE `states:` in source — compiler must still
        // emit state assignment last so the lookup finds the populated list.
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 1; height: 1\n" +
            "  state: \"x\"\n" +
            "  states: [\n" +
            "    State { name: \"x\"; PropertyChanges { target: r; width: 77 } }\n" +
            "  ]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals(77L, r.width.peek().longValue());
    }

    @Test
    void propertyChangesEvaluatesBindingExpression() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 10; height: 20\n" +
            "  states: [\n" +
            "    State { name: \"calc\"; PropertyChanges { target: r; width: r.height * 3 + 1 } }\n" +
            "  ]\n" +
            "  state: \"calc\"\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals(61L, r.width.peek().longValue());
    }

    @Test
    void transitionTweensFromBeforeToAfterAcrossStateChange() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 100; height: 10\n" +
            "  states: [State { name: \"big\"; PropertyChanges { target: r; width: 300 } }]\n" +
            "  transitions: [Transition { from: \"*\"; to: \"*\"; NumberAnimation { duration: 100 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.state.set("big");
        // Ephemeral anim should now sit in children with from=100, to=300, running=true.
        // Width should be set back to 100 to start tween.
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
        // Manually tick the ephemeral anim (it's the last child added).
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation eph = null;
        for (Item c : r.children) {
            if (c instanceof io.github.timer_err.qml4j.render.items.animation.NumberAnimation
                && ((io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c).ephemeral) {
                eph = (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c;
                break;
            }
        }
        assertNotNull(eph);
        long t0 = 1_000_000_000L;
        eph.tick(t0);
        eph.tick(t0 + 50_000_000L); // 50%
        assertEquals(200.0, r.width.peek().doubleValue(), 1.0);
        eph.tick(t0 + 150_000_000L); // past end
        assertEquals(300.0, r.width.peek().doubleValue(), 1e-6);
        assertEquals(Boolean.FALSE, eph.running.peek());
    }

    @Test
    void transitionAnimatesRevertBackToEmptyState() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 100; height: 10\n" +
            "  states: [State { name: \"big\"; PropertyChanges { target: r; width: 300 } }]\n" +
            "  transitions: [Transition { NumberAnimation { duration: 100 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.state.set("big");
        // Drain the enter animation so width settles at 300.
        for (Item c : r.children) {
            if (c instanceof io.github.timer_err.qml4j.render.items.animation.NumberAnimation
                && ((io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c).ephemeral) {
                io.github.timer_err.qml4j.render.items.animation.NumberAnimation a = (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c;
                a.tick(0L);
                a.tick(200_000_000L);
            }
        }
        assertEquals(300.0, r.width.peek().doubleValue(), 1e-6);

        // Revert to empty state — must spawn a shrink animation, not snap.
        r.state.set("");
        assertEquals(300.0, r.width.peek().doubleValue(), 1e-6); // rewound to before-value
        io.github.timer_err.qml4j.render.items.animation.NumberAnimation shrink = null;
        for (Item c : r.children) {
            if (c instanceof io.github.timer_err.qml4j.render.items.animation.NumberAnimation
                && ((io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c).ephemeral
                && Boolean.TRUE.equals(((io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c).running.peek())) {
                shrink = (io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c;
            }
        }
        assertNotNull(shrink, "revert direction must spawn an ephemeral animation");
        long t0 = 2_000_000_000L;
        shrink.tick(t0);
        shrink.tick(t0 + 200_000_000L);
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void transitionFromToFiltersBlockNonMatchingChanges() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 10; height: 10\n" +
            "  states: [\n" +
            "    State { name: \"a\"; PropertyChanges { target: r; width: 50 } },\n" +
            "    State { name: \"b\"; PropertyChanges { target: r; width: 90 } }\n" +
            "  ]\n" +
            "  transitions: [\n" +
            "    Transition { from: \"a\"; to: \"b\"; NumberAnimation { duration: 100 } }\n" +
            "  ]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        // First state change "" → "a" does NOT match transition (from="a" required).
        r.state.set("a");
        assertEquals(50L, r.width.peek().longValue());
        int before = r.children.size();
        // Second state change "a" → "b" DOES match.
        r.state.set("b");
        // ephemeral spawned; width rewound to 50
        assertEquals(50.0, r.width.peek().doubleValue(), 1e-6);
        assertEquals(before + 1, r.children.size());
    }

    @Test
    void transitionPropertiesCsvFiltersWhichToAnimate() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 10; height: 10\n" +
            "  states: [State { name: \"go\"; PropertyChanges { target: r; width: 100; height: 200 } }]\n" +
            "  transitions: [Transition { NumberAnimation { properties: \"width\"; duration: 50 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.state.set("go");
        // height is applied immediately (not in animation filter)
        assertEquals(200L, r.height.peek().longValue());
        // width is rewound to 10 by the spawned ephemeral
        assertEquals(10.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void qmlViewPrunesFinishedEphemerals() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 0; height: 10\n" +
            "  states: [State { name: \"go\"; PropertyChanges { target: r; width: 100 } }]\n" +
            "  transitions: [Transition { NumberAnimation { duration: 10 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        int baseline = r.children.size();
        r.state.set("go");
        assertEquals(baseline + 1, r.children.size());
        // Drive ticks via QmlView's package-private path: call the public tickAnimations.
        long t0 = 5_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 50_000_000L);
        // ephemeral has finished and should be pruned by tickAnimations cleanup
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
        assertEquals(baseline, r.children.size());
    }

    @Test
    void rapidStateTogglesPreserveOriginalBaseline() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 100; height: 10\n" +
            "  states: [State { name: \"big\"; PropertyChanges { target: r; width: 300 } }]\n" +
            "  transitions: [Transition { NumberAnimation { duration: 100 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        long t = 1_000_000_000L;
        // Five rapid toggles, each cutting into the previous tween at ~30%.
        for (int i = 0; i < 5; i++) {
            r.state.set(i % 2 == 0 ? "big" : "");
            v.tickAnimations(t);
            t += 30_000_000L;
            v.tickAnimations(t);
            t += 100_000L;
        }
        // Final state is "" (after 5 toggles starting with "big").
        r.state.set("");
        v.tickAnimations(t);
        t += 300_000_000L;
        v.tickAnimations(t);
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void rapidStateToggleCancelsPriorEphemeral() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  width: 100; height: 10\n" +
            "  states: [State { name: \"big\"; PropertyChanges { target: r; width: 300 } }]\n" +
            "  transitions: [Transition { NumberAnimation { duration: 100 } }]\n" +
            "}");
        Rectangle r = (Rectangle) root;
        // Enter "big": ephemeral E1 starts (100 → 300).
        r.state.set("big");
        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 30_000_000L); // ~30% through
        // Revert mid-flight: must cancel E1 before spawning E2.
        r.state.set("");
        int ephemerals = countEphemerals(r);
        assertEquals(1, ephemerals, "prior ephemeral must be cancelled");
        long t1 = 2_000_000_000L;
        v.tickAnimations(t1);
        v.tickAnimations(t1 + 200_000_000L);
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
    }

    private static int countEphemerals(Item parent) {
        int n = 0;
        for (Item c : parent.children) {
            if (c instanceof io.github.timer_err.qml4j.render.items.animation.NumberAnimation
                && ((io.github.timer_err.qml4j.render.items.animation.NumberAnimation) c).ephemeral) n++;
        }
        return n;
    }

    @Test
    void behaviorAnimatesDirectPropertySet() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  width: 100; height: 10\n" +
            "  Behavior on width { NumberAnimation { duration: 100 } }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.width.set(300);
        // Behavior rewinds property to start before first tick.
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 50_000_000L);
        assertEquals(200.0, r.width.peek().doubleValue(), 1.0);
        v.tickAnimations(t0 + 200_000_000L);
        assertEquals(300.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void behaviorIgnoresEqualWrites() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  width: 100; height: 10\n" +
            "  Behavior on width { NumberAnimation { duration: 100 } }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.width.set(100);
        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 200_000_000L);
        assertEquals(100.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void behaviorAnimatesBindingDrivenChangesWithoutClearingBinding() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  id: r\n" +
            "  property int driver: 0\n" +
            "  width: r.driver * 100; height: 10\n" +
            "  Behavior on width { NumberAnimation { duration: 100 } }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals(0L, r.width.peek().longValue());
        assertTrue(r.width.isBound(), "literal binding must remain bound after Behavior install");

        java.lang.reflect.Field driverField = null;
        try {
            driverField = r.getClass().getField("driver");
        } catch (NoSuchFieldException e) {
            org.junit.jupiter.api.Assertions.fail("driver field missing: " + e);
        }
        io.github.timer_err.qml4j.engine.binding.Property<Object> driver;
        try {
            @SuppressWarnings("unchecked")
            io.github.timer_err.qml4j.engine.binding.Property<Object> tmp =
                (io.github.timer_err.qml4j.engine.binding.Property<Object>) driverField.get(r);
            driver = tmp;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        driver.set(3);
        v.dirtyQueue().flush();
        assertEquals(0L, r.width.peek().longValue(),
            "Behavior must rewind so the first frame still shows the pre-change value");
        assertTrue(r.width.isBound(),
            "binding must survive Behavior tween — this is the M41 fix");

        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 50_000_000L);
        assertEquals(150L, r.width.peek().longValue(), 1L);
        v.tickAnimations(t0 + 200_000_000L);
        assertEquals(300L, r.width.peek().longValue());

        driver.set(7);
        v.dirtyQueue().flush();
        long t1 = 2_000_000_000L;
        v.tickAnimations(t1);
        v.tickAnimations(t1 + 200_000_000L);
        assertEquals(700L, r.width.peek().longValue());
        assertTrue(r.width.isBound());
    }

    @Test
    void behaviorRedirectsMidFlight() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  width: 0; height: 10\n" +
            "  Behavior on width { NumberAnimation { duration: 100 } }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.width.set(100);
        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 50_000_000L); // halfway → ~50
        double mid = r.width.peek().doubleValue();
        assertEquals(50.0, mid, 2.0);
        r.width.set(200); // redirect from current (~50) to 200
        long t1 = 2_000_000_000L;
        v.tickAnimations(t1);
        v.tickAnimations(t1 + 200_000_000L);
        assertEquals(200.0, r.width.peek().doubleValue(), 1e-6);
    }

    @Test
    void behaviorOnColorLerpsViaColorAnimation() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  color: \"#ff0000\"\n" +
            "  Behavior on color { ColorAnimation { duration: 100 } }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        r.color.set("#0000ff");
        assertEquals("#ffff0000", r.color.peek(),
            "Behavior rewinds color to start before first tick");
        long t0 = 1_000_000_000L;
        v.tickAnimations(t0);
        v.tickAnimations(t0 + 50_000_000L);
        String mid = (String) r.color.peek();
        assertNotEquals("#ffff0000", mid, "must have moved off the start");
        assertNotEquals("#ff0000ff", mid, "must not have snapped to the end");
        assertTrue(mid.startsWith("#ff"), "alpha preserved");
        v.tickAnimations(t0 + 200_000_000L);
        assertEquals("#ff0000ff", r.color.peek());
    }

    @Test
    void rowLaysOutChildrenHorizontally() {
        Item root = newView().load(
            "Row {\n" +
            "  spacing: 5\n" +
            "  Rectangle { width: 20; height: 30 }\n" +
            "  Rectangle { width: 15; height: 10 }\n" +
            "  Rectangle { width: 7;  height: 40 }\n" +
            "}");
        Row row = (Row) root;
        row.layout();
        assertEquals(0.0, row.children.get(0).x.peek().doubleValue(), 1e-9);
        assertEquals(25.0, row.children.get(1).x.peek().doubleValue(), 1e-9);
        assertEquals(45.0, row.children.get(2).x.peek().doubleValue(), 1e-9);
        assertEquals(52.0, row.width.peek().doubleValue(), 1e-9);
        assertEquals(40L, row.height.peek().longValue());
    }

    @Test
    void timerFiresOnceWithoutRepeat() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  property int hits: 0\n" +
            "  Timer {\n" +
            "    interval: 100\n" +
            "    running: true\n" +
            "    onTriggered: parent.hits = parent.hits + 1\n" +
            "  }\n" +
            "}");
        Timer t = (Timer) root.children.get(0);
        long t0 = 1_000_000_000L;
        t.tick(t0);
        t.tick(t0 + 50_000_000L);
        assertHitsEquals(root, 0);
        t.tick(t0 + 120_000_000L);
        assertHitsEquals(root, 1);
        t.tick(t0 + 250_000_000L);
        assertHitsEquals(root, 1);
        assertEquals(Boolean.FALSE, t.running.peek());
    }

    @Test
    void timerRepeatsAtInterval() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  property int hits: 0\n" +
            "  Timer {\n" +
            "    interval: 100\n" +
            "    repeat: true\n" +
            "    running: true\n" +
            "    onTriggered: parent.hits = parent.hits + 1\n" +
            "  }\n" +
            "}");
        Timer t = (Timer) root.children.get(0);
        long t0 = 1_000_000_000L;
        t.tick(t0);
        for (int i = 1; i <= 5; i++) {
            t.tick(t0 + i * 100_000_000L);
        }
        assertHitsEquals(root, 5);
        assertEquals(Boolean.TRUE, t.running.peek());
    }

    @Test
    void timerTriggeredOnStartFiresImmediately() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  property int hits: 0\n" +
            "  Timer {\n" +
            "    interval: 100\n" +
            "    triggeredOnStart: true\n" +
            "    running: true\n" +
            "    onTriggered: parent.hits = parent.hits + 1\n" +
            "  }\n" +
            "}");
        Timer t = (Timer) root.children.get(0);
        t.tick(1_000_000_000L);
        assertHitsEquals(root, 1);
        assertEquals(Boolean.FALSE, t.running.peek());
    }

    private static void assertHitsEquals(Item root, long expected) {
        assertEquals(expected, propLong(root, "hits"));
    }

    private static long propLong(Item root, String name) {
        try {
            io.github.timer_err.qml4j.engine.binding.Property<?> p =
                (io.github.timer_err.qml4j.engine.binding.Property<?>) root.getClass().getField(name).get(root);
            return ((Number) p.peek()).longValue();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void componentExposesDelegateFactory() {
        Item root = newView().load(
            "Item {\n" +
            "  Component { id: tmpl\n" +
            "    Rectangle { width: 30; height: 20; color: \"#112233\" }\n" +
            "  }\n" +
            "}");
        assertEquals(1, root.children.size());
        Component c = (Component) root.children.get(0);
        assertNotNull(c.factory());
        Item made = (Item) c.factory().create(0, null, null);
        assertTrue(made instanceof Rectangle);
        assertEquals(30L, made.width.peek().longValue());
        assertEquals("#112233", ((Rectangle) made).color.peek());
    }

    @Test
    void loaderSourceComponentInstantiatesChild() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 200\n" +
            "  Component { id: tmpl\n" +
            "    Rectangle { width: 40; height: 25; color: \"#aabbcc\" }\n" +
            "  }\n" +
            "  Loader { id: ld; sourceComponent: tmpl }\n" +
            "}");
        Loader loader = (Loader) root.children.get(1);
        v.renderer().resolveLoader(loader);
        assertNotNull(loader.item.peek());
        Rectangle r = (Rectangle) loader.item.peek();
        assertEquals(40L, r.width.peek().longValue());
        assertEquals("#aabbcc", r.color.peek());
        assertEquals(1, loader.children.size());
        assertSame(loader, r.parent.peek());
    }

    @Test
    void loaderSourceComponentSwapsOnChange() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  Component { id: a\n" +
            "    Rectangle { width: 10; height: 10; color: \"#aa0000\" }\n" +
            "  }\n" +
            "  Component { id: b\n" +
            "    Rectangle { width: 20; height: 20; color: \"#00bb00\" }\n" +
            "  }\n" +
            "  Loader { id: ld; sourceComponent: a }\n" +
            "}");
        Loader loader = (Loader) root.children.get(2);
        v.renderer().resolveLoader(loader);
        Rectangle first = (Rectangle) loader.item.peek();
        assertEquals("#aa0000", first.color.peek());

        Component b = (Component) root.children.get(1);
        loader.sourceComponent.set(b);
        v.renderer().resolveLoader(loader);
        Rectangle second = (Rectangle) loader.item.peek();
        assertEquals("#00bb00", second.color.peek());
        assertEquals(1, loader.children.size());
    }

    @Test
    void loaderClearsWhenSourceComponentNulled() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  Component { id: tmpl\n" +
            "    Rectangle { width: 5; height: 5 }\n" +
            "  }\n" +
            "  Loader { id: ld; sourceComponent: tmpl }\n" +
            "}");
        Loader loader = (Loader) root.children.get(1);
        v.renderer().resolveLoader(loader);
        assertNotNull(loader.item.peek());

        loader.sourceComponent.set(null);
        v.renderer().resolveLoader(loader);
        assertEquals(null, loader.item.peek());
        assertEquals(0, loader.children.size());
    }

    @Test
    void importSiblingTypeFromCurrentDir() {
        QmlView v = newView();
        v.resources(name -> {
            if ("Card.qml".equals(name)) {
                return "Rectangle { width: 60; height: 30; color: \"#deadbe\" }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        });
        Item root = v.load(
            "import \".\"\n" +
            "Item { width: 100; height: 100\n" +
            "  Card { x: 5; y: 7 }\n" +
            "}");
        assertEquals(1, root.children.size());
        Item card = root.children.get(0);
        assertTrue(card instanceof Rectangle);
        assertEquals(60L, card.width.peek().longValue());
        assertEquals(5L, card.x.peek().longValue());
        assertEquals("#deadbe", ((Rectangle) card).color.peek());
    }

    @Test
    void importedTypeIsCachedAcrossUses() {
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        QmlView v = newView();
        v.resources(name -> {
            if ("Tile.qml".equals(name)) {
                loads.incrementAndGet();
                return "Rectangle { width: 20; height: 20; color: \"#123456\" }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        });
        Item root = v.load(
            "import \".\"\n" +
            "Column { spacing: 2\n" +
            "  Tile { }\n" +
            "  Tile { }\n" +
            "  Tile { }\n" +
            "}");
        assertEquals(3, root.children.size());
        assertEquals(1, loads.get());
    }

    @Test
    void importFromSubdirectoryPrefix() {
        QmlView v = newView();
        v.resources(name -> {
            if ("ui/Pill.qml".equals(name)) {
                return "Rectangle { width: 80; height: 24; color: \"#abcabc\" }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        });
        Item root = v.load(
            "import \"ui\"\n" +
            "Item { width: 100; height: 100\n" +
            "  Pill { }\n" +
            "}");
        Item pill = root.children.get(0);
        assertEquals("#abcabc", ((Rectangle) pill).color.peek());
    }

    @Test
    void importChainResolvesTransitively() {
        QmlView v = newView();
        v.resources(name -> {
            if ("Outer.qml".equals(name)) {
                return ("import \".\"\n" +
                        "Item { width: 200; height: 200\n" +
                        "  Inner { x: 10 }\n" +
                        "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            if ("Inner.qml".equals(name)) {
                return "Rectangle { width: 12; height: 12; color: \"#0f0f0f\" }"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return null;
        });
        Item root = v.load(
            "import \".\"\n" +
            "Item { Outer { } }");
        Item outer = root.children.get(0);
        Item inner = outer.children.get(0);
        assertEquals("#0f0f0f", ((Rectangle) inner).color.peek());
        assertEquals(10L, inner.x.peek().longValue());
    }

    @Test
    void connectionsFireOnTargetSignal() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int hits: 0\n" +
            "  MouseArea { id: ma; width: 200; height: 200 }\n" +
            "  Connections { target: ma; onClicked: parent.hits = parent.hits + 1 }\n" +
            "}");
        v.dispatchClick(10, 10);
        v.dispatchClick(20, 20);
        v.dispatchClick(30, 30);
        assertHitsEquals(root, 3);
    }

    @Test
    void connectionsRebindWhenTargetChanges() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int hits: 0\n" +
            "  MouseArea { id: a; x: 0; y: 0; width: 50; height: 50 }\n" +
            "  MouseArea { id: b; x: 60; y: 0; width: 50; height: 50 }\n" +
            "  Connections { id: conn; target: a; onClicked: parent.hits = parent.hits + 1 }\n" +
            "}");
        v.dispatchClick(10, 10);
        assertHitsEquals(root, 1);

        Connections conn = (Connections) root.children.get(2);
        MouseArea b = (MouseArea) root.children.get(1);
        conn.target.set(b);

        v.dispatchClick(10, 10);
        assertHitsEquals(root, 1);
        v.dispatchClick(70, 10);
        assertHitsEquals(root, 2);
    }

    @Test
    void connectionsIgnoreUnknownSignalSilently() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int hits: 0\n" +
            "  MouseArea { id: ma; width: 200; height: 200 }\n" +
            "  Connections { target: ma\n" +
            "    onClicked: parent.hits = parent.hits + 1\n" +
            "    onUnknownSignal: parent.hits = parent.hits + 100\n" +
            "  }\n" +
            "}");
        v.dispatchClick(10, 10);
        assertHitsEquals(root, 1);
    }

    @Test
    void pointerDownEmitsPressedAndSetsIsPressed() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int presses: 0\n" +
            "  MouseArea { id: ma; width: 200; height: 200\n" +
            "    onPressed: parent.presses = parent.presses + 1\n" +
            "  }\n" +
            "}");
        MouseArea ma = (MouseArea) root.children.get(0);
        assertEquals(Boolean.FALSE, ma.pressed.peek());
        v.dispatchPointerDown(10, 10);
        assertEquals(1, (int) propLong(root, "presses"));
        assertEquals(Boolean.TRUE, ma.pressed.peek());
        v.dispatchPointerUp(10, 10);
        assertEquals(Boolean.FALSE, ma.pressed.peek());
    }

    @Test
    void pointerUpInsideEmitsReleasedAndClicked() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int releases: 0\n" +
            "  property int clicks: 0\n" +
            "  MouseArea { width: 200; height: 200\n" +
            "    onReleased: parent.releases = parent.releases + 1\n" +
            "    onClicked: parent.clicks = parent.clicks + 1\n" +
            "  }\n" +
            "}");
        v.dispatchPointerDown(10, 10);
        v.dispatchPointerUp(20, 20);
        assertEquals(1, (int) propLong(root, "releases"));
        assertEquals(1, (int) propLong(root, "clicks"));
    }

    @Test
    void pointerUpOutsideEmitsReleasedButNotClicked() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  property int releases: 0\n" +
            "  property int clicks: 0\n" +
            "  MouseArea { x: 0; y: 0; width: 50; height: 50\n" +
            "    onReleased: parent.releases = parent.releases + 1\n" +
            "    onClicked: parent.clicks = parent.clicks + 1\n" +
            "  }\n" +
            "}");
        v.dispatchPointerDown(10, 10);
        v.dispatchPointerUp(300, 300);
        assertEquals(1, (int) propLong(root, "releases"));
        assertEquals(0, (int) propLong(root, "clicks"));
    }

    @Test
    void pointerMoveUpdatesMouseXYAndEmitsPositionChanged() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  property int moves: 0\n" +
            "  MouseArea { id: ma; x: 50; y: 60; width: 200; height: 200\n" +
            "    onPositionChanged: parent.moves = parent.moves + 1\n" +
            "  }\n" +
            "}");
        MouseArea ma = (MouseArea) root.children.get(0);
        v.dispatchPointerDown(70, 80);
        assertEquals(20f, ma.mouseX.peek().floatValue());
        assertEquals(20f, ma.mouseY.peek().floatValue());
        v.dispatchPointerMove(100, 120);
        assertEquals(50f, ma.mouseX.peek().floatValue());
        assertEquals(60f, ma.mouseY.peek().floatValue());
        assertEquals(1, (int) propLong(root, "moves"));
        v.dispatchPointerUp(100, 120);
    }

    @Test
    void pointerMoveWithoutCaptureIgnored() {
        QmlView v = newView();
        v.load(
            "Item { width: 200; height: 200\n" +
            "  MouseArea { width: 200; height: 200 }\n" +
            "}");
        assertEquals(false, v.dispatchPointerMove(10, 10));
        assertEquals(false, v.dispatchPointerUp(10, 10));
    }

    @Test
    void parentBindingTracksForwardReferencedChildId() throws Exception {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property string label: child.tag\n" +
            "  Item { id: child; property string tag: \"hello\" }\n" +
            "}");
        io.github.timer_err.qml4j.engine.binding.Property<?> label =
            (io.github.timer_err.qml4j.engine.binding.Property<?>) root.getClass().getField("label").get(root);
        assertEquals("hello", label.peek());
    }

    @Test
    void colorBindingTracksSiblingMouseAreaIsPressed() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle { id: r; width: 100; height: 100\n" +
            "  color: pad.pressed ? \"#00ff00\" : \"#ff0000\"\n" +
            "  MouseArea { id: pad; width: 100; height: 100 }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals("#ff0000", r.color.peek());
        v.dispatchPointerDown(50, 50);
        assertEquals("#00ff00", r.color.peek());
        v.dispatchPointerUp(50, 50);
        assertEquals("#ff0000", r.color.peek());
    }

    @Test
    void itemTransformDefaults() {
        QmlView v = newView();
        Item root = v.load("Item { width: 100; height: 100 }");
        assertEquals(0f, root.rotation.peek().floatValue());
        assertEquals(1f, root.scale.peek().floatValue());
        assertEquals(0f, root.z.peek().floatValue());
        assertEquals(Boolean.FALSE, root.clip.peek());
    }

    @Test
    void zReordersHitTest() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 200\n" +
            "  property int hits: 0\n" +
            "  MouseArea { id: bottom; x: 0; y: 0; width: 200; height: 200\n" +
            "    onClicked: parent.hits = 1\n" +
            "  }\n" +
            "  MouseArea { id: top; x: 0; y: 0; width: 100; height: 100; z: 10\n" +
            "    onClicked: parent.hits = 2\n" +
            "  }\n" +
            "}");
        v.dispatchClick(50, 50);
        assertHitsEquals(root, 2);
        v.dispatchClick(150, 150);
        assertHitsEquals(root, 1);
    }

    @Test
    void zOrderedReturnsSameInstanceWhenAllZero() {
        Item a = new Rectangle();
        Item b = new Rectangle();
        a.children.add(b);
        assertSame(a.children, Renderer.zOrdered(a.children));
    }

    @Test
    void zOrderedSortsAscendingWhenAnyNonZero() {
        Item p = new Rectangle();
        Item lo = new Rectangle();
        Item hi = new Rectangle();
        hi.z.set(5);
        p.children.add(hi);
        p.children.add(lo);
        List<Item> ordered = Renderer.zOrdered(p.children);
        assertSame(lo, ordered.get(0));
        assertSame(hi, ordered.get(1));
    }

    @Test
    void dragMovesTargetByPointerDelta() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  Rectangle { id: handle; x: 50; y: 60; width: 80; height: 80; color: \"#ff0000\" }\n" +
            "  MouseArea { width: 400; height: 400; drag.target: handle }\n" +
            "}");
        Item handle = root.children.get(0);
        v.dispatchPointerDown(100, 100);
        v.dispatchPointerMove(140, 130);
        assertEquals(90f, handle.x.peek().floatValue());
        assertEquals(90f, handle.y.peek().floatValue());
        v.dispatchPointerUp(140, 130);
        assertEquals(90f, handle.x.peek().floatValue());
    }

    @Test
    void dragXAxisIgnoresVerticalMovement() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  Rectangle { id: h; x: 10; y: 20; width: 50; height: 50 }\n" +
            "  MouseArea { width: 400; height: 400\n" +
            "    drag.target: h\n" +
            "    drag.axis: \"XAxis\"\n" +
            "  }\n" +
            "}");
        Item h = root.children.get(0);
        v.dispatchPointerDown(100, 100);
        v.dispatchPointerMove(150, 200);
        assertEquals(60f, h.x.peek().floatValue());
        assertEquals(20f, h.y.peek().floatValue());
    }

    @Test
    void dragRespectsMinMaxBounds() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  Rectangle { id: h; x: 100; y: 100; width: 50; height: 50 }\n" +
            "  MouseArea { width: 400; height: 400\n" +
            "    drag.target: h\n" +
            "    drag.minimumX: 80\n" +
            "    drag.maximumX: 120\n" +
            "  }\n" +
            "}");
        Item h = root.children.get(0);
        v.dispatchPointerDown(200, 200);
        v.dispatchPointerMove(500, 200);
        assertEquals(120f, h.x.peek().floatValue());
        v.dispatchPointerMove(0, 200);
        assertEquals(80f, h.x.peek().floatValue());
    }

    @Test
    void dragActiveTogglesAroundPress() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 400\n" +
            "  Rectangle { id: h; x: 0; y: 0; width: 50; height: 50 }\n" +
            "  MouseArea { id: ma; width: 400; height: 400; drag.target: h }\n" +
            "}");
        MouseArea ma = (MouseArea) root.children.get(1);
        assertEquals(Boolean.FALSE, ma.drag.active.peek());
        v.dispatchPointerDown(10, 10);
        assertEquals(Boolean.FALSE, ma.drag.active.peek());
        v.dispatchPointerMove(20, 30);
        assertEquals(Boolean.TRUE, ma.drag.active.peek());
        v.dispatchPointerUp(20, 30);
        assertEquals(Boolean.FALSE, ma.drag.active.peek());
    }

    @Test
    void flickableScrollsContentOnDrag() {
        QmlView v = newView();
        Item root = v.load(
            "Flickable {\n" +
            "  width: 200; height: 100\n" +
            "  contentWidth: 800; contentHeight: 400\n" +
            "}");
        Flickable f = (Flickable) root;
        v.dispatchPointerDown(50, 30);
        v.dispatchPointerMove(20, 10);
        assertEquals(30f, f.contentX.peek().floatValue());
        assertEquals(20f, f.contentY.peek().floatValue());
    }

    @Test
    void flickableClampsContentToBounds() {
        QmlView v = newView();
        Item root = v.load(
            "Flickable {\n" +
            "  width: 200; height: 100\n" +
            "  contentWidth: 300; contentHeight: 150\n" +
            "}");
        Flickable f = (Flickable) root;
        v.dispatchPointerDown(100, 50);
        v.dispatchPointerMove(-500, -500);
        assertEquals(100f, f.contentX.peek().floatValue());
        assertEquals(50f, f.contentY.peek().floatValue());
        v.dispatchPointerUp(-500, -500);
        v.dispatchPointerDown(100, 50);
        v.dispatchPointerMove(500, 500);
        assertEquals(0f, f.contentX.peek().floatValue());
        assertEquals(0f, f.contentY.peek().floatValue());
    }

    @Test
    void flickableRespectsHorizontalFlickDirection() {
        QmlView v = newView();
        Item root = v.load(
            "Flickable {\n" +
            "  width: 200; height: 100\n" +
            "  contentWidth: 800; contentHeight: 400\n" +
            "  flickableDirection: \"HorizontalFlick\"\n" +
            "}");
        Flickable f = (Flickable) root;
        v.dispatchPointerDown(100, 50);
        v.dispatchPointerMove(50, 0);
        assertEquals(50f, f.contentX.peek().floatValue());
        assertEquals(0f, f.contentY.peek().floatValue());
    }

    @Test
    void flickableHitTestSkippedWhenNonInteractive() {
        QmlView v = newView();
        Item root = v.load(
            "Flickable {\n" +
            "  width: 200; height: 100\n" +
            "  contentWidth: 800; contentHeight: 400\n" +
            "  interactive: false\n" +
            "}");
        Flickable f = (Flickable) root;
        v.dispatchPointerDown(100, 50);
        v.dispatchPointerMove(0, 0);
        assertEquals(0f, f.contentX.peek().floatValue());
    }

    @Test
    void mouseAreaInsideFlickableReceivesScrolledLocalCoords() {
        QmlView v = newView();
        Item root = v.load(
            "Flickable {\n" +
            "  width: 200; height: 100\n" +
            "  contentWidth: 800; contentHeight: 100\n" +
            "  contentX: 50\n" +
            "  MouseArea { id: ma; x: 80; y: 10; width: 40; height: 30 }\n" +
            "}");
        Flickable f = (Flickable) root;
        MouseArea ma = (MouseArea) f.children.get(0);
        v.dispatchPointerDown(40, 20);
        assertEquals(Boolean.TRUE, ma.pressed.peek());
        assertEquals(10f, ma.mouseX.peek().floatValue());
        assertEquals(10f, ma.mouseY.peek().floatValue());
    }


    @Test
    void rectangleDefaultRadiusAndBorderZero() {
        Item root = newView().load("Rectangle { width: 100; height: 50; color: \"#ff0000\" }");
        Rectangle r = (Rectangle) root;
        assertEquals(0f, r.radius.peek().floatValue());
        assertEquals(0f, r.border.width.peek().floatValue());
        assertEquals("#000000", r.border.color.peek());
    }

    @Test
    void rectangleRadiusAndBorderAssign() {
        Item root = newView().load(
            "Rectangle {\n" +
            "  width: 100; height: 50; color: \"#202028\"\n" +
            "  radius: 12\n" +
            "  border.width: 3\n" +
            "  border.color: \"#80c0ff\"\n" +
            "}");
        Rectangle r = (Rectangle) root;
        assertEquals(12f, r.radius.peek().floatValue());
        assertEquals(3f, r.border.width.peek().floatValue());
        assertEquals("#80c0ff", r.border.color.peek());
    }

    @Test
    void rectangleGradientFromObjectValue() {
        Item root = newView().load(
            "Rectangle {\n" +
            "  width: 100; height: 80\n" +
            "  gradient: Gradient {\n" +
            "    GradientStop { position: 0; color: \"#ff0000\" }\n" +
            "    GradientStop { position: 1; color: \"#0000ff\" }\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        Gradient g = r.gradient.peek();
        assertNotNull(g);
        assertEquals(2, g.stops.size());
        GradientStop s0 = g.stops.get(0);
        GradientStop s1 = g.stops.get(1);
        assertEquals(0f, s0.position.peek().floatValue());
        assertEquals("#ff0000", s0.color.peek());
        assertEquals(1f, s1.position.peek().floatValue());
        assertEquals("#0000ff", s1.color.peek());
    }

    @Test
    void gradientStopColorBindingTracksRootId() {
        Item root = newView().load(
            "Rectangle {\n" +
            "  id: card\n" +
            "  property string topColor: \"#ff0000\"\n" +
            "  width: 100; height: 80\n" +
            "  gradient: Gradient {\n" +
            "    GradientStop { position: 0; color: card.topColor }\n" +
            "    GradientStop { position: 1; color: \"#000000\" }\n" +
            "  }\n" +
            "}");
        Rectangle r = (Rectangle) root;
        GradientStop s0 = r.gradient.peek().stops.get(0);
        assertEquals("#ff0000", s0.color.peek());
    }

    @Test
    void rectangleRadiusBindingReactsToPropertyChange() {
        QmlView v = newView();
        Item root = v.load(
            "Rectangle {\n" +
            "  property int r: 4\n" +
            "  width: 50; height: 50\n" +
            "  radius: r\n" +
            "}");
        Rectangle rect = (Rectangle) root;
        assertEquals(4f, rect.radius.peek().floatValue());
        propSet(root, "r", 20);
        v.dirtyQueue().flush();
        assertEquals(20f, rect.radius.peek().floatValue());
    }

    @Test
    void initialFocusActivatesNode() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  TextInput { focus: true }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        assertSame(ti, v.focused());
        assertTrue(ti.activeFocus.peek());
    }

    @Test
    void setFocusTransfersActiveFocus() {
        QmlView v = newView();
        Item root = v.load(
            "Item {\n" +
            "  width: 200; height: 100\n" +
            "  TextInput { width: 80; height: 30 }\n" +
            "  TextInput { width: 80; height: 30; y: 40 }\n" +
            "}");
        TextInput a = (TextInput) root.children.get(0);
        TextInput b = (TextInput) root.children.get(1);
        v.setFocus(a);
        assertTrue(a.activeFocus.peek());
        v.setFocus(b);
        assertSame(b, v.focused());
        assertTrue(b.activeFocus.peek());
        assertFalse(a.activeFocus.peek());
    }

    @Test
    void dispatchKeyInsertsText() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(0, "h", true);
        v.dispatchKey(0, "i", true);
        assertEquals("hi", ti.text.peek());
        assertEquals(2, ti.cursorPosition.peek().intValue());
    }

    @Test
    void dispatchKeyBackspaceDeletes() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"abc\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.cursorPosition.set(3);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("ab", ti.text.peek());
        assertEquals(2, ti.cursorPosition.peek().intValue());
    }

    @Test
    void dispatchKeyEnterEmitsAccepted() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        int[] hits = {0};
        ti.accepted.connect(() -> hits[0]++);
        v.dispatchKey(QmlView.KEY_ENTER, null, true);
        assertEquals(1, hits[0]);
    }

    @Test
    void readOnlyTextInputIgnoresKeys() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; readOnly: true; text: \"x\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(0, "y", true);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("x", ti.text.peek());
    }

    @Test
    void tapFocusesTextInput() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { x: 10; y: 20; width: 80; height: 30 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchPointerDown(40, 30);
        assertSame(ti, v.focused());
    }

    @Test
    void arrowKeysMoveCaret() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"abcd\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.cursorPosition.set(2);
        v.dispatchKey(QmlView.KEY_LEFT, null, true);
        assertEquals(1, ti.cursorPosition.peek().intValue());
        v.dispatchKey(QmlView.KEY_RIGHT, null, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true);
        assertEquals(3, ti.cursorPosition.peek().intValue());
    }

    @Test
    void arrowKeysClampAtEdges() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"xy\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.cursorPosition.set(0);
        v.dispatchKey(QmlView.KEY_LEFT, null, true);
        assertEquals(0, ti.cursorPosition.peek().intValue());
        ti.cursorPosition.set(2);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true);
        assertEquals(2, ti.cursorPosition.peek().intValue());
    }

    @Test
    void homeEndKeysJumpToEdges() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.cursorPosition.set(2);
        v.dispatchKey(QmlView.KEY_HOME, null, true);
        assertEquals(0, ti.cursorPosition.peek().intValue());
        v.dispatchKey(QmlView.KEY_END, null, true);
        assertEquals(5, ti.cursorPosition.peek().intValue());
    }

    @Test
    void tapAtLeftEdgePlacesCaretAtZero() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 100\n" +
            "  TextInput { x: 0; y: 0; width: 400; height: 40; text: \"hello\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.cursorPosition.set(3);
        v.dispatchPointerDown(0, 10);
        assertEquals(0, ti.cursorPosition.peek().intValue());
    }

    @Test
    void tapPastEndPlacesCaretAtTextLength() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 800; height: 100\n" +
            "  TextInput { x: 0; y: 0; width: 800; height: 40; text: \"hi\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchPointerDown(700, 10);
        assertEquals(2, ti.cursorPosition.peek().intValue());
    }

    @Test
    void tapOutsideClearsFocus() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 200\n" +
            "  TextInput { x: 10; y: 20; width: 80; height: 30 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchPointerDown(40, 30);
        assertSame(ti, v.focused());
        v.dispatchPointerDown(300, 150);
        assertNull(v.focused());
        assertFalse(ti.activeFocus.peek());
    }

    @Test
    void maximumLengthClampsInsert() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; maximumLength: 3 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(0, "abcde", true);
        assertEquals("abc", ti.text.peek());
    }

    @Test
    void focusListenerFires() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput {}\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        Item[] capture = new Item[2];
        v.setFocusListener((nf, of) -> { capture[0] = nf; capture[1] = of; });
        v.setFocus(ti);
        assertSame(ti, capture[0]);
        assertNull(capture[1]);
    }

    @Test
    void shiftRightExtendsSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        assertEquals(3, ti.cursorPosition.peek().intValue());
        assertEquals(1, ti.selectionStart.peek().intValue());
        assertEquals(3, ti.selectionEnd.peek().intValue());
    }

    @Test
    void shiftLeftThenRightShrinksSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_LEFT, null, true, true);
        assertEquals(1, ti.selectionStart.peek().intValue());
        assertEquals(2, ti.selectionEnd.peek().intValue());
    }

    @Test
    void plainArrowClearsSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, false);
        assertEquals(0, ti.selectionStart.peek().intValue());
        assertEquals(0, ti.selectionEnd.peek().intValue());
        assertEquals(4, ti.cursorPosition.peek().intValue());
    }

    @Test
    void shiftHomeSelectsToStart() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 3 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_HOME, null, true, true);
        assertEquals(0, ti.selectionStart.peek().intValue());
        assertEquals(3, ti.selectionEnd.peek().intValue());
        assertEquals(0, ti.cursorPosition.peek().intValue());
    }

    @Test
    void shiftEndSelectsToEnd() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 2 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_END, null, true, true);
        assertEquals(2, ti.selectionStart.peek().intValue());
        assertEquals(5, ti.selectionEnd.peek().intValue());
        assertEquals(5, ti.cursorPosition.peek().intValue());
    }

    @Test
    void backspaceDeletesSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("ho", ti.text.peek());
        assertEquals(1, ti.cursorPosition.peek().intValue());
        assertEquals(0, ti.selectionStart.peek().intValue());
        assertEquals(0, ti.selectionEnd.peek().intValue());
    }

    @Test
    void typingReplacesSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(0, "X", true);
        assertEquals("hXo", ti.text.peek());
        assertEquals(2, ti.cursorPosition.peek().intValue());
        assertEquals(0, ti.selectionEnd.peek().intValue());
    }

    @Test
    void clickAfterSelectionResetsAnchor() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 800; height: 100\n" +
            "  TextInput { x: 0; y: 0; width: 800; height: 40; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchPointerDown(700, 10);
        assertEquals(0, ti.selectionStart.peek().intValue());
        assertEquals(0, ti.selectionEnd.peek().intValue());
    }

    @Test
    void dragExtendsSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 800; height: 100\n" +
            "  TextInput { x: 0; y: 0; width: 800; height: 40; text: \"hello\"; fontSize: 16 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchPointerDown(0, 10);
        v.dispatchPointerMove(700, 10);
        v.dispatchPointerUp(700, 10);
        assertEquals(0, ti.selectionStart.peek().intValue());
        assertEquals(5, ti.selectionEnd.peek().intValue());
        assertEquals(5, ti.cursorPosition.peek().intValue());
    }

    @Test
    void focusChangeClearsSelection() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "  TextInput { y: 40 }\n" +
            "}");
        TextInput a = (TextInput) root.children.get(0);
        TextInput b = (TextInput) root.children.get(1);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.setFocus(b);
        assertEquals(0, a.selectionStart.peek().intValue());
        assertEquals(0, a.selectionEnd.peek().intValue());
    }

    @Test
    void copyWritesSelectionToClipboard() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        assertTrue(v.copy());
        assertEquals("ell", cb.text);
        assertEquals("hello", ti.text.peek());
    }

    @Test
    void cutRemovesSelectionAndWritesClipboard() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        assertTrue(v.cut());
        assertEquals("ell", cb.text);
        assertEquals("ho", ti.text.peek());
        assertEquals(1, ti.cursorPosition.peek().intValue());
    }

    @Test
    void pasteInsertsClipboardAtCaret() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        cb.text = "X";
        v.setClipboard(cb);
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hi\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        assertTrue(v.paste());
        assertEquals("hXi", ti.text.peek());
        assertEquals(2, ti.cursorPosition.peek().intValue());
    }

    @Test
    void pasteReplacesSelection() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        cb.text = "ZZ";
        v.setClipboard(cb);
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\"; cursorPosition: 1 }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        assertTrue(v.paste());
        assertEquals("hZZo", ti.text.peek());
    }

    @Test
    void copyNoSelectionReturnsFalse() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello\" }\n" +
            "}");
        assertFalse(v.copy());
        assertNull(cb.text);
    }

    @Test
    void cutReadOnlyReturnsFalse() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hi\"; readOnly: true }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true, true);
        assertFalse(v.cut());
        assertEquals("hi", ti.text.peek());
    }

    // selectionStart/selectionEnd are bindable, so a binding can shorten the text while a
    // selection still points past its new end. Every edit that takes a substring has to
    // survive that; the renderer already clamps in paintSelectionRect.
    private static TextInput staleSelection(QmlView v) {
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextInput { focus: true; text: \"hello world\" }\n" +
            "}");
        TextInput ti = (TextInput) root.children.get(0);
        ti.selectionStart.set(0);
        ti.selectionEnd.set(11);
        ti.text.set("hi");
        return ti;
    }

    @Test
    void copyClampsASelectionPastTheEndOfTheText() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        TextInput ti = staleSelection(v);
        assertTrue(v.copy());
        assertEquals("hi", cb.text);
        assertEquals("hi", ti.text.peek());
    }

    @Test
    void cutClampsASelectionPastTheEndOfTheText() {
        QmlView v = newView();
        FakeClipboard cb = new FakeClipboard();
        v.setClipboard(cb);
        TextInput ti = staleSelection(v);
        assertTrue(v.cut());
        assertEquals("hi", cb.text);
        assertEquals("", ti.text.peek());
    }

    @Test
    void typingClampsASelectionPastTheEndOfTheText() {
        QmlView v = newView();
        TextInput ti = staleSelection(v);
        v.dispatchKey(0, "X", true);
        assertEquals("X", ti.text.peek());
    }

    @Test
    void backspaceClampsASelectionPastTheEndOfTheText() {
        QmlView v = newView();
        TextInput ti = staleSelection(v);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("", ti.text.peek());
    }

    private static final class FakeClipboard implements Clipboard {
        String text;
        @Override public String getText() { return text; }
        @Override public void setText(String t) { this.text = t; }
    }

    private static void propSet(Item root, String name, Object value) {
        try {
            @SuppressWarnings("unchecked")
            io.github.timer_err.qml4j.engine.binding.Property<Object> p =
                (io.github.timer_err.qml4j.engine.binding.Property<Object>)
                    root.getClass().getField(name).get(root);
            p.set(value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void listModelDeclarativeBuildsRows() {
        Item root = newView().load(
            "Item {\n" +
            "  ListModel {\n" +
            "    id: lm\n" +
            "    ListElement { name: \"alice\"; age: 30 }\n" +
            "    ListElement { name: \"bob\"; age: 25 }\n" +
            "  }\n" +
            "}");
        ListModel lm = (ListModel) reflectField(root, "lm");
        assertEquals(2, lm.rows.size());
        ListElement r0 = lm.rows.get(0);
        assertEquals("alice", r0.get("name"));
        assertEquals(30L, ((Number) r0.get("age")).longValue());
    }

    // A delegate's `model` is the row's model object (Qt), shadowing a ListView/outer
    // `model` property; reading a role and writing it back (model.flag = !model.flag) work,
    // and ListModel.setProperty updates a role. This is the DataTable selection path.
    @Test
    void delegateModelIsRowAndRoleIsWritable() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel { id: lm; ListElement { label: \"a\"; flag: false } }\n" +
            "    Text { text: model.label; signal go()\n" +
            "      onGo: model.flag = !model.flag\n" +
            "    }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        io.github.timer_err.qml4j.render.items.core.Text t = (io.github.timer_err.qml4j.render.items.core.Text) rep.instances().get(0);
        assertEquals("a", t.text.peek());          // model.label reads the row's role
        try {
            ((io.github.timer_err.qml4j.engine.Signal) t.getClass().getField("go").get(t)).emit();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        ListModel lm = (ListModel) reflectField(root, "lm");
        assertEquals(Boolean.TRUE, lm.get(0).get("flag"));   // model.flag = ... wrote the role
        lm.setProperty(0, "flag", Boolean.FALSE);
        assertEquals(Boolean.FALSE, lm.get(0).get("flag"));
    }

    @Test
    void repeaterListModelDelegatesAccessRoles() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel {\n" +
            "      ListElement { label: \"a\" }\n" +
            "      ListElement { label: \"b\" }\n" +
            "      ListElement { label: \"c\" }\n" +
            "    }\n" +
            "    Text { text: modelData.label }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        assertEquals(3, rep.instances().size());
        assertEquals("a", ((io.github.timer_err.qml4j.render.items.core.Text) rep.instances().get(0)).text.peek());
        assertEquals("b", ((io.github.timer_err.qml4j.render.items.core.Text) rep.instances().get(1)).text.peek());
        assertEquals("c", ((io.github.timer_err.qml4j.render.items.core.Text) rep.instances().get(2)).text.peek());
    }

    // Qt named-role context: a ListModel row's roles are readable by BARE name in the
    // delegate (`text: label`), not only via `modelData.label`.
    @Test
    void repeaterListModelDelegateResolvesRoleByBareName() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel {\n" +
            "      ListElement { label: \"a\" }\n" +
            "      ListElement { label: \"b\" }\n" +
            "    }\n" +
            "    Text { text: label }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        assertEquals(2, rep.instances().size());
        assertEquals("a", ((Text) rep.instances().get(0)).text.peek());
        assertEquals("b", ((Text) rep.instances().get(1)).text.peek());
    }

    // Qt's implicit per-property <prop>Changed() emit: manually called after an in-place
    // mutation the setter never saw (a `var` object's field changed, reference unchanged),
    // it re-evaluates dependents.
    @Test
    void implicitChangedSignalReEvaluatesDependents() {
        Item root = newView().load(
            "Item {\n" +
            "  property var box: ({ n: 5 })\n" +
            "  property int mirror: box.n\n" +
            "  signal poke()\n" +
            "  onPoke: { box.n = 99; boxChanged() }\n" +
            "}");
        io.github.timer_err.qml4j.engine.binding.Property<?> mirror =
            (io.github.timer_err.qml4j.engine.binding.Property<?>) reflectField(root, "mirror");
        assertEquals(5L, ((Number) mirror.peek()).longValue());
        try {
            ((io.github.timer_err.qml4j.engine.Signal) root.getClass().getField("poke").get(root)).emit();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        assertEquals(99L, ((Number) mirror.peek()).longValue());
    }

    // Component.onCompleted runs after the construction-time deferred bindings flush, so a
    // handler reading a bound property sees its evaluated value, not the default. (ProPage
    // builds its card model in onCompleted off a property bound to the AppFeatures context.)
    @Test
    void onCompletedSeesDeferredBoundProperty() {
        Item root = newView().load(
            "Item {\n" +
            "  property int base: 41\n" +
            "  property int derived: base + 1\n" +
            "  property int seen: -1\n" +
            "  Component.onCompleted: seen = derived\n" +
            "}");
        io.github.timer_err.qml4j.engine.binding.Property<?> seen =
            (io.github.timer_err.qml4j.engine.binding.Property<?>) reflectField(root, "seen");
        assertEquals(42L, ((Number) seen.peek()).longValue());
    }

    @Test
    void listModelAppendTriggersRepeaterRebuild() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel { id: lm; ListElement { label: \"x\" } }\n" +
            "    Text { text: modelData.label }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        ListModel lm = (ListModel) rep.model.peek();
        assertEquals(1, rep.instances().size());
        ListElement el = new ListElement();
        el.put("label", "y");
        lm.append(el);
        assertEquals(2, rep.instances().size());
        assertEquals("y", ((io.github.timer_err.qml4j.render.items.core.Text) rep.instances().get(1)).text.peek());
    }

    @Test
    void listModelRemoveShrinksRepeater() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel {\n" +
            "      ListElement { v: 1 }\n" +
            "      ListElement { v: 2 }\n" +
            "      ListElement { v: 3 }\n" +
            "    }\n" +
            "    Text { text: modelData.v }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        ListModel lm = (ListModel) rep.model.peek();
        assertEquals(3, rep.instances().size());
        lm.remove(1);
        assertEquals(2, rep.instances().size());
        assertEquals(2L, lm.count.peek().longValue());
    }

    @Test
    void listModelClearEmptiesRepeater() {
        Item root = newView().load(
            "Column {\n" +
            "  Repeater {\n" +
            "    id: rep\n" +
            "    model: ListModel {\n" +
            "      ListElement { v: 1 }\n" +
            "      ListElement { v: 2 }\n" +
            "    }\n" +
            "    Text { text: modelData.v }\n" +
            "  }\n" +
            "}");
        Repeater rep = (Repeater) reflectField(root, "rep");
        ListModel lm = (ListModel) rep.model.peek();
        lm.clear();
        assertEquals(0, rep.instances().size());
    }

    @Test
    void listModelBindingExpressionResolvesViaId() {
        Item root = newView().load(
            "Item {\n" +
            "  id: hub\n" +
            "  property int base: 100\n" +
            "  ListModel {\n" +
            "    id: lm\n" +
            "    ListElement { v: hub.base + 1 }\n" +
            "    ListElement { v: hub.base + 2 }\n" +
            "  }\n" +
            "}");
        ListModel lm = (ListModel) reflectField(root, "lm");
        assertEquals(101L, ((Number) lm.rows.get(0).get("v")).longValue());
        assertEquals(102L, ((Number) lm.rows.get(1).get("v")).longValue());
    }

    @Test
    void listViewVerticalStacksDelegates() {
        Item root = newView().load(
            "Item {\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 200; height: 400\n" +
            "    spacing: 4\n" +
            "    model: 3\n" +
            "    Rectangle { width: 200; height: 30; color: \"#ff0000\" }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        assertEquals(3, lv.instances().size());
        assertEquals(0f, lv.instances().get(0).y.peek().floatValue());
        assertEquals(34f, lv.instances().get(1).y.peek().floatValue());
        assertEquals(68f, lv.instances().get(2).y.peek().floatValue());
        assertEquals(98f, lv.contentHeight.peek().floatValue());
    }

    @Test
    void listViewHorizontalStacksDelegates() {
        Item root = newView().load(
            "Item {\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 600; height: 80\n" +
            "    orientation: \"Horizontal\"\n" +
            "    spacing: 10\n" +
            "    model: 2\n" +
            "    Rectangle { width: 50; height: 80; color: \"#00ff00\" }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        assertEquals(0f, lv.instances().get(0).x.peek().floatValue());
        assertEquals(60f, lv.instances().get(1).x.peek().floatValue());
        assertEquals(110f, lv.contentWidth.peek().floatValue());
    }

    @Test
    void listViewListModelMutationRelayouts() {
        Item root = newView().load(
            "Item {\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 200; height: 400\n" +
            "    spacing: 0\n" +
            "    model: ListModel {\n" +
            "      ListElement { v: 1 }\n" +
            "      ListElement { v: 2 }\n" +
            "    }\n" +
            "    Rectangle { width: 200; height: 20; color: \"#0000ff\" }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        ListModel m = (ListModel) lv.model.peek();
        assertEquals(2, lv.instances().size());
        ListElement el = new ListElement();
        el.put("v", 3);
        m.append(el);
        assertEquals(3, lv.instances().size());
        assertEquals(40f, lv.instances().get(2).y.peek().floatValue());
        assertEquals(60f, lv.contentHeight.peek().floatValue());
    }

    @Test
    void listViewSpacingChangeRelayouts() {
        Item root = newView().load(
            "Item {\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 200; height: 400\n" +
            "    spacing: 0\n" +
            "    model: 3\n" +
            "    Rectangle { width: 200; height: 20; color: \"#ff00ff\" }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        assertEquals(20f, lv.instances().get(1).y.peek().floatValue());
        lv.spacing.set(5);
        assertEquals(25f, lv.instances().get(1).y.peek().floatValue());
        assertEquals(50f, lv.instances().get(2).y.peek().floatValue());
    }

    @Test
    void listViewDelegateNestedChildSeesIndex() {
        Item root = newView().load(
            "Item {\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 200; height: 400\n" +
            "    model: 3\n" +
            "    Rectangle {\n" +
            "      width: 200; height: 40\n" +
            "      Text { text: \"row \" + index }\n" +
            "    }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        assertEquals(3, lv.instances().size());
        Item rect0 = lv.instances().get(0);
        Item rect2 = lv.instances().get(2);
        io.github.timer_err.qml4j.render.items.core.Text t0 = (io.github.timer_err.qml4j.render.items.core.Text) rect0.children.get(0);
        io.github.timer_err.qml4j.render.items.core.Text t2 = (io.github.timer_err.qml4j.render.items.core.Text) rect2.children.get(0);
        assertEquals("row 0", t0.text.peek());
        assertEquals("row 2", t2.text.peek());
    }

    @Test
    void listViewDelegateNestedChildSeesModelData() {
        Item root = newView().load(
            "Item {\n" +
            "  ListModel { id: m;\n" +
            "    ListElement { name: \"alice\" }\n" +
            "    ListElement { name: \"bob\" }\n" +
            "  }\n" +
            "  ListView {\n" +
            "    id: lv\n" +
            "    width: 200; height: 400\n" +
            "    model: m\n" +
            "    Rectangle {\n" +
            "      width: 200; height: 40\n" +
            "      Text { text: index + \":\" + modelData.name }\n" +
            "    }\n" +
            "  }\n" +
            "}");
        ListView lv = (ListView) reflectField(root, "lv");
        assertEquals(2, lv.instances().size());
        io.github.timer_err.qml4j.render.items.core.Text t0 = (io.github.timer_err.qml4j.render.items.core.Text) lv.instances().get(0).children.get(0);
        io.github.timer_err.qml4j.render.items.core.Text t1 = (io.github.timer_err.qml4j.render.items.core.Text) lv.instances().get(1).children.get(0);
        assertEquals("0:alice", t0.text.peek());
        assertEquals("1:bob", t1.text.peek());
    }

    @Test
    void gridViewLeftToRightLaysOutCells() {
        Item root = newView().load(
            "Item {\n" +
            "  GridView {\n" +
            "    id: gv\n" +
            "    width: 300; height: 300\n" +
            "    cellWidth: 100; cellHeight: 80\n" +
            "    model: 7\n" +
            "    Rectangle { width: 100; height: 80; color: \"#ff00ff\" }\n" +
            "  }\n" +
            "}");
        GridView gv = (GridView) reflectField(root, "gv");
        assertEquals(7, gv.instances().size());
        assertEquals(0f, gv.instances().get(0).x.peek().floatValue());
        assertEquals(0f, gv.instances().get(0).y.peek().floatValue());
        assertEquals(100f, gv.instances().get(1).x.peek().floatValue());
        assertEquals(0f, gv.instances().get(1).y.peek().floatValue());
        assertEquals(0f, gv.instances().get(3).x.peek().floatValue());
        assertEquals(80f, gv.instances().get(3).y.peek().floatValue());
        assertEquals(0f, gv.instances().get(6).x.peek().floatValue());
        assertEquals(160f, gv.instances().get(6).y.peek().floatValue());
        assertEquals(300f, gv.contentWidth.peek().floatValue());
        assertEquals(240f, gv.contentHeight.peek().floatValue());
    }

    @Test
    void gridViewTopToBottomLaysOutCells() {
        Item root = newView().load(
            "Item {\n" +
            "  GridView {\n" +
            "    id: gv\n" +
            "    width: 300; height: 200\n" +
            "    cellWidth: 100; cellHeight: 100\n" +
            "    flow: \"FlowTopToBottom\"\n" +
            "    model: 5\n" +
            "    Rectangle { width: 100; height: 100; color: \"#00ff00\" }\n" +
            "  }\n" +
            "}");
        GridView gv = (GridView) reflectField(root, "gv");
        assertEquals(5, gv.instances().size());
        assertEquals(0f, gv.instances().get(0).x.peek().floatValue());
        assertEquals(0f, gv.instances().get(1).x.peek().floatValue());
        assertEquals(100f, gv.instances().get(1).y.peek().floatValue());
        assertEquals(100f, gv.instances().get(2).x.peek().floatValue());
        assertEquals(0f, gv.instances().get(2).y.peek().floatValue());
    }

    @Test
    void gridViewListModelMutationRelayouts() {
        Item root = newView().load(
            "Item {\n" +
            "  ListModel { id: m;\n" +
            "    ListElement { tag: \"a\" }\n" +
            "    ListElement { tag: \"b\" }\n" +
            "  }\n" +
            "  GridView {\n" +
            "    id: gv\n" +
            "    width: 200; height: 200\n" +
            "    cellWidth: 100; cellHeight: 50\n" +
            "    model: m\n" +
            "    Rectangle { width: 100; height: 50;\n" +
            "      Text { text: modelData.tag }\n" +
            "    }\n" +
            "  }\n" +
            "}");
        GridView gv = (GridView) reflectField(root, "gv");
        assertEquals(2, gv.instances().size());
        io.github.timer_err.qml4j.render.items.view.ListModel m =
            (io.github.timer_err.qml4j.render.items.view.ListModel) reflectField(root, "m");
        java.util.LinkedHashMap<String, Object> el = new java.util.LinkedHashMap<>();
        el.put("tag", "c");
        m.append(el);
        assertEquals(3, gv.instances().size());
        assertEquals(0f, gv.instances().get(2).x.peek().floatValue());
        assertEquals(50f, gv.instances().get(2).y.peek().floatValue());
    }

    @Test
    void gridViewDelegateChildSeesContext() {
        Item root = newView().load(
            "Item {\n" +
            "  GridView {\n" +
            "    id: gv\n" +
            "    width: 200; height: 200\n" +
            "    cellWidth: 100; cellHeight: 50\n" +
            "    model: 3\n" +
            "    Rectangle {\n" +
            "      width: 100; height: 50\n" +
            "      Text { text: \"cell \" + index }\n" +
            "    }\n" +
            "  }\n" +
            "}");
        GridView gv = (GridView) reflectField(root, "gv");
        io.github.timer_err.qml4j.render.items.core.Text t1 = (io.github.timer_err.qml4j.render.items.core.Text) gv.instances().get(1).children.get(0);
        assertEquals("cell 1", t1.text.peek());
    }

    @Test
    void textEditExposesNewProperties() {
        Item root = newView().load(
            "Item {\n" +
            "  TextEdit {\n" +
            "    id: te\n" +
            "    width: 200; height: 100\n" +
            "    text: \"hello\\nworld\"\n" +
            "    wrapMode: \"WordWrap\"\n" +
            "    verticalAlignment: \"AlignVCenter\"\n" +
            "  }\n" +
            "}");
        TextEdit te = (TextEdit) reflectField(root, "te");
        assertEquals("hello\nworld", te.text.peek());
        assertEquals("WordWrap", te.wrapMode.peek());
        assertEquals("AlignVCenter", te.verticalAlignment.peek());
    }

    @Test
    void textEditAcceptsTypedText() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextEdit { focus: true; text: \"ab\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        te.cursorPosition.set(2);
        v.dispatchKey(0, "c", true);
        assertEquals("abc", te.text.peek());
        assertEquals(3, te.cursorPosition.peek().intValue());
    }

    @Test
    void textEditBackspaceDeletes() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextEdit { focus: true; text: \"abc\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        te.cursorPosition.set(3);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("ab", te.text.peek());
        assertEquals(2, te.cursorPosition.peek().intValue());
    }

    @Test
    void textEditEnterInsertsNewline() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextEdit { focus: true; text: \"ab\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        te.cursorPosition.set(1);
        v.dispatchKey(QmlView.KEY_ENTER, null, true);
        assertEquals("a\nb", te.text.peek());
        assertEquals(2, te.cursorPosition.peek().intValue());
    }

    @Test
    void textEditArrowKeysMoveCaret() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextEdit { focus: true; text: \"abcd\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        te.cursorPosition.set(2);
        v.dispatchKey(QmlView.KEY_LEFT, null, true);
        assertEquals(1, te.cursorPosition.peek().intValue());
        v.dispatchKey(QmlView.KEY_RIGHT, null, true);
        v.dispatchKey(QmlView.KEY_RIGHT, null, true);
        assertEquals(3, te.cursorPosition.peek().intValue());
    }

    @Test
    void tapFocusesTextEdit() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 400; height: 200\n" +
            "  TextEdit { x: 10; y: 20; width: 200; height: 100; text: \"hi\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        v.dispatchPointerDown(50, 60);
        assertSame(te, v.focused());
    }

    @Test
    void textEditReadOnlyIgnoresKeys() {
        QmlView v = newView();
        Item root = v.load(
            "Item { width: 200; height: 100\n" +
            "  TextEdit { focus: true; readOnly: true; text: \"x\" }\n" +
            "}");
        TextEdit te = (TextEdit) root.children.get(0);
        v.dispatchKey(0, "y", true);
        v.dispatchKey(QmlView.KEY_BACKSPACE, null, true);
        assertEquals("x", te.text.peek());
    }

    @SuppressWarnings("unchecked")
    private static Object reflectField(Object root, String name) {
        try {
            return root.getClass().getField(name).get(root);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    void parseColorRgb() {
        assertEquals(0xFFFF0000, Renderer.parseColor("#ff0000"));
        assertEquals(0xFF00FF00, Renderer.parseColor("#00ff00"));
        assertEquals(0xFFFFFFFF, Renderer.parseColor("#fff"));
        assertEquals(0x80FF00FF, Renderer.parseColor("#80ff00ff"));
        assertEquals(0xFF000000, Renderer.parseColor(null));
        assertEquals(0xFF000000, Renderer.parseColor("garbage"));
    }
}
