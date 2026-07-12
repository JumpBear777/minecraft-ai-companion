package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 为「够到某个底部原木（通常是 base）」挑选一个合格的<b>落脚站格</b>，并顺带记录为此需要先清掉的
 * <b>本树接近遮挡叶</b>。这是砍树「接近规划」的第一块：同伴要站正下方开井，但 base 及其上一两块的正下方
 * 是泥土、钻不进去，只能先站在一个相邻格侧向够着挖——本类就负责挑这个相邻格。
 *
 * <p><b>纯只读、纯静态</b>：全部判断在扫描到的世界数据上做查询与算术，<em>不调寻路器、不改动世界</em>。
 * 这正是「扫描时数据已在手，就地判完」这一分工的落点——{@link TreeDetector} 识别树、{@link TreeStructure}
 * 定砍伐序、本类定落脚，三者都无状态、各司其职。
 *
 * <h2>候选与渐进半径</h2>
 * 以目标块所在竖列为中心，先在水平半径 {@link #INNER_RADIUS} 内找合格落脚格；找不到再扩到
 * {@link #OUTER_RADIUS}；仍找不到则放弃这棵树（返回空）。高树 base 处无叶，近处半径 1 就合格、第一轮命中；
 * 矮树树冠贴地把近处占满，靠扩张到 3 落在叶外沿。竖向候选在 {@code target.y-1 .. target.y+3} 找脚面。
 *
 * <h2>合格判据（三条全过）</h2>
 * <ol>
 *   <li><b>站得上</b>：候选格脚下是实心碰撞面，候选格与其上一格是 2 格身体空间。</li>
 *   <li><b>够得到</b>：从候选格推算的眼睛位置，到目标块用 vanilla 挖掘同一距离判据
 *       （眼→方块 AABB 最近点 &lt; 交互距离+1.0）。</li>
 *   <li><b>看得见</b>：眼睛→目标中心的线段射线命中的正是目标；若被挡且挡的是<em>本树叶</em>，
 *       记入待清清单、不淘汰；挡的是别的方块则淘汰该候选。</li>
 * </ol>
 * 合格候选按「需清叶最少 → 最贴近目标高度 → 最近 → 打包坐标兜底」择优。
 */
public final class TreeApproach {

    /** 落脚候选的初始水平搜索半径。 */
    private static final int INNER_RADIUS = 3;
    /** 初始半径找不到时扩张到的最大半径；仍找不到则放弃这棵树。 */
    private static final int OUTER_RADIUS = 4;
    /** 竖向候选相对目标块向下探的格数（覆盖单级下坡的低落脚）。 */
    private static final int DOWN = 1;
    /** 竖向候选相对目标块向上探的格数（覆盖站上坡侧低头够取）。 */
    private static final int UP = 3;
    /** 判定一片叶是否属于本树：离本树任一原木的水平/竖直范围（格）。 */
    private static final int LEAF_OWNERSHIP_RANGE = 2;

    private TreeApproach() {
    }

    /**
     * 一个落脚方案：站格 + 为够到目标需先清掉的本树接近遮挡叶。
     *
     * @param foothold  选中的落脚站格
     * @param occluders 需先清掉的本树叶（可能为空——表示无需清理直接可站可挖）
     * @param radius    实际用到的搜索半径（用于诊断/展示）
     */
    public record Approach(BlockPos foothold, List<BlockPos> occluders, int radius) {
    }

    /**
     * 为够到 {@code target} 挑一个落脚方案。
     *
     * @param player 同伴（用于世界、眼高、交互距离等 vanilla 度量）
     * @param tree   已识别的树（用于「这片叶属不属于本树」的判定）
     * @param target 要够到的底部原木（通常是 base）
     * @return 合格落脚方案；渐进扩张到最大半径仍无合格候选则返回空（应跳过该树）
     */
    public static Optional<Approach> plan(ServerPlayerEntity player, TreeDetector.Tree tree, BlockPos target) {
        World world = player.getEntityWorld();
        double eyeHeight = player.getStandingEyeHeight();
        double reach = player.getBlockInteractionRange() + 1.0D;

        // 渐进半径：先 INNER，找不到再 OUTER。
        for (int radius : new int[]{INNER_RADIUS, OUTER_RADIUS}) {
            List<Approach> candidates = new ArrayList<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue; // 目标所在列，人站不进去
                    }
                    if (Math.max(Math.abs(dx), Math.abs(dz)) > radius) {
                        continue;
                    }
                    for (int dy = -DOWN; dy <= UP; dy++) {
                        BlockPos foot = target.add(dx, dy, dz);
                        Approach approach = evaluate(world, tree, target, foot, eyeHeight, reach, radius);
                        if (approach != null) {
                            candidates.add(approach);
                        }
                    }
                }
            }
            if (!candidates.isEmpty()) {
                // AI 当前脚下位置：用于「就近选面」——在同样合格的候选里优先离同伴近的，
                // 避免绕到树的另一侧（四个正交面几何对称时，若无此键就只能靠 asLong 决胜，
                // 恒定选坐标最小的一侧、与同伴实际所在无关）。
                BlockPos playerPos = player.getBlockPos();
                candidates.sort(Comparator
                        // 1) 正对 base 面优先：正交方向（dx/dz 有一个为 0）胜过斜对角。斜角位常常“清完也够不到
                        //    base 的面”，所以让正面落脚点优先，即使它需清的叶更多。
                        .comparingInt((Approach a) -> isDiagonalTo(a.foothold(), target) ? 1 : 0)
                        // 2) 最贴近 base 高度
                        .thenComparingInt(a -> Math.abs(a.foothold().getY() - target.getY()))
                        // 3) 离同伴当前位置最近：同伴走最短路、朝最顺的那一面接近，不绕到树后。
                        .thenComparingDouble(a -> a.foothold().getSquaredDistance(playerPos))
                        // 4) 需清叶最少
                        .thenComparingInt(a -> a.occluders().size())
                        // 5) 离 base 最近
                        .thenComparingDouble(a -> a.foothold().getSquaredDistance(target))
                        // 6) 确定性兜底
                        .thenComparingLong(a -> a.foothold().asLong()));
                return Optional.of(candidates.get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * 评估单个候选站格。返回 null 表示不合格；否则返回它（含为够到目标需清的本树叶）。
     */
    private static Approach evaluate(World world, TreeDetector.Tree tree, BlockPos target,
                                     BlockPos foot, double eyeHeight, double reach, int radius) {
        // 判据 1：站得上——脚下实心，foot 与其上一格是空的身体空间。
        if (!isStandable(world, foot)) {
            return null;
        }

        // 站在 foot 时的眼睛位置。
        Vec3d eye = new Vec3d(foot.getX() + 0.5D, foot.getY() + eyeHeight, foot.getZ() + 0.5D);

        // 判据 2：够得到——复刻 vanilla canInteractWithBlockAt（眼→方块 AABB 最近点 < reach）。
        if (new Box(target).squaredMagnitude(eye) >= reach * reach) {
            return null;
        }

        // 判据 3：看得见（清叶后）——沿眼睛→目标中心的线段，在扫描到的方块数据上逐格查，不打射线。
        List<BlockPos> occluders = sightThroughOwnLeaves(world, tree, eye, target);
        if (occluders == null) {
            return null; // 视线被非本树方块挡死，淘汰该候选
        }
        return new Approach(foot, List.copyOf(occluders), radius);
    }

    /**
     * 沿 {@code eye → target 中心} 的线段，用<b>精确体素遍历</b>（Amanatides-Woo DDA）访问线段穿过的
     * <em>每一个</em>方块格，判断「清掉沿途本树叶后能否看见目标」。纯数据查询，不改动世界。
     *
     * <p>为什么用 DDA 而不是定步长采样：旧实现按 0.25 格步进采样，斜线可能在两个采样点之间擦过一个格的角
     * 而漏检——一片本该记入待清的挡视线叶被跳过，规划判「视线通」，执行期却被它挡住（CHOP FAIL）。DDA
     * 逐边界步进，数学上保证不漏任何被线段穿过的格。
     *
     * <p>与挖掘门控 {@link TreeChopSight#hasLineOfSight} 现在共用同一套视线模型——「从眼睛沿直线到目标
     * 中心，满碰撞方块挡断视线，本树叶视作可清除而穿过」——只是门控用 vanilla {@code world.raycast}
     * （执行期权威判据），本方法用无盲区的 DDA（规划期）。二者过去因「规划用理想眼位、执行用漂移后实际
     * 眼位」而分歧，已由 ALIGN 阶段把身体对齐到落脚格中心消除。
     *
     * <ul>
     *   <li>命中目标格 → 视线通，返回沿途收集到的<b>全部</b>本树待清叶（可能为空）。</li>
     *   <li>命中本树叶 → 记入待清清单，<em>继续前进</em>（相当于假设它已被清掉）。</li>
     *   <li>命中别的满碰撞方块（地形/建筑/别树）→ 清它会误伤且挡死，返回 null（淘汰候选）。</li>
     *   <li>空气/无碰撞 → 继续。</li>
     * </ul>
     *
     * @return 沿途需清的本树叶列表（视线可通）；或 null（被非本树方块挡死）
     */
    private static List<BlockPos> sightThroughOwnLeaves(World world, TreeDetector.Tree tree,
                                                        Vec3d eye, BlockPos target) {
        Vec3d end = target.toCenterPos();
        List<BlockPos> occluders = new ArrayList<>();

        double dx = end.x - eye.x;
        double dy = end.y - eye.y;
        double dz = end.z - eye.z;
        // 退化：眼睛已在目标格内，视线通、无需清叶。
        if (dx * dx + dy * dy + dz * dz < 1.0e-12) {
            return occluders;
        }

        // 当前格 = 眼睛所在格。
        int cx = (int) Math.floor(eye.x);
        int cy = (int) Math.floor(eye.y);
        int cz = (int) Math.floor(eye.z);
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        // 到下一条格边界的参数距离 t（射线 P(t)=eye+t*dir, t∈[0,1] 到达 end），以及跨一格的 t 增量。
        double tMaxX = boundaryT(eye.x, dx, stepX);
        double tMaxY = boundaryT(eye.y, dy, stepY);
        double tMaxZ = boundaryT(eye.z, dz, stepZ);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        int targetX = target.getX();
        int targetY = target.getY();
        int targetZ = target.getZ();
        BlockPos.Mutable cell = new BlockPos.Mutable();
        // 步数上限：线段最多穿过 |dx|+|dy|+|dz|+3 个格，给足冗余即可，纯防御。
        int maxSteps = (int) (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) + 4;
        for (int i = 0; i <= maxSteps; i++) {
            if (cx == targetX && cy == targetY && cz == targetZ) {
                return occluders; // 到达目标格：视线通
            }
            // 眼睛所在的起始格永远不算遮挡（身体自己不会挡自己）。
            if (i > 0) {
                cell.set(cx, cy, cz);
                if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                    if (isOwnTreeLeaf(world, tree, cell)) {
                        occluders.add(cell.toImmutable()); // 本树叶：记为待清，穿过它继续
                    } else {
                        return null; // 非本树满碰撞方块挡死：淘汰
                    }
                }
            }
            // 步进到下一格：沿最先到达的那条边界前进。
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                cx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                cy += stepY;
                tMaxY += tDeltaY;
            } else {
                cz += stepZ;
                tMaxZ += tDeltaZ;
            }
        }
        return occluders;
    }

    /**
     * 从坐标 {@code origin} 沿方向分量 {@code d}（正负决定步进方向 {@code step}）前进到<b>第一条整数格
     * 边界</b>的参数距离 t。用于 DDA 初始化。{@code d==0} 时该轴永不越界（返回正无穷）。
     */
    private static double boundaryT(double origin, double d, int step) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double frac = origin - Math.floor(origin);
        double distToBoundary = step > 0 ? (1.0 - frac) : frac;
        return distToBoundary / Math.abs(d);
    }

    /** 落脚点相对目标是否为斜对角（水平方向上 x、z 偏移都非 0）。正交方向（正对某个面）返回 false。 */
    private static boolean isDiagonalTo(BlockPos foot, BlockPos target) {
        return foot.getX() != target.getX() && foot.getZ() != target.getZ();
    }

    /** 脚下有实心碰撞面，且 foot 与其上一格是空（2 格身体空间）。 */
    private static boolean isStandable(World world, BlockPos foot) {
        boolean groundSolid = !world.getBlockState(foot.down()).getCollisionShape(world, foot.down()).isEmpty();
        boolean feetClear = world.getBlockState(foot).getCollisionShape(world, foot).isEmpty();
        boolean headClear = world.getBlockState(foot.up()).getCollisionShape(world, foot.up()).isEmpty();
        return groundSolid && feetClear && headClear;
    }

    /** 一片方块是否为「本树的自然叶」：自然叶（LEAVES 且 persistent=false）且离本树任一原木 ≤ 范围。 */
    private static boolean isOwnTreeLeaf(World world, TreeDetector.Tree tree, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!(state.isIn(BlockTags.LEAVES) && state.contains(LeavesBlock.PERSISTENT)
                && !state.get(LeavesBlock.PERSISTENT))) {
            return false;
        }
        for (BlockPos log : tree.logs()) {
            if (Math.abs(log.getX() - pos.getX()) <= LEAF_OWNERSHIP_RANGE
                    && Math.abs(log.getY() - pos.getY()) <= LEAF_OWNERSHIP_RANGE
                    && Math.abs(log.getZ() - pos.getZ()) <= LEAF_OWNERSHIP_RANGE) {
                return true;
            }
        }
        return false;
    }
}