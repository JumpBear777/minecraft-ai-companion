package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CompanionDebugCommands {
    private static final Map<UUID, InteractionTestLayout> INTERACTION_TEST_LAYOUTS = new HashMap<>();
    private static final Map<UUID, ToolTestLayout> TOOL_TEST_LAYOUTS = new HashMap<>();

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
                .then(CommandManager.literal("step_forward")
                        .executes(CompanionDebugCommands::stepForward))
                .then(CommandManager.literal("walk_forward")
                        .executes(CompanionDebugCommands::walkForward))
                .then(CommandManager.literal("jump_forward")
                        .executes(CompanionDebugCommands::jumpForward))
                .then(CommandManager.literal("sprint_forward")
                        .executes(CompanionDebugCommands::sprintForward))
                .then(CommandManager.literal("sneak_forward")
                        .executes(CompanionDebugCommands::sneakForward))
                .then(CommandManager.literal("swim_up")
                        .executes(CompanionDebugCommands::swimUp))
                .then(CommandManager.literal("look_at_me")
                        .executes(CompanionDebugCommands::lookAtMe))
                .then(CommandManager.literal("setup_boat_ride")
                        .executes(CompanionDebugCommands::setupBoatRide))
                .then(CommandManager.literal("dismount")
                        .executes(CompanionDebugCommands::dismount))
                .then(CommandManager.literal("setup_bed_sleep")
                        .executes(CompanionDebugCommands::setupBedSleep))
                .then(CommandManager.literal("wake")
                        .executes(CompanionDebugCommands::wake))
                .then(CommandManager.literal("setup_chest")
                        .executes(CompanionDebugCommands::setupChest))
                .then(CommandManager.literal("open_chest")
                        .executes(CompanionDebugCommands::openChest))
                .then(CommandManager.literal("chest_put_wood")
                        .executes(CompanionDebugCommands::chestPutWood))
                .then(CommandManager.literal("chest_take_first")
                        .executes(CompanionDebugCommands::chestTakeFirst))
                .then(CommandManager.literal("setup_interaction_blocks")
                        .executes(CompanionDebugCommands::setupInteractionBlocks))
                .then(CommandManager.literal("use_test_door")
                        .executes(CompanionDebugCommands::useTestDoor))
                .then(CommandManager.literal("use_test_button")
                        .executes(CompanionDebugCommands::useTestButton))
                .then(CommandManager.literal("use_test_lever")
                        .executes(CompanionDebugCommands::useTestLever))
                .then(CommandManager.literal("use_test_crafting_table")
                        .executes(CompanionDebugCommands::useTestCraftingTable))
                .then(CommandManager.literal("setup_tool_tests")
                        .executes(CompanionDebugCommands::setupToolTests))
                .then(CommandManager.literal("switch_axe")
                        .executes(CompanionDebugCommands::switchAxe))
                .then(CommandManager.literal("switch_shovel")
                        .executes(CompanionDebugCommands::switchShovel))
                .then(CommandManager.literal("switch_hoe")
                        .executes(CompanionDebugCommands::switchHoe))
                .then(CommandManager.literal("switch_sword")
                        .executes(CompanionDebugCommands::switchSword))
                .then(CommandManager.literal("tool_chop_log")
                        .executes(CompanionDebugCommands::toolChopLog))
                .then(CommandManager.literal("tool_till_ground")
                        .executes(CompanionDebugCommands::toolTillGround))
                .then(CommandManager.literal("tool_dig_dirt")
                        .executes(CompanionDebugCommands::toolDigDirt))
                .then(CommandManager.literal("tool_attack_zombie")
                        .executes(CompanionDebugCommands::toolAttackZombie))
                .then(CommandManager.literal("give_wood")
                        .executes(CompanionDebugCommands::giveWood))
                .then(CommandManager.literal("setup_pickup_item")
                        .executes(CompanionDebugCommands::setupPickupItem))
                .then(CommandManager.literal("setup_xp_orb")
                        .executes(CompanionDebugCommands::setupXpOrb))
                .then(CommandManager.literal("setup_zombie_attack")
                        .executes(CompanionDebugCommands::setupZombieAttack))
                .then(CommandManager.literal("give_blocks")
                        .executes(CompanionDebugCommands::giveBlocks))
                .then(CommandManager.literal("give_food")
                        .executes(CompanionDebugCommands::giveFood))
                .then(CommandManager.literal("give_speed")
                        .executes(CompanionDebugCommands::giveSpeed))
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
        source.sendFeedback(() -> Text.literal(String.format("movementSpeed=%.3f",
                player.getMovementSpeed())), false);
        source.sendFeedback(() -> Text.literal(String.format("pose: sprinting=%s sneaking=%s swimming=%s sleeping=%s riding=%s vehicle=%s",
                player.isSprinting(),
                player.isSneaking(),
                player.isSwimming(),
                player.isSleeping(),
                player.hasVehicle(),
                player.hasVehicle() ? player.getVehicle().getType().toString() : "none")), false);
        source.sendFeedback(() -> Text.literal("inventory: occupiedSlots=" + countOccupiedSlots(inventory)
                + " selected=" + describeStack(inventory.getSelectedStack())
                + " offhand=" + describeStack(player.getOffHandStack())), false);
        source.sendFeedback(() -> Text.literal("inventoryItems: " + describeInventoryItems(inventory, 8)), false);
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

    private static int stepForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        player.networkHandler.syncWithPlayerPosition();
        CompanionInputController.stepForward(player, 0.6D);
        CompanionBehaviorTestTasks.observeVelocity(player, source);
        source.sendFeedback(() -> Text.literal("Step forward sent through PlayerMoveC2SPacket.Full."), true);
        return 1;
    }

    private static int walkForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.walkForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Walk forward started through PlayerInputC2SPacket + PlayerMoveC2SPacket."), true);
        return started ? 1 : 0;
    }

    private static int jumpForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.jumpForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Jump forward started through PlayerInputC2SPacket + PlayerMoveC2SPacket."), true);
        return started ? 1 : 0;
    }

    private static int sprintForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.sprintForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Sprint forward started through ClientCommandC2SPacket + movement packets."), true);
        return started ? 1 : 0;
    }

    private static int sneakForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.sneakForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Sneak forward started through PlayerInputC2SPacket sneak input."), true);
        return started ? 1 : 0;
    }

    private static int swimUp(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.swimUp(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Swim up started through jump input. Put AICompanion in water before testing."), true);
        return started ? 1 : 0;
    }

    private static int lookAtMe(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        Entity target = source.getEntity();
        if (target == null) {
            source.sendFeedback(() -> Text.literal("Look at me requires an entity command source."), false);
            return 0;
        }

        CompanionInputController.lookAt(companion.get(), target);
        source.sendFeedback(() -> Text.literal("Look at me sent through PlayerMoveC2SPacket.LookAndOnGround."), true);
        return 1;
    }

    private static int setupBoatRide(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Entity boat = EntityType.OAK_BOAT.create(world, SpawnReason.COMMAND);
        if (boat == null) {
            source.sendFeedback(() -> Text.literal("Boat setup failed: could not create oak boat."), false);
            return 0;
        }

        Direction facing = player.getHorizontalFacing();
        double x = player.getX() + facing.getOffsetX() * 1.2D;
        double z = player.getZ() + facing.getOffsetZ() * 1.2D;
        boat.refreshPositionAndAngles(x, player.getY(), z, player.getYaw(), 0.0F);
        boolean spawned = world.spawnEntity(boat);
        ActionResult result = spawned ? boat.interact(player, Hand.MAIN_HAND) : ActionResult.FAIL;
        source.sendFeedback(() -> Text.literal("Boat ride setup: spawned=" + spawned
                + " interact=" + result
                + " riding=" + player.hasVehicle()), true);
        return player.hasVehicle() ? 1 : 0;
    }

    private static int dismount(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        boolean before = player.hasVehicle();
        player.stopRiding();
        source.sendFeedback(() -> Text.literal("Dismount: riding " + before + " -> " + player.hasVehicle()), true);
        return before && !player.hasVehicle() ? 1 : 0;
    }

    private static int setupBedSleep(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Direction facing = player.getHorizontalFacing();
        BlockPos foot = player.getBlockPos().offset(facing, 2);
        BlockPos head = foot.offset(facing);
        world.setBlockState(foot, Blocks.RED_BED.getDefaultState()
                .with(BedBlock.FACING, facing)
                .with(BedBlock.PART, BedPart.FOOT)
                .with(BedBlock.OCCUPIED, false));
        world.setBlockState(head, Blocks.RED_BED.getDefaultState()
                .with(BedBlock.FACING, facing)
                .with(BedBlock.PART, BedPart.HEAD)
                .with(BedBlock.OCCUPIED, false));
        world.setBlockState(foot.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(head.up(), Blocks.AIR.getDefaultState());

        var result = player.trySleep(foot);
        source.sendFeedback(() -> Text.literal("Bed sleep setup: foot=" + foot.toShortString()
                + " result=" + result
                + " sleeping=" + player.isSleeping()), true);
        return player.isSleeping() ? 1 : 0;
    }

    private static int wake(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        boolean before = player.isSleeping();
        player.networkHandler.onClientCommand(new ClientCommandC2SPacket(player, ClientCommandC2SPacket.Mode.STOP_SLEEPING));
        source.sendFeedback(() -> Text.literal("Wake: sleeping " + before + " -> " + player.isSleeping()), true);
        return before && !player.isSleeping() ? 1 : 0;
    }

    private static int setupChest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        BlockPos pos = getFrontChestPos(player);
        world.setBlockState(pos, Blocks.CHEST.getDefaultState());
        BlockEntity blockEntity = world.getBlockEntity(pos);
        boolean chest = blockEntity instanceof ChestBlockEntity;
        source.sendFeedback(() -> Text.literal("Chest setup: pos=" + pos.toShortString() + " chest=" + chest), true);
        return chest ? 1 : 0;
    }

    private static int openChest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        boolean opened = openFrontChest(player);
        source.sendFeedback(() -> Text.literal("Open chest: opened=" + opened
                + " handler=" + player.currentScreenHandler.getClass().getSimpleName()), true);
        return opened ? 1 : 0;
    }

    private static int chestPutWood(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        if (!openFrontChest(player)) {
            source.sendFeedback(() -> Text.literal("Chest put wood failed: no open chest handler."), false);
            return 0;
        }

        if (countItem(player, Items.OAK_LOG) == 0) {
            player.giveItemStack(new ItemStack(Items.OAK_LOG, 16));
        }

        GenericContainerScreenHandler handler = (GenericContainerScreenHandler) player.currentScreenHandler;
        int beforeChest = countItem(handler.getInventory(), Items.OAK_LOG);
        int beforePlayer = countItem(player, Items.OAK_LOG);
        int slot = findPlayerItemSlot(handler, player, Items.OAK_LOG);
        if (slot < 0) {
            source.sendFeedback(() -> Text.literal("Chest put wood failed: no oak logs in player slots."), false);
            return 0;
        }

        quickMove(player, handler, slot);
        int afterChest = countItem(handler.getInventory(), Items.OAK_LOG);
        int afterPlayer = countItem(player, Items.OAK_LOG);
        source.sendFeedback(() -> Text.literal("Chest put wood: slot=" + slot
                + " chestLogs " + beforeChest + " -> " + afterChest
                + " playerLogs " + beforePlayer + " -> " + afterPlayer), true);
        return afterChest > beforeChest ? 1 : 0;
    }

    private static int chestTakeFirst(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        if (!openFrontChest(player)) {
            source.sendFeedback(() -> Text.literal("Chest take first failed: no open chest handler."), false);
            return 0;
        }

        GenericContainerScreenHandler handler = (GenericContainerScreenHandler) player.currentScreenHandler;
        int beforeChestItems = countOccupiedSlots(handler.getInventory());
        int beforePlayerItems = countTotalItems(player);
        if (beforeChestItems == 0) {
            source.sendFeedback(() -> Text.literal("Chest take first failed: chest is empty."), false);
            return 0;
        }

        quickMove(player, handler, 0);
        int afterChestItems = countOccupiedSlots(handler.getInventory());
        int afterPlayerItems = countTotalItems(player);
        source.sendFeedback(() -> Text.literal("Chest take first: chestOccupied " + beforeChestItems + " -> " + afterChestItems
                + " playerTotalItems " + beforePlayerItems + " -> " + afterPlayerItems), true);
        return afterPlayerItems > beforePlayerItems ? 1 : 0;
    }

    private static int setupInteractionBlocks(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        InteractionTestLayout layout = getInteractionTestLayout(player);
        INTERACTION_TEST_LAYOUTS.put(player.getUuid(), layout);
        Direction facing = layout.facing();

        prepareTestFloor(world, layout.door().down());
        prepareTestFloor(world, layout.button().down());
        prepareTestFloor(world, layout.lever().down());
        prepareTestFloor(world, layout.craftingTable().down());

        world.setBlockState(layout.door(), Blocks.OAK_DOOR.getDefaultState()
                .with(DoorBlock.FACING, facing)
                .with(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .with(DoorBlock.OPEN, false));
        world.setBlockState(layout.door().up(), Blocks.OAK_DOOR.getDefaultState()
                .with(DoorBlock.FACING, facing)
                .with(DoorBlock.HALF, DoubleBlockHalf.UPPER)
                .with(DoorBlock.OPEN, false));
        world.setBlockState(layout.button(), Blocks.OAK_BUTTON.getDefaultState()
                .with(WallMountedBlock.FACE, BlockFace.FLOOR)
                .with(HorizontalFacingBlock.FACING, facing)
                .with(ButtonBlock.POWERED, false));
        world.setBlockState(layout.lever(), Blocks.LEVER.getDefaultState()
                .with(WallMountedBlock.FACE, BlockFace.FLOOR)
                .with(HorizontalFacingBlock.FACING, facing)
                .with(LeverBlock.POWERED, false));
        world.setBlockState(layout.craftingTable(), Blocks.CRAFTING_TABLE.getDefaultState());

        source.sendFeedback(() -> Text.literal("Interaction blocks setup:"
                + " door=" + layout.door().toShortString()
                + " button=" + layout.button().toShortString()
                + " lever=" + layout.lever().toShortString()
                + " craftingTable=" + layout.craftingTable().toShortString()), true);
        return 1;
    }

    private static int useTestDoor(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        InteractionTestLayout layout = getInteractionTestLayout(player);
        BlockState before = player.getEntityWorld().getBlockState(layout.door());
        boolean beforeOpen = before.contains(DoorBlock.OPEN) && before.get(DoorBlock.OPEN);
        boolean started = walkLookAndUseBlock(player, source, layout.door(), Direction.UP,
                (taskSource, taskPlayer, result, startPos, ticks) -> {
                    BlockState after = taskPlayer.getEntityWorld().getBlockState(layout.door());
                    boolean afterOpen = after.contains(DoorBlock.OPEN) && after.get(DoorBlock.OPEN);
                    taskSource.sendFeedback(() -> Text.literal("Use test door: result=" + result
                            + " open " + beforeOpen + " -> " + afterOpen
                            + " ticks=" + ticks
                            + " pos=" + formatPos(taskPlayer)), true);
                    return result.isAccepted() && beforeOpen != afterOpen;
                });
        source.sendFeedback(() -> Text.literal("Use test door walking task started."), true);
        return started ? 1 : 0;
    }

    private static int useTestButton(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        InteractionTestLayout layout = getInteractionTestLayout(player);
        BlockState before = player.getEntityWorld().getBlockState(layout.button());
        boolean beforePowered = before.contains(ButtonBlock.POWERED) && before.get(ButtonBlock.POWERED);
        boolean started = walkLookAndUseBlock(player, source, layout.button(), Direction.UP,
                (taskSource, taskPlayer, result, startPos, ticks) -> {
                    BlockState after = taskPlayer.getEntityWorld().getBlockState(layout.button());
                    boolean afterPowered = after.contains(ButtonBlock.POWERED) && after.get(ButtonBlock.POWERED);
                    taskSource.sendFeedback(() -> Text.literal("Use test button: result=" + result
                            + " powered " + beforePowered + " -> " + afterPowered
                            + " ticks=" + ticks
                            + " pos=" + formatPos(taskPlayer)), true);
                    return result.isAccepted() && afterPowered;
                });
        source.sendFeedback(() -> Text.literal("Use test button walking task started."), true);
        return started ? 1 : 0;
    }

    private static int useTestLever(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        InteractionTestLayout layout = getInteractionTestLayout(player);
        BlockState before = player.getEntityWorld().getBlockState(layout.lever());
        boolean beforePowered = before.contains(LeverBlock.POWERED) && before.get(LeverBlock.POWERED);
        boolean started = walkLookAndUseBlock(player, source, layout.lever(), Direction.UP,
                (taskSource, taskPlayer, result, startPos, ticks) -> {
                    BlockState after = taskPlayer.getEntityWorld().getBlockState(layout.lever());
                    boolean afterPowered = after.contains(LeverBlock.POWERED) && after.get(LeverBlock.POWERED);
                    taskSource.sendFeedback(() -> Text.literal("Use test lever: result=" + result
                            + " powered " + beforePowered + " -> " + afterPowered
                            + " ticks=" + ticks
                            + " pos=" + formatPos(taskPlayer)), true);
                    return result.isAccepted() && beforePowered != afterPowered;
                });
        source.sendFeedback(() -> Text.literal("Use test lever walking task started."), true);
        return started ? 1 : 0;
    }

    private static int useTestCraftingTable(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        InteractionTestLayout layout = getInteractionTestLayout(player);
        boolean started = walkLookAndUseBlock(player, source, layout.craftingTable(), Direction.UP,
                (taskSource, taskPlayer, result, startPos, ticks) -> {
                    taskSource.sendFeedback(() -> Text.literal("Use test crafting table: result=" + result
                            + " handler=" + taskPlayer.currentScreenHandler.getClass().getSimpleName()
                            + " ticks=" + ticks
                            + " pos=" + formatPos(taskPlayer)), true);
                    return result.isAccepted();
                });
        source.sendFeedback(() -> Text.literal("Use test crafting table walking task started."), true);
        return started ? 1 : 0;
    }

    private static int setupToolTests(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Direction facing = player.getHorizontalFacing();
        Direction right = facing.rotateYClockwise();
        BlockPos base = player.getBlockPos().offset(facing, 3);
        BlockPos log = base;
        BlockPos till = base.offset(right, 2);
        BlockPos dirt = base.offset(right, 4);
        BlockPos zombiePos = base.offset(right, 6);

        prepareTestFloor(world, log.down());
        prepareTestFloor(world, till.down());
        prepareTestFloor(world, dirt.down());
        prepareTestFloor(world, zombiePos.down());
        world.setBlockState(log, Blocks.OAK_LOG.getDefaultState());
        world.setBlockState(log.up(), Blocks.OAK_LOG.getDefaultState());
        world.setBlockState(till, Blocks.GRASS_BLOCK.getDefaultState());
        world.setBlockState(dirt, Blocks.DIRT.getDefaultState());

        ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
        UUID zombieUuid = null;
        boolean zombieSpawned = false;
        if (zombie != null) {
            zombie.refreshPositionAndAngles(
                    zombiePos.getX() + 0.5D,
                    zombiePos.getY(),
                    zombiePos.getZ() + 0.5D,
                    player.getYaw() + 180.0F,
                    0.0F);
            zombie.setAiDisabled(true);
            zombieSpawned = world.spawnEntity(zombie);
            if (zombieSpawned) {
                zombieUuid = zombie.getUuid();
            }
        }

        ToolTestLayout layout = new ToolTestLayout(log, till, dirt, zombieUuid);
        TOOL_TEST_LAYOUTS.put(player.getUuid(), layout);
        boolean finalZombieSpawned = zombieSpawned;
        source.sendFeedback(() -> Text.literal("Tool tests setup:"
                + " log=" + log.toShortString()
                + " till=" + till.toShortString()
                + " dirt=" + dirt.toShortString()
                + " zombie=" + finalZombieSpawned), true);
        return 1;
    }

    private static int switchAxe(CommandContext<ServerCommandSource> context) {
        return switchTool(context, new ItemStack(Items.IRON_AXE), "iron axe");
    }

    private static int switchShovel(CommandContext<ServerCommandSource> context) {
        return switchTool(context, new ItemStack(Items.IRON_SHOVEL), "iron shovel");
    }

    private static int switchHoe(CommandContext<ServerCommandSource> context) {
        return switchTool(context, new ItemStack(Items.IRON_HOE), "iron hoe");
    }

    private static int switchSword(CommandContext<ServerCommandSource> context) {
        return switchTool(context, new ItemStack(Items.IRON_SWORD), "iron sword");
    }

    private static int switchTool(CommandContext<ServerCommandSource> context, ItemStack stack, String label) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        setMainHandAndSync(companion.get(), stack);
        source.sendFeedback(() -> Text.literal("Switched AICompanion main hand to " + label + "."), true);
        return 1;
    }

    private static int toolChopLog(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ToolTestLayout layout = getToolTestLayout(player);
        String before = player.getEntityWorld().getBlockState(layout.log()).getBlock().getName().getString();
        setMainHandAndSync(player, new ItemStack(Items.IRON_AXE));
        CompanionInputController.lookAt(player, layout.log().toCenterPos());
        boolean started = CompanionMiningTasks.start(player, layout.log(), source, "Tool chop log");
        source.sendFeedback(() -> Text.literal("Tool chop log started: tool=" + describeStack(player.getMainHandStack())
                + " block=" + before
                + " started=" + started), true);
        return started ? 1 : 0;
    }

    private static int toolTillGround(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ToolTestLayout layout = getToolTestLayout(player);
        setMainHandAndSync(player, new ItemStack(Items.IRON_HOE));
        ActionResult result = useMainHandOnTop(player, layout.till());
        String after = player.getEntityWorld().getBlockState(layout.till()).getBlock().getName().getString();
        source.sendFeedback(() -> Text.literal("Tool till ground: result=" + result
                + " block=" + after
                + " tool=" + describeStack(player.getMainHandStack())), true);
        return result.isAccepted() ? 1 : 0;
    }

    private static int toolDigDirt(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ToolTestLayout layout = getToolTestLayout(player);
        String before = player.getEntityWorld().getBlockState(layout.dirt()).getBlock().getName().getString();
        setMainHandAndSync(player, new ItemStack(Items.IRON_SHOVEL));
        CompanionInputController.lookAt(player, layout.dirt().toCenterPos());
        boolean started = CompanionMiningTasks.start(player, layout.dirt(), source, "Tool dig dirt");
        source.sendFeedback(() -> Text.literal("Tool dig dirt started: tool=" + describeStack(player.getMainHandStack())
                + " block=" + before
                + " started=" + started), true);
        return started ? 1 : 0;
    }

    private static int toolAttackZombie(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ToolTestLayout layout = getToolTestLayout(player);
        if (layout.zombieUuid() == null) {
            source.sendFeedback(() -> Text.literal("Tool attack zombie failed: no test zombie. Run /aicompanion setup_tool_tests."), false);
            return 0;
        }

        Entity entity = ((ServerWorld) player.getEntityWorld()).getEntity(layout.zombieUuid());
        if (!(entity instanceof ZombieEntity zombie) || zombie.isRemoved()) {
            source.sendFeedback(() -> Text.literal("Tool attack zombie failed: test zombie is missing."), false);
            return 0;
        }

        setMainHandAndSync(player, new ItemStack(Items.IRON_SWORD));
        CompanionInputController.lookAt(player, zombie);
        float before = zombie.getHealth();
        player.swingHand(Hand.MAIN_HAND);
        player.attack(zombie);
        float after = zombie.getHealth();
        source.sendFeedback(() -> Text.literal(String.format("Tool attack zombie: tool=%s health %.1f -> %.1f alive=%s",
                describeStack(player.getMainHandStack()),
                before,
                after,
                zombie.isAlive())), true);
        return after < before ? 1 : 0;
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

    private static int setupPickupItem(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        ItemEntity item = new ItemEntity(world, player.getX(), player.getY(), player.getZ(), new ItemStack(Items.OAK_LOG, 3));
        item.setPickupDelay(0);
        item.setVelocity(Vec3d.ZERO);
        boolean spawned = world.spawnEntity(item);
        if (spawned) {
            CompanionBehaviorTestTasks.observeInventoryPickup(player, source);
        }

        source.sendFeedback(() -> Text.literal("Pickup setup: spawned real oak log item=" + spawned), true);
        return spawned ? 1 : 0;
    }

    private static int setupXpOrb(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        ExperienceOrbEntity orb = new ExperienceOrbEntity(world, player.getX(), player.getY(), player.getZ(), 7);
        boolean spawned = world.spawnEntity(orb);
        if (spawned) {
            CompanionBehaviorTestTasks.observeExperience(player, source);
        }

        source.sendFeedback(() -> Text.literal("XP setup: spawned real experience orb=" + spawned), true);
        return spawned ? 1 : 0;
    }

    private static int setupZombieAttack(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
        if (zombie == null) {
            source.sendFeedback(() -> Text.literal("Zombie setup failed: could not create zombie."), false);
            return 0;
        }

        Direction facing = player.getHorizontalFacing();
        double x = player.getX() + facing.getOffsetX() * 2.0D;
        double z = player.getZ() + facing.getOffsetZ() * 2.0D;
        zombie.refreshPositionAndAngles(x, player.getY(), z, player.getYaw() + 180.0F, 0.0F);
        zombie.setTarget(player);
        boolean spawned = world.spawnEntity(zombie);
        if (spawned) {
            CompanionBehaviorTestTasks.observeHealth(player, source);
        }

        source.sendFeedback(() -> Text.literal("Zombie setup: spawned hostile zombie targeting AICompanion=" + spawned), true);
        return spawned ? 1 : 0;
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

    private static int giveSpeed(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 60, 1));
        source.sendFeedback(() -> Text.literal(String.format("Gave AICompanion Speed II for 60s. movementSpeed=%.3f",
                player.getMovementSpeed())), true);
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
        Entity attacker = source.getEntity();
        boolean damaged = attacker instanceof ServerPlayerEntity serverPlayer
                ? player.damage(player.getEntityWorld(), player.getEntityWorld().getDamageSources().playerAttack(serverPlayer), 1.0F)
                : player.damage(player.getEntityWorld(), player.getEntityWorld().getDamageSources().generic(), 1.0F);
        CompanionBehaviorTestTasks.observeVelocity(player, source);
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
        boolean started = CompanionBehaviorTestTasks.observeGravity(player, source);
        source.sendFeedback(() -> Text.literal("Gravity test started: observing vanilla physics from current position."), true);
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
        player.addVelocity(velocity);
        boolean started = CompanionBehaviorTestTasks.observeVelocity(player, source);
        source.sendFeedback(() -> Text.literal(String.format("Velocity test started: vanilla addVelocity=%.2f %.2f %.2f",
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

    private static InteractionTestLayout getInteractionTestLayout(ServerPlayerEntity player) {
        InteractionTestLayout existing = INTERACTION_TEST_LAYOUTS.get(player.getUuid());
        if (existing != null) {
            return existing;
        }

        Direction facing = player.getHorizontalFacing();
        Direction right = facing.rotateYClockwise();
        BlockPos base = player.getBlockPos().offset(facing, 3);
        return new InteractionTestLayout(
                facing,
                base,
                base.offset(right, 2),
                base.offset(right, 4),
                base.offset(right, 6));
    }

    private static ToolTestLayout getToolTestLayout(ServerPlayerEntity player) {
        ToolTestLayout existing = TOOL_TEST_LAYOUTS.get(player.getUuid());
        if (existing != null) {
            return existing;
        }

        Direction facing = player.getHorizontalFacing();
        Direction right = facing.rotateYClockwise();
        BlockPos base = player.getBlockPos().offset(facing, 3);
        return new ToolTestLayout(
                base,
                base.offset(right, 2),
                base.offset(right, 4),
                null);
    }

    private static void prepareTestFloor(ServerWorld world, BlockPos floor) {
        world.setBlockState(floor, Blocks.STONE.getDefaultState());
        world.setBlockState(floor.up(), Blocks.AIR.getDefaultState());
        world.setBlockState(floor.up(2), Blocks.AIR.getDefaultState());
    }

    private static boolean walkLookAndUseBlock(
            ServerPlayerEntity player,
            ServerCommandSource source,
            BlockPos target,
            Direction side,
            CompanionBehaviorTestTasks.BlockUseResultHandler resultHandler) {
        player.networkHandler.syncWithPlayerPosition();
        CompanionInputController.lookAt(player, target.toCenterPos());
        return CompanionBehaviorTestTasks.walkToUseBlock(player, source, target, side, resultHandler);
    }

    private static ActionResult useMainHandOnTop(ServerPlayerEntity player, BlockPos pos) {
        CompanionInputController.lookAt(player, pos.toCenterPos());
        BlockHitResult hitResult = new BlockHitResult(
                pos.toCenterPos().add(0.0D, 0.5D, 0.0D),
                Direction.UP,
                pos,
                false);
        ItemStack stack = player.getMainHandStack();
        return player.interactionManager.interactBlock(player, player.getEntityWorld(), stack, Hand.MAIN_HAND, hitResult);
    }

    private static String formatPos(ServerPlayerEntity player) {
        return String.format("%.1f %.1f %.1f", player.getX(), player.getY(), player.getZ());
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

    private record InteractionTestLayout(Direction facing, BlockPos door, BlockPos button, BlockPos lever, BlockPos craftingTable) {
    }

    private record ToolTestLayout(BlockPos log, BlockPos till, BlockPos dirt, UUID zombieUuid) {
    }

    private static int countOccupiedSlots(Inventory inventory) {
        int occupied = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    private static int countTotalItems(ServerPlayerEntity player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            total += player.getInventory().getStack(slot).getCount();
        }
        return total;
    }

    private static int countItem(ServerPlayerEntity player, Item item) {
        return countItem(player.getInventory(), item);
    }

    private static int countItem(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static BlockPos getFrontChestPos(ServerPlayerEntity player) {
        return player.getBlockPos().offset(player.getHorizontalFacing(), 2);
    }

    private static boolean openFrontChest(ServerPlayerEntity player) {
        BlockPos pos = getFrontChestPos(player);
        if (!(player.getEntityWorld().getBlockEntity(pos) instanceof ChestBlockEntity)) {
            return false;
        }

        BlockHitResult hitResult = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, false);
        ItemStack stack = player.getMainHandStack();
        ActionResult result = player.interactionManager.interactBlock(player, player.getEntityWorld(), stack, Hand.MAIN_HAND, hitResult);
        return result.isAccepted() && player.currentScreenHandler instanceof GenericContainerScreenHandler;
    }

    private static int findPlayerItemSlot(ScreenHandler handler, ServerPlayerEntity player, Item item) {
        for (int slot = 0; slot < handler.slots.size(); slot++) {
            if (handler.slots.get(slot).inventory == player.getInventory()
                    && handler.slots.get(slot).getStack().isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static void quickMove(ServerPlayerEntity player, ScreenHandler handler, int slot) {
        handler.onSlotClick(slot, 0, SlotActionType.QUICK_MOVE, player);
        handler.sendContentUpdates();
    }

    private static String describeStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return stack.getCount() + "x " + stack.getName().getString();
    }

    private static String describeInventoryItems(PlayerInventory inventory, int limit) {
        StringBuilder builder = new StringBuilder();
        int shown = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                if (shown > 0) {
                    builder.append(", ");
                }
                builder.append("slot ").append(slot).append("=").append(describeStack(stack));
                shown++;
                if (shown >= limit) {
                    break;
                }
            }
        }

        if (shown == 0) {
            return "empty";
        }

        if (countOccupiedSlots(inventory) > shown) {
            builder.append(", ...");
        }
        return builder.toString();
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
