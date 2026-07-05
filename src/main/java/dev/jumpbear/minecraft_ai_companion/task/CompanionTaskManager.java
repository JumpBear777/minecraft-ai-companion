package dev.jumpbear.minecraft_ai_companion.task;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the single current {@link CompanionTask} per companion and drives its lifecycle.
 *
 * <p>This is the seam a future planner assigns work through: it calls {@link #assign} and never
 * touches task internals. The manager keeps exactly one task per companion (assigning a new one
 * cancels the old one), ticks it every server tick, and releases control back to the Life System
 * the moment the task reaches a terminal status.
 *
 * <p>Deliberately not built yet (documented extension points): task queue, {@code TaskPriority},
 * and interruption/resume. One current task keeps the foundation minimal while staying compatible
 * with those additions.
 */
public final class CompanionTaskManager {
    private static final Map<UUID, ActiveTask> ACTIVE = new HashMap<>();
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

    /** Assign a task as the companion's current task, cancelling any existing one first. */
    public static void assign(ServerPlayerEntity companion, CompanionTask task) {
        UUID uuid = companion.getUuid();
        ActiveTask previous = ACTIVE.remove(uuid);
        if (previous != null) {
            previous.task.stop(companion, TaskStatus.CANCELLED);
        }

        task.start(companion);
        ACTIVE.put(uuid, new ActiveTask(task));
        LAST_STATUS.remove(uuid);
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

    /** Cancel the current task and return control to the Life System. */
    public static boolean cancel(ServerPlayerEntity companion) {
        UUID uuid = companion.getUuid();
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
    }

    private static final class ActiveTask {
        private final CompanionTask task;

        private ActiveTask(CompanionTask task) {
            this.task = task;
        }
    }
}
