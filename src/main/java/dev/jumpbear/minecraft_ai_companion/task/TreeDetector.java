package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 同伴周围「树木识别」的纯静态、无状态工具。
 *
 * <p>本类被刻意设计为一个<em>工具</em>，而不是任务：它没有字段、不参与 tick、
 * 也不对世界产生副作用。每次调用 {@link #findNearestTree} 都会从零重新扫描世界，
 * 返回一份不可变的 {@link Tree} 快照。这是有意为之的——归档版的砍树模块把「识别」
 * 和「执行状态」混在一起（哪些原木被跳过、是否已搭柱上升、正在处理的原木列表），
 * 这种耦合正是它 bug 频出的重要原因。这里的识别只承担唯一一项职责：
 * 「我附近有没有一棵真实的、自然生成的树？它由哪些方块组成？」凡是会改动世界的动作
 * （挖掘、搭柱、拾取掉落物）都放在另一个<em>消费</em>这份快照的任务里。
 *
 * <p>正因为无状态，同一个识别器可以每 tick 调用、可以从调试命令调用、也可以从未来的
 * 砍树任务调用，它永远针对实时世界作答，不保留任何上一次调用的记忆。单次扫描唯一需要的
 * 临时状态——已被判定为「非自然树」而否决掉的原木簇集合——以方法局部变量保存并作为参数
 * 传递，绝不作为字段。
 */
public final class TreeDetector {

    /** 种子原木搜索盒的水平半径（单位：方块，每个水平轴各延伸这么远）。 */
    private static final int SEARCH_HORIZONTAL = 12;
    /** 种子搜索从同伴脚下向下延伸的距离。 */
    private static final int SEARCH_DOWN = 4;
    /** 种子搜索从同伴脚下向上延伸的距离。 */
    private static final int SEARCH_UP = 8;
    /**
     * 单次洪水填充能收集的原木数量硬上限。这是防止把连片森林（丛林树冠、深色橡木林）
     * 当成一棵「树」拉进来的保险：一旦达到这个数量就停止扩张，把已收集到的当作该簇，
     * 既限制了计算量，也让单个砍树目标保持在合理规模。
     */
    private static final int MAX_TREE_LOGS = 256;
    /**
     * 一个原木簇要被判定为真实树木，其周围所需的<em>自然</em>（非持久化）树叶最少数量。
     * 这个过滤条件用于排除玩家搭建的原木结构（原木小屋、栅栏、柱子）——它们周围没有
     * 自然树叶。
     */
    private static final int MIN_TREE_LEAVES = 4;
    /**
     * 统计自然树叶时，围绕每块原木搜索的盒半径。2 格足以覆盖直接压在树干顶上／旁边的
     * 树冠，又不至于扩散到相邻树木的枝叶里。
     */
    private static final int LEAF_SEARCH_RADIUS = 2;

    private TreeDetector() {
    }

    /**
     * 一棵已识别树木的不可变快照。
     *
     * @param logs 属于这棵树的全部原木方块，按洪水填充的发现（BFS）顺序排列。未来的砍树
     *             任务可以直接消费整簇（例如自底向上挖），无需重新扫描。
     * @param base 树干根部：整簇中 Y 最低的原木；Y 相同时用打包坐标作决定性 tiebreak，
     *             保证选取结果稳定。
     */
    public record Tree(List<BlockPos> logs, BlockPos base) {
    }

    /**
     * 查找同伴周围最近的一棵真实、自然生成的树。
     *
     * <p>算法：
     * <ol>
     *   <li>解析服务端世界；若我们不在服务端世界中则直接返回空。</li>
     *   <li>反复：取最近的种子原木（跳过任何已属于被否决簇的方块），对其连通的原木簇做
     *       洪水填充，再判定该簇是否为自然树。未通过树叶检测的簇会把它的<em>每一块原木</em>
     *       都加入 {@code rejected}，这样下一轮种子搜索就不会再选到同一批原木、从而不会
     *       陷入死循环。</li>
     *   <li>一旦某簇通过，取该簇中 Y 最低的原木为 base（先比 Y，再用打包坐标作决定性
     *       tiebreak）。</li>
     *   <li>返回快照。</li>
     * </ol>
     *
     * @return 最近的自然树；若范围内没有则返回空。
     */
    public static Optional<Tree> findNearestTree(ServerPlayerEntity companion) {
        if (!(companion.getEntityWorld() instanceof ServerWorld world)) {
            return Optional.empty();
        }

        // 已被证实属于被否决（非自然）簇的原木。传入种子搜索，使我们永远不会再从一个已排除的
        // 方块重新播种——这正是阻断「找到种子 -> 否决簇 -> 又找到同一个种子」死循环的关键。
        // 用 BlockPos.asLong() 打包，便于低成本地做成员判断。
        Set<Long> rejected = new HashSet<>();

        while (true) {
            // 取这一轮的「种子」：搜索盒内离同伴最近、且尚未被否决的一块原木。
            // 之后会从它洪水填充出整簇原木。
            BlockPos seed = findNearestSeedLog(companion, world, rejected);
            if (seed == null) {
                // 返回 null 表示搜索盒已扫遍、再没有任何未被否决的原木——要么附近本就没有原木，
                // 要么附近的原木簇都已在前几轮被判定为非自然树而进了 rejected。两种情况结论一致：
                // 没有可用的树，结束查找。这也是循环的终止保证——每轮都会把被否决的整簇加入
                // rejected，候选只会越来越少，最终必然走到这里。
                return Optional.empty();
            }

            List<BlockPos> logs = floodFillTree(world, seed);
            if (isNaturalTree(world, logs)) {
                BlockPos base = logs.stream()
                        // 最低的原木即树干根部；asLong() 打破平局，使同 Y 的两块原木总能
                        // 解析到同一个 base。
                        .min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                                .thenComparingLong(BlockPos::asLong))
                        .orElse(seed);
                return Optional.of(new Tree(logs, base));
            }

            // 不是自然树——把这一簇里的每块原木都禁止用作后续种子，然后继续寻找下一个候选。
            for (BlockPos log : logs) {
                rejected.add(log.asLong());
            }
        }
    }

    /** 若 {@code pos} 处的方块是任意一种原木（{@code #minecraft:logs} 标签）则返回 true。 */
    private static boolean isLog(World world, BlockPos pos) {
        return world.getBlockState(pos).isIn(BlockTags.LOGS);
    }

    /**
     * 扫描同伴周围的搜索盒，返回离同伴脚下最近的原木方块，跳过 {@code rejected} 中的位置。
     * 若找不到符合条件的原木则返回 {@code null}。
     */
    private static BlockPos findNearestSeedLog(ServerPlayerEntity companion, World world, Set<Long> rejected) {
        BlockPos origin = companion.getBlockPos();
        BlockPos nearest = null;
        double nearestSq = Double.MAX_VALUE;

        // 用可变游标避免为每个被扫描的格子分配一个 BlockPos。
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -SEARCH_HORIZONTAL; dx <= SEARCH_HORIZONTAL; dx++) {
            for (int dz = -SEARCH_HORIZONTAL; dz <= SEARCH_HORIZONTAL; dz++) {
                for (int dy = -SEARCH_DOWN; dy <= SEARCH_UP; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (rejected.contains(cursor.asLong()) || !isLog(world, cursor)) {
                        continue;
                    }

                    double distSq = origin.getSquaredDistance(cursor);
                    if (distSq < nearestSq) {
                        nearestSq = distSq;
                        // 把可变游标冻结成不可变位置再保存。
                        nearest = cursor.toImmutable();
                    }
                }
            }
        }

        return nearest;
    }

    /**
     * 从 {@code seed} 开始，用 26 向（面 + 棱 + 角）邻接做洪水填充，收集连通的原木簇，
     * 使自然树干／枝条上对角相接的原木仍归为同一簇。扩张在达到 {@link #MAX_TREE_LOGS} 时停止。
     *
     * @return 该簇的原木，按发现（BFS）顺序排列。
     */
    private static List<BlockPos> floodFillTree(World world, BlockPos seed) {
        List<BlockPos> logs = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        queue.add(seed);
        visited.add(seed.asLong());

        while (!queue.isEmpty() && logs.size() < MAX_TREE_LOGS) {
            BlockPos current = queue.poll();
            logs.add(current);

            // 26 个邻居：dx/dy/dz 各取 [-1,1]，排除 (0,0,0) 这个自身格。
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }

                        BlockPos neighbor = current.add(dx, dy, dz);
                        long key = neighbor.asLong();
                        if (visited.contains(key) || !isLog(world, neighbor)) {
                            continue;
                        }

                        visited.add(key);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return logs;
    }

    /**
     * 一个原木簇若被至少 {@link #MIN_TREE_LEAVES} 块自然树叶包围，即判定为自然树。
     * 我们围绕每块原木做 {@link #LEAF_SEARCH_RADIUS} 半径的盒搜索，统计不重复的自然树叶
     * 位置；用「不重复集合」是为了防止同一块紧邻多块原木的树叶被重复计数。
     */
    private static boolean isNaturalTree(World world, List<BlockPos> logs) {
        Set<Long> countedLeaves = new HashSet<>();

        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (BlockPos log : logs) {
            for (int dx = -LEAF_SEARCH_RADIUS; dx <= LEAF_SEARCH_RADIUS; dx++) {
                for (int dy = -LEAF_SEARCH_RADIUS; dy <= LEAF_SEARCH_RADIUS; dy++) {
                    for (int dz = -LEAF_SEARCH_RADIUS; dz <= LEAF_SEARCH_RADIUS; dz++) {
                        cursor.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        long key = cursor.asLong();
                        if (countedLeaves.contains(key)) {
                            continue;
                        }

                        if (isNaturalLeaf(world.getBlockState(cursor))) {
                            countedLeaves.add(key);
                            if (countedLeaves.size() >= MIN_TREE_LEAVES) {
                                // 提前退出：已经凑够判定所需的数量了。
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * 仅对<em>自然生长</em>的树叶返回 true。玩家放置的树叶其 {@code PERSISTENT = true}
     * （永不衰减）；树木自然长出的树叶其 {@code PERSISTENT = false}。要求「非持久化」的树叶，
     * 正是我们区分真实树木与玩家用原木+树叶搭建的装饰物的依据。
     */
    private static boolean isNaturalLeaf(BlockState state) {
        return state.isIn(BlockTags.LEAVES)
                && state.contains(LeavesBlock.PERSISTENT)
                && !state.get(LeavesBlock.PERSISTENT);
    }
}