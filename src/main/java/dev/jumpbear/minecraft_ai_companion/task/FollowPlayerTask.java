package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import dev.jumpbear.minecraft_ai_companion.FakeCompanionSpawner;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Continuously follow the nearest real player, staying within a comfortable distance.
 *
 * <p>This is the framework's first <em>open-ended</em> task: it normally never returns a terminal
 * status, proving the task contract supports long-running presence behaviors (unlike
 * CollectDroppedItems, which completes). It ends cleanly only when the target is no longer
 * followable (gone, dead, changed world, or lost beyond {@link #LOSE_RANGE}), handing control back
 * to the Life System.
 *
 * <p>Behavior is modeled on vanilla {@code FollowOwnerGoal}'s min/max distance thresholds, but the
 * companion is a {@link ServerPlayerEntity} (not a tameable mob) and, per project rules, it never
 * teleports: when it falls behind it walks back using the shared {@link CompanionNavigator}
 * entity-tracking path (vanilla navigation + hazard-gated approach).
 */
public final class FollowPlayerTask implements CompanionTask {
    /** Radius to search for a player to follow when the task starts. */
    private static final double ACQUIRE_RADIUS = 16.0D;
    /** Once closer than this, stop and just watch the player. */
    private static final double STOP_DISTANCE = 3.0D;
    /** Resume walking once farther than this (hysteresis so the companion does not jitter). */
    private static final double RESUME_DISTANCE = 4.5D;
    /** Give up following if the player gets farther than this. */
    private static final double LOSE_RANGE = 48.0D;
    /** Ticks between path recomputations while chasing the moving player. */
    private static final int REPATH_INTERVAL = 10;

    private UUID targetUuid;
    private CompanionNavigator navigator;
    private boolean closing;

    @Override
    public void start(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        ServerPlayerEntity target = resolveTarget(companion);
        if (target == null) {
            // No player to follow (never found one, or the followed player left/died/changed world).
            return TaskStatus.SUCCESS;
        }

        double distanceSquared = companion.squaredDistanceTo(target);
        if (distanceSquared > LOSE_RANGE * LOSE_RANGE) {
            return TaskStatus.SUCCESS; // lost the player
        }

        // Hysteresis: start closing when past RESUME_DISTANCE, stop once inside STOP_DISTANCE.
        if (closing) {
            if (distanceSquared <= STOP_DISTANCE * STOP_DISTANCE) {
                closing = false;
            }
        } else if (distanceSquared > RESUME_DISTANCE * RESUME_DISTANCE) {
            closing = true;
        }

        if (closing) {
            CompanionNavigator.NavResult result = navigator.tickFollow(target, REPATH_INTERVAL);
            if (result == CompanionNavigator.NavResult.STUCK || result == CompanionNavigator.NavResult.NO_PATH) {
                // Cannot make progress right now; stop moving and look at the player until the path
                // opens up (player moves) or they leave range. Stay RUNNING — following is ongoing.
                navigator.stop();
                CompanionInputController.lookAt(companion, target);
            }
        } else {
            navigator.stop();
            CompanionInputController.lookAt(companion, target);
        }

        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        if (navigator != null) {
            navigator.stop();
            navigator.dispose();
        }
    }

    @Override
    public String describe() {
        return "FollowPlayer(" + (targetUuid == null ? "acquiring" : targetUuid.toString().substring(0, 8))
                + (closing ? ", closing" : ", watching") + ")";
    }

    /**
     * Resolve the followed player, acquiring the nearest one on the first tick. Returns null if the
     * target is gone, dead, or in a different world.
     */
    private ServerPlayerEntity resolveTarget(ServerPlayerEntity companion) {
        if (targetUuid == null) {
            ServerPlayerEntity nearest = findNearestRealPlayer(companion);
            if (nearest != null) {
                targetUuid = nearest.getUuid();
            }
            return nearest;
        }

        ServerPlayerEntity target = companion.getEntityWorld().getServer().getPlayerManager().getPlayer(targetUuid);
        if (target == null || target.isRemoved() || !target.isAlive()
                || target.getEntityWorld() != companion.getEntityWorld()) {
            return null;
        }
        return target;
    }

    private static ServerPlayerEntity findNearestRealPlayer(ServerPlayerEntity companion) {
        Box box = companion.getBoundingBox().expand(ACQUIRE_RADIUS, ACQUIRE_RADIUS, ACQUIRE_RADIUS);
        List<ServerPlayerEntity> players = companion.getEntityWorld().getEntitiesByClass(
                ServerPlayerEntity.class,
                box,
                player -> player != companion
                        && !FakeCompanionSpawner.isCompanion(player)
                        && player.isAlive());
        return players.stream()
                .min(Comparator.comparingDouble(companion::squaredDistanceTo))
                .orElse(null);
    }
}
