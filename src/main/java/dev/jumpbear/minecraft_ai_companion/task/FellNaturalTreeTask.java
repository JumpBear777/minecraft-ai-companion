package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 砍倒一棵<b>附近、可确认归属、可安全到达的自然树</b>——整树逐原木处理。这是砍树能力的正式行为任务
 * （{@link ReachAndChopTask} 降级为窄调试用例后，本任务承接实际使用）。
 *
 * <h2>行为契约</h2>
 * <ul>
 *   <li><b>成功</b>（{@link TaskStatus#SUCCESS}）：{@link TreePlan} 快照里<em>每一块</em>原木都已确认变为空气。</li>
 *   <li><b>失败</b>（{@link TaskStatus#FAILURE}）：只要有计划原木残留即整体失败；每块残留原木的原因被记录
 *       （无落脚点 / 无路径 / 不可见 / 够不到 / 被非本树方块挡 / 挖掘被拒），供诊断。</li>
 *   <li><b>归属不清 → 拒绝</b>：{@link TreeDetector} 的保守形状校验拒绝该簇时，{@code start} 直接判失败，
 *       不猜测、不硬砍。</li>
 *   <li><b>收集分离</b>：本任务只负责「砍倒」；掉落物收集由调用方 {@code enqueueOnSuccess(Collect...)}
 *       作为后续工作，砍树失败不会被空收集覆盖。</li>
 * </ul>
 *
 * <h2>逐原木「可达性重规划」（借鉴 Numen 的决策模式，用本项目已验证的适配器实现）</h2>
 * 目标集合在 {@code start} 一次性固化为 {@link TreePlan}（有序原木 + 允许清理的本树叶），执行期<b>只读快照</b>、
 * 绝不重扫改变目标。对 {@link TreePlan#orderedLogs()} 里的每一块原木：
 * <ol>
 *   <li>先用 {@link TreeChopStep} 从<b>当前位置</b>实时判「够得到且看得见」——够到就地砍，省一次导航。</li>
 *   <li>够不到 / 看不见 / 被挡 → 取该原木的 {@link TreeApproach} 落脚候选（已按择优排序），
 *       用 {@link CompanionNavigator} 逐个尝试：{@code pathTo} 返回 false 或导航 {@code NO_PATH/STUCK}
 *       立即换<b>下一候选</b>（候选一旦消费绝不重试，杜绝 A→B→A 循环）；只认 {@code ARRIVED} 为到位。</li>
 *   <li>到位后再用 {@link TreeChopStep} 实时判并砍；仍被非本树方块挡则再换候选。</li>
 *   <li>候选耗尽 → 记录该原木失败原因、<b>继续下一块</b>（不放弃整棵——其余原木可能仍可达）。</li>
 * </ol>
 * 射线先命中「快照内、确认归属」的叶只清那一片（{@link TreeChopStep} 有界反应式清叶）；命中其它任何方块
 * 立即停该路径，<b>绝不</b>自动挖遮挡物（不学 Numen「任意障碍可挖」）。
 *
 * <h2>够不到的高处主干：搭柱升高（第二阶段）</h2>
 * 当某块主干原木<b>因高度够不到</b>（候选耗尽且最后原因是 out-of-reach，或压根无地面落脚点 no-foothold），
 * 且同伴此刻已站在一个稳定落脚列上时，进入 {@link Phase#PILLAR}：用已验证的 {@link CompanionPillar} 在
 * <b>脚下这一列</b>原地搭一格脚手架升高，把实际放下的坐标记进栈，再回 {@link Phase#CHOP} 重试当前原木——
 * 升到够得到为止，最多 {@link #MAX_PILLAR_LEVELS} 级（防畸形树无限叠柱）。整树处理完后进入
 * {@link Phase#RECLAIM}：从栈顶（最高、最后搭的）向下把自己搭的脚手架逐块挖回（真玩家式回收，不留残柱），
 * 挖前校验该位仍是脚手架方块（不盲信记忆）。脚手架方块来自背包（{@link CompanionHotbar#selectScaffoldBlock}，
 * 只认满方块）；调用方负责备料。
 *
 * <h2>范围</h2>
 * 主干列先建立一根脚手架柱；同伴仍站在这根柱顶时，高处分叉也可继续沿<b>同一列</b>升高后重试。
 * <b>需要离开该列、另起一根柱或横向搭桥的孤立高处分叉</b>仍诚实记失败。不建桥、不挖隧道、
 * 不跨列横移搭柱、不「任意障碍可挖」。
 */
public final class FellNaturalTreeTask implements CompanionTask {

    /** 寻路到落脚候选的到达判定距离（格）：走到相邻即可，最终是否可开工由 {@link TreeChopStep} 实时判。 */
    private static final int REACH_DISTANCE = 1;
    /** 单个候选的导航超时（tick）：走不到就换下一候选，不无限走。 */
    private static final int NAVIGATE_TIMEOUT_TICKS = 200;
    /** 单块原木最多为它搭多少级柱（防对畸形高树无限叠柱）。到顶仍够不到即记失败。 */
    private static final int MAX_PILLAR_LEVELS = 8;
    /** Per pillar level, clear at most this many own leaves from the vertical jump clearance. */
    private static final int MAX_PILLAR_CLEAR_LEAVES = 2;

    private enum Phase { CHOP, NAVIGATE, PILLAR_CLEAR, PILLAR, RECLAIM, DONE }

    /** 一块残留原木及其失败原因（供诊断/测试断言）。 */
    private record LogFailure(BlockPos log, String reason) {
    }

    /** A scaffold block this task actually placed, including its original block type for safe reclaim. */
    private record ScaffoldBlock(BlockPos pos, Block block) {
    }

    /** 可选诊断输出目标；仅命令发起者可见。为 null 时静默（正式行为默认不喧哗）。 */
    private final ServerCommandSource diagSink;

    private CompanionNavigator navigator;
    private TreeDetector.Tree tree;
    private TreePlan plan;
    private Phase phase = Phase.CHOP;

    /** start 阶段就判定的终态（没树 / 被拒）；非空则第一个 tick 直接返回它。 */
    private TaskStatus terminal;

    /** 当前处理到 orderedLogs 的哪一块。 */
    private int logIndex;
    /** 当前原木的落脚候选（懒计算）与游标；游标只增不减（消费即不重试）。 */
    private List<TreeApproach.Approach> candidates;
    private boolean candidatesComputed;
    private int candidateIndex;
    /** 当前原木最近一次的失败信号（候选耗尽时作为记录原因）。 */
    private String lastReason = "unreached";

    private TreeChopStep step;
    private int navigateTicks;

    /** 搭柱升高适配器（懒建）。 */
    private CompanionPillar pillar;
    /** 本任务实际搭下的每一格脚手架，自底向上入栈；RECLAIM 阶段自顶向下确认并挖回。 */
    private final Deque<ScaffoldBlock> placedScaffold = new ArrayDeque<>();
    /** 唯一允许搭柱的水平列。先由 base 主干建立，之后高处分叉只能从此列继续升高。 */
    private BlockPos pillarColumn;
    /** 当前原木已为它搭了几级柱（每换一块原木清零）。 */
    private int pillarLevels;
    /** A single own-leaf clear currently in progress before starting this pillar level. */
    private TreeChopStep pillarClearStep;
    private int pillarLeavesCleared;
    /** RECLAIM 阶段：当前正在挖回的脚手架块（等挖掘异步完成）。 */
    private ScaffoldBlock reclaiming;

    private final List<LogFailure> failures = new ArrayList<>();
    private final List<String> reclaimFailures = new ArrayList<>();

    public FellNaturalTreeTask() {
        this(null);
    }

    /** @param diagSink 可选诊断输出（仅命令发起者可见）；null 表示静默。 */
    public FellNaturalTreeTask(ServerCommandSource diagSink) {
        this.diagSink = diagSink;
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);

        Optional<TreeDetector.Tree> found = TreeDetector.findNearestTree(companion);
        if (found.isEmpty()) {
            terminal = TaskStatus.FAILURE;
            diag("start FAIL: no acceptable natural tree in range (rejected or none)");
            return;
        }
        this.tree = found.get();
        this.plan = TreePlan.capture(companion.getEntityWorld(), tree, tree.evidence());
        diag("start OK: base=" + plan.base().toShortString()
                + " logs=" + plan.orderedLogs().size()
                + " ownLeaves=" + plan.ownLeaves().size());
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (terminal != null) {
            return terminal;
        }
        World world = companion.getEntityWorld();

        // 推进到下一块仍存在的待砍原木。（RECLAIM/DONE 阶段不推进——它们不再处理原木。）
        if (phase != Phase.RECLAIM && phase != Phase.DONE) {
            while (logIndex < plan.orderedLogs().size()
                    && world.getBlockState(plan.orderedLogs().get(logIndex)).isAir()) {
                logIndex++;
                resetPerLog();
            }
            if (logIndex >= plan.orderedLogs().size()) {
                // 所有原木处理完：若搭过柱先回收，否则直接收尾。
                phase = placedScaffold.isEmpty() ? Phase.DONE : Phase.RECLAIM;
            }
        }

        return switch (phase) {
            case CHOP -> tickChop(companion);
            case NAVIGATE -> tickNavigate(companion);
            case PILLAR_CLEAR -> tickPillarClear(companion);
            case PILLAR -> tickPillar(companion);
            case RECLAIM -> tickReclaim(companion);
            case DONE -> finishTask(world);
        };
    }

    /** 从当前位置（或刚到达的落脚点）实时判并砍当前原木。 */
    private TaskStatus tickChop(ServerPlayerEntity companion) {
        BlockPos currentLog = plan.orderedLogs().get(logIndex);
        if (step == null) {
            step = new TreeChopStep(currentLog, plan);
        }
        TreeChopStep.Result r = step.tick(companion);
        switch (r) {
            case IN_PROGRESS -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN -> {
                diag("log " + currentLog.toShortString() + " BROKEN");
                logIndex++;
                resetPerLog();
                return TaskStatus.RUNNING;
            }
            // 够不到 / 看不见 / 被非本树方块挡 / 挖掘被拒：换落脚点重试。
            case OUT_OF_REACH, OUT_OF_SIGHT, BLOCKED_BY_FOREIGN, FAILED -> {
                lastReason = reasonOf(r);
                step.cancel(companion);
                step = null;
                return advanceToNextCandidate(companion, currentLog);
            }
        }
        return TaskStatus.RUNNING;
    }

    /** 计算/推进落脚候选并导航到下一个可达候选；候选耗尽则记失败、跳到下一块原木。 */
    private TaskStatus advanceToNextCandidate(ServerPlayerEntity companion, BlockPos currentLog) {
        if (!candidatesComputed) {
            candidates = TreeApproach.plan(companion, tree, currentLog);
            candidatesComputed = true;
            candidateIndex = 0;
            if (candidates.isEmpty()) {
                lastReason = "no-foothold";
            }
        }

        while (candidateIndex < candidates.size()) {
            TreeApproach.Approach a = candidates.get(candidateIndex);
            candidateIndex++; // 消费即不重试
            if (navigator.pathTo(a.foothold(), REACH_DISTANCE)) {
                phase = Phase.NAVIGATE;
                navigateTicks = 0;
                diag("log " + currentLog.toShortString() + " navigating to foothold "
                        + a.foothold().toShortString());
                return TaskStatus.RUNNING;
            }
            // pathTo 返回 false（无路径）：立即换下一候选，绝不把它当到达。
            lastReason = "no-path";
        }

        // 候选耗尽。若失败是因为「够不到高处」且同伴已站在稳定落脚列上，尝试搭柱升高再重试当前原木；
        // 否则记失败、继续下一块。
        if (canTryPillar(companion, currentLog)) {
            return enterPillar(companion, currentLog);
        }
        failures.add(new LogFailure(currentLog, lastReason));
        diag("log " + currentLog.toShortString() + " FAIL: " + lastReason + " (candidates exhausted)");
        logIndex++;
        resetPerLog();
        return TaskStatus.RUNNING;
    }

    /**
     * 是否值得为当前原木搭柱升高：失败原因是「够不到高处 / 无地面落脚点」（而非被挡/无路径），
     * 目标原木确实高于同伴当前脚位，且尚未达到搭柱级数上限。首根柱只能为 base 主干建立；
     * 之后高处分叉仅当同伴仍在该柱顶时可继续升高。被非本树方块挡、无路径、需要另起柱的分叉
     * 不该靠搭柱解决。
     */
    private boolean canTryPillar(ServerPlayerEntity companion, BlockPos currentLog) {
        boolean reachReason = "out-of-reach".equals(lastReason) || "no-foothold".equals(lastReason);
        boolean logIsAbove = currentLog.getY() > companion.getBlockY();
        if (!reachReason || !logIsAbove || pillarLevels >= MAX_PILLAR_LEVELS || !companion.isOnGround()) {
            return false;
        }
        if (pillarColumn == null) {
            return currentLog.getX() == plan.base().getX() && currentLog.getZ() == plan.base().getZ();
        }
        return companion.getBlockX() == pillarColumn.getX() && companion.getBlockZ() == pillarColumn.getZ();
    }

    /** Enter the bounded clearance phase before beginning the next pillar level. */
    private TaskStatus enterPillar(ServerPlayerEntity companion, BlockPos currentLog) {
        if (pillar == null) {
            pillar = new CompanionPillar(companion);
        }
        pillarClearStep = null;
        pillarLeavesCleared = 0;
        phase = Phase.PILLAR_CLEAR;
        diag("log " + currentLog.toShortString() + " out of reach; checking pillar clearance (level "
                + (pillarLevels + 1) + "/" + MAX_PILLAR_LEVELS + ")");
        return TaskStatus.RUNNING;
    }

    /**
     * Ensure the two blocks above the companion's feet are clear enough for the verified jump-and-place
     * adapter. Only a live natural leaf recorded in this TreePlan may be cleared; any other collision
     * remains a hard stop rather than an invitation to tunnel through a build or another tree.
     */
    private TaskStatus tickPillarClear(ServerPlayerEntity companion) {
        BlockPos currentLog = plan.orderedLogs().get(logIndex);
        if (pillarClearStep != null) {
            TreeChopStep.Result result = pillarClearStep.tick(companion);
            switch (result) {
                case IN_PROGRESS -> {
                    return TaskStatus.RUNNING;
                }
                case BROKEN -> {
                    pillarLeavesCleared++;
                    pillarClearStep = null;
                    return TaskStatus.RUNNING;
                }
                default -> {
                    return failPillar(companion, currentLog, "pillar-clear-blocked");
                }
            }
        }

        BlockPos feet = companion.getBlockPos();
        BlockPos blocker = firstPillarClearanceBlocker(companion, feet);
        if (blocker == null) {
            return beginPillar(companion, currentLog);
        }
        BlockState state = companion.getEntityWorld().getBlockState(blocker);
        if (pillarLeavesCleared >= MAX_PILLAR_CLEAR_LEAVES
                || !plan.isOwnLeaf(blocker)
                || !TreePlan.isNaturalLeaf(state)) {
            return failPillar(companion, currentLog, "pillar-blocked");
        }
        pillarClearStep = TreeChopStep.clearOwnLeaf(blocker, plan);
        return TaskStatus.RUNNING;
    }

    /** @return the first solid block obstructing the vertical jump body space, or null if clear. */
    private static BlockPos firstPillarClearanceBlocker(ServerPlayerEntity companion, BlockPos feet) {
        World world = companion.getEntityWorld();
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos pos = feet.up(dy);
            if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    /** Select a safe scaffold block and begin exactly one verified pillar level. */
    private TaskStatus beginPillar(ServerPlayerEntity companion, BlockPos currentLog) {
        if (!CompanionHotbar.selectScaffoldBlock(companion)) {
            return failPillar(companion, currentLog, "no-scaffold");
        }
        if (pillarColumn == null) {
            pillarColumn = new BlockPos(companion.getBlockX(), 0, companion.getBlockZ());
        }
        pillar.begin(1); // 一次升一级，升完回 CHOP 重试当前原木
        phase = Phase.PILLAR;
        diag("log " + currentLog.toShortString() + " out of reach; pillaring up (level "
                + (pillarLevels + 1) + "/" + MAX_PILLAR_LEVELS + ")");
        return TaskStatus.RUNNING;
    }

    /** Record a pillar failure and continue with the remaining planned logs. */
    private TaskStatus failPillar(ServerPlayerEntity companion, BlockPos currentLog, String reason) {
        if (pillar != null) {
            pillar.stop();
        }
        if (pillarClearStep != null) {
            pillarClearStep.cancel(companion);
            pillarClearStep = null;
        }
        lastReason = reason;
        failures.add(new LogFailure(currentLog, reason));
        diag("log " + currentLog.toShortString() + " FAIL: " + reason);
        logIndex++;
        resetPerLog();
        return TaskStatus.RUNNING;
    }

    /** 搭一级柱：升完记录实际放下的坐标、回 CHOP 重试当前原木；放置失败记原因继续下一块。 */
    private TaskStatus tickPillar(ServerPlayerEntity companion) {
        BlockPos currentLog = plan.orderedLogs().get(logIndex);
        CompanionPillar.PillarResult result = pillar.tick();
        // 每完成一级，lastPlacedPos 指向新坐标；与栈顶不同即入栈（柱自底向上，坐标不重复）。
        BlockPos last = pillar.lastPlacedPos();
        if (last != null && (placedScaffold.peek() == null || !last.equals(placedScaffold.peek().pos()))) {
            BlockState placed = companion.getEntityWorld().getBlockState(last);
            if (!placed.isAir()) {
                placedScaffold.push(new ScaffoldBlock(last, placed.getBlock()));
            }
        }
        switch (result) {
            case RISING, IDLE -> {
                return TaskStatus.RUNNING;
            }
            case DONE -> {
                pillar.stop();
                pillarLevels++;
                // 回 CHOP 重试当前原木（保留 pillarLevels，但候选要重算——站位变了）。
                candidates = null;
                candidatesComputed = false;
                candidateIndex = 0;
                step = null;
                phase = Phase.CHOP;
                return TaskStatus.RUNNING;
            }
            case FAILED -> {
                return failPillar(companion, currentLog, "pillar-placement-blocked");
            }
        }
        return TaskStatus.RUNNING;
    }

    /**
     * 回收自己搭的脚手架：从栈顶（最高、最后搭的）向下逐块挖回。同伴此刻站在柱顶，挖掉脚下方块后靠 vanilla
     * 重力自然下降一格，再挖下一块。挖前校验该位仍是「非空气」（脚手架还在），对不上就跳过——不盲信记忆。
     */
    private TaskStatus tickReclaim(ServerPlayerEntity companion) {
        World world = companion.getEntityWorld();

        // 有挖掘在进行：等它完成。
        if (reclaiming != null) {
            if (CompanionMiningTasks.hasTask(companion)) {
                return TaskStatus.RUNNING;
            }
            CompanionMiningTasks.MiningResult result = CompanionMiningTasks.pollResult(companion);
            ScaffoldBlock completed = reclaiming;
            reclaiming = null;
            if (result != CompanionMiningTasks.MiningResult.BROKEN
                    || !world.getBlockState(completed.pos()).isAir()) {
                reclaimFailures.add(completed.pos().toShortString() + ": mining failed");
                diag("reclaim failed: " + completed.pos().toShortString());
            }
            placedScaffold.pop();
        }
        if (placedScaffold.isEmpty()) {
            phase = Phase.DONE;
            return finishTask(world);
        }

        ScaffoldBlock scaffold = placedScaffold.peek();
        BlockPos pos = scaffold.pos();
        // 已是空气（被别处清掉/记忆脱节）：跳过。
        if (world.getBlockState(pos).isAir()) {
            placedScaffold.pop();
            return TaskStatus.RUNNING;
        }
        // A player or another task may have replaced our dirt/cobble while the tree task was running.
        // Do not mine the replacement; record it and leave it untouched.
        if (!world.getBlockState(pos).isOf(scaffold.block())) {
            reclaimFailures.add(pos.toShortString() + ": scaffold replaced");
            diag("reclaim skip (replaced): " + pos.toShortString());
            placedScaffold.pop();
            return TaskStatus.RUNNING;
        }
        // 够不到（同伴不在柱顶、该块在头顶外）：立即跳过，不启动注定超时的挖掘。留残柱会在诊断里报告。
        if (!companion.canInteractWithBlockAt(pos, 1.0D)) {
            diag("reclaim skip (out of reach): " + pos.toShortString());
            reclaimFailures.add(pos.toShortString() + ": out of reach");
            placedScaffold.pop();
            return TaskStatus.RUNNING;
        }
        CompanionInputController.lookAt(companion, pos.toCenterPos());
        if (!CompanionMiningTasks.start(companion, pos)) {
            reclaimFailures.add(pos.toShortString() + ": could not start mining");
            placedScaffold.pop();
            return TaskStatus.RUNNING;
        }
        reclaiming = scaffold;
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickNavigate(ServerPlayerEntity companion) {
        BlockPos currentLog = plan.orderedLogs().get(logIndex);
        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case ARRIVED -> {
                navigator.stop();
                phase = Phase.CHOP;
                step = null; // 从这个落脚点重新实时判并砍
                return TaskStatus.RUNNING;
            }
            // IDLE 表示无路径可走（不是到达）；NO_PATH/STUCK 同理——都换下一候选。
            case IDLE, NO_PATH, STUCK -> {
                navigator.stop();
                lastReason = result == CompanionNavigator.NavResult.STUCK ? "stuck" : "no-path";
                phase = Phase.CHOP;
                return advanceToNextCandidate(companion, currentLog);
            }
            default -> {
                if (++navigateTicks >= NAVIGATE_TIMEOUT_TICKS) {
                    navigator.stop();
                    lastReason = "nav-timeout";
                    phase = Phase.CHOP;
                    return advanceToNextCandidate(companion, currentLog);
                }
                return TaskStatus.RUNNING;
            }
        }
    }

    /** 收尾：以<b>实际世界状态</b>为准——任一计划原木仍存在即整体失败。 */
    private TaskStatus finishTask(World world) {
        phase = Phase.DONE;
        List<BlockPos> residual = new ArrayList<>();
        for (BlockPos log : plan.orderedLogs()) {
            if (!world.getBlockState(log).isAir()) {
                residual.add(log);
            }
        }
        if (residual.isEmpty()) {
            diag("DONE: all " + plan.orderedLogs().size() + " logs felled"
                    + (reclaimFailures.isEmpty() ? "" : "; scaffold residuals=" + reclaimFailures));
            return TaskStatus.SUCCESS;
        }
        diag("DONE with FAILURE: " + residual.size() + "/" + plan.orderedLogs().size()
                + " logs remain; reasons=" + failures);
        return TaskStatus.FAILURE;
    }

    private void resetPerLog() {
        candidates = null;
        candidatesComputed = false;
        candidateIndex = 0;
        lastReason = "unreached";
        step = null;
        pillarLevels = 0;
        pillarClearStep = null;
        pillarLeavesCleared = 0;
        phase = Phase.CHOP;
    }

    private static String reasonOf(TreeChopStep.Result r) {
        return switch (r) {
            case OUT_OF_REACH -> "out-of-reach";
            case OUT_OF_SIGHT -> "out-of-sight";
            case BLOCKED_BY_FOREIGN -> "blocked-by-foreign";
            case FAILED -> "mining-failed";
            default -> "unreached";
        };
    }

    private void diag(String msg) {
        if (diagSink != null) {
            diagSink.sendFeedback(() -> Text.literal("[FellTree] " + msg), false);
        }
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (step != null) {
            step.cancel(companion);
            step = null;
        }
        if (pillar != null) {
            pillar.stop();
        }
        if (pillarClearStep != null) {
            pillarClearStep.cancel(companion);
            pillarClearStep = null;
        }
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
        CompanionMiningTasks.cancel(companion);
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        int total = plan == null ? 0 : plan.orderedLogs().size();
        return "FellNaturalTree(" + phase + ", log=" + logIndex + "/" + total
                + ", failures=" + failures.size() + ")";
    }
}
