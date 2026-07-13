package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A tree felling plan: everything the executing {@link FellNaturalTreeTask} needs, <b>captured once</b>
 * from a {@link TreeDetector.Tree} snapshot and then never re-derived. This is the "structure captured
 * once, execution consumes the structure" division the redo is built on — the plan freezes the target
 * set (which logs, in what order) and the ownership set (which leaves may be cleared) so that leaf
 * decay, another entity's edits, or the companion's own progress can never silently change what the
 * task is allowed to touch mid-run.
 *
 * <p><b>Immutable and read-only.</b> The plan holds no execution state (no cursor, no "already tried"
 * flags) — that lives in the task. Reuse of a plan across ticks always answers about the same frozen
 * target/ownership set; the live world is queried only through the executing task's real-time sight
 * checks, never to grow the plan.
 *
 * <h2>Leaf ownership</h2>
 * The single source of truth for "may this leaf be cleared". A block is an own-tree leaf iff it is a
 * natural leaf ({@code #minecraft:leaves} tag, {@code PERSISTENT=false}) and lies within
 * {@link #LEAF_OWNERSHIP_RANGE} (Chebyshev) of some log in this plan. {@code ownLeaves} pre-computes the
 * exact set as packed {@link BlockPos#asLong()} keys so both approach planning and the chop step test
 * membership in O(1) against the <em>same</em> definition — no drift between "planned as clearable" and
 * "cleared at execution".
 */
public record TreePlan(List<BlockPos> orderedLogs, Set<Long> ownLeaves, BlockPos base, Evidence evidence) {

    /**
     * How close (Chebyshev distance, blocks) a natural leaf must be to some log in the plan to count as
     * this tree's own leaf. 2 covers a canopy pressed directly on/around the trunk without reaching into
     * an adjacent tree's foliage. Shared by approach planning and the chop step.
     */
    public static final int LEAF_OWNERSHIP_RANGE = 2;

    /**
     * Why this cluster was accepted as a natural tree, for diagnostics and repeatable test assertions.
     *
     * @param logCount      total logs in the frozen snapshot
     * @param naturalLeaves distinct natural leaves counted around the cluster during acceptance
     * @param maxLogsPerLayer the densest single Y layer's log count (a wall/floor smell if high)
     * @param height        vertical span of the cluster (maxY - minY + 1)
     * @param maxHorizontal the larger horizontal bounding-box extent (blocks)
     */
    public record Evidence(int logCount, int naturalLeaves, int maxLogsPerLayer, int height, int maxHorizontal) {
    }

    /**
     * Freeze a detected tree into an executable plan: order the logs for felling
     * ({@link TreeStructure#ordered}) and pre-compute the own-leaf ownership set. Pure — reads the world
     * once to classify leaves, produces an immutable snapshot, and never touches it again.
     *
     * @param world the world to read leaf states from at capture time
     * @param tree  the detected tree snapshot
     * @param evidence acceptance evidence gathered by {@link TreeDetector}
     */
    public static TreePlan capture(World world, TreeDetector.Tree tree, Evidence evidence) {
        List<BlockPos> ordered = TreeStructure.ordered(tree);
        Set<Long> owned = computeOwnLeaves(world, tree.logs());
        return new TreePlan(List.copyOf(ordered), Set.copyOf(owned), tree.base(), evidence);
    }

    /** @return true if {@code leaf} is an own-tree natural leaf this plan is allowed to clear. */
    public boolean isOwnLeaf(BlockPos leaf) {
        return ownLeaves.contains(leaf.asLong());
    }

    /**
     * Scan a {@link #LEAF_OWNERSHIP_RANGE} box around every log and collect the distinct natural-leaf
     * positions. Uses a visited set keyed by packed position so a leaf adjacent to several logs is
     * counted once.
     */
    private static Set<Long> computeOwnLeaves(World world, List<BlockPos> logs) {
        Set<Long> owned = new HashSet<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (BlockPos log : logs) {
            for (int dx = -LEAF_OWNERSHIP_RANGE; dx <= LEAF_OWNERSHIP_RANGE; dx++) {
                for (int dy = -LEAF_OWNERSHIP_RANGE; dy <= LEAF_OWNERSHIP_RANGE; dy++) {
                    for (int dz = -LEAF_OWNERSHIP_RANGE; dz <= LEAF_OWNERSHIP_RANGE; dz++) {
                        cursor.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        long key = cursor.asLong();
                        if (owned.contains(key)) {
                            continue;
                        }
                        if (isNaturalLeaf(world.getBlockState(cursor))) {
                            owned.add(key);
                        }
                    }
                }
            }
        }
        return owned;
    }

    /** A natural (non-persistent) leaf — the same signal {@link TreeDetector} uses to accept a tree. */
    static boolean isNaturalLeaf(BlockState state) {
        return state.isIn(BlockTags.LEAVES)
                && state.contains(LeavesBlock.PERSISTENT)
                && !state.get(LeavesBlock.PERSISTENT);
    }
}