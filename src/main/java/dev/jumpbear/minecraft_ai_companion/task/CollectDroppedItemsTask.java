package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.entity.ItemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * First complete autonomous task: collect nearby dropped items until none remain.
 *
 * <p>Pickup itself is pure vanilla (an {@link ItemEntity} is absorbed when a player's bounding box
 * overlaps it), so the task only has to walk onto each item. No teleporting, no scripted pickup.
 *
 * <p>The "walk to a moving entity, close the final gap, avoid hazards, give up if unreachable" flow
 * lives in {@link CompanionNavigator#tickFollow}, shared with FollowPlayer/AttackTarget. This task
 * keeps only the item-specific logic: choose the nearest non-skipped item, detect pickup, and loop
 * until nothing suitable remains. Items that time out or cannot be reached are skipped (remembered)
 * so the loop always makes progress.
 */
public final class CollectDroppedItemsTask implements CompanionTask {
    private static final double SEARCH_RADIUS = 24.0D;
    /** Total ticks allowed on one item before giving up on it and skipping to the next. */
    private static final int PER_ITEM_TIMEOUT = 200;
    /** Ticks between path recomputations while chasing an item. */
    private static final int REPATH_INTERVAL = 20;

    private final CompanionNavigator navigator;
    private final Set<UUID> skipped = new HashSet<>();

    private UUID targetId;
    private int targetTicks;
    private int collected;

    public CollectDroppedItemsTask(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        // Target is chosen lazily on the first tick.
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        ItemEntity target = resolveOrAcquireTarget(companion);
        if (target == null) {
            if (targetId == null) {
                // Nothing suitable left within range. Only FAILURE if items existed but every one
                // was unreachable (all skipped, nothing collected); otherwise the objective is done.
                boolean nothingCollectedButItemsSkipped = collected == 0 && !skipped.isEmpty();
                return nothingCollectedButItemsSkipped ? TaskStatus.FAILURE : TaskStatus.SUCCESS;
            }
            return TaskStatus.RUNNING; // pickup happened this tick; re-acquire next tick
        }

        targetTicks++;
        if (targetTicks > PER_ITEM_TIMEOUT) {
            skipCurrentTarget();
            return TaskStatus.RUNNING;
        }

        CompanionNavigator.NavResult result = navigator.tickFollow(target, REPATH_INTERVAL);
        switch (result) {
            case MOVING -> {
            }
            // ARRIVED: standing on the item; vanilla collision absorbs it (detected next tick).
            case ARRIVED -> {
            }
            // Cannot reach it (blocked, hazard in the way): abandon this item and move on.
            case STUCK, NO_PATH, IDLE -> skipCurrentTarget();
        }
        return TaskStatus.RUNNING;
    }

    @Override
    public void stop(ServerPlayerEntity companion, TaskStatus finalStatus) {
        navigator.stop();
        navigator.dispose();
    }

    @Override
    public String describe() {
        return "CollectDroppedItems(collected=" + collected
                + (targetId != null ? ", target=" + targetId.toString().substring(0, 8) : "")
                + ", skipped=" + skipped.size() + ")";
    }

    /**
     * Return the current target, or acquire a new one. Detects pickup: if the tracked item is gone,
     * count it collected and immediately try to acquire the next one (same tick), so collecting one
     * item does not end the task while others remain.
     */
    private ItemEntity resolveOrAcquireTarget(ServerPlayerEntity companion) {
        if (targetId != null) {
            ItemEntity current = findById(companion, targetId);
            if (current != null && current.isAlive()) {
                return current;
            }
            // Tracked item vanished — most likely picked up. Fall through to acquire the next one.
            collected++;
            clearTarget();
        }

        ItemEntity next = acquireTarget(companion);
        if (next != null) {
            targetId = next.getUuid();
            targetTicks = 0;
        }
        return next;
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
        navigator.stop();
    }

    private static ItemEntity findById(ServerPlayerEntity companion, UUID id) {
        Box box = companion.getBoundingBox().expand(SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D);
        List<ItemEntity> items = companion.getEntityWorld().getEntitiesByClass(
                ItemEntity.class, box, item -> id.equals(item.getUuid()));
        return items.isEmpty() ? null : items.get(0);
    }
}
