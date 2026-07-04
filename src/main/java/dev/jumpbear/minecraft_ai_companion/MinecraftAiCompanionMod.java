package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class MinecraftAiCompanionMod implements ModInitializer {
    public static final String MOD_ID = "minecraft-ai-companion";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("aicompanion")
                .then(CommandManager.literal("spawn")
                        .executes(MinecraftAiCompanionMod::spawnCompanion)));
    }

    private static int spawnCompanion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity companion = FakeCompanionSpawner.spawnNear(source);
        source.sendFeedback(() -> Text.literal("Spawned AI companion prototype: AICompanion"), true);
        return 1;
    }
}
