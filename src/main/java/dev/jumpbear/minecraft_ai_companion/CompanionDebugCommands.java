package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.Optional;

public final class CompanionDebugCommands {
    private CompanionDebugCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("aicompanion")
                .then(CommandManager.literal("spawn")
                        .executes(CompanionDebugCommands::spawn))
                .then(CommandManager.literal("status")
                        .executes(CompanionDebugCommands::status))
                .then(CommandManager.literal("move_here")
                        .executes(CompanionDebugCommands::moveHere))
                .then(CommandManager.literal("give_wood")
                        .executes(CompanionDebugCommands::giveWood))
                .then(CommandManager.literal("hurt")
                        .executes(CompanionDebugCommands::hurt))
                .then(CommandManager.literal("break_front")
                        .executes(CompanionDebugCommands::breakFront))
                .then(CommandManager.literal("swing")
                        .executes(CompanionDebugCommands::swing))
                .then(CommandManager.literal("mine_front_visual")
                        .executes(CompanionDebugCommands::mineFrontVisual))
                .then(CommandManager.literal("remove")
                        .executes(CompanionDebugCommands::remove)));
    }

    private static int spawn(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity companion = FakeCompanionSpawner.spawnNear(source);
        source.sendFeedback(() -> Text.literal("Spawned or moved companion: " + companion.getName().getString()), true);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        PlayerInventory inventory = player.getInventory();
        HungerManager hunger = player.getHungerManager();
        source.sendFeedback(() -> Text.literal("AICompanion status:"), false);
        source.sendFeedback(() -> Text.literal(String.format("health=%.1f/%.1f hunger=%d saturation=%.1f xpLevel=%d totalXp=%d",
                player.getHealth(),
                player.getMaxHealth(),
                hunger.getFoodLevel(),
                hunger.getSaturationLevel(),
                player.experienceLevel,
                player.totalExperience)), false);
        source.sendFeedback(() -> Text.literal(String.format("gameMode=%s creative=%s spectator=%s canTakeDamage=%s genericInvulnerable=%s",
                player.getGameMode(),
                player.isCreative(),
                player.isSpectator(),
                player.canTakeDamage(),
                player.isInvulnerableTo(player.getEntityWorld(), player.getEntityWorld().getDamageSources().generic()))), false);
        source.sendFeedback(() -> Text.literal(String.format("pos=%.1f %.1f %.1f world=%s",
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getEntityWorld().getRegistryKey().getValue())), false);
        source.sendFeedback(() -> Text.literal("inventory: occupiedSlots=" + countOccupiedSlots(inventory)
                + " selected=" + describeStack(inventory.getSelectedStack())
                + " offhand=" + describeStack(player.getOffHandStack())), false);
        return 1;
    }

    private static int moveHere(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        FakeCompanionSpawner.teleportNear(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Moved AICompanion near you."), true);
        return 1;
    }

    private static int giveWood(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ItemStack stack = new ItemStack(Items.OAK_LOG, 16);
        boolean inserted = companion.get().giveItemStack(stack);
        source.sendFeedback(() -> Text.literal(inserted
                ? "Gave AICompanion 16 oak logs."
                : "Could not fully insert oak logs into AICompanion inventory."), true);
        return inserted ? 1 : 0;
    }

    private static int hurt(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        float before = player.getHealth();
        boolean damaged = player.damage(player.getEntityWorld(), player.getEntityWorld().getDamageSources().generic(), 2.0F);
        float after = player.getHealth();
        source.sendFeedback(() -> Text.literal(String.format("Hurt AICompanion: damaged=%s health %.1f -> %.1f", damaged, before, after)), true);
        return damaged ? 1 : 0;
    }

    private static int breakFront(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        BlockPos target = player.getBlockPos().offset(player.getHorizontalFacing());
        String blockName = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
        boolean broken = player.interactionManager.tryBreakBlock(target);
        source.sendFeedback(() -> Text.literal("Break front: target=" + target.toShortString()
                + " block=" + blockName
                + " broken=" + broken), true);
        return broken ? 1 : 0;
    }

    private static int swing(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        companion.get().swingHand(Hand.MAIN_HAND);
        source.sendFeedback(() -> Text.literal("AICompanion swung main hand."), true);
        return 1;
    }

    private static int mineFrontVisual(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        BlockPos target = player.getBlockPos().offset(player.getHorizontalFacing());
        String blockName = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
        boolean started = CompanionMiningTasks.start(player, target);
        source.sendFeedback(() -> Text.literal("Mine front visual: target=" + target.toShortString()
                + " block=" + blockName
                + " started=" + started), true);
        return started ? 1 : 0;
    }

    private static int remove(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        boolean removed = FakeCompanionSpawner.remove(source.getServer());
        source.sendFeedback(() -> Text.literal(removed ? "Removed AICompanion." : "AICompanion is not present."), true);
        return removed ? 1 : 0;
    }

    private static Optional<ServerPlayerEntity> requireCompanion(ServerCommandSource source) {
        Optional<ServerPlayerEntity> companion = FakeCompanionSpawner.find(source.getServer());
        if (companion.isEmpty()) {
            source.sendFeedback(() -> Text.literal("AICompanion is not present. Run /aicompanion spawn first."), false);
        }
        return companion;
    }

    private static int countOccupiedSlots(PlayerInventory inventory) {
        int occupied = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static String describeStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return stack.getCount() + "x " + stack.getName().getString();
    }
}
