package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
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

    public static boolean useItemVisually(ServerPlayerEntity player, ServerCommandSource source) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            return false;
        }

        player.setCurrentHand(Hand.MAIN_HAND);
        TASKS.put(player.getUuid(), new ItemUseTask(player, source, stack.copy()));
        return true;
    }

    public static boolean applyVelocity(ServerPlayerEntity player, ServerCommandSource source, Vec3d velocity) {
        player.setVelocity(velocity);
        TASKS.put(player.getUuid(), new VelocityObservationTask(player, source));
        return true;
    }

    private interface BehaviorTask {
        boolean tick();
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

            if (ticks % 4 == 0) {
                player.swingHand(Hand.MAIN_HAND);
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
}
