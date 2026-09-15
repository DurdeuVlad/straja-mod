package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.support.Fakes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretaryServiceTest {
    @Test
    void copiesHeldBookAndLeavesOriginalMessage() {
        Fakes.TestPlayer player = new Fakes.TestPlayer("secretary-reader", 9);
        player.selectSlot(0);
        player.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:written_book", 1, 1, java.util.Map.of("title", "Ordine")));
        var original = player.inventory.stackAt(0);

        PlayerGateway.BookCopyResult result = new SecretaryService().copyHeldBook(player);

        assertEquals(PlayerGateway.BookCopyResult.COPIED, result);
        assertEquals(original, player.inventory.stackAt(0));
        assertEquals(original, player.inventory.stackAt(1));
        assertTrue(player.told("copie"));
        assertTrue(player.told("Originalul a rămas"));
    }

    @Test
    void nonBookFallsThroughWithoutSecretaryMessage() {
        Fakes.TestPlayer player = new Fakes.TestPlayer("secretary-empty", 9);

        PlayerGateway.BookCopyResult result = new SecretaryService().copyHeldBook(player);

        assertEquals(PlayerGateway.BookCopyResult.NOT_A_BOOK, result);
        assertTrue(player.messages.isEmpty());
    }

    @Test
    void fullInventoryDoesNotClaimCopySuccess() {
        Fakes.TestPlayer player = new Fakes.TestPlayer("secretary-full", 9);
        player.selectSlot(0);
        player.inventory.slots.set(0, new com.dwurdy.straja.application.port.out.ItemView(
                "minecraft:written_book", 1, 1, java.util.Map.of("title", "Ordine")));
        for (int slot = 1; slot < player.inventory.slots(); slot++) {
            player.inventory.slots.set(slot, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }

        PlayerGateway.BookCopyResult result = new SecretaryService().copyHeldBook(player);

        assertEquals(PlayerGateway.BookCopyResult.NO_SPACE, result);
        assertTrue(player.told("Nu ai loc"));
        assertTrue(player.messages.stream().noneMatch(message -> message.contains("dat o copie")));
    }
}
