package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import dev.jumpbear.minecraft_ai_companion.task.CompanionTaskManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;

public final class MinecraftAiCompanionMod implements ModInitializer {
    public static final String MOD_ID = "minecraft-ai-companion";

    @Override
    public void onInitialize() {
        CompanionMiningTasks.register();
        CompanionBehaviorTestTasks.register();
        CompanionCombatHooks.register();
        CompanionTaskManager.register();
        CompanionLifeSystem.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        CompanionDebugCommands.register(dispatcher);
    }
}
