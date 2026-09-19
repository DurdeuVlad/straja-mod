package com.dwurdy.straja.adapter.out.npc.customnpcs;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CustomNpcsNpcSurfaceProviderTest {
    @Test
    void listenerRegistrationUsesTheConcreteInteractionEventType() {
        FakeEventBus eventBus = new FakeEventBus();
        Class<?> eventType = FakeInteractionEvent.class;
        Consumer<Object> listener = ignored -> { };

        CustomNpcsNpcSurfaceProvider.registerInteractionListener(eventBus, eventType, listener);

        assertSame(eventType, eventBus.eventType);
        assertSame(listener, eventBus.listener);
    }

    public static final class FakeEventBus {
        private Class<?> eventType;
        private Consumer<?> listener;

        public void addListener(Consumer<?> listener) {
            throw new AssertionError("generic listener registration must not be selected");
        }

        public void addListener(Class<?> eventType, Consumer<?> listener) {
            this.eventType = eventType;
            this.listener = listener;
        }
    }

    private static final class FakeInteractionEvent {
    }
}
