package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.QmlEngine;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.runtime.member.MemberAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// A Behavior's template duration/easing.type written as non-literals (an enum, another
// object's property) are deferred bindings that evaluate after Behavior.attach. The
// Behavior must read them when an animation starts, not snapshot the pre-binding
// defaults (250 ms, Linear) at attach -- and must pick up a later change of the binding.
class BehaviorBoundTimingTest {

    private static final String SRC =
        "import QtQuick\n"
        + "Item {\n"
        + "  width: 200; height: 200\n"
        + "  Item { id: cfg; objectName: \"cfg\"; property int dur: 100 }\n"
        + "  Item {\n"
        + "    objectName: \"knob\"; x: 0\n"
        + "    Behavior on x { NumberAnimation { duration: cfg.dur; easing.type: Easing.OutCubic } }\n"
        + "  }\n"
        + "}";

    private static void frame(QmlView v, long nowMs) {
        DirtyQueue dq = v.dirtyQueue();
        dq.install();
        try {
            v.tickAnimations(nowMs * 1_000_000L);
            dq.flush();
        } finally {
            dq.uninstall();
        }
    }

    @Test
    void behaviorUsesBoundDurationAndEasingAndFollowsLaterChange() {
        QmlView v = QmlView.withStockTypes(new QmlEngine());
        try {
            v.load(SRC);
            Item knob = v.findByObjectName("knob");
            frame(v, 1_000);

            knob.x.set(100.0);
            frame(v, 2_000);
            frame(v, 2_050);
            // OutCubic at half of 100 ms: 1 - 0.5^3 = 0.875 (the attach-time defaults,
            // Linear over 250 ms, would give 20).
            assertEquals(87.5, knob.x.peekDouble(), 0.01, "bound OutCubic over bound 100 ms");
            frame(v, 2_100);
            assertEquals(100.0, knob.x.peekDouble(), 0.001, "finished after bound 100 ms");

            MemberAccess.writeMember(v.findByObjectName("cfg"), "dur", 200);
            frame(v, 2_500);

            knob.x.set(0.0);
            frame(v, 3_000);
            frame(v, 3_100);
            assertEquals(12.5, knob.x.peekDouble(), 0.01, "half of the re-bound 200 ms, OutCubic");
            frame(v, 3_200);
            assertEquals(0.0, knob.x.peekDouble(), 0.001, "finished after re-bound 200 ms");
        } finally {
            v.dispose();
        }
    }
}
