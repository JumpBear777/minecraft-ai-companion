package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
                .then(CommandManager.literal("give_blocks")
                        .executes(CompanionDebugCommands::giveBlocks))
                .then(CommandManager.literal("give_food")
                        .executes(CompanionDebugCommands::giveFood))
                .then(CommandManager.literal("hurt")
                        .executes(CompanionDebugCommands::hurt))
                .then(CommandManager.literal("hurt_visual")
                        .executes(CompanionDebugCommands::hurtVisual))
                .then(CommandManager.literal("eat")
                        .executes(CompanionDebugCommands::eat))
                .then(CommandManager.literal("use_item_visual")
                        .executes(CompanionDebugCommands::useItemVisual))
                .then(CommandManager.literal("equip_tool")
                        .executes(CompanionDebugCommands::equipTool))
                .then(CommandManager.literal("equip_armor")
                        .executes(CompanionDebugCommands::equipArmor))
                .then(CommandManager.literal("sync_equipment")
                        .executes(CompanionDebugCommands::syncEquipment))
                .then(CommandManager.literal("break_front")
                        .executes(CompanionDebugCommands::breakFront))
                .then(CommandManager.literal("place_front")
                        .executes(CompanionDebugCommands::placeFront))
                .then(CommandManager.literal("place_front_debug")
                        .executes(CompanionDebugCommands::placeFrontDebug))
                .then(CommandManager.literal("swing")
                        .executes(CompanionDebugCommands::swing))
                .then(CommandManager.literal("mine_front_visual")
                        .executes(CompanionDebugCommands::mineFrontVisual))
                .then(CommandManager.literal("gravity_test")
                        .executes(CompanionDebugCommands::gravityTest))
                .then(CommandManager.literal("velocity_test")
                        .executes(CompanionDebugCommands::velocityTest))
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
        Vec3d velocity = player.getVelocity();
        source.sendFeedback(() -> Text.literal(String.format("movement: onGround=%s velocity=%.3f %.3f %.3f",
                player.isOnGround(),
                velocity.x,
                velocity.y,
                velocity.z)), false);
        source.sendFeedback(() -> Text.literal("inventory: occupiedSlots=" + countOccupiedSlots(inventory)
                + " selected=" + describeStack(inventory.getSelectedStack())
                + " offhand=" + describeStack(player.getOffHandStack())), false);
        source.sendFeedback(() -> Text.literal("equipment: main=" + describeStack(player.getMainHandStack())
                + " head=" + describeStack(player.getEquippedStack(EquipmentSlot.HEAD))
                + " chest=" + describeStack(player.getEquippedStack(EquipmentSlot.CHEST))
                + " legs=" + describeStack(player.getEquippedStack(EquipmentSlot.LEGS))
                + " feet=" + describeStack(player.getEquippedStack(EquipmentSlot.FEET))), false);
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

    private static int giveBlocks(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ItemStack stack = new ItemStack(Items.OAK_PLANKS, 16);
        setMainHandAndSync(player, stack);
        source.sendFeedback(() -> Text.literal("Put 16 oak planks in AICompanion main hand and synced equipment."), true);
        return 1;
    }

    private static int giveFood(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        setMainHandAndSync(player, new ItemStack(Items.APPLE, 4));
        player.getHungerManager().setFoodLevel(12);
        player.getHungerManager().setSaturationLevel(0.0F);
        source.sendFeedback(() -> Text.literal("Put 4 apples in AICompanion main hand, synced equipment, and lowered hunger for eating test."), true);
        return 1;
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

    private static int hurtVisual(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        float before = player.getHealth();
        boolean damaged = player.damage(player.getEntityWorld(), player.getEntityWorld().getDamageSources().generic(), 1.0F);
        player.getEntityWorld().sendEntityStatus(player, (byte) 2);
        player.getEntityWorld().sendEntityDamage(player, player.getEntityWorld().getDamageSources().generic());
        Direction facing = player.getHorizontalFacing();
        Vec3d knockback = new Vec3d(-facing.getOffsetX() * 0.25D, 0.12D, -facing.getOffsetZ() * 0.25D);
        CompanionBehaviorTestTasks.applyKnockbackHop(player, source, knockback);
        sendVelocityToNearbyPlayers(player);
        float after = player.getHealth();
        source.sendFeedback(() -> Text.literal(String.format("Hurt visual: damaged=%s health %.1f -> %.1f", damaged, before, after)), true);
        return damaged ? 1 : 0;
    }

    private static int eat(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ItemStack stack = player.getMainHandStack();
        int beforeFood = player.getHungerManager().getFoodLevel();
        int beforeCount = stack.getCount();
        player.swingHand(Hand.MAIN_HAND);
        ItemStack result = stack.finishUsing(player.getEntityWorld(), player);
        player.setStackInHand(Hand.MAIN_HAND, result);
        int afterFood = player.getHungerManager().getFoodLevel();
        int afterCount = result.getCount();
        source.sendFeedback(() -> Text.literal("Eat: item=" + describeStack(stack)
                + " food " + beforeFood + " -> " + afterFood
                + " count " + beforeCount + " -> " + afterCount), true);
        return afterFood > beforeFood || afterCount < beforeCount ? 1 : 0;
    }

    private static int useItemVisual(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.useItemVisually(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Use item visual started=" + started
                + " hand=" + describeStack(companion.get().getMainHandStack())), true);
        return started ? 1 : 0;
    }

    private static int equipTool(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        setMainHandAndSync(companion.get(), new ItemStack(Items.IRON_AXE));
        source.sendFeedback(() -> Text.literal("Equipped AICompanion with an iron axe."), true);
        return 1;
    }

    private static int equipArmor(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        int receivers = syncEquipmentToNearbyPlayers(player);
        source.sendFeedback(() -> Text.literal("Equipped AICompanion with iron armor and synced equipment: receivers=" + receivers), true);
        return 1;
    }

    private static int syncEquipment(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        int receivers = syncEquipmentToNearbyPlayers(player);
        source.sendFeedback(() -> Text.literal("Synced equipment to nearby players: receivers=" + receivers), true);
        return receivers > 0 ? 1 : 0;
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

    private static int placeFront(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Direction facing = player.getHorizontalFacing();
        BlockPos support = player.getBlockPos().down().offset(facing);
        BlockHitResult hitResult = new BlockHitResult(support.toCenterPos().add(0.0D, 0.5D, 0.0D), Direction.UP, support, false);
        ItemStack stack = player.getMainHandStack();
        int before = stack.getCount();
        ActionResult result = player.interactionManager.interactBlock(player, player.getEntityWorld(), stack, Hand.MAIN_HAND, hitResult);
        int after = player.getMainHandStack().getCount();
        source.sendFeedback(() -> Text.literal("Place front: support=" + support.toShortString()
                + " item=" + describeStack(stack)
                + " result=" + result
                + " count " + before + " -> " + after), true);
        return result.isAccepted() ? 1 : 0;
    }

    private static int placeFrontDebug(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Direction facing = player.getHorizontalFacing();
        BlockPos support = player.getBlockPos().down().offset(facing);
        BlockPos target = support.up();
        BlockHitResult hitResult = new BlockHitResult(support.toCenterPos().add(0.0D, 0.5D, 0.0D), Direction.UP, support, false);
        ItemStack stack = player.getMainHandStack();
        int before = stack.getCount();
        String supportBefore = player.getEntityWorld().getBlockState(support).getBlock().getName().getString();
        String targetBefore = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
        ActionResult result = player.interactionManager.interactBlock(player, player.getEntityWorld(), stack, Hand.MAIN_HAND, hitResult);
        int after = player.getMainHandStack().getCount();
        String supportAfter = player.getEntityWorld().getBlockState(support).getBlock().getName().getString();
        String targetAfter = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
        source.sendFeedback(() -> Text.literal("Place front debug:"), false);
        source.sendFeedback(() -> Text.literal("facing=" + facing
                + " support=" + support.toShortString()
                + " target=" + target.toShortString()
                + " item=" + describeStack(stack)), false);
        source.sendFeedback(() -> Text.literal("support " + supportBefore + " -> " + supportAfter
                + " target " + targetBefore + " -> " + targetAfter
                + " result=" + result
                + " count " + before + " -> " + after), true);
        return result.isAccepted() ? 1 : 0;
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

    private static int gravityTest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        boolean started = CompanionBehaviorTestTasks.driveFall(player, source);
        source.sendFeedback(() -> Text.literal("Gravity test started: server-driven fall from current position."), true);
        return started ? 1 : 0;
    }

    private static int velocityTest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Direction facing = player.getHorizontalFacing();
        Vec3d velocity = new Vec3d(-facing.getOffsetX() * 0.28D, 0.0D, -facing.getOffsetZ() * 0.28D);
        boolean started = CompanionBehaviorTestTasks.applyVelocity(player, source, velocity);
        sendVelocityToNearbyPlayers(player);
        source.sendFeedback(() -> Text.literal(String.format("Velocity test started: velocity=%.2f %.2f %.2f",
                velocity.x, velocity.y, velocity.z)), true);
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

    private static void sendVelocityToNearbyPlayers(ServerPlayerEntity player) {
        sendToNearbyPlayers(player, new EntityVelocityUpdateS2CPacket(player));
    }

    private static void setMainHandAndSync(ServerPlayerEntity player, ItemStack stack) {
        player.setStackInHand(Hand.MAIN_HAND, stack);
        syncEquipmentToNearbyPlayers(player);
    }

    private static int syncEquipmentToNearbyPlayers(ServerPlayerEntity player) {
        List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(
                Pair.of(EquipmentSlot.MAINHAND, player.getEquippedStack(EquipmentSlot.MAINHAND).copy()),
                Pair.of(EquipmentSlot.OFFHAND, player.getEquippedStack(EquipmentSlot.OFFHAND).copy()),
                Pair.of(EquipmentSlot.HEAD, player.getEquippedStack(EquipmentSlot.HEAD).copy()),
                Pair.of(EquipmentSlot.CHEST, player.getEquippedStack(EquipmentSlot.CHEST).copy()),
                Pair.of(EquipmentSlot.LEGS, player.getEquippedStack(EquipmentSlot.LEGS).copy()),
                Pair.of(EquipmentSlot.FEET, player.getEquippedStack(EquipmentSlot.FEET).copy()));
        EntityEquipmentUpdateS2CPacket packet = new EntityEquipmentUpdateS2CPacket(player.getId(), equipment);
        return sendToNearbyPlayers(player, packet);
    }

    private static int sendToNearbyPlayers(ServerPlayerEntity player, net.minecraft.network.packet.Packet<?> packet) {
        int receivers = 0;
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(player) <= 4096.0D) {
                viewer.networkHandler.sendPacket(packet);
                receivers++;
            }
        }
        return receivers;
    }
}
