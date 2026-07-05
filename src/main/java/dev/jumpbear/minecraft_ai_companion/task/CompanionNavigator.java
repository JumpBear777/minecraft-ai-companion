package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
 * (CollectDroppedItems, FollowPlayer, ChopTree, AttackTarget, Explore, ...) reuses the same proven
 * movement path instead of writing its own. The companion is a {@link ServerPlayerEntity}, not a
 * {@code PathAwareEntity}, so vanilla pathfinding is borrowed through a disposable villager proxy
 * and only the path <em>following</em> is adapted onto the player body.
 */
public final class CompanionNavigator {
    private static final double NODE_REACHED_DISTANCE_SQUARED = 0.45D * 0.45D;
    private static final double PROGRESS_EPSILON_SQUARED = 0.0004D;
    /** How long the companion may make no progress before the navigator gives up on this path. */
    private static final int GIVE_UP_STUCK_TICKS = 60;

    private final ServerPlayerEntity player;
    private Path path;
    private Vec3d lastProgressPos;
    private int stuckTicks;
    private int jumpInputTicks;

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
        return HAZARD_NODE_TYPES.contains(type);
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
