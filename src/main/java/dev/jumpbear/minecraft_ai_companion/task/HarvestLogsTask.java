package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 收集木材直到<b>攒够设定数量</b>的整合任务——把「砍倒一棵树」和「捡起木材」两个已验证的子任务
 * ({@link FellNaturalTreeTask} + {@link CollectDroppedItemsTask}) 组合成一个<b>跨多棵树的配额循环</b>。
 * 这是 {@link CompanionTask} 契约里「一个任务组合已验证任务/适配器、绝不自己散落移动/挖掘逻辑」的落点：
 * 本任务不碰导航、不碰射线、不碰挖掘——它只<b>驱动</b>内层任务的生命周期，并在每轮之间做<b>配额判定</b>。
 *
 * <h2>为什么配额逻辑在这一层，而不在 FellNaturalTreeTask 里</h2>
 * 「攒够 N 根原木」是一个<b>跨任务累积</b>的量，不是单棵树的属性：一棵高树 12 根、一棵普通橡木 5 根，
 * 要凑够 64 必然砍多棵。所以配额只能在「又砍倒一棵、把这棵的收成并入总数」之后判断。
 * {@link FellNaturalTreeTask} 只负责「把它 start 时找到的<em>那一棵</em>最近的合格自然树砍倒」——它不该
 * 知道全局配额（对应 CLAUDE.md 的分工：Planner 决定做什么、Skill 只管砍一棵）。本任务就是那个 Planner 侧
 * 的循环控制器。
 *
 * <h2>计数口径：背包 log 物品的<b>增量</b></h2>
 * 达成条件不数「计划原木数」也不扫世界，而是数<b>同伴背包里 {@code #minecraft:logs} 物品比开工前多了多少</b>。
 * 这与残留、分叉、部分失败、够不到的高枝<em>全部解耦</em>——只认「真正到手的木头」，最接近「砍了多少木材」的
 * 真实语义。（掉落物→背包由内层 {@link CollectDroppedItemsTask} 完成；vanilla 拾取，无脚本化。）
 *
 * <h2>每轮流程与检查时机</h2>
 * <ol>
 *   <li><b>FELL</b>：驱动一个 {@link FellNaturalTreeTask}（静默）。它自己找当前最近的合格自然树砍倒；
 *       找不到树时直接失败——这也天然是「正常砍伐半径内没有更多树」的信号。</li>
 *   <li><b>COLLECT</b>：驱动一个 {@link CollectDroppedItemsTask}（只捡原木/树苗），把这一棵的掉落收进背包。</li>
 *   <li><b>CHECK</b>（就在这里、每轮一次）：统计背包 log 增量。
 *       <ul>
 *         <li>≥ {@code targetLogs} → {@link TaskStatus#SUCCESS}。</li>
 *         <li>否则本轮 FELL 是否砍到过树：没砍到（砍伐半径内空了）→ 进 <b>SCOUT</b> 漫游侦察；砍到了则
 *             与上一轮末总数比，无增长则「贫瘠轮」计数 +1、有增长则清零。连续 {@code maxBarrenRounds} 轮
 *             零进展 → {@link TaskStatus#FAILURE}（诚实报告「攒了 X/N」）。</li>
 *         <li>否则开下一轮 FELL。</li>
 *       </ul></li>
 *   <li><b>SCOUT</b>（漫游探索）：{@link FellNaturalTreeTask} 只看脚下 {@link TreeDetector} 的正常半径，
 *       够不到摊得更开的林子。当砍伐半径内空了，用<b>更大的侦察半径</b>({@link #SCOUT_RADIUS})找一棵远树，
 *       用已验证的 {@link CompanionNavigator} 走到它附近（进入正常砍伐半径内），再回 FELL 正常砍。走不到的
 *       远树记入<b>避让集</b>不反复纠缠；连侦察半径都没树才真正判失败。侦察本身不砍不挖，只复用导航适配器。</li>
 * </ol>
 *
 * <p><b>状态可见</b>：失败时最后总数保留在诊断里；成功即「够了就停」，不会砍空整片林子。
 */
public final class HarvestLogsTask implements CompanionTask {

    /** 需要攒够的原木物品数（背包增量口径）。 */
    private final int targetLogs;
    /** 连续多少轮「收集后 log 总数无增长」即判失败（防在反复失败/无树时无限找）。 */
    private final int maxBarrenRounds;
    /** 可选诊断输出；仅命令发起者可见。null 时静默。 */
    private final ServerCommandSource diagSink;

    /** 漫游侦察的水平半径：远大于 {@link TreeDetector} 的正常砍伐半径，用于发现摊得更开的林子。 */
    private static final int SCOUT_RADIUS = 48;
    /** 导航到远树的到达判定距离（格）：走到树附近、进入正常砍伐半径即可，精确落脚交给 FellNaturalTree。 */
    private static final int SCOUT_REACH_DISTANCE = 6;
    /** 单次侦察导航的超时（tick）：走不到就把该树记入避让集、换下一棵，不无限走。 */
    private static final int SCOUT_NAV_TIMEOUT_TICKS = 400;

    private enum Phase { FELL, COLLECT, CHECK, SCOUT, SCOUT_NAV, DONE }

    private Phase phase = Phase.FELL;
    /** start 阶段就判定的终态（异常早退）；非空则第一个 tick 直接返回它。 */
    private TaskStatus terminal;

    /** 开工前背包里的 log 物品数——增量的基线。 */
    private int baselineLogs;
    /** 上一轮 CHECK 时的 log 总数，用于「本轮是否有进展」。 */
    private int lastRoundLogs;
    /** 连续零进展轮数。 */
    private int barrenRounds;
    /** 已进行的轮数（诊断用）。 */
    private int round;

    /** 当前正在驱动的内层子任务（FellNaturalTree 或 CollectDroppedItems），null 表示本轮尚未起。 */
    private CompanionTask inner;

    /** 漫游导航适配器（懒建）。复用已验证的 {@link CompanionNavigator}，本任务不自写移动逻辑。 */
    private CompanionNavigator navigator;
    /** 侦察时已确认「走不到」的远树原木坐标（asLong 打包）：传给 TreeDetector 跳过，不反复纠缠同一棵。 */
    private final Set<Long> unreachable = new HashSet<>();
    /** 当前侦察导航已走的 tick 数（超时换下一棵）。 */
    private int scoutNavTicks;
    /** 当前侦察正在前往的那棵树的全部原木（走不到时整簇记入 {@link #unreachable}）。 */
    private Set<Long> scoutTargetLogs;

    public HarvestLogsTask(int targetLogs, int maxBarrenRounds, ServerCommandSource diagSink) {
        this.targetLogs = Math.max(1, targetLogs);
        this.maxBarrenRounds = Math.max(1, maxBarrenRounds);
        this.diagSink = diagSink;
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        baselineLogs = countLogs(companion);
        lastRoundLogs = baselineLogs;
        diag("start: target=" + targetLogs + " logs, baseline=" + baselineLogs
                + " in inventory, maxBarrenRounds=" + maxBarrenRounds);
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        if (terminal != null) {
            return terminal;
        }

        // 本轮若还没起内层任务，按当前相位起一个。
        if (inner == null && (phase == Phase.FELL || phase == Phase.COLLECT)) {
            round += (phase == Phase.FELL ? 1 : 0);
            inner = phase == Phase.FELL
                    ? new FellNaturalTreeTask(null)
                    : new CollectDroppedItemsTask(companion, HarvestLogsTask::isLogOrSapling);
            inner.start(companion);
        }

        return switch (phase) {
            case FELL, COLLECT -> tickInner(companion);
            case CHECK -> tickCheck(companion);
            case SCOUT -> tickScout(companion);
            case SCOUT_NAV -> tickScoutNav(companion);
            case DONE -> TaskStatus.SUCCESS;
        };
    }

    /** 驱动当前内层子任务；它到终态就 stop 它、推进相位。内层的失败<b>不</b>直接翻转本任务——靠配额判定收尾。 */
    private TaskStatus tickInner(ServerPlayerEntity companion) {
        TaskStatus s = inner.tick(companion);
        if (!s.isTerminal()) {
            return TaskStatus.RUNNING;
        }
        inner.stop(companion, s);
        inner = null;
        // FELL 完成（无论砍成没砍成）→ 去收集这一棵的掉落；COLLECT 完成 → 去做配额检查。
        phase = phase == Phase.FELL ? Phase.COLLECT : Phase.CHECK;
        return TaskStatus.RUNNING;
    }

    /**
     * 每轮收集后做一次配额判定：够了成功；本轮有进展则继续下一轮 FELL；本轮零进展则先去 SCOUT 漫游找远树
     * （砍伐半径内可能已空），并累计贫瘠轮——连续 {@code maxBarrenRounds} 轮零进展即失败（漫游也找不到树
     * 时最终会走到这里，是无限循环的总闸）。
     */
    private TaskStatus tickCheck(ServerPlayerEntity companion) {
        int current = countLogs(companion);
        int gained = current - baselineLogs;
        if (gained >= targetLogs) {
            phase = Phase.DONE;
            diag("DONE: harvested " + gained + "/" + targetLogs + " logs over " + round + " rounds");
            return TaskStatus.SUCCESS;
        }

        boolean progressed = current > lastRoundLogs;
        if (progressed) {
            barrenRounds = 0;
        } else {
            barrenRounds++;
        }
        lastRoundLogs = current;
        diag("round " + round + ": " + gained + "/" + targetLogs + " logs (barren " + barrenRounds
                + "/" + maxBarrenRounds + ")");

        if (barrenRounds >= maxBarrenRounds) {
            phase = Phase.DONE;
            terminal = TaskStatus.FAILURE;
            diag("FAILURE: " + barrenRounds + " rounds with no progress; harvested "
                    + gained + "/" + targetLogs + " logs");
            return TaskStatus.FAILURE;
        }

        // 本轮有进展：砍伐半径内还有树，直接开下一轮 FELL。零进展：半径内可能空了，先漫游找远树重定位。
        phase = progressed ? Phase.FELL : Phase.SCOUT;
        return TaskStatus.RUNNING;
    }

    /**
     * 漫游侦察：用大半径 {@link #SCOUT_RADIUS} 找一棵砍伐半径够不到的远树，导航过去。找到 → 进 SCOUT_NAV
     * 走过去；大半径也没树（避让集之外全空）→ 回 CHECK 让贫瘠计数收尾（下一轮仍零进展即失败）。
     */
    private TaskStatus tickScout(ServerPlayerEntity companion) {
        if (navigator == null) {
            navigator = new CompanionNavigator(companion);
        }
        Optional<TreeDetector.Tree> far = TreeDetector.findNearestTree(companion, SCOUT_RADIUS, unreachable);
        if (far.isEmpty()) {
            // 连大半径都没有未避让的树：真的没得砍了。回 FELL——它会立即 findNearestTree 失败、
            // 经 COLLECT→CHECK 再记一个贫瘠轮，最终触发 maxBarrenRounds 失败（诚实收尾，不自造终态）。
            diag("scout: no tree within " + SCOUT_RADIUS + " blocks; giving up relocation");
            phase = Phase.FELL;
            return TaskStatus.RUNNING;
        }

        TreeDetector.Tree tree = far.get();
        scoutTargetLogs = new HashSet<>();
        for (BlockPos log : tree.logs()) {
            scoutTargetLogs.add(log.asLong());
        }
        if (!navigator.pathTo(tree.base(), SCOUT_REACH_DISTANCE)) {
            // 走不到这棵：整簇记入避让集，下个 tick 找下一棵（不反复纠缠同一棵够不到的树）。
            unreachable.addAll(scoutTargetLogs);
            scoutTargetLogs = null;
            diag("scout: no path to tree at " + tree.base().toShortString() + "; avoiding it");
            return TaskStatus.RUNNING;
        }
        scoutNavTicks = 0;
        phase = Phase.SCOUT_NAV;
        diag("scout: relocating toward tree at " + tree.base().toShortString());
        return TaskStatus.RUNNING;
    }

    /** 走向侦察选中的远树；到达（进入正常砍伐半径附近）→ 回 FELL 正常砍；走不到 → 记避让、回 SCOUT 换一棵。 */
    private TaskStatus tickScoutNav(ServerPlayerEntity companion) {
        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case ARRIVED -> {
                navigator.stop();
                scoutTargetLogs = null;
                phase = Phase.FELL; // 已在树附近，正常半径的 FellNaturalTree 现在够得到它
                return TaskStatus.RUNNING;
            }
            case IDLE, NO_PATH, STUCK -> {
                navigator.stop();
                if (scoutTargetLogs != null) {
                    unreachable.addAll(scoutTargetLogs);
                    scoutTargetLogs = null;
                }
                phase = Phase.SCOUT; // 换下一棵远树
                return TaskStatus.RUNNING;
            }
            default -> {
                if (++scoutNavTicks >= SCOUT_NAV_TIMEOUT_TICKS) {
                    navigator.stop();
                    if (scoutTargetLogs != null) {
                        unreachable.addAll(scoutTargetLogs);
                        scoutTargetLogs = null;
                    }
                    phase = Phase.SCOUT;
                }
                return TaskStatus.RUNNING;
            }
        }
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (inner != null) {
            inner.stop(companion, finalStatus);
            inner = null;
        }
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
            navigator = null;
        }
    }

    @Override
    public String describe() {
        return "HarvestLogs(" + phase + ", round=" + round + ", target=" + targetLogs
                + ", barren=" + barrenRounds + ")";
    }

    /** 背包中所有 {@code #minecraft:logs} 物品的总数量。 */
    private static int countLogs(ServerPlayerEntity companion) {
        int total = 0;
        var inventory = companion.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && stack.isIn(ItemTags.LOGS)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 内层收集过滤：只主动去捡原木和树苗（伐木工的收成），忽略无关掉落。 */
    private static boolean isLogOrSapling(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.SAPLINGS);
    }

    private void diag(String msg) {
        if (diagSink != null) {
            diagSink.sendFeedback(() -> Text.literal("[Harvest] " + msg), false);
        }
    }
}