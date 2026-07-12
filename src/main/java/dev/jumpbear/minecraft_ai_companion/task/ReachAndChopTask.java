package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 砍树最小闭环任务：走到 base 旁的落脚点 → 清掉挡路的本树叶 → 换上最快工具 → 视线门控挖掉 base 与其上一块
 * （共 2 块）。这是把「探测 / 排序 / 落脚规划 / 视线门控挖掘」几块拼成<b>真实可执行</b>流程的第一段；整列、
 * 搭方块、分叉列留待后续扩展。
 *
 * <p>组合已验证的适配器，本任务不自带新的物理/寻路/挖掘逻辑：{@link TreeDetector}（找树）、
 * {@link TreeApproach}（选落脚格 + 待清叶，纯数据、不打射线）、{@link CompanionNavigator}（走过去）、
 * {@link CompanionMiningTasks}（异步逐块挖）、{@link CompanionHotbar}（换最快工具）、
 * {@link TreeChopSight}（挖前视线门控）。
 *
 * <h2>每一步可恢复</h2>
 * 无论在哪个阶段被中断（取消、重新指派、同伴死亡），{@link #stop} 都<b>无条件、幂等</b>地释放本任务临时占用
 * 的一切：停下并释放寻路代理、取消半截的挖掘并清掉破坏裂纹、松开所有移动输入。这样同伴任何时刻被打断都能
 * 回到干净状态、交还生命系统，不留残留（悬空破坏裂纹、卡住的 interactionManager 破坏状态、按住的输入）。
 */
public final class ReachAndChopTask implements CompanionTask {

    /** 寻路到落脚格附近的到达判定距离。用 1（走到相邻格即可）而非 0：vanilla 寻路 + 假客户端行走有
     *  ~0.45 格末端漂移，苛求精确到格会 STUCK。粗到达后由 {@link Phase#POSITION} 阶段用<b>实时够取</b>
     *  判定是否已站到可开工的位置——不再苛求亚格精度。 */
    private static final int REACH_DISTANCE = 1;
    /** 导航整体超时（tick）。 */
    private static final int NAVIGATE_TIMEOUT_TICKS = 200;
    /** POSITION 阶段绝对上限（tick）：从进入起算，无论如何到点就放弃，杜绝无限挂起。 */
    private static final int POSITION_MAX_TICKS = 40;
    /** POSITION 阶段「到落脚中心的最近距离」多少 tick 未改善即判定被卡住（净进度高水位，免疫振荡/侧滑）。 */
    private static final int POSITION_STALL_TICKS = 15;
    /** 判定「这一 tick 是否更接近落脚中心」的最小改善量（格²）：约 0.1 格。 */
    private static final double POSITION_IMPROVE_EPSILON_SQUARED = 0.01D;

    private enum Phase { NAVIGATE, POSITION, CLEAR, CHOP, DONE }

    private CompanionNavigator navigator;
    private Phase phase = Phase.NAVIGATE;

    /** base（最低待挖原木），POSITION 阶段用它做实时够取判定。 */
    private BlockPos base;
    /** 要挖的块：base 与其上一块，自底向上。 */
    private final List<BlockPos> chopTargets = new ArrayList<>();
    /** 待清的接近遮挡叶。 */
    private List<BlockPos> occluders = new ArrayList<>();
    private BlockPos foothold;

    private int chopIndex;
    private int clearIndex;
    private int navigateTicks;
    /** POSITION 阶段：到落脚中心的历史最近距离平方（净进度高水位）、未改善计时、绝对计时。 */
    private double bestFootDistSquared = Double.MAX_VALUE;
    private int positionStallTicks;
    private int positionTicks;
    /** 是否已就当前 CLEAR/CHOP 目标发起过一次 CompanionMiningTasks（等它异步完成）。 */
    private boolean miningStarted;
    /** start 阶段就判定的终态（没树/没落脚点）；非空则第一个 tick 直接返回它。 */
    private TaskStatus terminal;

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);

        Optional<TreeDetector.Tree> tree = TreeDetector.findNearestTree(companion);
        if (tree.isEmpty()) {
            terminal = TaskStatus.FAILURE;
            debug(companion, "start FAIL: no tree found");
            return;
        }
        TreeDetector.Tree t = tree.get();
        this.base = t.base();

        Optional<TreeApproach.Approach> approach = TreeApproach.plan(companion, t, base);
        if (approach.isEmpty()) {
            terminal = TaskStatus.FAILURE; // 无合格落脚点，跳过这棵树
            debug(companion, "start FAIL: no foothold for base " + base.toShortString());
            return;
        }
        this.foothold = approach.get().foothold();
        this.occluders = new ArrayList<>(approach.get().occluders());
        // 挖 2 块：base 与其正上方一块（同列相邻，落脚点够到 base 即够到它）。
        this.chopTargets.add(base);
        this.chopTargets.add(base.up());

        debug(companion, "start OK: base=" + base.toShortString()
                + " foothold=" + foothold.toShortString() + " occluders=" + occluders.size());
        navigator.pathTo(foothold, REACH_DISTANCE);
    }

    /** 临时诊断：把一行消息广播给所有玩家聊天栏，定位阶段/失败原因。定位完可删。 */
    private static void debug(ServerPlayerEntity companion, String msg) {
        companion.getEntityWorld().getServer().getPlayerManager()
                .broadcast(net.minecraft.text.Text.literal("[ChopDebug] " + msg), false);
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (terminal != null) {
            return terminal; // start 阶段已判定失败
        }
        return switch (phase) {
            case NAVIGATE -> tickNavigate(companion);
            case POSITION -> tickPosition(companion);
            case CLEAR -> tickClear(companion);
            case CHOP -> tickChop(companion);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    private TaskStatus tickNavigate(ServerPlayerEntity companion) {
        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case ARRIVED, IDLE -> {
                navigator.stop();
                phase = Phase.POSITION;
                debug(companion, "NAVIGATE done (" + result + ") -> POSITION, target foothold=" + foothold.toShortString());
                return TaskStatus.RUNNING;
            }
            case NO_PATH, STUCK -> {
                navigator.stop();
                debug(companion, "NAVIGATE FAIL: " + result);
                return TaskStatus.FAILURE;
            }
            default -> {
                if (++navigateTicks >= NAVIGATE_TIMEOUT_TICKS) {
                    navigator.stop();
                    debug(companion, "NAVIGATE FAIL: timeout");
                    return TaskStatus.FAILURE;
                }
                return TaskStatus.RUNNING;
            }
        }
    }

    /**
     * 粗到达后确认站位是否可开工，用<b>实时够取</b>而非亚格精确对齐作判据。这是「假客户端最弱的就是亚格定位」
     * 的针对性设计：{@link CompanionNavigator} 只把身体送到落脚格<em>相邻</em>（~0.45 格漂移），而 vanilla 的
     * 够取容差约 4.5 格——只要真人此刻能够到 base 就能开工，末端漂移完全落在容差内，无需把身体压进某个亚格窗口。
     *
     * <p>流程：
     * <ol>
     *   <li>先直接判「能否够到 base」（{@link ServerPlayerEntity#canInteractWithBlockAt} 距离判据，纯眼位、
     *       与朝向无关）。够得到 → 立刻进 CLEAR，<em>不再</em>要求走到落脚中心。</li>
     *   <li>够不到 → 朝落脚中心步进微调。用<b>净进度高水位</b>判卡死：记录「到落脚中心的历史最近距离」，
     *       若 {@link #POSITION_STALL_TICKS} 内没再刷新更近记录，即判被卡（免疫振荡/侧滑——那种情形高水位
     *       不会下降）；另设 {@link #POSITION_MAX_TICKS} 绝对上限，杜绝任何形式的无限挂起。</li>
     * </ol>
     * 这取代了旧的「强制驱到落脚中心 ≤0.2」逻辑：旧逻辑因全速 travel 动量冲过窗口 + per-tick 位移判据无法
     * 识别侧滑振荡，会无限 RUNNING 挂起。
     */
    private TaskStatus tickPosition(ServerPlayerEntity companion) {
        // 能够到 base 就够了——直接开工，不苛求站到落脚格中心。
        if (companion.canInteractWithBlockAt(base, 1.0D)) {
            CompanionInputController.releaseInput(companion);
            phase = Phase.CLEAR;
            debug(companion, "POSITION ok (can reach base) at " + companion.getBlockPos().toShortString()
                    + " -> CLEAR, occluders=" + occluders.size());
            return TaskStatus.RUNNING;
        }

        // 绝对上限：无论如何到点就放弃这棵树，绝不无限挂起。
        if (++positionTicks >= POSITION_MAX_TICKS) {
            CompanionInputController.releaseInput(companion);
            debug(companion, "POSITION FAIL: timeout, still can't reach base " + base.toShortString()
                    + " from " + companion.getBlockPos().toShortString());
            return TaskStatus.FAILURE;
        }

        // 净进度高水位卡死检测：只认「离落脚中心更近」的真实进展；振荡/侧滑不会刷新高水位 → 计时累积到上限判卡。
        double dx = (foothold.getX() + 0.5D) - companion.getX();
        double dz = (foothold.getZ() + 0.5D) - companion.getZ();
        double footDistSq = dx * dx + dz * dz;
        if (footDistSq < bestFootDistSquared - POSITION_IMPROVE_EPSILON_SQUARED) {
            bestFootDistSquared = footDistSq;
            positionStallTicks = 0;
        } else if (++positionStallTicks >= POSITION_STALL_TICKS) {
            CompanionInputController.releaseInput(companion);
            debug(companion, "POSITION FAIL: wedged (no progress toward foothold) short of "
                    + foothold.toShortString() + " at " + companion.getBlockPos().toShortString());
            return TaskStatus.FAILURE;
        }

        // 朝落脚中心步进（不跳——垂直已由寻路解决）。
        CompanionInputController.lookAt(companion, new net.minecraft.util.math.Vec3d(
                foothold.getX() + 0.5D, companion.getEyeY(), foothold.getZ() + 0.5D));
        CompanionInputController.applyServerTravelForward(companion, false);
        return TaskStatus.RUNNING;
    }

    /** 逐块挖掉待清遮挡叶。清叶不做视线门控——它们本就是挡在视线上的本树叶。 */
    private TaskStatus tickClear(ServerPlayerEntity companion) {
        if (clearIndex >= occluders.size()) {
            phase = Phase.CHOP;
            return TaskStatus.RUNNING;
        }

        BlockPos leaf = occluders.get(clearIndex);
        // 已经是空气（自然衰减或被别处清掉）：跳过，不算失败。
        if (companion.getEntityWorld().getBlockState(leaf).isAir()) {
            clearIndex++;
            miningStarted = false;
            return TaskStatus.RUNNING;
        }
        if (!miningStarted) {
            CompanionInputController.lookAt(companion, leaf.toCenterPos());
            CompanionMiningTasks.start(companion, leaf);
            miningStarted = true;
            return TaskStatus.RUNNING;
        }
        if (CompanionMiningTasks.hasTask(companion)) {
            return TaskStatus.RUNNING; // 挖掘进行中
        }
        // 挖掘结束：取走结果、推进下一片（清叶成败都推进——不阻断主流程）。
        CompanionMiningTasks.pollResult(companion);
        clearIndex++;
        miningStarted = false;
        return TaskStatus.RUNNING;
    }

    /** 换最快工具，然后视线门控挖掉 base 与其上一块。 */
    private TaskStatus tickChop(ServerPlayerEntity companion) {
        if (chopIndex >= chopTargets.size()) {
            phase = Phase.DONE;
            return TaskStatus.SUCCESS;
        }

        BlockPos target = chopTargets.get(chopIndex);
        if (companion.getEntityWorld().getBlockState(target).isAir()) {
            chopIndex++; // 已经没了，推进
            miningStarted = false;
            return TaskStatus.RUNNING;
        }
        if (!miningStarted) {
            // 换上挖这块最快的工具（斧头砍原木），走 vanilla 选/换物路径。
            BlockState state = companion.getEntityWorld().getBlockState(target);
            CompanionHotbar.selectBestToolFor(companion, state);
            // 视线门控：看得见且够得着才挖，否则失败——绝不隔空挖。
            if (!TreeChopSight.lookAndCheck(companion, target)) {
                debug(companion, "CHOP FAIL: no line-of-sight/reach to " + target.toShortString()
                        + " from " + companion.getBlockPos().toShortString());
                return TaskStatus.FAILURE;
            }
            debug(companion, "CHOP start dig " + target.toShortString());
            CompanionMiningTasks.start(companion, target);
            miningStarted = true;
            return TaskStatus.RUNNING;
        }
        if (CompanionMiningTasks.hasTask(companion)) {
            // 挖掘期间保持朝向目标：挖一块要几十 tick，期间身体可能被推挤/漂移，若偏出朝向或距离，
            // vanilla 会在 break 中途静默拒绝（processBlockBreakingAction 的 reach 校验）。每 tick 重新
            // 看向目标中心，把朝向钉住，减少 mid-break 出 reach。
            CompanionInputController.lookAt(companion, target.toCenterPos());
            return TaskStatus.RUNNING;
        }
        CompanionMiningTasks.MiningResult result = CompanionMiningTasks.pollResult(companion);
        miningStarted = false;
        if (result != CompanionMiningTasks.MiningResult.BROKEN) {
            debug(companion, "CHOP FAIL: mining result=" + result + " for " + target.toShortString());
            return TaskStatus.FAILURE; // 没挖掉（够不着/被拒/不可破坏）
        }
        debug(companion, "CHOP ok: broke " + target.toShortString());
        chopIndex++;
        return TaskStatus.RUNNING;
    }

    /**
     * 无条件、幂等地释放本任务占用的一切，保证任何阶段被中断都可恢复到干净状态。
     */
    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
        // 取消半截的挖掘并清掉破坏裂纹与 interactionManager 的破坏状态。
        CompanionMiningTasks.cancel(companion);
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        return "ReachAndChop(" + phase
                + ", foothold=" + (foothold == null ? "none" : foothold.toShortString())
                + ", clear=" + clearIndex + "/" + occluders.size()
                + ", chop=" + chopIndex + "/" + chopTargets.size() + ")";
    }
}