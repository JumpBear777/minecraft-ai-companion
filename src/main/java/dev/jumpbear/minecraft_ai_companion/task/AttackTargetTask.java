package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Attack the nearest hostile mob, clearing them one by one, then hand control back to the Life
 * System. This is the framework's first "reach a target, then act on it" task, and the first to
 * combine {@link CompanionNavigator#tickFollow} (shared entity tracking) with an existing vanilla
 * interaction path (real {@code PlayerEntity.attack}).
 *
 * <p>Vanilla-first throughout:
 * <ul>
 *   <li>Attack range is the player's {@code getEntityInteractionRange()} attribute, not a constant,
 *       so mods that change reach are respected.</li>
 *   <li>Swings only when {@code getAttackCooldownProgress(0.5F)} has reached 1.0 — a fully charged
 *       vanilla melee hit, not tick-rate spam.</li>
 *   <li>Damage, knockback, crits, sweeping, and enchantments are all left to {@code player.attack}.</li>
 *   <li>Chasing uses {@code tickFollow}, so the companion will not walk into lava to reach a mob.</li>
 * </ul>
 */
public final class AttackTargetTask implements CompanionTask {
    /** Radius to search for a hostile mob to engage. */
    private static final double SEARCH_RADIUS = 20.0D;
    /** Extra reach margin below interaction range so we stop just inside striking distance. */
    private static final double ATTACK_RANGE_MARGIN = 0.5D;
    /** Ticks between path recomputations while chasing the moving mob. */
    private static final int REPATH_INTERVAL = 10;

    private final CompanionNavigator navigator;

    private UUID targetId;
    private int killed;

    public AttackTargetTask(ServerPlayerEntity companion) {
        this.navigator = new CompanionNavigator(companion);
    }

    @Override
    public void start(ServerPlayerEntity companion) {
        // Target chosen lazily on the first tick.
    }

    @Override
    public TaskStatus tick(ServerPlayerEntity companion) {
        HostileEntity target = resolveOrAcquireTarget(companion);
        if (target == null) {
            // No hostiles left within range: objective complete.
            return TaskStatus.SUCCESS;
        }

        double attackRange = companion.getEntityInteractionRange() + ATTACK_RANGE_MARGIN;
        if (companion.squaredDistanceTo(target) > attackRange * attackRange) {
            CompanionNavigator.NavResult result = navigator.tickFollow(target, REPATH_INTERVAL);
            if (result == CompanionNavigator.NavResult.STUCK || result == CompanionNavigator.NavResult.NO_PATH) {
                // Cannot safely reach this mob (blocked, or hazard in the way). Abandon it.
                targetId = null;
                navigator.stop();
            }
            return TaskStatus.RUNNING;
        }

        // In range: face the mob and strike on a fully charged cooldown.
        navigator.stop();
        CompanionInputController.lookAt(companion, target);
        if (companion.getAttackCooldownProgress(0.5F) >= 1.0F) {
            companion.swingHand(Hand.MAIN_HAND);
            companion.attack(target); // vanilla attack() resets the attack cooldown internally
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
        return "AttackTarget(killed=" + killed
                + (targetId != null ? ", target=" + targetId.toString().substring(0, 8) : ", searching")
                + ")";
    }

    /**
     * Return the current target, or acquire a new one. If the tracked mob died or vanished, count it
     * and immediately try to acquire the next one this tick, so killing one mob does not end the task
     * while others remain.
     */
    private HostileEntity resolveOrAcquireTarget(ServerPlayerEntity companion) {
        if (targetId != null) {
            HostileEntity current = findById(companion, targetId);
            if (current != null && current.isAlive()) {
                return current;
            }
            killed++;
            targetId = null;
            navigator.stop();
        }

        HostileEntity next = acquireTarget(companion);
        if (next != null) {
            targetId = next.getUuid();
        }
        return next;
    }

    private HostileEntity acquireTarget(ServerPlayerEntity companion) {
        Box box = companion.getBoundingBox().expand(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS);
        List<HostileEntity> mobs = companion.getEntityWorld().getEntitiesByClass(
                HostileEntity.class,
                box,
                mob -> mob.isAlive());
        return mobs.stream()
                .min(Comparator.comparingDouble(companion::squaredDistanceTo))
                .orElse(null);
    }

    private static HostileEntity findById(ServerPlayerEntity companion, UUID id) {
        Box box = companion.getBoundingBox().expand(SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D, SEARCH_RADIUS + 4.0D);
        List<HostileEntity> mobs = companion.getEntityWorld().getEntitiesByClass(
                HostileEntity.class, box, mob -> id.equals(mob.getUuid()));
        return mobs.isEmpty() ? null : mobs.get(0);
    }
}
