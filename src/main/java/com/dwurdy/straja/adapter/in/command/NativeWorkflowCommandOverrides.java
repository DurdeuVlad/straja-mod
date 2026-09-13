package com.dwurdy.straja.adapter.in.command;

import com.dwurdy.straja.adapter.out.minecraft.MinecraftPlayerGateway;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Overrides legacy player-facing shortcuts after the main /straja tree has
 * been registered. Brigadier merges duplicate literal nodes and replaces the
 * executable command on the matching child node.
 *
 * <p>Operators keep direct recovery/admin access; ordinary gameplay goes
 * through the physical NPCs as agreed.</p>
 */
public final class NativeWorkflowCommandOverrides {
    private NativeWorkflowCommandOverrides() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        var root = Commands.literal("straja");

        root.then(Commands.literal("start").executes(c -> secretaryOnly(c.getSource(), "start")));
        root.then(Commands.literal("stop").executes(c -> secretaryOnly(c.getSource(), "stop")));
        root.then(Commands.literal("salary").executes(c -> secretaryOnly(c.getSource(), "salary")));
        root.then(Commands.literal("recruit").executes(c -> recruiterOnly(c.getSource())));
        root.then(Commands.literal("recrute").executes(c -> recruiterOnly(c.getSource())));

        dispatcher.register(root);
    }

    private static int secretaryOnly(CommandSourceStack source, String operation) {
        var runtime = StrajaRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("Straja nu este pornită."));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Comanda necesită un jucător; folosește suprafața admin/test pentru recovery."));
            return 0;
        }
        var gateway = new MinecraftPlayerGateway(source.getServer(), player.getUUID());

        // Permission-2 is an explicit admin/recovery escape hatch; ordinary
        // members cannot bypass the physical Secretary workflow.
        if (source.hasPermission(2)) {
            switch (operation) {
                case "start" -> runtime.guards().startDuty(gateway);
                case "stop" -> runtime.guards().stopDuty(gateway);
                case "salary" -> runtime.guards().salary(gateway);
                default -> { return 0; }
            }
            return 1;
        }

        source.sendFailure(Component.literal(switch (operation) {
            case "start" -> "Tura se începe la Secretară.";
            case "stop" -> "Tura se încheie voluntar la Secretară.";
            case "salary" -> "Salariul se încasează la Secretară (Shift + click dreapta).";
            default -> "Operațiunea se face la Secretariat.";
        }));
        return 0;
    }

    private static int recruiterOnly(CommandSourceStack source) {
        var runtime = StrajaRuntime.get();
        if (runtime == null) {
            source.sendFailure(Component.literal("Straja nu este pornită."));
            return 0;
        }
        var player = source.getPlayer();
        if (player == null) return 0;
        var gateway = new MinecraftPlayerGateway(source.getServer(), player.getUUID());
        if (source.hasPermission(2)) {
            runtime.guards().recruit(gateway);
            return 1;
        }
        source.sendFailure(Component.literal(
                "Recrutarea se deschide la Recrutor. După ce vorbești cu el, răspunzi la test cu /straja quiz <răspuns>."));
        return 0;
    }
}
