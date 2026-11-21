package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.engine.Signal;
import io.github.timer_err.qml4j.engine.binding.DirtyQueue;
import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.ListModel;
import io.github.timer_err.qml4j.render.items.view.Repeater;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepeaterResourceLifecycleTest {
    @Test
    void repeatedDisposalReleasesSiblingDelegatesAndDisconnectsSharedModel() throws Exception {
        Item root = new Item();
        ListModel model = new ListModel();
        model.append(Collections.singletonMap("name", "first"));
        model.append(Collections.singletonMap("name", "second"));
        int inserted = handlerCount(model.rowsInserted);
        int removed = handlerCount(model.rowsRemoved);
        int changed = handlerCount(model.rowsChanged);
        AtomicInteger releases = new AtomicInteger();
        try {
            for (int i = 0; i < 100; i++) {
                Repeater repeater = attachRepeater(root, model, releases);
                List<Item> delegates = List.copyOf(repeater.instances());
                assertEquals(2, delegates.size());
                repeater.dispose();
                assertTrue(root.children.isEmpty());
                assertEquals(0, repeater.count());
                for (Item delegate : delegates) assertNull(delegate.parent.peek());
                assertEquals((i + 1) * 2, releases.get());
                assertEquals(inserted, handlerCount(model.rowsInserted));
                assertEquals(removed, handlerCount(model.rowsRemoved));
                assertEquals(changed, handlerCount(model.rowsChanged));
            }
        } finally {
            root.dispose();
        }
    }

    @Test
    void parentDisposalDoesNotSkipLaterChildrenOrReviveQueuedDelegates() {
        Item root = new Item();
        ListModel model = new ListModel();
        model.append(Collections.singletonMap("name", "first"));
        AtomicInteger releases = new AtomicInteger();
        Repeater repeater = attachRepeater(root, model, releases);
        Item footer = trackedItem(releases);
        footer.parent.set(root);
        root.children.add(footer);
        DirtyQueue queue = new DirtyQueue();
        queue.install();
        try {
            model.append(Collections.singletonMap("name", "second"));
            root.dispose();
            queue.flush();
            assertEquals(2, releases.get(), "delegate and following sibling both release");
            assertEquals(0, repeater.count(), "a queued rebuild cannot revive a disposed repeater");
        } finally {
            queue.uninstall();
        }
    }

    private static Repeater attachRepeater(Item root, ListModel model, AtomicInteger releases) {
        Repeater repeater = new Repeater();
        repeater.parent.set(root);
        root.children.add(repeater);
        repeater.model.set(model);
        repeater.setDelegate((index, data, parent) -> {
            Item item = trackedItem(releases);
            item.parent.set((Item) parent);
            return item;
        });
        return repeater;
    }

    private static Item trackedItem(AtomicInteger releases) {
        return new Item() {
            @Override
            protected void releaseResources() {
                releases.incrementAndGet();
            }
        };
    }

    private static int handlerCount(Signal signal) throws Exception {
        Field field = Signal.class.getDeclaredField("handlers");
        field.setAccessible(true);
        return ((List<?>) field.get(signal)).size();
    }
}
