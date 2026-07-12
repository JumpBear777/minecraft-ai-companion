package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * 「搭柱子」端到端测试任务：一条命令跑完三步——寻址 → 在目的地搭柱 → 把自己搭的柱子替换掉。
 *
 * <p>它的价值是打通并验证整条链路，而不是实现一个成品行为，所以每一步的“决策”部分（去哪、搭多高、
 * 何时停）目前都用<em>占位策略</em>（随机），并被单独隔离成私有方法，注释标明「先随机，后替换为真实策略」。
 * 真正干活的部分全部复用已验证的适配器，本任务<b>不自带</b>任何新的物理/放置/寻路逻辑：
 * <ul>
 *   <li>寻址走 {@link CompanionNavigator}（原版寻路代理 + 服务端行走输入）；</li>
 *   <li>选脚手架方块走 {@link CompanionHotbar#selectScaffoldBlock}（原版选/换物路径）；</li>
 *   <li>搭柱走 {@link CompanionPillar}（原版跳跃 + 放置，时机已实测验证）。</li>
 * </ul>
 *
 * <p><b>「记住自己搭的方块」</b>——这是本任务的关键设计点，也是它区别于「事后扫描世界」的地方：
 * 原木/泥土这类方块在世界里没有任何「谁放的」标记，放置后与自然生成的完全无法区分，所以想要之后
 * 精确地找回、回收自己搭的那些方块，唯一可靠的办法是<b>在放置的当下把坐标记进内存</b>。这里用一个
 * <b>栈</b>（{@link Deque} 当 LIFO）保存每一层实际放下的位置：搭柱是自底向上，回收/替换则从栈顶
 * （最高、最后放的）往下弹出，天然的后进先出顺序正好对应「先拆最上面那块」。这份记录是<em>任务的
 * 执行状态</em>，只活在本任务实例里，绝不放进任何无状态工具类。
 *
 * <p>这份内存记录终究只是运行时快照：柱子可能被别的东西破坏、或与世界状态脱节，所以替换前会先
 * 校验该位置现在是否仍是我放的那种脚手架方块，对不上就跳过——不盲信记忆。
 */
public final class PillarTestTask implements CompanionTask {

    /** 随机寻址时，水平偏移的最大格数（以任务开始时同伴所在位置为锚）。 */
    private static final int WANDER_RADIUS = 8;
    /** 随机寻址允许的最小水平偏移，避免随机到脚下、根本不用走。 */
    private static final int WANDER_MIN_RADIUS = 3;
    /** 走到目标点时接受的到达判定距离（格），供 {@code pathTo} 的 reachDistance 使用。 */
    private static final int NAVIGATE_REACH_DISTANCE = 1;
    /** 随机柱高的下限（层）。 */
    private static final int MIN_PILLAR_HEIGHT = 3;
    /** 随机柱高的上限（层，含）。 */
    private static final int MAX_PILLAR_HEIGHT = 6;
    /** 寻址阶段的整体超时（tick）；到时就地转入搭柱，避免寻路问题卡死测试。 */
    private static final int NAVIGATE_TIMEOUT_TICKS = 200;
    /** 替换阶段每 tick 处理一块，做出「逐块点亮」的可见效果；这是它的节流间隔。 */
    private static final int REPLACE_INTERVAL_TICKS = 2;

    /** 三步流程。 */
    private enum Phase { NAVIGATE, PILLAR, REPLACE, DONE }

    private final Random random;

    private Phase phase = Phase.NAVIGATE;
    private CompanionNavigator navigator;
    private CompanionPillar pillar;

    /** 本任务实际放下的每一层柱子方块坐标，自底向上入栈；替换阶段自顶向下弹出。 */
    private final Deque<BlockPos> placed = new ArrayDeque<>();

    /** 随机选定的目标点（寻址阶段的去向）。 */
    private BlockPos navigateTarget;
    /** 随机选定的柱高（层）。 */
    private int pillarHeight;
    /** 寻址阶段已耗 tick，用于超时兜底。 */
    private int navigateTicks;
    /** 替换阶段的节流计时。 */
    private int replaceTimer;
    /** 进入搭柱阶段时选不到脚手架方块则置真，让 {@link #tickPillar} 收尾为失败。 */
    private boolean failFast;

    public PillarTestTask(ServerPlayerEntity companion) {
        // 随机种子混入 UUID 与 nanoTime，和 Life System 的做法一致，保证每次测试的随机性。
        this.random = new Random(companion.getUuid().getLeastSignificantBits() ^ System.nanoTime());
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);
        this.pillar = new CompanionPillar(companion);
        // 第一步的决策：随机挑一个附近的水平目标点（先随机，后续可替换为真实寻址策略）。
        this.navigateTarget = pickRandomTarget(companion);
        // 第二步的决策：随机挑一个柱高（先随机，后续可替换为真实停止条件）。
        this.pillarHeight = MIN_PILLAR_HEIGHT + random.nextInt(MAX_PILLAR_HEIGHT - MIN_PILLAR_HEIGHT + 1);
        this.navigator.pathTo(navigateTarget, NAVIGATE_REACH_DISTANCE);
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        return switch (phase) {
            case NAVIGATE -> tickNavigate(companion);
            case PILLAR -> tickPillar(companion);
            case REPLACE -> tickReplace(companion);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    /**
     * 第一步：走向随机目标点。到达、无路、被卡、或超时，都进入搭柱阶段——本测试的核心是搭柱，
     * 寻址只是把同伴带到一个新地方，因此寻址失败不应让整个测试失败。
     */
    private TaskStatus tickNavigate(ServerPlayerEntity companion) {
        CompanionNavigator.NavResult result = navigator.tick();
        boolean arrivedOrGaveUp = result == CompanionNavigator.NavResult.ARRIVED
                || result == CompanionNavigator.NavResult.NO_PATH
                || result == CompanionNavigator.NavResult.STUCK
                || result == CompanionNavigator.NavResult.IDLE;
        if (arrivedOrGaveUp || ++navigateTicks >= NAVIGATE_TIMEOUT_TICKS) {
            navigator.stop();
            enterPillarPhase(companion);
        }
        return TaskStatus.RUNNING;
    }

    /** 进入搭柱阶段：先把泥土之类的满方块选进主手，选不到则整个测试失败。 */
    private void enterPillarPhase(ServerPlayerEntity companion) {
        if (!CompanionHotbar.selectScaffoldBlock(companion)) {
            phase = Phase.DONE;
            failFast = true;
            return;
        }
        pillar.begin(pillarHeight);
        phase = Phase.PILLAR;
    }

    /**
     * 第二步：搭柱。每完成一层就把该层实际放下的坐标记进栈（读 {@link CompanionPillar#lastPlacedPos()}，
     * 它捕获的是跳跃前的真实放置位，不是落地后可能横移的推断值）。
     */
    private TaskStatus tickPillar(ServerPlayerEntity companion) {
        if (failFast) {
            return TaskStatus.FAILURE;
        }

        CompanionPillar.PillarResult result = pillar.tick();
        // 柱子逐层升高，lastPlacedPos 每完成一层就指向更高的新坐标；与栈顶不同即说明有新层落定，
        // 入栈。柱子自底向上，坐标不会重复，只需和栈顶比较即可避免重复入栈。
        BlockPos last = pillar.lastPlacedPos();
        if (last != null && !last.equals(placed.peek())) {
            placed.push(last);
        }

        return switch (result) {
            case RISING, IDLE -> TaskStatus.RUNNING;
            case DONE -> {
                enterReplacePhase();
                yield TaskStatus.RUNNING;
            }
            // 中途放置失败：已经搭好的部分照样进入替换阶段，好让测试能看到「记录了几层就替换几层」。
            case FAILED -> {
                if (placed.isEmpty()) {
                    yield TaskStatus.FAILURE; // 一层都没搭起来，直接失败
                }
                enterReplacePhase();
                yield TaskStatus.RUNNING;
            }
        };
    }

    private void enterReplacePhase() {
        pillar.stop();
        phase = Phase.REPLACE;
    }

    /**
     * 第三步：把自己搭的柱子换成青金石块。从栈顶（最高、最后放的）往下逐块替换，每 {@link
     * #REPLACE_INTERVAL_TICKS} tick 换一块，做出自顶向下逐块点亮的可见效果。替换前校验该位置
     * 现在是否仍是我放下的脚手架方块，对不上（被破坏、被别的东西改动）就跳过——不盲信记忆。
     */
    private TaskStatus tickReplace(ServerPlayerEntity companion) {
        if (!(companion.getEntityWorld() instanceof ServerWorld world)) {
            return TaskStatus.FAILURE;
        }
        if (placed.isEmpty()) {
            phase = Phase.DONE;
            return TaskStatus.SUCCESS;
        }
        if (--replaceTimer > 0) {
            return TaskStatus.RUNNING;
        }
        replaceTimer = REPLACE_INTERVAL_TICKS;

        BlockPos pos = placed.pop();
        // 只有当该位置仍是「非空气的固体方块」时才替换。柱子用的泥土等满方块放下后是固体；若已被
        // 破坏成空气，则记忆与世界脱节，跳过它。这里用 isAir 作最小校验即可满足测试目的。
        if (!world.getBlockState(pos).isAir()) {
            world.setBlockState(pos, Blocks.LAPIS_BLOCK.getDefaultState());
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
        if (pillar != null) {
            pillar.stop();
        }
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        return "PillarTest(" + phase + ", height=" + pillarHeight + ", placed=" + placed.size() + ")";
    }

    /**
     * 占位寻址策略：以同伴当前脚下为锚，在 [{@link #WANDER_MIN_RADIUS}, {@link #WANDER_RADIUS}] 的
     * 水平环形范围内随机取一点，Y 用同伴当前 Y。<b>先随机，后续可替换为真实寻址策略</b>（例如走向
     * 某个待建造点）。
     *
     *Spawned or moved companion: AICompanion
     * Tree test: cleared 21x21 arena (stone floor) at -9, 71, -63.
     * h=12. Place a tree, then /aicompanion spaun + task chop_base
     * Tree test STEP: 1-block step (high side west 2 .. 5), tree on high
     * edge, base -12, 72, -63. Rieproduces the wedge bug. spaun +
     * task chop_base.
     * [ChopDebug] start OK: base =- 12, 72, -63 foothold =- 14, 72, -63
     * occluders=0
     * Task assigned: ReachAndChop (gave 1 iron axe; walks to base,
     * clears leaves, switches to axe, chops base + block above)
     * [ChopDebug] NAVIGATE done (ARRIVEO) -> POSITION, target
     * foothold =- 14, 72, -63
     * [ChopDebug] POSITION ok (oan reach base) at -14, 72, -63 ->
     * CLEAR, occluders=0
     * [ChopDebug] CHOP start dig -12, 72, -63
     * [ChopDebug] CHOP start dig -12, 73, -63
     *
     *
     *
     */
    private BlockPos pickRandomTarget(ServerPlayerEntity companion) {
        BlockPos origin = companion.getBlockPos();
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = WANDER_MIN_RADIUS + random.nextDouble() * (WANDER_RADIUS - WANDER_MIN_RADIUS);
        int dx = (int) Math.round(Math.cos(angle) * distance);
        int dz = (int) Math.round(Math.sin(angle) * distance);
        return origin.add(dx, 0, dz);
    }
}
