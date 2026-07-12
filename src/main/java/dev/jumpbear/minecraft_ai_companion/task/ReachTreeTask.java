package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 「走到树旁」测试任务（树处理测试链的第一步，寻路阶段）。
 *
 * <p>本任务只做一件事：扫描最近的自然树，把它整理成一条<b>有序结构</b>（{@link TreeStructure}，低→高、
 * 同层树干优先）记下来，然后导航走到树干根部（base）旁、面向它。<b>不改动任何方块</b>——上色与砍伐是
 * 后续两条命令的事。有序结构由本任务算出、经 {@link #orderedLogs()} 交给命令侧保存，贯穿整条测试链，
 * 执行永远按它走而不再重扫世界。
 *
 * <p><b>矮树是已知失败源</b>：矮树树冠贴地，树叶（有完整碰撞体）环绕 base，{@link CompanionNavigator}
 * 借用的 villager 代理寻路会被挡住，无法把同伴带到 base 相邻格，表现为 {@code NO_PATH}/{@code STUCK}。
 * 这不是 bug，是「同伴是 ServerPlayerEntity、借 villager 代理寻路」在矮树几何下的固有局限。此时本任务
 * 干净地报 {@link TaskStatus#FAILURE} 并让状态可见，而不是硬挤。
 */
public final class ReachTreeTask implements CompanionTask {

    /** 走到 base 的到达判定距离（格），交给 {@code pathTo} 的 reachDistance。 */
    private static final int REACH_DISTANCE = 2;
    /** 整体超时（tick）：走不到就报失败，避免矮树被树叶挡住时无限重试。 */
    private static final int TIMEOUT_TICKS = 200;

    private enum Phase { NAVIGATE, DONE }

    private CompanionNavigator navigator;
    private Phase phase = Phase.NAVIGATE;

    /** 树干根部：导航目标，也是后续交互/上色的锚点。 */
    private BlockPos base;
    /** 有序原木结构（低→高、树干优先），交给命令侧保存供上色与砍伐使用。 */
    private List<BlockPos> orderedLogs = List.of();
    private int ticks;

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);

        var tree = TreeDetector.findNearestTree(companion);
        if (tree.isEmpty()) {
            // 没有树可用：start 里无法直接终止，置空后由第一个 tick 收尾为 FAILURE。
            this.base = null;
            return;
        }

        TreeDetector.Tree t = tree.get();
        this.base = t.base();
        this.orderedLogs = TreeStructure.ordered(t);
        navigator.pathTo(base, REACH_DISTANCE);
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (base == null) {
            return TaskStatus.FAILURE; // start 时没找到树
        }
        if (phase == Phase.DONE) {
            return TaskStatus.SUCCESS;
        }

        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case ARRIVED, IDLE -> {
                // 到位：面向 base，交出控制权，后续命令负责上色与砍伐。
                CompanionInputController.lookAt(companion, base.toCenterPos());
                navigator.stop();
                phase = Phase.DONE;
                return TaskStatus.SUCCESS;
            }
            case NO_PATH, STUCK -> {
                // 矮树被树叶挡住的典型路径：干净失败，不硬挤。
                navigator.stop();
                return TaskStatus.FAILURE;
            }
            default -> {
                if (++ticks >= TIMEOUT_TICKS) {
                    navigator.stop();
                    return TaskStatus.FAILURE;
                }
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
        CompanionInputController.releaseInput(companion);
    }

    @Override
    public String describe() {
        return "ReachTree(" + phase + ", base=" + (base == null ? "none" : base.toShortString())
                + ", logs=" + orderedLogs.size() + ")";
    }

    /** 树干根部坐标；none 时为 null。 */
    public BlockPos base() {
        return base;
    }

    /** 有序原木结构（低→高、树干优先），供上色与砍伐命令消费。 */
    public List<BlockPos> orderedLogs() {
        return orderedLogs;
    }
}