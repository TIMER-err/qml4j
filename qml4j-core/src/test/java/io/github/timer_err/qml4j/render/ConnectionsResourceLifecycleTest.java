package io.github.timer_err.qml4j.render;

import io.github.timer_err.qml4j.render.items.core.Item;
import io.github.timer_err.qml4j.render.items.view.Connections;
import io.github.timer_err.qml4j.render.items.view.ListModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionsResourceLifecycleTest {
    @Test
    void disposingConsumerTreeDisconnectsLongLivedPublisher() {
        ListModel publisher = new ListModel();
        Item consumer = new Item();
        Connections connections = attach(consumer, publisher);
        connections.connectSignal("rowsChanged", args -> consumer.width.set(consumer.width.peekInt() + 1));
        publisher.rowsChanged.emit();
        assertEquals(1, consumer.width.peekInt());

        consumer.dispose();
        publisher.rowsChanged.emit();
        assertEquals(1, consumer.width.peekInt(), "a disposed consumer must receive no external callbacks");
    }

    @Test
    void repeatedConsumerCyclesDoNotAccumulatePublisherCallbacks() {
        ListModel publisher = new ListModel();
        AtomicInteger callbacks = new AtomicInteger();
        for (int cycle = 0; cycle < 100; cycle++) {
            Item consumer = new Item();
            Connections connections = attach(consumer, publisher);
            connections.connectSignal("rowsChanged", args -> callbacks.incrementAndGet());
            publisher.rowsChanged.emit();
            assertEquals(cycle + 1, callbacks.get());
            consumer.dispose();
            publisher.rowsChanged.emit();
            assertEquals(cycle + 1, callbacks.get());
        }
    }

    @Test
    void rebindingPreservesOnlyCurrentPublisherUntilDisposal() {
        ListModel first = new ListModel();
        ListModel second = new ListModel();
        Item consumer = new Item();
        Connections connections = attach(consumer, first);
        AtomicInteger callbacks = new AtomicInteger();
        connections.connectSignal("rowsChanged", args -> callbacks.incrementAndGet());
        connections.target.set(second);
        first.rowsChanged.emit();
        assertEquals(0, callbacks.get());
        second.rowsChanged.emit();
        assertEquals(1, callbacks.get());

        consumer.dispose();
        second.rowsChanged.emit();
        assertEquals(1, callbacks.get());
        connections.target.set(first);
        first.rowsChanged.emit();
        assertEquals(1, callbacks.get(), "late target writes cannot reconnect discarded handlers");
    }

    private static Connections attach(Item consumer, ListModel publisher) {
        Connections connections = new Connections();
        connections.parent.set(consumer);
        consumer.resources.add(connections);
        connections.target.set(publisher);
        return connections;
    }
}
