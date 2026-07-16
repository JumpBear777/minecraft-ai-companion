package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * <b>安全下挖到目标层</b>：像真玩家挖阶梯一样，一级一级往前下方挖，直到脚部达到 {@code targetY}。这是挖矿能力
 * 的第一阶段——只负责「安全地下去」，到层后的鱼骨挖矿是第二阶段的独立任务。
 *
 * <h2>每一级的动作：挖前方一列 → 向前走一步（重力自然降一格）</h2>
 * 同伴脚在 {@code feet}、朝 {@code dir}。每一级挖<b>前方那一列</b>的三格（相对当前脚位）：
 * <ul>
 *   <li>{@code upper} = 前方、比头再高一格（头部通行净空）</li>
 *   <li>{@code middle} = 前方、与脚同高</li>
 *   <li>{@code lower} = 前方、比脚低一格（走过去后落脚的那一格）</li>
 * </ul>
 * 挖开后向前走一步：同伴走进前方一列，因 {@code lower} 已空，靠 vanilla 重力<b>垂直落一格</b>，落到实心地板
 * {@code floor}（= 前方低两格）上，新脚位变为 {@code lower}。等价于「前进一格 + 下降一格」，但动作是「平走 +
 * 重力下落」，没有不可靠的斜向移动。
 *
 * <p>挖的格数会自然呈现 <b>1 → 2 → 3 → 3 …</b>：平地起步时 {@code upper}/{@code middle} 本就是天空（空气），
 * {@link #tickDig} 遇空气直接跳过，所以第 1 级只实挖 {@code lower}、第 2 级挖 {@code middle}+{@code lower}、
 * 进入地下后每级挖满三格。无需为前两级特判。
 *
 * <h2>行为契约</h2>
 * <ul>
 *   <li><b>成功</b>（{@link TaskStatus#SUCCESS}）：脚部 Y ≤ {@code targetY}。</li>
 *   <li><b>遇障碍先躲避而非停下</b>：动手前发现下一级会破入矿洞/岩浆/水/头顶下落方块时，<b>不</b>立即失败，而是
 *       发起一次横向躲避——<b>先探当前朝向的左侧、左侧不能挖再探右侧</b>，选一个能安全平走 {@link #EVADE_STEPS}
 *       格的方向横移过去，然后<b>拐弯</b>（下挖主方向更新为该侧移方向）以新朝向继续挖；途中若再遇障碍，就再躲
 *       一次。左右始终相对<b>当前朝向</b>算，拐弯后不会把「正后方」当一侧而原地前后弹。</li>
 *   <li><b>失败</b>（{@link TaskStatus#FAILURE}）：左右两侧都无法安全横移；或连续躲避达到
 *       {@link #MAX_CONSECUTIVE_EVADES} 次仍没能成功下挖任何一级（被大空腔/流体包围、避无可避）；或挖不动/走卡住。
 *       <b>绝不</b>破进空腔/流体。</li>
 * </ul>
 * 「遇障碍横向躲避、避无可避才停」是当前选定策略（早期版本是「遇空腔即停」，现改为先绕行）。
 *
 * <h2>唯一判据 {@link MiningHazard}，几何在本任务；复用已验证适配器，不自写移动/挖掘</h2>
 * 判据（流体/空腔/天空）由 {@link MiningHazard} 用 vanilla 状态判定；几何由本任务负责。挖 =
 * {@link CompanionMiningTasks}；选工具 = {@link CompanionHotbar}；走 = {@link CompanionInputController}。
 * 淹水兜底由全局 {@code CompanionWaterSafety} 负责。
 */
public final class SafeDescentTask implements CompanionTask {

    /** 单块挖掘的等待上限（tick）：超时即判这块挖不动，停整段。 */
    private static final int DIG_TIMEOUT_TICKS = 320;
    /** 向前走进下一级并落地的等待上限（tick）：走不过去/落不下去就判失败，不无限顶。 */
    private static final int WALK_TIMEOUT_TICKS = 80;
    /** 规划下一级前等待「站稳落地」的上限（tick）：永不落地（卡半空/水中）即判失败，不无限挂起。 */
    private static final int SETTLE_TIMEOUT_TICKS = 100;
    /**
     * 落地站稳后、重新规划下一级前的<b>延迟</b>（tick，约 1 秒）。同伴挖完走进/下落后，身体位置要一小段时间才
     * 真正稳定；若立刻用当时的脚位规划，会读到「半格没跟上」的错位坐标，导致下一级 middle 落在错误的格子。
     * 等它站稳一秒再读脚位，坐标才可靠。
     */
    private static final int PLAN_SETTLE_DELAY_TICKS = 20;
    /**
     * 一次躲避横移的格数：遇障碍时不停下，而是先向左（不行再向右）水平前进这么多格，避开障碍后继续下挖。
     * 3 格足以绕过障碍那一列的侧边。
     */
    private static final int EVADE_STEPS = 3;
    /**
     * 连续躲避的次数上限：每躲避一次而没能成功下挖任何一级就 +1，成功下挖一级即归零。达到上限说明被大空腔/
     * 流体包围陷入死循环，停下报失败，避免同伴无限横向跑飞。
     */
    private static final int MAX_CONSECUTIVE_EVADES = 8;
    /**
     * 规划时向前「看几级」的深度：除当前级外，再沿阶梯投影未来这么多级的落脚地板，提前发现临近的矿洞/流体。
     * 提前一两级触发躲避时，脚位离洞还较远、两侧多半仍是实心地，躲避才有成功的余地——真拖到洞的正上方边缘，
     * 两侧往往也已是洞，躲无可躲（实测 L83「blocked-both-sides」失败的根因）。深挖实心岩层时投影落脚点都是
     * 实心，不会误报，只有真有空腔/流体才触发。
     */
    private static final int LOOKAHEAD_LEVELS = 2;

    private enum Phase {
        PLAN, DIG_UPPER, DIG_MIDDLE, DIG_LOWER, WALK,
        EVADE_DIG_UPPER, EVADE_DIG_SIDE, EVADE_WALK,
        DONE
    }

    /** 目标层：脚部 Y 达到（≤）此值即成功。 */
    private final int targetY;
    /** 可选诊断输出；仅命令发起者可见。null 时静默。 */
    private final ServerCommandSource diagSink;

    /**
     * <b>当前朝向 = 下挖主方向</b>（start 时按朝向初始化）。「左/右」始终相对它算（左=逆时针、右=顺时针）。
     * <b>侧移躲避完成后会更新为实际侧移方向（拐弯）</b>，使阶梯朝新方向继续挖下去——这样下次躲避的左右基于
     * 新朝向，永远不会把「正后方」当成一侧而原地前后弹（实测 water 处 west↔east 反复弹的根因）。
     */
    private Direction digDir;
    private Phase phase = Phase.PLAN;

    /** start 阶段就判定的终态；非空则第一个 tick 直接返回它。 */
    private TaskStatus terminal;

    /** 当前这一级前方一列的三格 + 落脚地板（见类注释）。 */
    private BlockPos upper;
    private BlockPos middle;
    private BlockPos lower;
    private BlockPos floor;

    private int digTicks;
    private int walkTicks;
    private int settleTicks;
    /** 落地后已稳定站立的连续 tick 数；达到 {@link #PLAN_SETTLE_DELAY_TICKS} 才规划下一级。 */
    private int planDelayTicks;
    private int levelsDug;
    private String failReason;

    /** 本次躲避锁定的横移方向（左或右），由 {@link #beginEvade} 选定。 */
    private Direction evadeDir;
    /** 本次躲避已完成的横移格数（0..{@link #EVADE_STEPS}）；达到 {@link #EVADE_STEPS} 即结束躲避回 PLAN。 */
    private int evadeStep;
    /** 当前躲避格待挖的两格：侧方头顶净空、侧方与脚同高。侧方脚下地板不挖（踩着平走）。 */
    private BlockPos evadeUp;
    private BlockPos evadeSide;
    /** 连续躲避计数：见 {@link #MAX_CONSECUTIVE_EVADES}。成功下挖一级即归零。 */
    private int consecutiveEvades;
    /**
     * 下一次躲避<b>优先尝试</b>的一侧：true=先试左、false=先试右。每次发起躲避后翻转，使连续躲避来回摆动，
     * 不会一路往同一侧漂移。注意「左/右」是相对下挖方向 {@link #digDir} 算的（左=逆时针、右=顺时针），
     * 由于 digDir 全程锁定，某一侧恒对应同一罗盘方向。
     */
    private boolean evadeStartLeft = true;

    public SafeDescentTask(int targetY, ServerCommandSource diagSink) {
        this.targetY = targetY;
        this.diagSink = diagSink;
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        if (companion.getBlockY() <= targetY) {
            terminal = TaskStatus.SUCCESS;
            diag("start: already at/below target Y=" + targetY + " (feet Y=" + companion.getBlockY() + ")");
            return;
        }
        this.digDir = companion.getHorizontalFacing();
        diag("start OK: descending " + digDir + " to Y=" + targetY + " from feet Y=" + companion.getBlockY());
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (terminal != null) {
            return terminal;
        }
        return switch (phase) {
            case PLAN -> tickPlan(companion);
            case DIG_UPPER -> tickDig(companion, upper, Phase.DIG_MIDDLE);
            case DIG_MIDDLE -> tickDig(companion, middle, Phase.DIG_LOWER);
            case DIG_LOWER -> tickDig(companion, lower, Phase.WALK);
            case WALK -> tickWalk(companion);
            case EVADE_DIG_UPPER -> tickDig(companion, evadeUp, Phase.EVADE_DIG_SIDE);
            case EVADE_DIG_SIDE -> tickDig(companion, evadeSide, Phase.EVADE_WALK);
            case EVADE_WALK -> tickEvadeWalk(companion);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    /**
     * 规划并安全校验下一级：算出前方一列三格与落脚地板，做保守校验。任一不通过 → 停、记录、失败（保守优先：
     * 遇矿洞/流体一律停下报告）。安全则进入挖掘阶段。
     */
    private TaskStatus tickPlan(ServerPlayerEntity companion) {
        // 必须站稳才规划下一级：移动途中或半空中读到的脚位不可靠。永不落地时超时判失败，不无限挂起。
        if (!companion.isOnGround()) {
            planDelayTicks = 0;
            if (++settleTicks >= SETTLE_TIMEOUT_TICKS) {
                return fail("not-on-ground", null);
            }
            return TaskStatus.RUNNING;
        }
        settleTicks = 0;
        // 落地后再等一小段时间让身体位置真正稳定，避免用「半格没跟上」的错位脚位规划下一级。
        if (++planDelayTicks < PLAN_SETTLE_DELAY_TICKS) {
            return TaskStatus.RUNNING;
        }
        planDelayTicks = 0;
        if (companion.getBlockY() <= targetY) {
            phase = Phase.DONE;
            diag("DONE: reached target Y=" + targetY + " after " + levelsDug + " levels");
            return TaskStatus.SUCCESS;
        }

        BlockPos feet = companion.getBlockPos();
        BlockPos front = feet.offset(digDir);
        middle = front;              // 前方、与脚同高
        upper = front.up();          // 前方、头顶净空
        lower = front.down();        // 前方、低一格：走过去后的落脚格
        floor = front.down(2);       // 前方、低两格：落脚踩的实心地板

        MiningHazard.Finding hazard = checkSafety(companion.getEntityWorld());
        if (!hazard.isSafe()) {
            return beginEvade(companion, hazard.hazard() + " at " + hazard.pos().toShortString());
        }
        BlockPos fallingBlocker = firstFallingHazard(companion.getEntityWorld());
        if (fallingBlocker != null) {
            return beginEvade(companion, "FALLING at " + fallingBlocker.toShortString());
        }
        // 前瞻：当前级安全，但沿阶梯往前 LOOKAHEAD_LEVELS 级若有临近的洞/流体，也提前躲避——趁脚位离洞还远、
        // 两侧仍实心时绕开，而不是拖到洞的正上方边缘才发现（那时两侧多半也已是洞，躲无可躲）。
        MiningHazard.Finding ahead = checkLookahead(companion.getEntityWorld());
        if (ahead != null) {
            return beginEvade(companion, "ahead " + ahead.hazard() + " at " + ahead.pos().toShortString());
        }

        digTicks = 0;
        phase = Phase.DIG_UPPER;
        diag("plan L" + (levelsDug + 1) + " feet=" + feet.toShortString()
                + " upper=" + upper.toShortString() + " middle=" + middle.toShortString()
                + " lower=" + lower.toShortString() + " floor=" + floor.toShortString());
        return TaskStatus.RUNNING;
    }

    /**
     * 下一级的保守安全校验（几何在本任务、判据在 {@link MiningHazard}）：
     * <ol>
     *   <li><b>落脚地板</b>：{@code floor} 必须实心（走过去的落脚点）。是流体 → LAVA/WATER；是空气且在地表以下
     *       → CAVE（落脚会踏空/进洞）。这是「不进矿洞」最本质的一条——脚下有实心地板就不会掉进洞。
     *       地表以上的天空不算（平地起步不误报）。</li>
     *   <li><b>流体涌入</b>：对将挖开的三格 {@code upper}/{@code middle}/{@code lower}，查各自<b>自身</b> + UP +
     *       四水平洪流方向邻居（借鉴 Numen/vanilla）有无流体——挖开会灌进来（含正上方）。查自身可挡住落脚格
     *       {@code lower} 本身即流体源的情形（只查邻居会漏）。</li>
     * </ol>
     * 返回首个非安全发现，或 {@link MiningHazard.Finding#SAFE}。将挖的三格<b>自身</b>不做「空气=CAVE」判定——
     * 阶梯前格在地表起伏处本就可能是天空，交由挖掘阶段（空气则直接跳过）。<b>不</b>再检查侧壁空腔：旁边一格
     * 有空气并不危险，却会让起伏/被挖过的地形上连第一步都开不了工（实测「连开始都开不了」的原因）。
     */
    private MiningHazard.Finding checkSafety(World world) {
        // 1) 落脚地板必须实心（流体/地下空腔都不行）——不进矿洞的本质约束。
        MiningHazard.Hazard floorFluid = MiningHazard.fluidHazard(world, floor);
        if (floorFluid != MiningHazard.Hazard.SAFE) {
            return new MiningHazard.Finding(floorFluid, floor.toImmutable());
        }
        if (world.getBlockState(floor).isAir() && !MiningHazard.isOpenSky(world, floor)) {
            return new MiningHazard.Finding(MiningHazard.Hazard.CAVE, floor.toImmutable());
        }

        // 2) 挖开三格会否引流体涌入：查各自自身 + UP + 四水平洪流方向邻居。查自身关键——lower 是走过去的落脚格，
        //    若它本身是流体源，只查邻居会漏掉。
        for (BlockPos dig : new BlockPos[]{upper, middle, lower}) {
            MiningHazard.Hazard self = MiningHazard.fluidHazard(world, dig);
            if (self != MiningHazard.Hazard.SAFE) {
                return new MiningHazard.Finding(self, dig.toImmutable());
            }
            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) {
                    continue; // 向下不会因破这一格而涌入
                }
                BlockPos neighbor = dig.offset(dir);
                MiningHazard.Hazard fluid = MiningHazard.fluidHazard(world, neighbor);
                if (fluid != MiningHazard.Hazard.SAFE) {
                    return new MiningHazard.Finding(fluid, neighbor.toImmutable());
                }
            }
        }

        return MiningHazard.Finding.SAFE;
    }

    /**
     * 前瞻校验：当前级已判安全后，沿阶梯向前投影未来 {@link #LOOKAHEAD_LEVELS} 级，检查<b>各级的落脚地板</b>是否
     * 临近矿洞/流体。阶梯每级「前进一格 + 下降一格」，故第 k 级（k≥1）的落脚地板 = 当前 {@code floor} 再沿
     * {@code digDir} 前移 k 格、下移 k 格。只要有一级落脚地板是流体、或是地下空腔（空气且非天空），就返回该发现，
     * 让规划提前躲避。
     *
     * <p>只查<b>落脚地板</b>一格（不查涌入邻居）：前瞻是为了「早点看见洞、趁两侧还实心时绕开」，落脚地板是否
     * 塌空是洞最本质的信号；流体涌入这类细致校验留给真正走到那一级时的 {@link #checkSafety}，避免前瞻过度保守
     * 在正常起伏地形上频繁误触发。返回首个非安全级，全部安全则 null。
     */
    private MiningHazard.Finding checkLookahead(World world) {
        for (int k = 1; k <= LOOKAHEAD_LEVELS; k++) {
            BlockPos aheadFloor = floor.offset(digDir, k).down(k);
            MiningHazard.Hazard fluid = MiningHazard.fluidHazard(world, aheadFloor);
            if (fluid != MiningHazard.Hazard.SAFE) {
                return new MiningHazard.Finding(fluid, aheadFloor.toImmutable());
            }
            if (world.getBlockState(aheadFloor).isAir() && !MiningHazard.isOpenSky(world, aheadFloor)) {
                return new MiningHazard.Finding(MiningHazard.Hazard.CAVE, aheadFloor.toImmutable());
            }
        }
        return null;
    }

    /**
     * @return 最顶待挖格 {@code upper} 正上方的下落方块位置，或 null。三格待挖是竖直连续一列，下面两格正上方就是
     *         待挖格本身（无需检），唯一「挖开后失去支撑而下落」的是这一列最顶端之上的方块。
     */
    private BlockPos firstFallingHazard(World world) {
        BlockPos above = upper.up();
        BlockState state = world.getBlockState(above);
        return state.getBlock() instanceof FallingBlock ? above.toImmutable() : null;
    }

    /**
     * 异步挖一格：方块已是空气 → 本格完成，进下一相位。否则若无挖掘在跑就启动一个，有就等它、并盯着方块状态。
     *
     * <p><b>推进判据只用「方块是否变空气」，不用 {@link CompanionMiningTasks#pollResult} 的 BROKEN</b>。原实现
     * 用 pollResult 判完成，但 CompanionMiningTasks 完成后把结果留在一个全局槽里等人来取；若某格挖完那一 tick
     * 走了「已空气直接跳过」的分支、没有取走结果，这个 BROKEN 就会滞留，被<b>下一格</b>的 pollResult 误取，导致
     * 下一格明明是实心却被当成挖好而跳过（实机「不挖中间那格」的根因）。改为只认世界真实状态：只要目标格还不是
     * 空气就继续挖，FAILED/超时才失败。启动新挖掘前先清掉可能滞留的旧结果。
     */
    private TaskStatus tickDig(ServerPlayerEntity companion, BlockPos pos, Phase next) {
        World world = companion.getEntityWorld();
        if (world.getBlockState(pos).isAir()) {
            // 本格已空（挖掉了、或本就是空气）：清掉这次挖掘可能留下的结果，避免污染下一格，然后推进。
            CompanionMiningTasks.pollResult(companion);
            phase = next;
            return TaskStatus.RUNNING;
        }

        if (CompanionMiningTasks.hasTask(companion)) {
            CompanionInputController.lookAt(companion, pos.toCenterPos());
            if (++digTicks >= DIG_TIMEOUT_TICKS) {
                CompanionMiningTasks.cancel(companion);
                return fail("dig-timeout at " + pos.toShortString(), null);
            }
            return TaskStatus.RUNNING;
        }

        // 没有挖掘在跑，但目标还不是空气：可能是上一次挖掘失败了（被拒/够不到），也可能还没开始。
        // 先看有没有失败结果——有就是这一格挖不动。
        if (CompanionMiningTasks.pollResult(companion) == CompanionMiningTasks.MiningResult.FAILED) {
            return fail("mining-failed at " + pos.toShortString(), null);
        }

        // 启动一次新挖掘：选工具、瞄准、开挖。
        CompanionInputController.lookAt(companion, pos.toCenterPos());
        CompanionHotbar.selectBestToolFor(companion, world.getBlockState(pos));
        if (!CompanionMiningTasks.start(companion, pos)) {
            return fail("could-not-start-mining at " + pos.toShortString(), null);
        }
        digTicks = 0;
        return TaskStatus.RUNNING;
    }

    /**
     * 前方一列已挖开：向前走一步。同伴走进前方一列后，因 {@code lower} 已空，靠 vanilla 重力垂直落到
     * {@code floor} 上，新脚位 = {@code lower}。
     *
     * <p>到达判定要求同伴的<b>方块坐标水平分量确实等于 {@code lower}</b>（真正走进了前方那一列），而不是仅凭
     * 「已降到 lower 高度」——否则同伴刚一下落、水平还没进前方格就被判到达，于是<b>原地降一格没有前进</b>，下一级
     * 的 {@code front} 便与本级重叠、middle 落在已挖空处被跳过（实机「第2级没挖中间那格直接走」的根因）。
     */
    private TaskStatus tickWalk(ServerPlayerEntity companion) {
        BlockPos pos = companion.getBlockPos();
        boolean enteredColumn = pos.getX() == lower.getX() && pos.getZ() == lower.getZ();
        boolean dropped = pos.getY() <= lower.getY();
        if (enteredColumn && dropped && companion.isOnGround()) {
            CompanionInputController.releaseInput(companion);
            levelsDug++;
            consecutiveEvades = 0; // 成功下挖一级，躲避死循环计数归零
            walkTicks = 0;
            planDelayTicks = 0;
            phase = Phase.PLAN;
            return TaskStatus.RUNNING;
        }

        if (++walkTicks >= WALK_TIMEOUT_TICKS) {
            CompanionInputController.releaseInput(companion);
            diag("  WALK timeout: pos=" + String.format("%.2f,%.2f,%.2f",
                    companion.getX(), companion.getY(), companion.getZ())
                    + " onGround=" + companion.isOnGround()
                    + " target lower=" + lower.toShortString());
            return fail("walk-stuck at " + lower.toShortString(), "could not walk into ");
        }

        // 水平朝前方列走（视线放平，只驱动前进；重力负责下落）。
        CompanionInputController.lookAt(companion,
                new Vec3d(lower.getX() + 0.5D, companion.getEyeY(), lower.getZ() + 0.5D));
        CompanionInputController.applyServerTravelForward(companion, false);
        return TaskStatus.RUNNING;
    }

    /**
     * 遇障碍时发起一次横向躲避（不停下）：<b>永远先探左侧、左侧不能挖再探右侧</b>（左/右相对<b>当前朝向</b>
     * {@link #digDir} 算），选一个能安全横移 {@link #EVADE_STEPS} 格的方向锁定，然后进入躲避挖掘阶段。
     * 躲避完成后 {@link #digDir} 会更新为该侧移方向（拐弯），所以下次遇障碍的「左/右」基于新朝向，绝不会把
     * 「正后方」当成一侧而原地前后弹。
     *
     * <p>两侧都不安全 → 保守判失败（报原始障碍）。连续躲避达到 {@link #MAX_CONSECUTIVE_EVADES} 次而没能成功
     * 下挖任何一级 → 判失败，避免被大空腔/流体包围时无限横向跑飞。
     *
     * @param hazardDesc 触发躲避的原始障碍描述（两侧都堵/次数超限时作为失败原因）
     */
    private TaskStatus beginEvade(ServerPlayerEntity companion, String hazardDesc) {
        if (++consecutiveEvades > MAX_CONSECUTIVE_EVADES) {
            return fail("too-many-evades (last hazard: " + hazardDesc + ")", null);
        }

        World world = companion.getEntityWorld();
        Direction left = digDir.rotateYCounterclockwise();
        Direction right = digDir.rotateYClockwise();
        // 永远先试左（相对当前朝向），左侧不能安全横移才试右。
        Direction chosen = canEvade(world, companion.getBlockPos(), left) ? left
                : canEvade(world, companion.getBlockPos(), right) ? right
                : null;
        if (chosen == null) {
            return fail("blocked-both-sides (" + hazardDesc + ")", "could not evade ");
        }

        evadeDir = chosen;
        evadeStep = 0;
        planEvadeStep(companion.getBlockPos());
        digTicks = 0;
        walkTicks = 0;
        phase = Phase.EVADE_DIG_UPPER;
        diag("evade " + evadeDir + " (" + hazardDesc + ") x" + EVADE_STEPS
                + " [consecutive=" + consecutiveEvades + "]");
        return TaskStatus.RUNNING;
    }

    /**
     * 预检某个横移方向的整段 {@link #EVADE_STEPS} 格是否可安全平走通过：从当前脚位起，对每一格用<b>和下挖同一套
     * {@link MiningHazard} 判据</b>校验——侧方脚下地板须实心（踩着平走、不掉下去），将挖开的两格（侧头顶净空、
     * 侧同高）无流体涌入、头顶无下落方块。整段 {@link #EVADE_STEPS} 格全安全才返回 true。
     */
    private boolean canEvade(World world, BlockPos feet, Direction dir) {
        BlockPos cursor = feet;
        for (int i = 0; i < EVADE_STEPS; i++) {
            BlockPos side = cursor.offset(dir);       // 侧方、与脚同高（要挖开走进）
            BlockPos sideUp = side.up();              // 侧方、头顶净空（要挖开）
            BlockPos sideFloor = side.down();         // 侧方、脚下地板（踩着走，不挖）

            // 脚下地板必须实心：是流体 → 危险；是空气且在地表以下 → 空腔（走过去会踏空/进洞）。
            if (MiningHazard.fluidHazard(world, sideFloor) != MiningHazard.Hazard.SAFE) {
                return false;
            }
            if (world.getBlockState(sideFloor).isAir() && !MiningHazard.isOpenSky(world, sideFloor)) {
                return false;
            }
            // 将挖开的两格：查各自自身 + UP + 四水平洪流方向邻居有无流体。查自身尤为关键——side 是横移后脚所在
            // 格，若它本身是岩浆/水源（如一格深的流体坑坑底），只查邻居会漏掉，导致平走直接踏进流体。
            for (BlockPos dig : new BlockPos[]{sideUp, side}) {
                if (MiningHazard.fluidHazard(world, dig) != MiningHazard.Hazard.SAFE) {
                    return false;
                }
                for (Direction d : Direction.values()) {
                    if (d == Direction.DOWN) {
                        continue;
                    }
                    if (MiningHazard.fluidHazard(world, dig.offset(d)) != MiningHazard.Hazard.SAFE) {
                        return false;
                    }
                }
                // 头顶下落方块：挖开这一格后其正上方若是下落方块会砸下来。
                BlockState above = world.getBlockState(dig.up());
                if (above.getBlock() instanceof FallingBlock) {
                    return false;
                }
            }
            cursor = side; // 平走：Y 不变，脚位前移到侧方格
        }
        return true;
    }

    /** 按当前脚位算出这一躲避格待挖的两格（侧头顶净空、侧同高）。侧方脚下地板不挖（踩着平走）。 */
    private void planEvadeStep(BlockPos feet) {
        evadeSide = feet.offset(evadeDir);
        evadeUp = evadeSide.up();
    }

    /**
     * 躲避格已挖开：向侧方平走一步（Y 不变，不下降）。到达判定要求方块坐标水平分量确实等于 {@link #evadeSide}
     * 且已落地。走满 {@link #EVADE_STEPS} 格后回 {@link Phase#PLAN} 继续下挖；否则规划下一躲避格继续横移。
     */
    private TaskStatus tickEvadeWalk(ServerPlayerEntity companion) {
        BlockPos pos = companion.getBlockPos();
        // 横移应 Y 不变：要求水平进入 evadeSide 列且高度仍等于该格（sideFloor 已预检实心，正常不会下落）。
        // 加 Y 判定作兜底——万一意外下落，避免在更低处误判横移成功。
        boolean entered = pos.getX() == evadeSide.getX() && pos.getZ() == evadeSide.getZ()
                && pos.getY() == evadeSide.getY();
        if (entered && companion.isOnGround()) {
            CompanionInputController.releaseInput(companion);
            walkTicks = 0;
            if (++evadeStep >= EVADE_STEPS) {
                // 拐弯：躲避走满后，把下挖主方向更新为实际侧移方向，以新朝向为新的「前」继续挖阶梯。
                // 这样下次遇障碍的左/右基于新朝向算，绝不会把「正后方」当一侧而原地前后弹。
                digDir = evadeDir;
                planDelayTicks = 0;
                phase = Phase.PLAN;
                diag("  evade done: shifted " + EVADE_STEPS + " " + evadeDir
                        + "; digDir now " + digDir);
                return TaskStatus.RUNNING;
            }
            planEvadeStep(pos);
            digTicks = 0;
            phase = Phase.EVADE_DIG_UPPER;
            return TaskStatus.RUNNING;
        }

        if (++walkTicks >= WALK_TIMEOUT_TICKS) {
            CompanionInputController.releaseInput(companion);
            diag("  EVADE_WALK timeout: pos=" + String.format("%.2f,%.2f,%.2f",
                    companion.getX(), companion.getY(), companion.getZ())
                    + " onGround=" + companion.isOnGround()
                    + " target side=" + evadeSide.toShortString());
            return fail("evade-walk-stuck at " + evadeSide.toShortString(), "could not evade into ");
        }

        // 水平朝侧方格走（视线放平，只驱动前进；此处不应下落，脚下地板已预检为实心）。
        CompanionInputController.lookAt(companion,
                new Vec3d(evadeSide.getX() + 0.5D, companion.getEyeY(), evadeSide.getZ() + 0.5D));
        CompanionInputController.applyServerTravelForward(companion, false);
        return TaskStatus.RUNNING;
    }

    /** 统一失败收尾：记录原因、置终态、诊断。prefix 为 null 时只报原因。 */
    private TaskStatus fail(String reason, String prefix) {
        failReason = reason;
        phase = Phase.DONE;
        terminal = TaskStatus.FAILURE;
        diag("FAILURE: " + (prefix == null ? "" : prefix) + reason + " (level " + (levelsDug + 1) + ")");
        return TaskStatus.FAILURE;
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        CompanionMiningTasks.cancel(companion);
        CompanionInputController.releaseInput(companion);
        CompanionInputController.resetPitch(companion);
    }

    @Override
    public String describe() {
        return "SafeDescent(" + phase + ", targetY=" + targetY + ", levels=" + levelsDug
                + (consecutiveEvades > 0 ? ", evades=" + consecutiveEvades : "")
                + (failReason == null ? "" : ", fail=" + failReason) + ")";
    }

    private void diag(String msg) {
        if (diagSink != null) {
            diagSink.sendFeedback(() -> Text.literal("[Mine] " + msg), false);
        }
    }
}
