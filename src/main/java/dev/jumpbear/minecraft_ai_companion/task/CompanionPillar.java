package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Reusable movement adapter: pillar the companion straight up by placing a block from the main hand
 * onto the block it stands on during the jump apex, then landing on it, one level at a time. This is
 * the "real player builds up to reach something high" primitive, reusable by any task that must rise
 * above interaction range (future BuildStructure/Explore-over-a-wall).
 *
 * <p>Straight extraction of the timing verified in-world during the pillar-up spike:
 * {@code World.canPlace} validates a candidate block against the player's collision box, so the feet
 * position can only be filled after the jump lifts the player a full block clear of it (near the
 * apex). Reuses proven paths: jump = {@link CompanionInputController#pressJumpOnly}, place =
 * {@code interactionManager.interactBlock}. No new physics/placement logic of its own.
 *
 * <p>Like {@link CompanionNavigator}, this is a tick-driven adapter: call {@link #begin} with the
 * number of levels to rise, then {@link #tick} every server tick until it returns a terminal result.
 * A caller that wants to re-evaluate after each level (e.g. "am I close enough now?") calls
 * {@code begin(1)} per level.
 */
public final class CompanionPillar {
    /**
     * How far above the stand block the feet must rise before a full block placed there stops
     * intersecting the player (a full block occupies [standY, standY+1]; the player box bottom is
     * getY()). Vanilla jump apex is ~1.25 above, leaving a small window. Verified value from the spike.
     */
    private static final double PLACE_CLEARANCE = 1.0D;
    /** Give up on a level if its placement window never opens (head-blocked, cannot jump clear). */
    private static final int LEVEL_TIMEOUT_TICKS = 40;

    private final ServerPlayerEntity player;
    private int remainingLevels;
    private Level level;
    /** Position actually filled by the most recently completed level; null until one completes. */
    private BlockPos lastPlacedPos;

    public CompanionPillar(ServerPlayerEntity player) {
        this.player = player;
    }

    /** Result of a single {@link #tick()}. */
    public enum PillarResult {
        /** Not currently pillaring (no {@link #begin} in effect). */
        IDLE,
        /** Rising/placing this level; keep ticking. */
        RISING,
        /** All requested levels placed and settled. */
        DONE,
        /** A level could not be placed (window never opened, or vanilla rejected the placement). */
        FAILED
    }

    /** True if the main hand currently holds a placeable block (the only thing this adapter needs). */
    public boolean hasPlaceableBlock() {
        return player.getMainHandStack().getItem() instanceof BlockItem;
    }

    /**
     * The world position filled by the most recently completed level (captured before the jump, so it
     * is the actual placed block, not an inference from the post-landing feet position which can drift
     * laterally). Null until the first level completes. Callers that record placed blocks for later
     * reclamation should read this rather than inferring {@code getBlockPos().down()}.
     */
    public BlockPos lastPlacedPos() {
        return lastPlacedPos;
    }

    /** Begin pillaring up {@code levels} blocks. Call {@link #tick} each tick afterwards. */
    public void begin(int levels) {
        this.remainingLevels = Math.max(0, levels);
        this.level = null;
    }

    /** Advance the current pillar by one tick. */
    public PillarResult tick() {
        if (remainingLevels <= 0) {
            return PillarResult.DONE;
        }
        if (!hasPlaceableBlock()) {
            stop();
            return PillarResult.FAILED;
        }
        if (level == null) {
            level = new Level(player);
        }

        switch (level.tick()) {
            case RISING -> {
                return PillarResult.RISING;
            }
            case DONE -> {
                lastPlacedPos = level.standPos;
                remainingLevels--;
                level = null;
                return remainingLevels <= 0 ? PillarResult.DONE : PillarResult.RISING;
            }
            case FAILED -> {
                stop();
                return PillarResult.FAILED;
            }
        }
        return PillarResult.RISING;
    }

    /** Release input and abandon any in-progress pillar. Safe to call repeatedly. */
    public void stop() {
        CompanionInputController.releaseInput(player);
        level = null;
        remainingLevels = 0;
    }

    /** Per-level phases and result, mirroring the verified spike. */
    private enum Phase { LOOK_DOWN, JUMP, PLACE_WINDOW, SETTLE }

    private enum LevelResult { RISING, DONE, FAILED }

    /**
     * The single-block pillar-up state machine, captured against the stand position at construction.
     * A fresh instance is created for each level, so it always evaluates the companion's current
     * (post-landing) position.
     */
    private static final class Level {
        private final ServerPlayerEntity player;
        /** Feet block at the start of this level: the position to fill. */
        private final BlockPos standPos;
        /** Ground block directly below: we click its top face to place onto it. */
        private final BlockPos supportPos;

        private Phase phase = Phase.LOOK_DOWN;
        private int ticks;

        private Level(ServerPlayerEntity player) {
            this.player = player;
            this.standPos = player.getBlockPos();
            this.supportPos = standPos.down();
        }

        private LevelResult tick() {
            if (++ticks > LEVEL_TIMEOUT_TICKS) {
                return LevelResult.FAILED;
            }

            switch (phase) {
                case LOOK_DOWN -> {
                    CompanionInputController.lookAt(player, Vec3d.ofCenter(supportPos).add(0.0D, 0.5D, 0.0D));
                    phase = Phase.JUMP;
                }
                case JUMP -> {
                    CompanionInputController.pressJumpOnly(player);
                    phase = Phase.PLACE_WINDOW;
                }
                case PLACE_WINDOW -> {
                    if (player.getY() - standPos.getY() >= PLACE_CLEARANCE) {
                        if (tryPlace()) {
                            phase = Phase.SETTLE;
                        } else {
                            return LevelResult.FAILED;
                        }
                    }
                    // else keep waiting for the apex; the timeout guards a window that never opens.
                }
                case SETTLE -> {
                    if (player.isOnGround() && !player.getEntityWorld().getBlockState(standPos).isAir()) {
                        return LevelResult.DONE;
                    }
                }
            }
            return LevelResult.RISING;
        }

        /** Place a block from the main hand onto the support block's top face, filling standPos. */
        private boolean tryPlace() {
            ItemStack stack = player.getMainHandStack();
            if (!(stack.getItem() instanceof BlockItem)) {
                return false;
            }
            BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(supportPos).add(0.0D, 0.5D, 0.0D),
                    Direction.UP,
                    supportPos,
                    false);
            ActionResult result = player.interactionManager.interactBlock(
                    player, player.getEntityWorld(), stack, Hand.MAIN_HAND, hit);
            boolean placed = result.isAccepted() && !player.getEntityWorld().getBlockState(standPos).isAir();
            if (placed) {
                // A real player swings when placing; the fake client has no client-side prediction to
                // do it, so trigger the swing animation explicitly (same as CompanionMiningTasks).
                player.swingHand(Hand.MAIN_HAND);
            }
            return placed;
        }
    }
}
