package com.dwurdy.straja.adapter.out.delivery;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftInventoryView;
import com.dwurdy.straja.application.port.out.DeliveryProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import io.github.mortuusars.envelope.world.item.component.PackageContents;
import io.github.mortuusars.envelope.world.item.mail.Mail;
import io.github.mortuusars.envelope.world.mail.MailService;
import io.github.mortuusars.envelope.world.mail.address.type.PlayerAddress;
import io.github.mortuusars.envelope.world.mail.delivery.DeliveryDraft;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Envelope mail adapter. All Envelope API calls are confined here; failures
 * return Mode.FAILED so callers persist a retry state instead of assuming
 * delivery.
 */
public class EnvelopeDeliveryProvider implements DeliveryProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("Straja");

    private final MinecraftServer server;
    private final StrajaPolicies policies;

    public EnvelopeDeliveryProvider(MinecraftServer server, StrajaPolicies policies) {
        this.server = server;
        this.policies = policies;
    }

    @Override public boolean available() {
        try {
            ServerLevel level = server.overworld();
            return level != null && MailService.operatesIn(level);
        } catch (Throwable error) {
            return false;
        }
    }

    @Override public Outcome sendLetter(PlayerGateway sender, String recipientName, String subject, String body) {
        if (!policies.envelopeEnabled) return new Outcome(Mode.DISABLED, "envelope_disabled");
        if (!available()) return new Outcome(Mode.UNAVAILABLE, "envelope_unavailable");
        try {
            String text = body == null ? "" : body;
            if (text.length() > policies.envelopeMaxBodyLength) {
                text = text.substring(0, policies.envelopeMaxBodyLength);
            }
            ItemStack letter = Mail.createLetter(Component.literal(text))
                    .recipient(new PlayerAddress(recipientName))
                    .get();
            ServerPlayer senderEntity = senderEntity(sender);
            DeliveryDraft draft = new DeliveryDraft()
                    .to(new PlayerAddress(recipientName))
                    .deliver(letter);
            if (senderEntity != null) {
                draft.from(new PlayerAddress(senderEntity)).owner(senderEntity.getUUID());
            }
            MailService.of(server.overworld()).getDeliveryManager().startService(draft);
            return new Outcome(Mode.DELIVERED, subject == null ? "" : subject);
        } catch (Throwable error) {
            LOGGER.error("[Straja] envelope letter delivery failed: {}", error.toString());
            if (policies.envelopeFallbackToChat) {
                PlayerGateway target = findOnline(recipientName);
                if (target != null) {
                    target.tell("[Straja][Mail offline] " + (body == null ? "" : body));
                    return new Outcome(Mode.CHAT_FALLBACK, "delivered_via_chat");
                }
            }
            return Outcome.failed(String.valueOf(error));
        }
    }

    @Override public Outcome sendPackage(PlayerGateway sender, String recipientName,
                                         List<ItemSpec> contents, String label) {
        if (!policies.envelopeEnabled) return new Outcome(Mode.DISABLED, "envelope_disabled");
        if (!available()) return new Outcome(Mode.UNAVAILABLE, "envelope_unavailable");
        try {
            List<ItemStack> stacks = contents.stream().map(MinecraftInventoryView::build).toList();
            ItemStack pkg = Mail.createPackage(new PackageContents(stacks))
                    .recipient(new PlayerAddress(recipientName))
                    .get();
            ServerPlayer senderEntity = senderEntity(sender);
            DeliveryDraft draft = new DeliveryDraft()
                    .to(new PlayerAddress(recipientName))
                    .deliver(pkg);
            if (senderEntity != null) {
                draft.from(new PlayerAddress(senderEntity)).owner(senderEntity.getUUID());
            }
            MailService.of(server.overworld()).getDeliveryManager().startService(draft);
            return new Outcome(Mode.DELIVERED, label == null ? "" : label);
        } catch (Throwable error) {
            LOGGER.error("[Straja] envelope package delivery failed: {}", error.toString());
            return Outcome.failed(String.valueOf(error));
        }
    }

    private ServerPlayer senderEntity(PlayerGateway sender) {
        if (sender == null) return null;
        return server.getPlayerList().getPlayer(sender.uuid());
    }

    private PlayerGateway findOnline(String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) {
                return new com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway(server, player.getUUID());
            }
        }
        return null;
    }
}
