package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

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
 *
 * <p>A {@link Predicate} filter decides <em>which</em> dropped items are worth going to (the same role
 * MineColonies' {@code isItemWorthPickingUp} plays). The no-filter constructor collects everything —
 * handy for a bare {@code /aicompanion task collect}. Chained after a chore, a caller passes a
 * narrower filter so, e.g., a lumberjack only gathers logs and saplings and ignores unrelated drops.
 */
public final class CollectDroppedItemsTask implements CompanionTask {
    private static final double SEARCH_RADIUS = 24.0D;
    /** Total ticks allowed on one item before giving up on it and skipping to the next. */
    private static final int PER_ITEM_TIMEOUT = 200;
    /** Ticks between path recomputations while chasing an item. */
    private static final int REPATH_INTERVAL = 20;

    private final CompanionNavigator navigator;
    private final Predicate<ItemStack> filter;
    private final Set<UUID> skipped = new HashSet<>();

    private UUID targetId;
    private int targetTicks;
    private int collected;
    /**
     * One retry sweep: an item that timed out or briefly could not be reached is remembered in
     * {@link #skipped} and dropped from the main pass so the loop makes progress. Once nothing fresh
     * remains, the skipped set is retried a single time (the item may have settled, or the companion
     * may now be standing somewhere it can reach it) before the task ends. This is what stops the pass
     * from leaving a few reachable drops behind — "pick up cleanly" — without looping forever.
     */
    private boolean retriedSkipped;

    /** Collect every dropped item within range (no filtering). */
    public CollectDroppedItemsTask(ServerPlayerEntity companion) {
        this(companion, stack -> true);
    }

    /**
     * Collect only dropped items whose stack matches {@code filter}. Unmatched items are never
     * targeted (a stray drop the companion happens to walk over is still picked up by vanilla — the
     * filter only governs what it deliberately goes to).
     */
    public CollectDroppedItemsTask(ServerPlayerEntity companion, Predicate<ItemStack> filter) {
        this.navigator = new CompanionNavigator(companion);
        this.filter = filter;
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
                // Nothing suitable left in the fresh set. Before ending, retry the skipped items once:
                // an item that timed out or was momentarily unreachable may now be reachable (it
                // settled, or the companion moved). This is the "pick up cleanly" sweep.
                if (!retriedSkipped && !skipped.isEmpty()) {
                    retriedSkipped = true;
                    skipped.clear();
                    return TaskStatus.RUNNING;
                }
                // Only FAILURE if items existed but every one was unreachable even after the retry
                // sweep (all skipped, nothing collected); otherwise the objective is done.
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
                        && filter.test(item.getStack())
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
