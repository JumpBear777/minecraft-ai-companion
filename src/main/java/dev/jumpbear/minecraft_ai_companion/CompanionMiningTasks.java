package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CompanionMiningTasks {
    private static final Map<UUID, MiningTask> TASKS = new LinkedHashMap<>();
    /**
     * Terminal result of the most recently finished mining task per player, held until a caller polls
     * it. This is the channel that lets a task tell whether a break actually destroyed the block -
     * {@link #hasTask} only says "a break is in progress", not whether it succeeded.
     */
    private static final Map<UUID, MiningResult> RESULTS = new LinkedHashMap<>();

    private CompanionMiningTasks() {
    }

    /** Outcome of a finished mining task, for {@link #pollResult}. */
    public enum MiningResult {
        /** The target block is now air - the break destroyed it. */
        BROKEN,
        /** The break did not destroy the block: out of reach, protected, unbreakable, or timed out. */
        FAILED
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, MiningTask>> iterator = TASKS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, MiningTask> entry = iterator.next();
                MiningTask task = entry.getValue();
                MiningResult result = task.tick();
                if (result != null) {
                    RESULTS.put(entry.getKey(), result);
                    iterator.remove();
                }
            }
        });
    }

    public static boolean start(ServerPlayerEntity player, BlockPos target) {
        return start(player, target, null, "Mining");
    }

    public static boolean start(ServerPlayerEntity player, BlockPos target, ServerCommandSource source, String label) {
        if (player.getEntityWorld().getBlockState(target).isAir()) {
            return false;
        }

        // Abandon any in-progress break for this player before starting a new one, so the
        // block-breaking overlay and the interaction manager's destroy state do not leak across
        // targets. (Vanilla's START_DESTROY_BLOCK silently supersedes a prior break, but the visible
        // crack overlay and our local delta timer do not, so we abort explicitly.)
        MiningTask existing = TASKS.remove(player.getUuid());
        if (existing != null) {
            existing.abort();
        }
        // A previous task's terminal result is now stale; clear it so a caller polling after this
        // start only ever sees the result of the task we are about to run.
        RESULTS.remove(player.getUuid());

        TASKS.put(player.getUuid(), new MiningTask(player, target, source, label));
        return true;
    }

    public static boolean hasTask(ServerPlayerEntity player) {
        return TASKS.containsKey(player.getUuid());
    }

    /**
     * Take the terminal result of the most recently finished mining task for this player, clearing it.
     * The intended pattern is to gate this with {@link #hasTask}: while a task runs the result is
     * absent; on the tick after it finishes {@code hasTask} is false and this returns the outcome.
     *
     * @return the outcome, or {@code null} if no task has finished since the last poll (nothing was
     *         started, or it was cancelled before finishing)
     */
    public static MiningResult pollResult(ServerPlayerEntity player) {
        return RESULTS.remove(player.getUuid());
    }

    /**
     * Abort any in-progress mining for this player and clear its block-breaking overlay, so a caller
     * that owns the companion's body (e.g. a task's {@code stop}) can release a clean state. Safe to
     * call when no mining task is active. Does not record a result - a cancellation is not a break
     * outcome the caller should act on.
     */
    public static void cancel(ServerPlayerEntity player) {
        MiningTask task = TASKS.remove(player.getUuid());
        if (task != null) {
            task.abort();
            RESULTS.remove(player.getUuid());
        }
    }

    private static final class MiningTask {
        /** Total ticks a single break may run before it is abandoned as failed (bounds the slow/wedged). */
        private static final int MAX_TICKS = 300;
        /** Ticks to wait at zero breaking progress before declaring a block unbreakable. */
        private static final int NO_PROGRESS_TICKS = 10;

        private final ServerPlayerEntity player;
        private final BlockPos target;
        private final ServerCommandSource source;
        private final String label;
        private final String initialBlockName;
        private int ticks;
        private int lastProgress = -1;

        private MiningTask(ServerPlayerEntity player, BlockPos target, ServerCommandSource source, String label) {
            this.player = player;
            this.target = target;
            this.source = source;
            this.label = label;
            this.initialBlockName = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
            player.interactionManager.processBlockBreakingAction(
                    target,
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP,
                    player.getEntityWorld().getTopYInclusive(),
                    0);
        }

        /** @return the terminal result when finished, or {@code null} to keep ticking. */
        private MiningResult tick() {
            // Already gone: the body was removed, or the block became air out from under us (creative
            // instant-break on START, or another source destroyed it mid-break). Either way it is gone.
            if (player.isRemoved() || player.getEntityWorld().getBlockState(target).isAir()) {
                clearProgress();
                reportFinished(true);
                return MiningResult.BROKEN;
            }

            if (ticks % 5 == 0) {
                player.swingHand(Hand.MAIN_HAND);
            }

            BlockState state = player.getEntityWorld().getBlockState(target);
            float perTickDelta = state.calcBlockBreakingDelta(player, player.getEntityWorld(), target);
            float breakingDelta = perTickDelta * (ticks + 1);
            int progress = Math.min(9, (int) (breakingDelta * 10.0F));
            if (progress != lastProgress) {
                player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, progress);
                lastProgress = progress;
            }
            ticks++;

            if (breakingDelta >= 1.0F) {
                clearProgress();
                player.interactionManager.processBlockBreakingAction(
                        target,
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        Direction.UP,
                        player.getEntityWorld().getTopYInclusive(),
                        1);
                // The STOP may be silently refused by vanilla's reach/protection checks at the top of
                // processBlockBreakingAction (the body can drift out of reach mid-break), so verify the
                // block actually disappeared rather than assuming the break landed.
                boolean broken = player.getEntityWorld().getBlockState(target).isAir();
                reportFinished(broken);
                return broken ? MiningResult.BROKEN : MiningResult.FAILED;
            }

            // Unbreakable block (hardness < 0, e.g. bedrock/barrier, or a mod-protected block): delta is
            // 0, so breakingDelta never grows and the threshold above is never reached. Bail rather than
            // spin forever on a break that cannot happen.
            if (perTickDelta <= 0.0F && ticks >= NO_PROGRESS_TICKS) {
                clearProgress();
                abort();
                reportFinished(false);
                return MiningResult.FAILED;
            }

            // Overall cap: a break taking this long is wedged - most likely the body drifted out of
            // reach and our local delta timer kept climbing on a target vanilla now silently refuses to
            // break. Stop waiting on it.
            if (ticks >= MAX_TICKS) {
                clearProgress();
                abort();
                reportFinished(false);
                return MiningResult.FAILED;
            }

            return null;
        }

        private void clearProgress() {
            player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, -1);
        }

        /**
         * Stop mining mid-break: clear the breaking overlay and tell the interaction manager to abort
         * the destroy action, matching the vanilla {@code ABORT_DESTROY_BLOCK} client action.
         */
        private void abort() {
            clearProgress();
            player.interactionManager.processBlockBreakingAction(
                    target,
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP,
                    player.getEntityWorld().getTopYInclusive(),
                    0);
        }

        private void reportFinished(boolean success) {
            if (source == null) {
                return;
            }

            String after = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
            source.sendFeedback(() -> Text.literal(label
                    + ": block " + initialBlockName + " -> " + after
                    + " ticks=" + ticks
                    + " success=" + success), true);
        }
    }
}
