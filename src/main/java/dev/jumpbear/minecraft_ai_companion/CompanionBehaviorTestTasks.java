package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CompanionBehaviorTestTasks {
    private static final Map<UUID, BehaviorTask> TASKS = new LinkedHashMap<>();

    private CompanionBehaviorTestTasks() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<BehaviorTask> iterator = TASKS.values().iterator();
            while (iterator.hasNext()) {
                BehaviorTask task = iterator.next();
                if (task.tick()) {
                    iterator.remove();
                }
            }
        });
    }

    public static boolean observeGravity(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new GravityObservationTask(player, source));
        return true;
    }

    public static boolean observeVelocity(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new VelocityObservationTask(player, source));
        return true;
    }

    public static boolean observeInventoryPickup(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new InventoryPickupObservationTask(player, source));
        return true;
    }

    public static boolean observeExperience(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new ExperienceObservationTask(player, source));
        return true;
    }

    public static boolean observeHealth(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new HealthObservationTask(player, source));
        return true;
    }

    public static boolean useItemVisually(ServerPlayerEntity player, ServerCommandSource source) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            return false;
        }

        player.setCurrentHand(Hand.MAIN_HAND);
        TASKS.put(player.getUuid(), new ItemUseTask(player, source, stack.copy()));
        return true;
    }

    public static boolean walkForward(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new MovementInputTask(player, source, MovementMode.WALK_FORWARD));
        return true;
    }

    public static boolean jumpForward(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new MovementInputTask(player, source, MovementMode.JUMP_FORWARD));
        return true;
    }

    public static boolean sprintForward(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new MovementInputTask(player, source, MovementMode.SPRINT_FORWARD));
        return true;
    }

    public static boolean sneakForward(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new MovementInputTask(player, source, MovementMode.SNEAK_FORWARD));
        return true;
    }

    public static boolean swimUp(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new MovementInputTask(player, source, MovementMode.SWIM_UP));
        return true;
    }

    public static boolean walkToUseBlock(
            ServerPlayerEntity player,
            ServerCommandSource source,
            BlockPos target,
            Direction side,
            BlockUseResultHandler resultHandler) {
        TASKS.put(player.getUuid(), new WalkToUseBlockTask(player, source, target, side, resultHandler));
        return true;
    }

    private interface BehaviorTask {
        boolean tick();
    }

    public interface BlockUseResultHandler {
        boolean report(
                ServerCommandSource source,
                ServerPlayerEntity player,
                ActionResult result,
                Vec3d startPos,
                int ticks);
    }

    private enum MovementMode {
        WALK_FORWARD("Walk forward"),
        JUMP_FORWARD("Jump forward"),
        SPRINT_FORWARD("Sprint forward"),
        SNEAK_FORWARD("Sneak forward"),
        SWIM_UP("Swim up");

        private final String label;

        MovementMode(String label) {
            this.label = label;
        }
    }

    private static final class GravityObservationTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 40;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final double startY;
        private int ticks;

        private GravityObservationTask(ServerPlayerEntity player, ServerCommandSource source) {
            this.player = player;
            this.source = source;
            this.startY = player.getY();
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= TOTAL_TICKS) {
                double endY = player.getY();
                Vec3d velocity = player.getVelocity();
                source.sendFeedback(() -> Text.literal(String.format(
                        "Gravity observe: y %.2f -> %.2f delta=%.2f onGround=%s velocity=%.3f %.3f %.3f",
                        startY,
                        endY,
                        endY - startY,
                        player.isOnGround(),
                        velocity.x,
                        velocity.y,
                        velocity.z)), true);
                return true;
            }

            return false;
        }
    }

    private static final class VelocityObservationTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 20;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final Vec3d startPos;
        private int ticks;

        private VelocityObservationTask(ServerPlayerEntity player, ServerCommandSource source) {
            this.player = player;
            this.source = source;
            this.startPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= TOTAL_TICKS) {
                Vec3d endPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                Vec3d velocity = player.getVelocity();
                source.sendFeedback(() -> Text.literal(String.format(
                        "Velocity observe: pos %.2f %.2f %.2f -> %.2f %.2f %.2f velocity=%.3f %.3f %.3f",
                        startPos.x,
                        startPos.y,
                        startPos.z,
                        endPos.x,
                        endPos.y,
                        endPos.z,
                        velocity.x,
                        velocity.y,
                        velocity.z)), true);
                return true;
            }

            return false;
        }
    }

    private static final class InventoryPickupObservationTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 30;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final int startOccupiedSlots;
        private final int startItemCount;
        private int ticks;

        private InventoryPickupObservationTask(ServerPlayerEntity player, ServerCommandSource source) {
            this.player = player;
            this.source = source;
            this.startOccupiedSlots = countOccupiedSlots(player);
            this.startItemCount = countTotalItems(player);
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= TOTAL_TICKS) {
                int endOccupiedSlots = countOccupiedSlots(player);
                int endItemCount = countTotalItems(player);
                source.sendFeedback(() -> Text.literal(String.format(
                        "Pickup observe: occupiedSlots %d -> %d totalItems %d -> %d",
                        startOccupiedSlots,
                        endOccupiedSlots,
                        startItemCount,
                        endItemCount)), true);
                return true;
            }

            return false;
        }

        private static int countOccupiedSlots(ServerPlayerEntity player) {
            int occupied = 0;
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                if (!player.getInventory().getStack(slot).isEmpty()) {
                    occupied++;
                }
            }
            return occupied;
        }

        private static int countTotalItems(ServerPlayerEntity player) {
            int total = 0;
            for (int slot = 0; slot < player.getInventory().size(); slot++) {
                total += player.getInventory().getStack(slot).getCount();
            }
            return total;
        }
    }

    private static final class ExperienceObservationTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 40;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final int startLevel;
        private final int startTotalExperience;
        private int ticks;

        private ExperienceObservationTask(ServerPlayerEntity player, ServerCommandSource source) {
            this.player = player;
            this.source = source;
            this.startLevel = player.experienceLevel;
            this.startTotalExperience = player.totalExperience;
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= TOTAL_TICKS) {
                source.sendFeedback(() -> Text.literal(String.format(
                        "Experience observe: level %d -> %d totalXp %d -> %d",
                        startLevel,
                        player.experienceLevel,
                        startTotalExperience,
                        player.totalExperience)), true);
                return true;
            }

            return false;
        }
    }

    private static final class HealthObservationTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 100;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final float startHealth;
        private int ticks;

        private HealthObservationTask(ServerPlayerEntity player, ServerCommandSource source) {
            this.player = player;
            this.source = source;
            this.startHealth = player.getHealth();
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= TOTAL_TICKS) {
                source.sendFeedback(() -> Text.literal(String.format(
                        "Health observe: %.1f -> %.1f",
                        startHealth,
                        player.getHealth())), true);
                return true;
            }

            return false;
        }
    }

    private static final class ItemUseTask implements BehaviorTask {
        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final ItemStack originalStack;
        private final int originalFood;
        private final int originalCount;
        private final int maxTicks;
        private int ticks;

        private ItemUseTask(ServerPlayerEntity player, ServerCommandSource source, ItemStack originalStack) {
            this.player = player;
            this.source = source;
            this.originalStack = originalStack;
            this.originalFood = player.getHungerManager().getFoodLevel();
            this.originalCount = player.getMainHandStack().getCount();
            this.maxTicks = Math.max(1, player.getMainHandStack().getMaxUseTime(player));
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            if (ticks >= maxTicks) {
                ItemStack stack = player.getMainHandStack();
                ItemStack result = stack.finishUsing(player.getEntityWorld(), player);
                player.setStackInHand(Hand.MAIN_HAND, result);
                player.clearActiveItem();

                int afterFood = player.getHungerManager().getFoodLevel();
                int afterCount = result.getCount();
                source.sendFeedback(() -> Text.literal("Use item visual: item=" + originalStack.getName().getString()
                        + " ticks=" + ticks
                        + " food " + originalFood + " -> " + afterFood
                        + " count " + originalCount + " -> " + afterCount), true);
                return true;
            }

            return false;
        }
    }

    private static final class MovementInputTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 20;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final MovementMode mode;
        private final Vec3d startPos;
        private int ticks;

        private MovementInputTask(ServerPlayerEntity player, ServerCommandSource source, MovementMode mode) {
            this.player = player;
            this.source = source;
            this.mode = mode;
            this.startPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            tickInput();

            if (ticks >= TOTAL_TICKS) {
                CompanionInputController.releaseInput(player);
                Vec3d endPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                Vec3d velocity = player.getVelocity();
                source.sendFeedback(() -> Text.literal(String.format(
                        "%s input: pos %.2f %.2f %.2f -> %.2f %.2f %.2f velocity=%.3f %.3f %.3f onGround=%s sprinting=%s sneaking=%s inWater=%s",
                        mode.label,
                        startPos.x,
                        startPos.y,
                        startPos.z,
                        endPos.x,
                        endPos.y,
                        endPos.z,
                        velocity.x,
                        velocity.y,
                        velocity.z,
                        player.isOnGround(),
                        player.isSprinting(),
                        player.isSneaking(),
                        player.isTouchingWater())), true);
                return true;
            }

            return false;
        }

        private void tickInput() {
            switch (mode) {
                case WALK_FORWARD -> CompanionInputController.pressForward(player, false);
                case JUMP_FORWARD -> CompanionInputController.pressForward(player, ticks == 1 && player.isOnGround());
                case SPRINT_FORWARD -> CompanionInputController.pressForward(player, false, false, true);
                case SNEAK_FORWARD -> CompanionInputController.pressForward(player, false, true, false);
                case SWIM_UP -> CompanionInputController.pressJumpOnly(player);
            }
        }
    }

    private static final class WalkToUseBlockTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 120;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final BlockPos target;
        private final Direction side;
        private final BlockUseResultHandler resultHandler;
        private final Vec3d startPos;
        private Vec3d lastProgressPos;
        private int ticks;
        private int stuckTicks;
        private int jumpInputTicks;

        private WalkToUseBlockTask(
                ServerPlayerEntity player,
                ServerCommandSource source,
                BlockPos target,
                Direction side,
                BlockUseResultHandler resultHandler) {
            this.player = player;
            this.source = source;
            this.target = target;
            this.side = side;
            this.resultHandler = resultHandler;
            this.startPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            this.lastProgressPos = this.startPos;
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            Vec3d targetCenter = target.toCenterPos();
            if (player.canInteractWithBlockAt(target, 1.0D)) {
                CompanionInputController.releaseInput(player);
                CompanionInputController.lookAt(player, targetCenter);
                BlockHitResult hitResult = new BlockHitResult(targetCenter, side, target, false);
                ItemStack stack = player.getMainHandStack();
                ActionResult result = player.interactionManager.interactBlock(
                        player,
                        player.getEntityWorld(),
                        stack,
                        Hand.MAIN_HAND,
                        hitResult);
                resultHandler.report(source, player, result, startPos, ticks);
                return true;
            }

            if (ticks >= TOTAL_TICKS) {
                CompanionInputController.releaseInput(player);
                Vec3d endPos = new Vec3d(player.getX(), player.getY(), player.getZ());
                source.sendFeedback(() -> Text.literal(String.format(
                        "Walk to use block failed: target=%s pos %.2f %.2f %.2f -> %.2f %.2f %.2f canInteract=false horizontalCollision=%s stuckTicks=%d",
                        target.toShortString(),
                        startPos.x,
                        startPos.y,
                        startPos.z,
                        endPos.x,
                        endPos.y,
                        endPos.z,
                        player.horizontalCollision,
                        stuckTicks)), true);
                return true;
            }

            CompanionInputController.lookAt(player, targetCenter);
            if (shouldJumpOverObstacle() && jumpInputTicks == 0) {
                jumpInputTicks = 8;
            }

            boolean jumpInput = jumpInputTicks > 0;
            CompanionInputController.applyServerTravelForward(player, jumpInput);
            if (jumpInputTicks > 0) {
                jumpInputTicks--;
            }
            return false;
        }

        private boolean shouldJumpOverObstacle() {
            Vec3d currentPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            double movedX = currentPos.x - lastProgressPos.x;
            double movedZ = currentPos.z - lastProgressPos.z;
            double movedSquared = movedX * movedX + movedZ * movedZ;
            if (movedSquared < 0.0004D) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
                lastProgressPos = currentPos;
            }

            return player.isOnGround() && (player.horizontalCollision || stuckTicks >= 8);
        }
    }
}
