package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.CompanionMiningTasks;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

/**
 * The <b>single shared "try to chop this one log from where I stand" primitive</b>. Both the正式
 * {@link FellNaturalTreeTask} and the debug {@link ReachAndChopTask} drive an instance of this, so the
 * "can I mine it, is a leaf in the way, is a foreign block in the way" judgment lives in <em>one</em>
 * place and the two tasks can never drift apart on it (the recurring failure mode of the archived chop
 * module, which had planning and execution using two different sight algorithms).
 *
 * <h2>One judgment model, evaluated live</h2>
 * Every decision uses the companion's <b>current</b> eye position and vanilla's own metrics — never a
 * pre-planned ideal pose:
 * <ul>
 *   <li><b>Reach</b>: {@link ServerPlayerEntity#canInteractWithBlockAt}(target, 1.0) — the exact
 *       distance check the server-side mining path uses.</li>
 *   <li><b>Sight</b>: a two-point {@code world.raycast} (OUTLINE, no fluids) from the eye to the target.
 *       <b>Multi-aim</b> (Numen-style): rays are cast to the block centre and each of the six faces, and
 *       the target counts as visible as soon as <em>any</em> ray reaches it. A single centre ray is
 *       fooled by non-collidable blocks that still have an outline — short grass, flowers, saplings,
 *       cobwebs — sitting on the centre line; a top/side face usually has a clear line past them. This is
 *       the single sight model; the approach planner's DDA is only a hint.</li>
 * </ul>
 * Because success is judged from where the body actually is, the planner's occluder list and the
 * executor's real rays can never disagree in a way that wedges the task. Only when <em>no</em> aim ray
 * reaches the target does this decide why: a same-tree leaf on the way ⇒ clear it (bounded, below); any
 * non-owned block on every line ⇒ {@link Result#BLOCKED_BY_FOREIGN} and the caller relocates, rather than
 * digging blind.
 *
 * <h2>Reactive, bounded leaf clearing (own tree only)</h2>
 * When the ray to the target first hits a leaf that the {@link TreePlan} confirms is this tree's own
 * (via {@link TreePlan#isOwnLeaf}), this clears <em>only that leaf</em> and re-checks next tick —
 * peeling occluders one at a time as they reveal the next. Clearing is capped at
 * {@link #MAX_CLEAR_PER_TARGET} attempts per target so a leaf that refuses to break can never spin
 * forever. A ray that hits <em>anything else</em> — terrain, a build, another tree, a persistent leaf —
 * stops immediately with {@link Result#BLOCKED_BY_FOREIGN}; this never mines a block it does not own
 * (no Numen-style "dig whatever is in the way").
 *
 * <p>Tick-driven like {@link CompanionPillar}/{@link CompanionNavigator}: construct with the target and
 * plan, then call {@link #tick} every server tick until it returns a terminal {@link Result}. Mining is
 * asynchronous through {@link CompanionMiningTasks}; this holds the in-flight state and polls it.
 */
public final class TreeChopStep {

    /** Vanilla mining distance slack (matches {@link CompanionMiningTasks} / {@link TreeChopSight}). */
    private static final double REACH_SLACK = 1.0D;
    /**
     * Max leaf-clear attempts before giving up on this target. Bounds the reactive peel loop: a leaf
     * that will not break (or a mis-owned leaf that keeps re-appearing on the ray) cannot loop forever.
     * A base is normally freed by clearing 1-2 leaves; 6 leaves plenty of headroom without unbounded work.
     */
    private static final int MAX_CLEAR_PER_TARGET = 6;

    /** Outcome of a {@link #tick}. Only {@link #IN_PROGRESS} is non-terminal. */
    public enum Result {
        /** Still working this tick (aiming, mining, or clearing a leaf); keep ticking. */
        IN_PROGRESS,
        /** The target is now air — it was destroyed (by us this call, or already gone). */
        BROKEN,
        /** Cannot reach the target from the current position (vanilla distance check fails). */
        OUT_OF_REACH,
        /** Reach is fine but the target is not visible and no own-leaf occluder explains it. */
        OUT_OF_SIGHT,
        /** A block we do not own (terrain/build/other tree/persistent leaf) blocks the line — stop. */
        BLOCKED_BY_FOREIGN,
        /** Mining the target (or too many leaves) failed: protected, unbreakable, or refused. */
        FAILED
    }

    private enum TargetKind { LOG, OWN_LEAF }

    private final BlockPos target;
    private final TreePlan plan;
    private final TargetKind targetKind;

    /** The block currently being mined (target, or an own-leaf occluder); null when nothing in flight. */
    private BlockPos currentDig;
    /** Whether a {@link CompanionMiningTasks} op for {@link #currentDig} is in flight. */
    private boolean miningStarted;
    /** True when the in-flight target is an own leaf rather than the planned log. */
    private boolean currentDigIsOwnLeaf;
    private int leavesCleared;

    public TreeChopStep(BlockPos target, TreePlan plan) {
        this(target, plan, TargetKind.LOG);
    }

    /** Create a step that may break exactly one currently-natural leaf owned by this plan. */
    public static TreeChopStep clearOwnLeaf(BlockPos target, TreePlan plan) {
        return new TreeChopStep(target, plan, TargetKind.OWN_LEAF);
    }

    private TreeChopStep(BlockPos target, TreePlan plan, TargetKind targetKind) {
        this.target = target;
        this.plan = plan;
        this.targetKind = targetKind;
    }

    /** Advance one tick. See {@link Result}. */
    public Result tick(ServerPlayerEntity companion) {
        World world = companion.getEntityWorld();

        // Target already gone (we broke it last tick, or another source did): done.
        BlockState targetState = world.getBlockState(target);
        if (targetState.isAir()) {
            return Result.BROKEN;
        }
        // The plan captures coordinates, not an immutable world. Never keep mining after another player
        // or task replaced a planned log/leaf with an unrelated block at the same position.
        if (targetKind == TargetKind.LOG && !targetState.isIn(BlockTags.LOGS)) {
            return Result.BLOCKED_BY_FOREIGN;
        }
        if (targetKind == TargetKind.OWN_LEAF
                && (!plan.isOwnLeaf(target) || !TreePlan.isNaturalLeaf(targetState))) {
            return Result.BLOCKED_BY_FOREIGN;
        }

        // A mining op is in flight (on the target or a leaf): poll it, hold aim meanwhile.
        if (miningStarted) {
            BlockState digState = world.getBlockState(currentDig);
            if (currentDigIsOwnLeaf && !digState.isAir() && !TreePlan.isNaturalLeaf(digState)) {
                CompanionMiningTasks.cancel(companion);
                miningStarted = false;
                currentDig = null;
                currentDigIsOwnLeaf = false;
                return Result.BLOCKED_BY_FOREIGN;
            }
            if (CompanionMiningTasks.hasTask(companion)) {
                // Hold the crosshair on what we are mining. Vanilla break reach is orientation
                // independent, so this is not required for the break to land; it keeps the visible
                // swing/aim coherent and is cheap.
                CompanionInputController.lookAt(companion, currentDig.toCenterPos());
                return Result.IN_PROGRESS;
            }
            // Finished: consume the result and decide.
            CompanionMiningTasks.MiningResult result = CompanionMiningTasks.pollResult(companion);
            boolean wasTarget = currentDig.equals(target);
            miningStarted = false;
            currentDig = null;
            currentDigIsOwnLeaf = false;
            if (wasTarget) {
                // Re-verify the block actually disappeared (a mid-break drift can make vanilla silently
                // refuse the STOP), rather than trusting the reported result alone.
                if (result == CompanionMiningTasks.MiningResult.BROKEN && world.getBlockState(target).isAir()) {
                    return Result.BROKEN;
                }
                return Result.FAILED;
            }
            // Was a leaf: re-evaluate sight next tick regardless of leaf outcome (a failed clear will be
            // re-encountered on the ray and retried, bounded by MAX_CLEAR_PER_TARGET).
            return Result.IN_PROGRESS;
        }

        // Nothing in flight: evaluate reach + sight from the CURRENT eye position.
        CompanionInputController.lookAt(companion, target.toCenterPos());
        if (!companion.canInteractWithBlockAt(target, REACH_SLACK)) {
            return Result.OUT_OF_REACH;
        }

        // Multi-aim visibility (Numen-style). A single centre ray is blocked by any block that has an
        // outline shape even though it has no collision — short grass, flowers, saplings, cobwebs — that
        // happens to sit between the eye and the target centre. That is not an own-leaf, so a centre-only
        // check classifies it BLOCKED_BY_FOREIGN and a normal tree whose base is ringed by grass fails
        // from every foothold. Cast to the block centre plus each of the six faces; the target is visible
        // (and mineable) as soon as ANY ray reaches it — the player-facing or top face clears the grass
        // that only blocks the centre line. Only when no ray reaches the target do we decide why.
        Vec3d eye = companion.getEyePos();
        BlockPos ownLeafBlocker = null; // a same-tree leaf blocking some ray: clearable, makes progress
        boolean foreignBlocker = false; // some ray blocked by a block we do not own
        for (Vec3d aim : aimPoints(target)) {
            BlockHitResult hit = world.raycast(new RaycastContext(
                    eye, aim,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    companion));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                // A clear line to the target exists: bring the fastest tool and start the break.
                return beginDig(companion, target);
            }
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos hitPos = hit.getBlockPos();
                // TreePlan freezes which coordinates were own leaves at task start, but the world may
                // change while the task runs. Never treat a replacement block at such a coordinate as
                // an owned leaf: it must still be a natural leaf now before we are allowed to clear it.
                if (plan.isOwnLeaf(hitPos) && TreePlan.isNaturalLeaf(world.getBlockState(hitPos))) {
                    if (ownLeafBlocker == null) {
                        ownLeafBlocker = hitPos.toImmutable();
                    }
                } else {
                    foreignBlocker = true;
                }
            }
            // MISS on a point inside a full-cube log means an unobstructed line that did not register the
            // target's outline (does not occur for solid logs); treat it as "this ray did not confirm the
            // target" and let the other aim points decide, rather than digging blind.
        }

        // No ray reached the target. Prefer clearing a blocking own-leaf (peels toward visibility, bounded);
        // otherwise report the reason so the caller can relocate rather than dig blind.
        if (ownLeafBlocker != null) {
            if (leavesCleared >= MAX_CLEAR_PER_TARGET) {
                return Result.FAILED; // bounded: refuse to peel forever
            }
            leavesCleared++;
            return beginDig(companion, ownLeafBlocker);
        }
        if (foreignBlocker) {
            return Result.BLOCKED_BY_FOREIGN;
        }
        return Result.OUT_OF_SIGHT;
    }

    /**
     * The points on {@code target} to test for a clear line of sight: the block centre plus a point just
     * inside each of the six faces. Face points use a 0.45 offset from centre (not 0.5) so each endpoint
     * lies strictly <em>inside</em> the target's outline — a ray reaching it must penetrate the target
     * block, so {@code world.raycast} registers the target (or a closer occluder) rather than clipping the
     * boundary ambiguously.
     */
    private static Vec3d[] aimPoints(BlockPos target) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        double lo = 0.05D;
        double mid = 0.5D;
        double hi = 0.95D;
        return new Vec3d[]{
                new Vec3d(x + mid, y + mid, z + mid), // centre
                new Vec3d(x + hi, y + mid, z + mid),  // +X face
                new Vec3d(x + lo, y + mid, z + mid),  // -X face
                new Vec3d(x + mid, y + hi, z + mid),  // +Y (top) face
                new Vec3d(x + mid, y + lo, z + mid),  // -Y (bottom) face
                new Vec3d(x + mid, y + mid, z + hi),  // +Z face
                new Vec3d(x + mid, y + mid, z + lo),  // -Z face
        };
    }

    /** Select the fastest tool for {@code pos} and start an async mining op on it. */
    private Result beginDig(ServerPlayerEntity companion, BlockPos pos) {
        BlockState state = companion.getEntityWorld().getBlockState(pos);
        CompanionHotbar.selectBestToolFor(companion, state);
        if (!CompanionMiningTasks.start(companion, pos)) {
            return Result.FAILED; // became air between check and start, or could not begin
        }
        currentDig = pos;
        currentDigIsOwnLeaf = !pos.equals(target) || targetKind == TargetKind.OWN_LEAF;
        miningStarted = true;
        return Result.IN_PROGRESS;
    }

    /** Cancel any in-flight mining for this step. Safe to call repeatedly; delegates to the owning task's stop. */
    public void cancel(ServerPlayerEntity companion) {
        if (miningStarted) {
            CompanionMiningTasks.cancel(companion);
            miningStarted = false;
            currentDig = null;
            currentDigIsOwnLeaf = false;
        }
    }
}
