package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Optional;

/**
 * <b>窄范围调试 / 回归用例</b>：走到 base 旁一个落脚候选 → 用共享的 {@link TreeChopStep} 视线门控挖掉
 * base 与其正上方一块（共 2 块）。这是把「探测 → 落脚规划 → 导航 → 实时门控挖掘」拼成最小可执行片段的
 * 回归探针，供 {@code /aicompanion task chop_base} 手测单块闭环是否健康。<b>正式整树砍伐请用
 * {@link FellNaturalTreeTask}</b>——本任务只砍最低两块、不逐原木重规划，刻意保持窄。
 *
 * <p>与正式任务共享同一套执行原语：落脚候选来自 {@link TreeApproach#plan}（排序列表，逐个尝试），
 * 挖掘/清叶/视线门控全走 {@link TreeChopStep}（单一实时判据），移动全走 {@link CompanionNavigator}
 * （任务内不再自行 {@code applyServerTravelForward} 微调）。诊断输出只发给命令发起者（{@link #diagSink}），
 * 不再向全服广播。
 *
 * <h2>每步可恢复</h2>
 * {@link #stop} 无条件、幂等释放：取消半截挖掘 + 清破坏裂纹、停并释放寻路代理、松开输入。
 */
public final class ReachAndChopTask implements CompanionTask {

    /** 寻路到落脚候选的到达判定距离（格）；最终能否开工由 {@link TreeChopStep} 实时判。 */
    private static final int REACH_DISTANCE = 1;
    /** 单候选导航超时（tick）。 */
    private static final int NAVIGATE_TIMEOUT_TICKS = 200;

    private enum Phase { NAVIGATE, CHOP, DONE }

    /** 可选诊断输出目标；仅命令发起者可见。null 时静默。 */
    private final ServerCommandSource diagSink;

    private CompanionNavigator navigator;
    private TreeDetector.Tree tree;
    private TreePlan plan;
    private Phase phase = Phase.NAVIGATE;

    private BlockPos base;
    /** 要挖的块：base 与其上一块，自底向上。 */
    private List<BlockPos> chopTargets = List.of();
    private int chopIndex;

    private List<TreeApproach.Approach> candidates = List.of();
    private int candidateIndex;
    private int navigateTicks;

    private TreeChopStep step;

    /** start 阶段就判定的终态（没树 / 没落脚点）；非空则第一个 tick 直接返回它。 */
    private TaskStatus terminal;

    public ReachAndChopTask() {
        this(null);
    }

    /** @param diagSink 可选诊断输出（仅命令发起者可见）；null 表示静默。 */
    public ReachAndChopTask(ServerCommandSource diagSink) {
        this.diagSink = diagSink;
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);

        Optional<TreeDetector.Tree> found = TreeDetector.findNearestTree(companion);
        if (found.isEmpty()) {
            terminal = TaskStatus.FAILURE;
            diag("start FAIL: no acceptable tree found");
            return;
        }
        this.tree = found.get();
        this.base = tree.base();
        this.plan = TreePlan.capture(companion.getEntityWorld(), tree, tree.evidence());
        this.chopTargets = List.of(base, base.up());

        this.candidates = TreeApproach.plan(companion, tree, base);
        if (candidates.isEmpty()) {
            terminal = TaskStatus.FAILURE;
            diag("start FAIL: no foothold for base " + base.toShortString());
            return;
        }
        diag("start OK: base=" + base.toShortString() + " candidates=" + candidates.size());
        beginNavigateToCurrentCandidate();
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (terminal != null) {
            return terminal;
        }
        return switch (phase) {
            case NAVIGATE -> tickNavigate(companion);
            case CHOP -> tickChop(companion);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    private boolean beginNavigateToCurrentCandidate() {
        while (candidateIndex < candidates.size()) {
            TreeApproach.Approach a = candidates.get(candidateIndex);
            candidateIndex++;
            if (navigator.pathTo(a.foothold(), REACH_DISTANCE)) {
                phase = Phase.NAVIGATE;
                navigateTicks = 0;
                diag("navigating to foothold " + a.foothold().toShortString());
                return true;
            }
            diag("foothold " + a.foothold().toShortString() + " unreachable (no path), trying next");
        }
        return false;
    }

    private TaskStatus tickNavigate(ServerPlayerEntity companion) {
        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case ARRIVED -> {
                navigator.stop();
                phase = Phase.CHOP;
                step = null;
                return TaskStatus.RUNNING;
            }
            case IDLE, NO_PATH, STUCK -> {
                navigator.stop();
                diag("NAVIGATE " + result + " -> next candidate");
                return nextCandidateOrFail(companion);
            }
            default -> {
                if (++navigateTicks >= NAVIGATE_TIMEOUT_TICKS) {
                    navigator.stop();
                    diag("NAVIGATE timeout -> next candidate");
                    return nextCandidateOrFail(companion);
                }
                return TaskStatus.RUNNING;
            }
        }
    }

    private TaskStatus tickChop(ServerPlayerEntity companion) {
        if (chopIndex >= chopTargets.size()) {
            phase = Phase.DONE;
            return TaskStatus.SUCCESS;
        }
        BlockPos target = chopTargets.get(chopIndex);
        if (companion.getEntityWorld().getBlockState(target).isAir()) {
            chopIndex++;
            step = null;
            return TaskStatus.RUNNING;
        }
        if (step == null) {
            step = new TreeChopStep(target, plan);
        }
        TreeChopStep.Result r = step.tick(companion);
        switch (r) {
            case IN_PROGRESS -> {
                return TaskStatus.RUNNING;
            }
            case BROKEN -> {
                diag("chop ok: broke " + target.toShortString());
                chopIndex++;
                step = null;
                return TaskStatus.RUNNING;
            }
            case OUT_OF_REACH, OUT_OF_SIGHT, BLOCKED_BY_FOREIGN, FAILED -> {
                diag("chop " + target.toShortString() + " -> " + r + "; trying next foothold");
                step.cancel(companion);
                step = null;
                return nextCandidateOrFail(companion);
            }
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus nextCandidateOrFail(ServerPlayerEntity companion) {
        if (beginNavigateToCurrentCandidate()) {
            return TaskStatus.RUNNING;
        }
        diag("FAIL: candidates exhausted for base " + base.toShortString());
        return TaskStatus.FAILURE;
    }

    private void diag(String msg) {
        if (diagSink != null) {
            diagSink.sendFeedback(() -> Text.literal("[ChopBase] " + msg), false);
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
        CompanionMiningTasks.cancel(companion);
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        return "ReachAndChop(" + phase
                + ", base=" + (base == null ? "none" : base.toShortString())
                + ", chop=" + chopIndex + "/" + chopTargets.size() + ")";
    }
}
