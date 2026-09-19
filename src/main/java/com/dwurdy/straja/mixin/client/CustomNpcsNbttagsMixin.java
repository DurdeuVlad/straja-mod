package com.dwurdy.straja.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supplies CustomNPCs with the client registry provider while decoding a
 * player-facing custom GUI.
 *
 * <p>CustomNPCs 1.21.1.20251230 routes button item decoding through
 * {@code NBTTags.getProvider()}, which only reads its server-side static. A
 * dedicated client has no server object in that classloader, so a native
 * {@code GuiCustom} containing a button disconnects before it can render.
 * This optional client-only patch uses the already-connected client level's
 * registry access instead. It is gated by the CustomNPCs mod id in
 * {@code neoforge.mods.toml}; Straja remains loadable without CustomNPCs.
 */
@Mixin(targets = "noppes.npcs.NBTTags")
public abstract class CustomNpcsNbttagsMixin {
    @Inject(method = "getProvider", at = @At("HEAD"), cancellable = true, remap = false)
    private static void straja$useClientRegistry(
            CallbackInfoReturnable<HolderLookup.Provider> callback) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            callback.setReturnValue(minecraft.level.registryAccess());
        }
    }
}
