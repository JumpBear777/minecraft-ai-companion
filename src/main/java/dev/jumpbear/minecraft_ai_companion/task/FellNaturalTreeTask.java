package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
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
 * <h2>范围（第一阶段）</h2>
 * 只做<b>地面可达</b>的逐原木流程：普通树、分支树、多主干树。超过交互高度、需要 {@link CompanionPillar}
 * 搭柱才能够到的树干留待<b>第二阶段</b>——本任务此时对够不到的高处原木诚实记失败，不建桥、不挖隧道。
 */
public final class FellNaturalTreeTask implements CompanionTask {

    /** 寻路到落脚候选的到达判定距离（格）：走到相邻即可，最终是否可开工由 {@link TreeChopStep} 实时判。 */
    private static final int REACH_DISTANCE = 1;
    /** 单个候选的导航超时（tick）：走不到就换下一候选，不无限走。 */
    private static final int NAVIGATE_TIMEOUT_TICKS = 200;

    private enum Phase { CHOP, NAVIGATE, DONE }

    /** 一块残留原木及其失败原因（供诊断/测试断言）。 */
    private record LogFailure(BlockPos log, String reason) {
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

    private final List<LogFailure> failures = new ArrayList<>();

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

        // 推进到下一块仍存在的待砍原木。
        while (logIndex < plan.orderedLogs().size()
                && world.getBlockState(plan.orderedLogs().get(logIndex)).isAir()) {
            logIndex++;
            resetPerLog();
        }
        if (logIndex >= plan.orderedLogs().size()) {
            return finishTask(world);
        }

        return switch (phase) {
            case CHOP -> tickChop(companion);
            case NAVIGATE -> tickNavigate(companion);
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

        // 候选耗尽：记录失败原因，继续下一块原木。
        failures.add(new LogFailure(currentLog, lastReason));
        diag("log " + currentLog.toShortString() + " FAIL: " + lastReason + " (candidates exhausted)");
        logIndex++;
        resetPerLog();
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
            diag("DONE: all " + plan.orderedLogs().size() + " logs felled");
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
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
        dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks.cancel(companion);
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        int total = plan == null ? 0 : plan.orderedLogs().size();
        return "FellNaturalTree(" + phase + ", log=" + logIndex + "/" + total
                + ", failures=" + failures.size() + ")";
    }
}
