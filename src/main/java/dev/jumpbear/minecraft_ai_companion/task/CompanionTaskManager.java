package dev.jumpbear.minecraft_ai_companion.task;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the single current {@link CompanionTask} per companion and drives its lifecycle.
 *
 * <p>This is the seam a future planner assigns work through: it calls {@link #assign} (interrupt the
 * current task and run this one now) or {@link #enqueue} (run this one after whatever is already
 * queued) and never touches task internals. The manager keeps exactly one <em>active</em> task per
 * companion plus an ordered queue behind it; it ticks the active task every server tick, and when a
 * task reaches a terminal status it starts the next queued task, or releases control back to the Life
 * System when the queue is empty.
 *
 * <p>The queue is what lets one command express a multi-step chore - e.g. "chop this tree, then pick
 * up the wood" is {@code assign(ChopTree)} + {@link #enqueueOnSuccess enqueueOnSuccess(Collect)}: the
 * gather only runs if the chop succeeded, so a failed chop stays visible as the last status instead
 * of being masked by an empty collect pass. Interruption/resume and {@code TaskPriority} remain
 * future extension points.
 */
public final class CompanionTaskManager {
    private static final Map<UUID, ActiveTask> ACTIVE = new HashMap<>();
    private static final Map<UUID, Deque<QueuedTask>> QUEUES = new HashMap<>();
    private static final Map<UUID, TaskStatus> LAST_STATUS = new HashMap<>();

    private CompanionTaskManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ACTIVE.isEmpty()) {
                return;
            }

            // Iterate over a snapshot; tick may mutate ACTIVE via finish().
            for (UUID uuid : ACTIVE.keySet().toArray(UUID[]::new)) {
                ActiveTask active = ACTIVE.get(uuid);
                if (active == null) {
                    continue;
                }

                ServerPlayerEntity companion = server.getPlayerManager().getPlayer(uuid);
                if (companion == null || companion.isRemoved() || !companion.isAlive()) {
                    finish(uuid, active, companion, TaskStatus.CANCELLED);
                    continue;
                }

                TaskStatus status = active.task.tick(companion);
                if (status.isTerminal()) {
                    finish(uuid, active, companion, status);
                }
            }
        });
    }

    /**
     * Assign a task as the companion's current task <em>now</em>, cancelling any existing task and
     * clearing anything queued behind it. This is the hard-interrupt entry point: use it when the new
     * work supersedes everything already planned.
     */
    public static void assign(ServerPlayerEntity companion, CompanionTask task) {
        UUID uuid = companion.getUuid();
        ActiveTask previous = ACTIVE.remove(uuid);
        if (previous != null) {
            previous.task.stop(companion, TaskStatus.CANCELLED);
        }
        QUEUES.remove(uuid);

        task.start(companion);
        ACTIVE.put(uuid, new ActiveTask(task));
        LAST_STATUS.remove(uuid);
    }

    /**
     * Queue a task to run after the current one (and anything already queued) finishes, regardless of
     * how that predecessor ended. If nothing is active, the task starts immediately. Order is
     * preserved (FIFO), so chained chores run in the order they were enqueued.
     */
    public static void enqueue(ServerPlayerEntity companion, CompanionTask task) {
        enqueue(companion, task, false);
    }

    /**
     * Queue a task that runs only if the task immediately before it finished {@link TaskStatus#SUCCESS}.
     * If that predecessor failed or was cancelled, this task is dropped without running and without
     * overwriting the last status - so a failed predecessor stays visible (e.g. a failed chop is no
     * longer masked by an empty collect pass). If nothing is active, the task starts only if the last
     * task succeeded (or there was none).
     */
    public static void enqueueOnSuccess(ServerPlayerEntity companion, CompanionTask task) {
        enqueue(companion, task, true);
    }

    private static void enqueue(ServerPlayerEntity companion, CompanionTask task, boolean onlyOnSuccess) {
        UUID uuid = companion.getUuid();
        if (!ACTIVE.containsKey(uuid)) {
            // Nothing running. A normal task starts now; a success-only task starts only if the last
            // task succeeded (or there was none) - otherwise its precondition already failed and
            // nothing remains to trigger it, so drop it.
            TaskStatus previous = LAST_STATUS.get(uuid);
            if (onlyOnSuccess && previous != null && previous != TaskStatus.SUCCESS) {
                return;
            }
            task.start(companion);
            ACTIVE.put(uuid, new ActiveTask(task));
            LAST_STATUS.remove(uuid);
            return;
        }
        QUEUES.computeIfAbsent(uuid, k -> new ArrayDeque<>()).addLast(new QueuedTask(task, onlyOnSuccess));
    }

    public static boolean hasActiveTask(ServerPlayerEntity companion) {
        return ACTIVE.containsKey(companion.getUuid());
    }

    public static Optional<CompanionTask> current(ServerPlayerEntity companion) {
        ActiveTask active = ACTIVE.get(companion.getUuid());
        return Optional.ofNullable(active).map(a -> a.task);
    }

    /** The terminal status of the last finished task, if any (cleared when a new task starts). */
    public static Optional<TaskStatus> lastStatus(ServerPlayerEntity companion) {
        return Optional.ofNullable(LAST_STATUS.get(companion.getUuid()));
    }

    /** Cancel the current task and the whole queue, returning control to the Life System. */
    public static boolean cancel(ServerPlayerEntity companion) {
        UUID uuid = companion.getUuid();
        QUEUES.remove(uuid);
        ActiveTask active = ACTIVE.remove(uuid);
        if (active == null) {
            return false;
        }

        active.task.stop(companion, TaskStatus.CANCELLED);
        LAST_STATUS.put(uuid, TaskStatus.CANCELLED);
        return true;
    }

    private static void finish(UUID uuid, ActiveTask active, ServerPlayerEntity companion, TaskStatus status) {
        ACTIVE.remove(uuid);
        LAST_STATUS.put(uuid, status);
        if (companion != null) {
            active.task.stop(companion, status);
        }

        // Start the next runnable queued task, if any. A dead/removed companion (null) drops the queue.
        Deque<QueuedTask> queue = QUEUES.get(uuid);
        if (queue == null || companion == null) {
            QUEUES.remove(uuid);
            return;
        }

        // Skip success-only tasks whose predecessor (the task that just finished, `status`) did not
        // succeed, without starting them or overwriting the last status - their precondition failed.
        CompanionTask next = null;
        while (!queue.isEmpty()) {
            QueuedTask queued = queue.pollFirst();
            if (queued.onlyOnSuccess && status != TaskStatus.SUCCESS) {
                continue;
            }
            next = queued.task;
            break;
        }
        if (queue.isEmpty()) {
            QUEUES.remove(uuid);
        }
        if (next == null) {
            return; // nothing left to run (all remaining were gated on a success that did not happen)
        }
        next.start(companion);
        ACTIVE.put(uuid, new ActiveTask(next));
    }

    private static final class ActiveTask {
        private final CompanionTask task;

        private ActiveTask(CompanionTask task) {
            this.task = task;
        }
    }

    /** A queued task plus whether it may only run after a SUCCESS predecessor. */
    private static final class QueuedTask {
        private final CompanionTask task;
        private final boolean onlyOnSuccess;

        private QueuedTask(CompanionTask task, boolean onlyOnSuccess) {
            this.task = task;
            this.onlyOnSuccess = onlyOnSuccess;
        }
    }
}
