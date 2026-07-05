package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * First complete autonomous task: collect nearby dropped items until none remain.
 *
 * <p>It validates the whole framework end to end while staying simple: pickup itself is pure vanilla
 * (an {@link ItemEntity} is absorbed when a player's bounding box overlaps it), so the task only has
 * to walk onto each item. No teleporting, no scripted pickup call.
 *
 * <p>Per target it runs a small phase machine:
 * <ol>
 *   <li>ACQUIRE: choose the nearest non-skipped item and ask {@link CompanionNavigator} for a path.</li>
 *   <li>NAVIGATE: follow the path via the vanilla navigation chain.</li>
 *   <li>APPROACH: vanilla pathing "arrives" up to ~1 block short of the item, which is too far for
 *       pickup collision. So after arriving we walk the final short distance straight toward the
 *       item center until it is absorbed.</li>
 * </ol>
 *
 * <p>The per-target tick budget ({@link #PER_ITEM_TIMEOUT}) spans NAVIGATE and APPROACH and is only
 * reset when the target actually changes. This is what prevents the previous freeze bug, where
 * arriving-but-not-picking-up re-acquired the same item forever with a perpetually reset timer.
 */
public final class CollectDroppedItemsTask implements CompanionTask {
    private static final double SEARCH_RADIUS = 24.0D;
    private static final int PATH_REACH_DISTANCE = 1;
    /** Total ticks allowed on one item (navigate + approach) before giving up on it. */
    private static final int PER_ITEM_TIMEOUT = 200;
    /** Ticks allowed for the final straight-line approach before skipping the item. */
    private static final int APPROACH_TIMEOUT = 50;
    /** If no path is found but the item is at least this close, still try a direct approach. */
    private static final double DIRECT_APPROACH_DISTANCE = 2.5D;
    /** How often to recompute the path while navigating (items drift after landing). */
    private static final int REPATH_INTERVAL = 20;

    private final CompanionNavigator navigator;
    private final Set<UUID> skipped = new HashSet<>();

    private Phase phase = Phase.ACQUIRE;
    private UUID targetId;
    private int targetTicks;
    private int approachTicks;
    private int repathTimer;
    private int collected;

    public CollectDroppedItemsTask(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        // Setup happens lazily in tick() when the first target is chosen.
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        // Detect pickup (or the item despawning/merging) regardless of phase.
        if (targetId != null) {
            ItemEntity current = findById(companion, targetId);
            if (current == null || !current.isAlive()) {
                collected++;
                clearTarget();
            }
        }

        return switch (phase) {
            case ACQUIRE -> tickAcquire(companion);
            case NAVIGATE -> tickNavigate(companion);
            case APPROACH -> tickApproach(companion);
        };
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        navigator.stop();
        navigator.dispose();
    }

    @Override
    public String describe() {
        return "CollectDroppedItems(phase=" + phase + ", collected=" + collected
                + (targetId != null ? ", target=" + targetId.toString().substring(0, 8) : "")
                + ", skipped=" + skipped.size() + ")";
    }

    private TaskStatus tickAcquire(ServerPlayerEntity companion) {
        ItemEntity target = acquireTarget(companion);
        if (target == null) {
            // Nothing suitable left within range. Only FAILURE if items existed but every one was
            // unreachable (all skipped, nothing ever collected); otherwise the objective is done.
            boolean nothingCollectedButItemsSkipped = collected == 0 && !skipped.isEmpty();
            return nothingCollectedButItemsSkipped ? TaskStatus.FAILURE : TaskStatus.SUCCESS;
        }

        targetId = target.getUuid();
        targetTicks = 0;
        approachTicks = 0;

        if (navigator.pathToEntity(target, PATH_REACH_DISTANCE)) {
            phase = Phase.NAVIGATE;
            repathTimer = REPATH_INTERVAL;
        } else if (companion.squaredDistanceTo(target) <= DIRECT_APPROACH_DISTANCE * DIRECT_APPROACH_DISTANCE) {
            // Item is close but the pathfinder returned nothing (e.g. already within reach). Walk in.
            phase = Phase.APPROACH;
        } else {
            skipCurrentTarget();
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickNavigate(ServerPlayerEntity companion) {
        ItemEntity target = findById(companion, targetId);
        if (target == null) {
            // Pickup was handled at the top of tick(); fall back to acquiring next.
            clearTarget();
            return TaskStatus.RUNNING;
        }

        targetTicks++;
        if (targetTicks > PER_ITEM_TIMEOUT) {
            skipCurrentTarget();
            return TaskStatus.RUNNING;
        }

        if (--repathTimer <= 0) {
            navigator.pathToEntity(target, PATH_REACH_DISTANCE);
            repathTimer = REPATH_INTERVAL;
        }

        CompanionNavigator.NavResult result = navigator.tick();
        switch (result) {
            case MOVING -> {
            }
            case ARRIVED, IDLE -> {
                // Close the final gap that vanilla path reach leaves before pickup collision.
                phase = Phase.APPROACH;
                approachTicks = 0;
            }
            case STUCK, NO_PATH -> skipCurrentTarget();
        }
        return TaskStatus.RUNNING;
    }

    private TaskStatus tickApproach(ServerPlayerEntity companion) {
        ItemEntity target = findById(companion, targetId);
        if (target == null) {
            clearTarget();
            return TaskStatus.RUNNING;
        }

        targetTicks++;
        approachTicks++;
        if (approachTicks > APPROACH_TIMEOUT || targetTicks > PER_ITEM_TIMEOUT) {
            skipCurrentTarget();
            return TaskStatus.RUNNING;
        }

        Vec3d itemPos = target.getEntityPos();

        // Safety gate: the direct approach bypasses the pathfinder, so reuse the vanilla hazard
        // classification to refuse walking onto lava/fire/etc. The item block itself and the next
        // step toward it must both be safe; otherwise abandon this item rather than walk into danger.
        Vec3d toItem = new Vec3d(itemPos.x - companion.getX(), 0.0D, itemPos.z - companion.getZ());
        BlockPos nextStep = BlockPos.ofFloored(
                companion.getX() + normalizedStep(toItem.x, toItem),
                companion.getY(),
                companion.getZ() + normalizedStep(toItem.z, toItem));
        if (navigator.isHazardAt(target.getBlockPos()) || navigator.isHazardAt(nextStep)) {
            skipCurrentTarget();
            return TaskStatus.RUNNING;
        }

        // Steer straight at the item and walk forward until vanilla collision absorbs it.
        CompanionInputController.lookAt(companion, new Vec3d(itemPos.x, companion.getEyeY(), itemPos.z));
        CompanionInputController.applyServerTravelForward(companion, false);
        return TaskStatus.RUNNING;
    }

    /** One-block step component along a horizontal direction (0 if the direction is degenerate). */
    private static double normalizedStep(double component, Vec3d direction) {
        double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        return length < 1.0E-4D ? 0.0D : component / length;
    }

    private ItemEntity acquireTarget(ServerPlayerEntity companion) {
        Box box = companion.getBoundingBox().expand(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);
        List<ItemEntity> items = companion.getEntityWorld().getEntitiesByClass(
                ItemEntity.class,
                box,
                item -> item.isAlive()
                        && !item.getStack().isEmpty()
                        && !skipped.contains(item.getUuid()));
        return items.stream()
                .min(Comparator.comparingDouble(companion::squaredDistanceTo))
                .orElse(null);
    }

    private void skipCurrentTarget() {
        if (targetId != null) {
            skipped.add(targetId);
        }
        clearTarget();
    }

    private void clearTarget() {
        targetId = null;
        targetTicks = 0;
        approachTicks = 0;
        repathTimer = 0;
        phase = Phase.ACQUIRE;
        navigator.stop();
    }

    private static ItemEntity findById(ServerPlayerEntity companion, UUID id) {
        Box box = companion.getBoundingBox().expand(SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D);
        List<ItemEntity> items = companion.getEntityWorld().getEntitiesByClass(
                ItemEntity.class, box, item -> id.equals(item.getUuid()));
        return items.isEmpty() ? null : items.get(0);
    }

    private enum Phase {
        ACQUIRE,
        NAVIGATE,
        APPROACH
    }
}
