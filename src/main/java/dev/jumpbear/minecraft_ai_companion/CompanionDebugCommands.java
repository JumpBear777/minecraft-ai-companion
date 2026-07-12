package dev.jumpbear.minecraft_ai_companion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.datafixers.util.Pair;
import dev.jumpbear.minecraft_ai_companion.task.AttackTargetTask;
import dev.jumpbear.minecraft_ai_companion.task.CollectDroppedItemsTask;
import dev.jumpbear.minecraft_ai_companion.task.CompanionHotbar;
import dev.jumpbear.minecraft_ai_companion.task.CompanionTask;
import dev.jumpbear.minecraft_ai_companion.task.CompanionTaskManager;
import dev.jumpbear.minecraft_ai_companion.task.FollowPlayerTask;
import dev.jumpbear.minecraft_ai_companion.task.PillarTestTask;
import dev.jumpbear.minecraft_ai_companion.task.ReachAndChopTask;
import dev.jumpbear.minecraft_ai_companion.task.ReachTreeTask;
import dev.jumpbear.minecraft_ai_companion.task.TreeApproach;
import dev.jumpbear.minecraft_ai_companion.task.TreeChopSight;
import dev.jumpbear.minecraft_ai_companion.task.TreeDetector;
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
import net.minecraft.util.hit.HitResult;
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
    /**
     * 跨命令保存的树处理测试布局：base（树干根部）+ 有序原木结构 + 砍伐游标。
     * reach_tree 走到位后填入，tree_paint 上色、tree_chop_next 逐段砍伐都读它。
     */
    private static final Map<UUID, TreeTestLayout> TREE_TEST_LAYOUTS = new HashMap<>();

    /**
     * 16 色羊毛调色板，按染料顺序（白→橙→品红→…→黑）。tree_paint 用 {@code 序号 % 16} 取色，
     * 使从下往上的原木呈现规律的颜色梯度；超过 16 段的树颜色循环。
     */
    private static final net.minecraft.block.Block[] WOOL_PALETTE = {
            Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL, Blocks.LIGHT_BLUE_WOOL,
            Blocks.YELLOW_WOOL, Blocks.LIME_WOOL, Blocks.PINK_WOOL, Blocks.GRAY_WOOL,
            Blocks.LIGHT_GRAY_WOOL, Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
            Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL
    };

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
                .then(CommandManager.literal("move_control_forward")
                        .executes(CompanionDebugCommands::moveControlForward))
                .then(CommandManager.literal("navigation_forward")
                        .executes(CompanionDebugCommands::navigationForward))
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
                .then(CommandManager.literal("select_block")
                        .executes(CompanionDebugCommands::selectBlock))
                .then(CommandManager.literal("tree_scan")
                        .executes(CompanionDebugCommands::treeScan))
                .then(CommandManager.literal("setup_tree_clear")
                        .executes(CompanionDebugCommands::setupTreeClear))
                .then(CommandManager.literal("setup_tree_standard")
                        .executes(CompanionDebugCommands::setupTreeStandard))
                .then(CommandManager.literal("setup_tree_step")
                        .executes(CompanionDebugCommands::setupTreeStep))
                .then(CommandManager.literal("setup_tree_grass")
                        .executes(CompanionDebugCommands::setupTreeGrass))
                .then(CommandManager.literal("los_test")
                        .executes(CompanionDebugCommands::losTest))
                .then(CommandManager.literal("tree_plan_show")
                        .executes(CompanionDebugCommands::treePlanShow))
                .then(CommandManager.literal("tree_paint")
                        .executes(CompanionDebugCommands::treePaint))
                .then(CommandManager.literal("tree_chop_next")
                        .executes(CompanionDebugCommands::treeChopNext))
                .then(CommandManager.literal("swing")
                        .executes(CompanionDebugCommands::swing))
                .then(CommandManager.literal("mine_front_visual")
                        .executes(CompanionDebugCommands::mineFrontVisual))
                .then(CommandManager.literal("gravity_test")
                        .executes(CompanionDebugCommands::gravityTest))
                .then(CommandManager.literal("velocity_test")
                        .executes(CompanionDebugCommands::velocityTest))
                .then(CommandManager.literal("task")
                        .then(CommandManager.literal("collect")
                                .executes(CompanionDebugCommands::taskCollect))
                        .then(CommandManager.literal("follow")
                                .executes(CompanionDebugCommands::taskFollow))
                        .then(CommandManager.literal("attack")
                                .executes(CompanionDebugCommands::taskAttack))
                        .then(CommandManager.literal("pillar")
                                .executes(CompanionDebugCommands::taskPillar))
                        .then(CommandManager.literal("reach_tree")
                                .executes(CompanionDebugCommands::taskReachTree))
                        .then(CommandManager.literal("chop_base")
                                .executes(CompanionDebugCommands::taskChopBase))
                        .then(CommandManager.literal("current")
                                .executes(CompanionDebugCommands::taskCurrent))
                        .then(CommandManager.literal("status")
                                .executes(CompanionDebugCommands::taskStatus))
                        .then(CommandManager.literal("cancel")
                                .executes(CompanionDebugCommands::taskCancel)))
                .then(CommandManager.literal("remove")
                        .executes(CompanionDebugCommands::remove)));
    }

    private static int spawn(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity companion = FakeCompanionSpawner.spawnNear(source);
        source.sendFeedback(() -> Text.literal("Spawned or moved companion: " + companion.getName().getString()), true);
        return 1;
    }

    private static int taskCollect(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        CompanionTaskManager.assign(player, new CollectDroppedItemsTask(player));
        source.sendFeedback(() -> Text.literal("Task assigned: CollectDroppedItems"), true);
        return 1;
    }

    private static int taskFollow(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        CompanionTaskManager.assign(player, new FollowPlayerTask());
        source.sendFeedback(() -> Text.literal("Task assigned: FollowPlayer"), true);
        return 1;
    }

    private static int taskAttack(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        CompanionTaskManager.assign(player, new AttackTargetTask(player));
        source.sendFeedback(() -> Text.literal("Task assigned: AttackTarget"), true);
        return 1;
    }

    /**
     * 搭柱子端到端测试：先塞一组泥土进背包作脚手架（这样一条命令即可自洽测试，无需手动喂方块），
     * 再指派 {@link PillarTestTask}。任务内部会走「随机寻址 → 目的地随机高度搭柱 → 把自己搭的柱子
     * 换成青金石块」三步。
     */
    private static int taskPillar(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        player.giveItemStack(new ItemStack(Items.DIRT, 64));
        CompanionTaskManager.assign(player, new PillarTestTask(player));
        source.sendFeedback(() -> Text.literal("Task assigned: PillarTest (gave 64 dirt as scaffold)"), true);
        return 1;
    }

    /**
     * 树处理测试第一步：走到树旁（寻路阶段）。指派 {@link ReachTreeTask}——它扫描最近的自然树、把它
     * 整理成一条有序结构（低→高、树干优先）、然后走到 base 旁并面向它。<b>不改动方块</b>。
     *
     * <p>{@code CompanionTaskManager.assign} 会<b>同步</b>调用 {@code task.start()}，所以本方法在 assign
     * 返回后即可读到 task 算出的 base 与有序结构，立刻存入 {@link #TREE_TEST_LAYOUTS} 供后两条命令
     * {@code tree_paint} / {@code tree_chop_next} 使用。整条测试链只在这里扫一次树、存一份结构，之后
     * 所有步骤都按这份内存结构的顺序执行，不再重扫世界。若 start 时没找到树，base 为 null，不保存布局。
     */
    private static int taskReachTree(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ReachTreeTask task = new ReachTreeTask();
        CompanionTaskManager.assign(player, task);
        if (task.base() == null) {
            source.sendFeedback(() -> Text.literal("Reach tree: no tree found in range"), true);
            return 0;
        }

        TREE_TEST_LAYOUTS.put(player.getUuid(), new TreeTestLayout(task.base(), task.orderedLogs()));
        source.sendFeedback(() -> Text.literal("Task assigned: ReachTree base=" + task.base().toShortString()
                + " logs=" + task.orderedLogs().size()
                + " (walking to the tree; then run /aicompanion tree_paint)"), true);
        return 1;
    }

    /**
     * 砍树最小闭环测试：先塞一把铁斧进背包（供「挖前换最快工具」测试，一条命令自洽），再指派
     * {@link ReachAndChopTask}。任务内部会走「扫树 → TreeApproach 选落脚格 → 走过去 → 清待清叶 →
     * 换斧 → 视线门控挖掉 base 与其上一块」。任何阶段被打断都可干净恢复（见任务的 stop）。
     */
    private static int taskChopBase(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        player.giveItemStack(new ItemStack(Items.IRON_AXE));
        CompanionTaskManager.assign(player, new ReachAndChopTask());
        source.sendFeedback(() -> Text.literal("Task assigned: ReachAndChop (gave 1 iron axe; walks to base, "
                + "clears leaves, switches to axe, chops base + block above)"), true);
        return 1;
    }

    /**
     * 树处理测试第二步：按内存结构顺序，把每段原木换成对应颜色的羊毛（从低到高、树干优先）。用颜色肉眼
     * 验证「从下往上、树干优先于分叉」的顺序是否正确。<b>保留最顶端那一块原木</b>（不上色），继续供养
     * 树叶、避免上色期间树叶衰减。羊毛只有 16 色，超过 16 段的树用 {@code 序号 % 16} 循环取色。
     */
    private static int treePaint(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        TreeTestLayout layout = TREE_TEST_LAYOUTS.get(player.getUuid());
        if (layout == null) {
            source.sendFeedback(() -> Text.literal("Tree paint: no structure. Run /aicompanion task reach_tree first."), false);
            return 0;
        }

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        List<BlockPos> logs = layout.orderedLogs();
        // 最后一段（结构末元素 = 最高处）保留原木供养树叶，不上色。
        int paintCount = Math.max(0, logs.size() - 1);
        for (int i = 0; i < paintCount; i++) {
            world.setBlockState(logs.get(i), WOOL_PALETTE[i % WOOL_PALETTE.length].getDefaultState());
        }
        source.sendFeedback(() -> Text.literal("Tree paint: painted " + paintCount + "/" + logs.size()
                + " logs low->high (top log kept as wood to feed leaves)"), true);
        return 1;
    }

    /**
     * 树处理测试第三步（可重复）：<b>按内存结构顺序</b>从最底取下一段原木/羊毛，用已验证的
     * {@link CompanionMiningTasks} 挖掉它。每执行一次挖一段，一路能砍到顶（含最后保留的那块原木）。
     * 顺序完全来自 {@code reach_tree} 存下的结构，不重扫世界；砍到哪一段由 layout 里的游标记录。
     *
     * <p>已知边界：同伴交互距离约 4.5 格，本步<b>不搭柱</b>，所以砍到约 4~5 格以上时会够不着、
     * {@link CompanionMiningTasks} 挖不动——这是有意暴露的身体约束，不在本测试范围内解决。
     */
    private static int treeChopNext(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        TreeTestLayout layout = TREE_TEST_LAYOUTS.get(player.getUuid());
        if (layout == null) {
            source.sendFeedback(() -> Text.literal("Tree chop: no structure. Run /aicompanion task reach_tree first."), false);
            return 0;
        }
        if (layout.chopCursor >= layout.orderedLogs().size()) {
            source.sendFeedback(() -> Text.literal("Tree chop: whole structure already chopped."), true);
            TREE_TEST_LAYOUTS.remove(player.getUuid());
            return 0;
        }

        BlockPos target = layout.orderedLogs().get(layout.chopCursor);
        // 视线门控：只有当同伴此刻真能「看见并够得着」目标时才挖，否则拒挖、不推进游标——
        // 补上假客户端缺失的视线校验，杜绝隔着泥土/其它方块挖穿目标。
        if (!hasLineOfSight(player, target)) {
            source.sendFeedback(() -> Text.literal("Tree chop next: target=" + target.toShortString()
                    + " index=" + layout.chopCursor + "/" + layout.orderedLogs().size()
                    + " BLOCKED (out of sight / out of reach) -> refuse to dig"), true);
            return 0;
        }

        CompanionInputController.lookAt(player, target.toCenterPos());
        boolean started = CompanionMiningTasks.start(player, target, source,
                "Tree chop [" + (layout.chopCursor + 1) + "/" + layout.orderedLogs().size() + "]");
        if (started) {
            layout.chopCursor++;
        }
        source.sendFeedback(() -> Text.literal("Tree chop next: target=" + target.toShortString()
                + " index=" + layout.chopCursor + "/" + layout.orderedLogs().size()
                + " started=" + started), true);
        return started ? 1 : 0;
    }

    private static int taskCurrent(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Optional<CompanionTask> current = CompanionTaskManager.current(player);
        source.sendFeedback(() -> Text.literal("Current task: "
                + current.map(CompanionTask::describe).orElse("<none>")), false);
        return 1;
    }

    private static int taskStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        boolean running = CompanionTaskManager.hasActiveTask(player);
        String last = CompanionTaskManager.lastStatus(player).map(Enum::name).orElse("<none>");
        String current = CompanionTaskManager.current(player).map(CompanionTask::describe).orElse("<none>");
        source.sendFeedback(() -> Text.literal(String.format(
                "Task status: running=%s current=%s lastResult=%s", running, current, last)), false);
        return 1;
    }

    private static int taskCancel(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean cancelled = CompanionTaskManager.cancel(companion.get());
        source.sendFeedback(() -> Text.literal(cancelled
                ? "Task cancelled; control returned to Life System."
                : "No active task to cancel."), true);
        return cancelled ? 1 : 0;
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

    private static int moveControlForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.moveControlForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("MoveControl forward started through a ServerPlayerEntity adapter based on vanilla MoveControl."), true);
        return started ? 1 : 0;
    }

    private static int navigationForward(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        boolean started = CompanionBehaviorTestTasks.navigationForward(companion.get(), source);
        source.sendFeedback(() -> Text.literal("Navigation forward started through a vanilla mob pathfinding proxy plus ServerPlayerEntity movement adapter."), true);
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

    private static int selectBlock(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        int beforeSlot = player.getInventory().getSelectedSlot();
        String beforeHand = describeStack(player.getMainHandStack());
        boolean selected = CompanionHotbar.selectBlock(player);
        int afterSlot = player.getInventory().getSelectedSlot();
        String afterHand = describeStack(player.getMainHandStack());
        source.sendFeedback(() -> Text.literal("Select block: found=" + selected
                + " slot " + beforeSlot + " -> " + afterSlot
                + " hand " + beforeHand + " -> " + afterHand), true);
        return selected ? 1 : 0;
    }

    /**
     * 视线门控（委托给 {@link TreeChopSight}，与实际砍树任务共用同一实现，单一事实来源）：同伴当前能否
     * 「看见并够得着」目标方块。度量对齐 vanilla：距离用 {@code canInteractWithBlockAt(target, 1.0)}；视线用
     * 眼睛→目标中心的<b>线段</b>射线（不依赖朝向，避免「发包设朝向同 tick 读不到」的时序坑）。
     *
     * @return true 表示够得着且视线通（命中的正是目标）；false 表示够不着或被别的方块挡住——都不应挖。
     */
    private static boolean hasLineOfSight(ServerPlayerEntity player, BlockPos target) {
        return TreeChopSight.hasLineOfSight(player, target);
    }

    /**
     * 视线门控自检测试台（一条命令、同步自我验证）：在同伴正前方 2 格放金块（目标）、正前方 1 格（正好
     * 挡在眼睛→目标直线上）放泥土（遮挡），然后用<b>与实际砍伐同一个</b> {@link #hasLineOfSight} 函数测两次：
     * <ul>
     *   <li>A 阶段（有遮挡）：门控应判 <b>false</b>（射线命中泥土，看不见目标）——这正是隔墙挖 bug 场景，
     *       必须被拦下。</li>
     *   <li>B 阶段（移除遮挡）：门控应判 <b>true</b>（射线命中金块目标）——看得见就放行。</li>
     * </ul>
     * 两次都符合预期才算通过。测完把金块也清成空气，方便反复测。
     */
    private static int losTest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Direction facing = player.getHorizontalFacing();
        // 目标与遮挡都放在「脚 +1」那一层，使射线接近水平、必然穿过遮挡格。
        BlockPos target = player.getBlockPos().offset(facing, 2).up();
        BlockPos occluder = player.getBlockPos().offset(facing, 1).up();

        world.setBlockState(target, Blocks.GOLD_BLOCK.getDefaultState());

        // A 阶段：放上遮挡，门控应为 false。
        world.setBlockState(occluder, Blocks.DIRT.getDefaultState());
        boolean blockedSees = hasLineOfSight(player, target);

        // B 阶段：移除遮挡，门控应为 true。
        world.setBlockState(occluder, Blocks.AIR.getDefaultState());
        boolean clearSees = hasLineOfSight(player, target);

        // 收尾：清掉目标金块，方便反复测。
        world.setBlockState(target, Blocks.AIR.getDefaultState());

        boolean pass = !blockedSees && clearSees;
        source.sendFeedback(() -> Text.literal("LOS test: A(blocked) sees=" + blockedSees + " (expect false)"
                + " | B(clear) sees=" + clearSees + " (expect true)"
                + " -> " + (pass ? "PASS" : "FAIL")), true);
        return pass ? 1 : 0;
    }

    /**
     * 接近规划「先画后验证」命令：扫描最近的自然树，用 {@link TreeApproach} 为 base 挑一个落脚站格，
     * 并把结果<b>画出来</b>供肉眼验证——不接实际走位与挖掘。
     * <ul>
     *   <li>选中的落脚格 → 绿宝石块。</li>
     *   <li>为够到 base 需先清的本树遮挡叶 → 钻石块（钻石块在一坨真树叶里才看得清标记）。</li>
     *   <li>聊天栏报告：用到的搜索半径、落脚格坐标、需清叶数量；找不到则报「跳过这棵树」。</li>
     * </ul>
     * 纯规划展示：{@link TreeApproach#plan} 只读世界、不改动，本命令只额外画上标记方块。
     */
    private static int treePlanShow(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Optional<TreeDetector.Tree> tree = TreeDetector.findNearestTree(player);
        if (tree.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Tree plan: no tree found in range"), true);
            return 0;
        }

        TreeDetector.Tree t = tree.get();
        Optional<TreeApproach.Approach> approach = TreeApproach.plan(player, t, t.base());
        if (approach.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Tree plan: base=" + t.base().toShortString()
                    + " -> NO foothold within radius; SKIP this tree"), true);
            return 0;
        }

        TreeApproach.Approach a = approach.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        // 待清遮挡叶 → 钻石块（醒目，便于在树叶堆里看清标记）。
        for (BlockPos occ : a.occluders()) {
            world.setBlockState(occ, Blocks.DIAMOND_BLOCK.getDefaultState());
        }
        // 落脚格 → 绿宝石块。
        world.setBlockState(a.foothold(), Blocks.EMERALD_BLOCK.getDefaultState());

        source.sendFeedback(() -> Text.literal("Tree plan: base=" + t.base().toShortString()
                + " foothold=" + a.foothold().toShortString()
                + " radius=" + a.radius()
                + " occluders=" + a.occluders().size()
                + " (emerald=foothold, diamond=leaves to clear)"), true);
        return 1;
    }

    /**
     * 扫描最近的自然树，并用真方块替换它以便肉眼验证、方便多次重复测试：
     * 整簇原木替换成金块，树干根部（base）再覆盖成钻石块。
     * 因为 base 本身也在 logs 里，所以先整簇铺金块、最后单独把 base 覆写为钻石块，顺序不能颠倒。
     * 用真方块而非破坏裂纹，是为了让替换结果持久留在世界里，重复扫描测试时一眼可辨。
     */
    private static int treeScan(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        Optional<ServerPlayerEntity> companion = requireCompanion(source);
        if (companion.isEmpty()) {
            return 0;
        }

        ServerPlayerEntity player = companion.get();
        Optional<TreeDetector.Tree> tree = TreeDetector.findNearestTree(player);
        if (tree.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Tree scan: no tree found in range"), true);
            return 0;
        }

        TreeDetector.Tree t = tree.get();
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        // 先把整簇原木铺成金块。
        for (BlockPos log : t.logs()) {
            world.setBlockState(log, Blocks.GOLD_BLOCK.getDefaultState());
        }
        // 再把树干根部覆写成钻石块（base 也在 logs 里，必须放在铺金块之后）。
        world.setBlockState(t.base(), Blocks.DIAMOND_BLOCK.getDefaultState());
        source.sendFeedback(() -> Text.literal("Tree scan: logs=" + t.logs().size()
                + " base=" + t.base().toShortString()), true);
        return 1;
    }

    /** 命令原点：以命令发起者（玩家）脚下方块为基准，落脚场景都相对它布置，玩家站哪就在哪搭。 */
    private static BlockPos testOrigin(ServerCommandSource source) {
        return BlockPos.ofFloored(source.getPosition());
    }

    /** 清出一块干净测试场：以 origin 为中心，水平 ±R、脚下 -1、向上 +H 全部填空气，脚下一层填石头做地板。 */
    private static void clearArena(ServerWorld world, BlockPos origin, int radius, int height) {
        BlockPos.Mutable c = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // 地板（origin.y - 1）铺石头。
                c.set(origin.getX() + dx, origin.getY() - 1, origin.getZ() + dz);
                world.setBlockState(c, Blocks.STONE.getDefaultState());
                // origin.y .. origin.y+height 清空。
                for (int dy = 0; dy <= height; dy++) {
                    c.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    world.setBlockState(c, Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    /** 在 (base..base+trunk-1) 竖直放一列橡木原木，并在顶部铺一坨自然叶（persistent=false），构成可被 TreeDetector 识别的树。 */
    private static void plantOakTree(ServerWorld world, BlockPos base, int trunk) {
        for (int i = 0; i < trunk; i++) {
            world.setBlockState(base.up(i), Blocks.OAK_LOG.getDefaultState());
        }
        // 树冠：以树顶为中心 3×3×2 铺自然叶（不覆盖树干本身）。
        BlockPos crown = base.up(trunk - 1);
        BlockState leaf = Blocks.OAK_LEAVES.getDefaultState()
                .with(net.minecraft.block.LeavesBlock.PERSISTENT, false)
                .with(net.minecraft.block.LeavesBlock.DISTANCE, 1);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos p = crown.add(dx, dy, dz);
                    if (world.getBlockState(p).isAir()) {
                        world.setBlockState(p, leaf);
                    }
                }
            }
        }
    }

    /** 清场：只清出一块 21×21、高 12 的干净石地空场，不放树。用于手动摆树或反复复位场地。 */
    private static int setupTreeClear(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = testOrigin(source);
        clearArena(world, origin, 10, 12);
        source.sendFeedback(() -> Text.literal("Tree test: cleared 21x21 arena (stone floor) at "
                + origin.toShortString() + ", h=12. Place a tree, then /aicompanion spawn + task chop_base."), true);
        return 1;
    }

    /** 标准树场景：清场 + 在玩家正前方 3 格放一棵 4 段橡木树。最基础的最小闭环验证（平地、无干扰）。 */
    private static int setupTreeStandard(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = testOrigin(source);
        clearArena(world, origin, 10, 12);
        Direction facing = source.getEntity() instanceof ServerPlayerEntity p ? p.getHorizontalFacing() : Direction.NORTH;
        BlockPos base = origin.offset(facing, 3);
        plantOakTree(world, base, 4);
        source.sendFeedback(() -> Text.literal("Tree test STANDARD: cleared arena + 4-log oak at base "
                + base.toShortString() + " (3 blocks " + facing + "). spawn + task chop_base."), true);
        return 1;
    }

    /**
     * 台阶树场景（复现线下 bug）：清场后，前半区（facing 方向 0..4 格）比后半区高 1 格，形成台阶立面；
     * 树种在台阶高侧边缘。用于验证「同伴不会绕到台阶低侧被泥土+树干楔死」。
     */
    private static int setupTreeStep(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = testOrigin(source);
        clearArena(world, origin, 10, 12);
        Direction facing = source.getEntity() instanceof ServerPlayerEntity p ? p.getHorizontalFacing() : Direction.NORTH;

        // 高台：facing 方向 2..5 格、左右 ±3 抬高 1 格（在石地板上再铺一层泥土做台面）。
        BlockPos.Mutable c = new BlockPos.Mutable();
        Direction right = facing.rotateYClockwise();
        for (int f = 2; f <= 5; f++) {
            for (int s = -3; s <= 3; s++) {
                BlockPos col = origin.offset(facing, f).offset(right, s);
                c.set(col);
                world.setBlockState(c, Blocks.DIRT.getDefaultState()); // 台面（origin.y 层）
            }
        }
        // 树种在高台中部边缘（facing 3 格），base 在台面之上（origin.y + 1）。
        BlockPos base = origin.offset(facing, 3).up();
        plantOakTree(world, base, 4);
        source.sendFeedback(() -> Text.literal("Tree test STEP: 1-block step (high side " + facing
                + " 2..5), tree on high edge, base " + base.toShortString()
                + ". Reproduces the wedge bug. spawn + task chop_base."), true);
        return 1;
    }

    /**
     * 杂草树场景（复现 DDA/门控形状分歧 bug）：标准树 + 在 base 周围地面铺草/花等 noCollision 植被。
     * 用于验证「脚边草不再让门控恒 false 导致 base 砍不动」。
     */
    private static int setupTreeGrass(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        BlockPos origin = testOrigin(source);
        clearArena(world, origin, 10, 12);
        Direction facing = source.getEntity() instanceof ServerPlayerEntity p ? p.getHorizontalFacing() : Direction.NORTH;
        BlockPos base = origin.offset(facing, 3);
        plantOakTree(world, base, 4);
        // base 四周一圈地面铺短草（noCollision，OUTLINE 非空——正是分歧触发物）。
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos g = base.offset(d);
            if (world.getBlockState(g).isAir()) {
                world.setBlockState(g, Blocks.SHORT_GRASS.getDefaultState());
            }
        }
        source.sendFeedback(() -> Text.literal("Tree test GRASS: standard tree + short_grass ring around base "
                + base.toShortString() + " (tests DDA/gate shape divergence). spawn + task chop_base."), true);
        return 1;
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

    /**
     * 树处理测试布局：base（树干根部）+ 有序原木结构（低→高、树干优先）+ 砍伐游标。
     * 由 reach_tree 存入，tree_paint 读取上色，tree_chop_next 按游标逐段砍并推进游标。
     * 用可变类（非 record）因为 {@link #chopCursor} 需要跨多次 tree_chop_next 命令累加。
     */
    private static final class TreeTestLayout {
        private final BlockPos base;
        private final List<BlockPos> orderedLogs;
        /** 下一段要砍的结构下标（0=最底）；每成功发起一段砍伐后 +1。 */
        private int chopCursor;

        private TreeTestLayout(BlockPos base, List<BlockPos> orderedLogs) {
            this.base = base;
            this.orderedLogs = orderedLogs;
        }

        private BlockPos base() {
            return base;
        }

        private List<BlockPos> orderedLogs() {
            return orderedLogs;
        }
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
