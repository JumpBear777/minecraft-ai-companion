package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次 {@link TreeDetector} 扫描得到的原木簇，整理成一条<b>有序砍伐/处理结构</b>：<b>以“竖列”为单位，
 * 每一列从下到上整根处理完，再换下一列；主干列最先，其余分叉列按离主干由近到远</b>。这份有序结构一旦算出
 * 就固定下来，供「上色可视化」「按序砍伐」等步骤消费——执行永远按这条已存好的顺序走，
 * <em>不再反复扫描世界</em>。
 *
 * <p>这是「结构捕获一次、执行消费结构」这一分工的落点：{@link TreeDetector} 只负责“有没有树、由哪些块
 * 组成”（无状态识别），本类只负责“按什么顺序处理它们”（无状态排序），真正的动作（走、挖、放）由任务
 * 与命令去做。三者互不越界。
 *
 * <h2>为什么以“列”为单位（同列优先，而非同层）</h2>
 * 砍伐必须<b>优先同一竖列（相同 x,z）</b>：一列从下往上砍空，再动下一列。若按“同一 Y 层”横扫，会在不同
 * 列之间来回跳，导致同伴脚下/够取关系频繁变化，后续（尤其是搭方块辅助够取时）会产生大量不合理情形。
 * 把处理单位定成“列”，就保证了每一列都是自底向上被清空的连续过程。
 *
 * <h2>排序规则</h2>
 * <ol>
 *   <li>先按 (x,z) 把原木分成若干<b>竖列</b>。</li>
 *   <li><b>主干列</b>（base 所在的 x,z 列）整列排在最前，自底向上。</li>
 *   <li>其余<b>分叉列</b>按到主干竖轴的水平距离由近到远排；同距离用列底块的 Y、
 *       再用 {@link BlockPos#asLong()} 兜底，保证顺序完全确定、可重复。</li>
 *   <li>每一列内部一律 Y 从低到高。</li>
 * </ol>
 *
 * <p>注：将来若要在够不着时搭方块（须搭在待挖块正下方），会依赖“每一列自底向上清空”这个前提；顺路挖到
 * 的其他待挖块、搭方块等行为目前<b>不在本类范围</b>，本类只产出顺序。丛林/深色橡木的 2×2 粗主干这次只把
 * base 那一列当主干，其余三列会被当作分叉列——超出当前范围。
 */
public final class TreeStructure {

    private TreeStructure() {
    }

    /**
     * 按「主干列在前、其余分叉列由近到远、每列自底向上」把树的原木排成一条确定顺序。
     *
     * @param tree {@link TreeDetector} 的扫描结果（logs + base）
     * @return 有序的原木坐标列表；主干列在前（base→顶），随后逐列分叉（每列从低到高）
     */
    public static List<BlockPos> ordered(TreeDetector.Tree tree) {
        BlockPos base = tree.base();

        // 1) 按 (x,z) 竖列分组。用 LinkedHashMap 只为遍历稳定；列间顺序稍后显式排序决定。
        Map<Long, List<BlockPos>> columns = new LinkedHashMap<>();
        for (BlockPos p : tree.logs()) {
            columns.computeIfAbsent(columnKey(p), k -> new ArrayList<>()).add(p);
        }

        // 2) 每列内部自底向上（Y 升序，asLong 兜底）。
        for (List<BlockPos> column : columns.values()) {
            column.sort(Comparator.comparingInt(BlockPos::getY).thenComparingLong(BlockPos::asLong));
        }

        // 3) 列与列之间：主干列（离轴距离 0）自然排在最前；其余按离轴距离、列底 Y、asLong 兜底。
        List<List<BlockPos>> ordered = new ArrayList<>(columns.values());
        ordered.sort(Comparator
                .comparingLong((List<BlockPos> col) -> horizontalDistanceSq(col.get(0), base))
                .thenComparingInt(col -> col.get(0).getY())
                .thenComparingLong(col -> col.get(0).asLong()));

        // 4) 拼装：逐列依次展开。
        List<BlockPos> result = new ArrayList<>(tree.logs().size());
        for (List<BlockPos> column : ordered) {
            result.addAll(column);
        }
        return result;
    }

    /** 以 (x,z) 为竖列的分组键——同一竖列的方块共享它。x 占高 32 位、z 占低 32 位，两段不重叠，
     *  位域拼接（{@code |}）即单射，同 {@code BlockPos.asLong} 的做法。 */
    private static long columnKey(BlockPos p) {
        return ((long) p.getX() << 32) | (p.getZ() & 0xffffffffL);
    }

    /** 方块 {@code p} 到 base 竖轴（同 x,z 列）的水平距离平方。 */
    private static long horizontalDistanceSq(BlockPos p, BlockPos base) {
        long dx = p.getX() - base.getX();
        long dz = p.getZ() - base.getZ();
        return dx * dx + dz * dz;
    }
}