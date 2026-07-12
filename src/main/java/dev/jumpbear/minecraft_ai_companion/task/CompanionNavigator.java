package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.EnumSet;
import java.util.Set;

/**
 * Reusable movement adapter that walks the companion to a target using a complete vanilla
 * navigation chain, then follows the resulting path with the companion's server-side travel input.
 *
 * <p>This is a straight extraction of the path-follow logic that was already manually validated in
 * {@code CompanionBehaviorTestTasks.NavigationForwardTask} (villager pathfinding proxy ->
 * {@code NoPenaltyTargeting}-free {@code findPathTo} -> {@code applyServerTravelForward}
 * -> stuck/obstacle jump -> node advance). It is pulled into one place so every task
 * (CollectDroppedItems, FollowPlayer, AttackTarget, Explore, ...) reuses the same proven
 * movement path instead of writing its own. The companion is a {@link ServerPlayerEntity}, not a
 * {@code PathAwareEntity}, so vanilla pathfinding is borrowed through a disposable villager proxy
 * and only the path <em>following</em> is adapted onto the player body.
 */
public final class CompanionNavigator {
    private static final double NODE_REACHED_DISTANCE_SQUARED = 0.45D * 0.45D;
    private static final double PROGRESS_EPSILON_SQUARED = 0.0004D;
    /** How long the companion may make no progress before the navigator gives up on this path. */
    private static final int GIVE_UP_STUCK_TICKS = 60;
    /**
     * How long the direct final-gap approach may make no horizontal progress before reporting STUCK.
     * Shorter than {@link #GIVE_UP_STUCK_TICKS}: by the direct-approach stage the companion is right
     * next to the target, so if it has not closed the last block in this many ticks it is wedged
     * (against an edge, a fence, an item resting on an unreachable ledge) and the caller should move on
     * rather than press forever into it until the per-item task timeout.
     */
    private static final int APPROACH_GIVE_UP_STUCK_TICKS = 20;
    /**
     * Horizontal distance (squared) within which {@link #tickFollow} abandons pathfinding and commits
     * to the steady direct approach. ~2 blocks: close enough that a vanilla path would be one or two
     * block-aligned nodes that only fight the item-aligned look and make the head oscillate, and near
     * enough that the hazard-gated straight walk closes the gap on its own.
     */
    private static final double DIRECT_APPROACH_COMMIT_SQUARED = 2.0D * 2.0D;
    /**
     * Vertical gap allowed for the direct final-gap approach. Beyond this, horizontal overlap does not
     * mean "arrived" - the target may be in a pit, on a ledge, or above the companion. Let pathfinding
     * try to solve it; if no path exists, report STUCK instead of standing above/below it until timeout.
     */
    private static final double DIRECT_APPROACH_MAX_VERTICAL = 1.5D;
    /**
     * Maximum drop (blocks) a direct step may take without counting as a hazard. A fall of up to this
     * many blocks deals no damage (vanilla fall damage starts at 4 blocks); a step whose only ground
     * is farther below than this is a cliff/pit the companion must not walk straight into. One-block
     * steps and small slopes pass; pillar tops, cliffs, and deep pits do not.
     */
    private static final int MAX_SAFE_DROP = 3;

    private final ServerPlayerEntity player;
    private Path path;
    private Vec3d lastProgressPos;
    private int stuckTicks;
    private int jumpInputTicks;
    private int followRepathTimer;
    /** Progress tracking for the direct final-gap approach, independent of the path-follow tracking. */
    private Vec3d lastApproachPos;
    private int approachStuckTicks;

    /**
     * Cached vanilla pathfinding proxy. It is a detached villager (never spawned into the world, so
     * never ticked) used only to borrow vanilla navigation and hazard classification. Reused across
     * ticks to avoid per-check entity allocation; rebuilt when the companion changes world so its
     * navigation/PathContext always evaluate the companion's current dimension.
     */
    private VillagerEntity proxy;
    private ServerWorld proxyWorld;

    public CompanionNavigator(ServerPlayerEntity player) {
        this.player = player;
    }

    /** Result of a single {@link #tick()}. */
    public enum NavResult {
        /** No path is currently assigned. */
        IDLE,
        /** Following the path this tick. */
        MOVING,
        /** Reached the end of the path. */
        ARRIVED,
        /** No path could be computed to the requested target. */
        NO_PATH,
        /** Made no progress for too long; the caller should pick a new target or give up. */
        STUCK
    }

    /** Compute a path to a block position. Returns false if no path is available. */
    public boolean pathTo(BlockPos target, int reachDistance) {
        Path computed = computePath(target, reachDistance);
        return adoptPath(computed);
    }

    /** Compute a path to a world position. Returns false if no path is available. */
    public boolean pathTo(Vec3d target, int reachDistance) {
        return pathTo(BlockPos.ofFloored(target), reachDistance);
    }

    /** Compute a path to an entity's current block position. Returns false if no path is available. */
    public boolean pathToEntity(Entity target, int reachDistance) {
        return pathTo(target.getBlockPos(), reachDistance);
    }

    public boolean isFollowingPath() {
        return path != null && !path.isFinished();
    }

    /**
     * Advance the current path by one tick. Look toward the next node, jump over obstacles when
     * stuck or when the next node is higher, and drive forward with vanilla server-side travel.
     */
    public NavResult tick() {
        if (path == null) {
            return NavResult.IDLE;
        }

        advanceReachedNodes();
        if (path.isFinished()) {
            CompanionInputController.releaseInput(player);
            path = null;
            return NavResult.ARRIVED;
        }

        Vec3d nodePos = path.getNodePosition(player);
        boolean madeProgress = updateStuckTracking();
        if (!madeProgress && stuckTicks >= GIVE_UP_STUCK_TICKS) {
            CompanionInputController.releaseInput(player);
            path = null;
            return NavResult.STUCK;
        }

        CompanionInputController.lookAt(player, new Vec3d(nodePos.x, player.getEyeY(), nodePos.z));
        if (shouldJumpToward(nodePos) && jumpInputTicks == 0) {
            jumpInputTicks = 8;
        }

        boolean jumpInput = jumpInputTicks > 0;
        CompanionInputController.applyServerTravelForward(player, jumpInput);
        if (jumpInputTicks > 0) {
            jumpInputTicks--;
        }
        return NavResult.MOVING;
    }

    /** Release movement input and drop the current path. Safe to call repeatedly. */
    public void stop() {
        CompanionInputController.releaseInput(player);
        path = null;
        stuckTicks = 0;
        jumpInputTicks = 0;
        followRepathTimer = 0;
        resetApproachTracking();
    }

    /**
     * Continuously move toward a (possibly moving) entity: recompute the path every
     * {@code repathInterval} ticks, follow it, and once the vanilla path reaches its end walk the
     * final short gap straight at the entity (needed because vanilla pathing "arrives" up to ~1 block
     * short). The direct approach is hazard-gated with the same vanilla classification the pathfinder
     * uses, so it never walks the companion onto lava/fire/etc.
     *
     * <p>This is the shared entity-tracking behavior used by CollectDroppedItems, FollowPlayer, and
     * (future) AttackTarget. Callers decide what a result means: ARRIVED = adjacent to the target
     * (pick up / attack / wait); STUCK/NO_PATH = give up or retarget.
     *
     * @param target         the entity to move toward
     * @param repathInterval ticks between path recomputations (e.g. 10-20)
     * @return MOVING while en route, ARRIVED when adjacent, STUCK/NO_PATH when it cannot proceed
     */
    public NavResult tickFollow(Entity target, int repathInterval) {
        // Once close enough that only the final short gap remains, commit to the direct approach and
        // stop repathing. Otherwise, near a resting item the two look sources fight every tick: the
        // path-follow tick() looks at the block-aligned path node while directApproach looks at the
        // item's fractional position, and because the vanilla path "arrives" ~1 block short, path is
        // exhausted (null) each tick and immediately recomputed — so the head snapped between the two
        // headings every tick (the "摇头" while collecting). Below this range the block-aligned mini
        // path adds nothing the direct walk cannot do, so skip it and aim steadily at the item.
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        double vertical = Math.abs(target.getY() - player.getY());
        if (dx * dx + dz * dz <= DIRECT_APPROACH_COMMIT_SQUARED
                && vertical <= DIRECT_APPROACH_MAX_VERTICAL) {
            if (path != null) {
                CompanionInputController.releaseInput(player);
                path = null;
            }
            followRepathTimer = 0;
            return directApproach(target);
        }

        if (--followRepathTimer <= 0 || path == null) {
            pathToEntity(target, 1);
            followRepathTimer = repathInterval;
        }

        if (path != null) {
            NavResult result = tick();
            if (result != NavResult.ARRIVED && result != NavResult.IDLE) {
                return result;
            }
            // ARRIVED/IDLE falls through to the direct approach below.
        }

        return directApproach(target);
    }

    /**
     * Walk straight at the entity for the final gap the pathfinder leaves, refusing to step into a
     * hazard. Returns MOVING while closing in, ARRIVED once within pickup/interaction range, or
     * STUCK if the only way forward is unsafe.
     */
    private NavResult directApproach(Entity target) {
        Vec3d targetPos = target.getEntityPos();
        double dx = targetPos.x - player.getX();
        double dz = targetPos.z - player.getZ();
        double dy = targetPos.y - player.getY();
        double horizontalSquared = dx * dx + dz * dz;
        if (horizontalSquared <= NODE_REACHED_DISTANCE_SQUARED) {
            if (Math.abs(dy) > DIRECT_APPROACH_MAX_VERTICAL) {
                CompanionInputController.releaseInput(player);
                resetApproachTracking();
                return NavResult.STUCK;
            }
            CompanionInputController.releaseInput(player);
            resetApproachTracking();
            return NavResult.ARRIVED;
        }

        double length = Math.sqrt(horizontalSquared);
        BlockPos nextStep = BlockPos.ofFloored(
                player.getX() + dx / length,
                player.getY(),
                player.getZ() + dz / length);
        if (isHazardAt(target.getBlockPos()) || isHazardAt(nextStep)) {
            CompanionInputController.releaseInput(player);
            resetApproachTracking();
            return NavResult.STUCK;
        }

        // No horizontal progress for too long -> wedged against something we cannot close the gap
        // through. Report STUCK so the caller skips this target instead of shoving forever.
        if (!updateApproachProgress()) {
            if (approachStuckTicks >= APPROACH_GIVE_UP_STUCK_TICKS) {
                CompanionInputController.releaseInput(player);
                resetApproachTracking();
                return NavResult.STUCK;
            }
        }

        CompanionInputController.lookAt(player, new Vec3d(targetPos.x, player.getEyeY(), targetPos.z));
        CompanionInputController.applyServerTravelForward(player, false);
        return NavResult.MOVING;
    }

    /** @return true if the companion moved horizontally since the last direct-approach tick. */
    private boolean updateApproachProgress() {
        Vec3d currentPos = player.getEntityPos();
        if (lastApproachPos == null) {
            lastApproachPos = currentPos;
            approachStuckTicks = 0;
            return true;
        }
        double movedX = currentPos.x - lastApproachPos.x;
        double movedZ = currentPos.z - lastApproachPos.z;
        if (movedX * movedX + movedZ * movedZ < PROGRESS_EPSILON_SQUARED) {
            approachStuckTicks++;
            return false;
        }
        approachStuckTicks = 0;
        lastApproachPos = currentPos;
        return true;
    }

    private void resetApproachTracking() {
        lastApproachPos = null;
        approachStuckTicks = 0;
    }

    /**
     * Discard the cached pathfinding proxy. Optional: the detached proxy is garbage-collected with
     * the navigator anyway, but a task's {@code stop} may call this to release it promptly.
     */
    public void dispose() {
        if (proxy != null) {
            proxy.discard();
            proxy = null;
            proxyWorld = null;
        }
    }

    /**
     * PathNodeTypes the companion must never step onto during a direct approach. These mirror the
     * vanilla penalties the pathfinder itself treats as impassable/damaging, so the direct-approach
     * fallback uses the same hazard judgment as the navigation chain instead of a hard-coded list.
     */
    private static final Set<PathNodeType> HAZARD_NODE_TYPES = EnumSet.of(
            PathNodeType.LAVA,
            PathNodeType.DAMAGE_FIRE,
            PathNodeType.DANGER_FIRE,
            PathNodeType.DAMAGE_OTHER,
            PathNodeType.DANGER_OTHER,
            PathNodeType.DAMAGE_CAUTIOUS,
            PathNodeType.POWDER_SNOW,
            PathNodeType.DANGER_POWDER_SNOW);

    /**
     * Reuse the vanilla land pathfinding hazard classification to decide whether a block position is
     * unsafe to walk onto. Borrows a disposable villager proxy so
     * {@link LandPathNodeMaker#getLandNodeType} evaluates the position exactly as the navigation
     * chain would. Fails safe (treats as hazardous) if the proxy cannot be created.
     */
    public boolean isHazardAt(BlockPos pos) {
        VillagerEntity proxy = borrowProxy();
        if (proxy == null) {
            return true;
        }

        proxy.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.0F, 0.0F);
        PathNodeType type = LandPathNodeMaker.getLandNodeType(proxy, pos);
        if (HAZARD_NODE_TYPES.contains(type)) {
            return true;
        }
        // The pathfinder's single-cell classification returns OPEN for a cell above a cliff, because
        // vanilla judges drops during neighbour evaluation, not from one node. A direct step does not
        // get that neighbour logic, so gate it ourselves: an open cell with no solid ground within a
        // safe drop is a cliff/pit the companion must not walk straight into.
        return isDropHazard(pos);
    }

    /**
     * True if {@code pos} has no safe landing within {@link #MAX_SAFE_DROP} blocks below it. A landing
     * can be normal collision (solid ground, slabs, leaves, etc.) or water, which prevents fall damage
     * and is deliberately allowed so collecting shallow-water drops is not classified as a cliff.
     */
    private boolean isDropHazard(BlockPos pos) {
        World world = player.getEntityWorld();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int i = 0; i <= MAX_SAFE_DROP; i++) {
            cursor.set(pos.getX(), pos.getY() - i, pos.getZ());
            if (world.getFluidState(cursor).isIn(FluidTags.WATER)) {
                return false; // water is a safe landing and a valid target for floated drops
            }
            if (!world.getBlockState(cursor).getCollisionShape(world, cursor).isEmpty()) {
                return false; // ground within a safe drop: stepping here lands, it does not fall
            }
        }
        return true; // no ground within MAX_SAFE_DROP -> cliff
    }

    private Path computePath(BlockPos target, int reachDistance) {
        VillagerEntity proxy = borrowProxy();
        if (proxy == null) {
            return null;
        }

        proxy.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        proxy.setAiDisabled(true);
        proxy.setNoGravity(true);
        proxy.setOnGround(player.isOnGround());
        return proxy.getNavigation().findPathTo(target, reachDistance);
    }

    /**
     * Return the cached pathfinding proxy, creating it (or rebuilding it after a world change) as
     * needed. Returns null if the companion is not in a server world or the entity cannot be created.
     */
    private VillagerEntity borrowProxy() {
        if (!(player.getEntityWorld() instanceof ServerWorld world)) {
            return null;
        }

        if (proxy == null || proxyWorld != world) {
            if (proxy != null) {
                proxy.discard();
            }
            proxy = EntityType.VILLAGER.create(world, SpawnReason.COMMAND);
            proxyWorld = world;
        }
        return proxy;
    }

    private boolean adoptPath(Path computed) {
        if (computed == null || computed.getLength() == 0) {
            path = null;
            return false;
        }

        path = computed;
        lastProgressPos = player.getEntityPos();
        stuckTicks = 0;
        jumpInputTicks = 0;
        return true;
    }

    private void advanceReachedNodes() {
        while (path != null && !path.isFinished()) {
            Vec3d nodePos = path.getNodePosition(player);
            double dx = player.getX() - nodePos.x;
            double dz = player.getZ() - nodePos.z;
            boolean closeHorizontally = dx * dx + dz * dz <= NODE_REACHED_DISTANCE_SQUARED;
            boolean closeVertically = Math.abs(player.getY() - nodePos.y) < 1.1D;
            if (!closeHorizontally || !closeVertically) {
                return;
            }

            path.next();
        }
    }

    /** @return true if the companion moved meaningfully since the last tick. */
    private boolean updateStuckTracking() {
        Vec3d currentPos = player.getEntityPos();
        double movedX = currentPos.x - lastProgressPos.x;
        double movedZ = currentPos.z - lastProgressPos.z;
        double movedSquared = movedX * movedX + movedZ * movedZ;
        if (movedSquared < PROGRESS_EPSILON_SQUARED) {
            stuckTicks++;
            return false;
        }

        stuckTicks = 0;
        lastProgressPos = currentPos;
        return true;
    }

    private boolean shouldJumpToward(Vec3d nodePos) {
        boolean nextNodeIsHigher = nodePos.y > player.getY() + 0.5D;
        return player.isOnGround() && (nextNodeIsHigher || player.horizontalCollision || stuckTicks >= 8);
    }
}
