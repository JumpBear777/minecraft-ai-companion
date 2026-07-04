package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;
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
        TASKS.put(player.getUuid(), new DrivenVelocityTask(player, source, velocity));
        player.setVelocity(velocity);
        return true;
    }

    public static boolean applyVelocity(ServerPlayerEntity player, Vec3d velocity) {
        TASKS.put(player.getUuid(), new DrivenVelocityTask(player, null, velocity));
        player.setVelocity(velocity);
        return true;
    }

    public static boolean applyKnockbackHop(ServerPlayerEntity player, ServerCommandSource source, Vec3d velocity) {
        TASKS.put(player.getUuid(), new KnockbackHopTask(player, source, velocity));
        player.setVelocity(velocity);
        return true;
    }

    public static boolean applyKnockbackHop(ServerPlayerEntity player, Vec3d velocity) {
        TASKS.put(player.getUuid(), new KnockbackHopTask(player, null, velocity));
        player.setVelocity(velocity);
        return true;
    }

    public static boolean driveFall(ServerPlayerEntity player, ServerCommandSource source) {
        TASKS.put(player.getUuid(), new DrivenFallTask(player, source));
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

    private static final class DrivenFallTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 80;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final double startY;
        private double velocityY;
        private int ticks;

        private DrivenFallTask(ServerPlayerEntity player, ServerCommandSource source) {
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
            velocityY = Math.max(-1.2D, velocityY - 0.08D);
            double nextY = player.getY() + velocityY;
            OptionalDouble groundY = findGroundY(player.getX(), nextY, player.getZ());
            if (groundY.isPresent() && nextY <= groundY.getAsDouble() && velocityY <= 0.0D) {
                double landedY = groundY.getAsDouble();
                player.teleport((net.minecraft.server.world.ServerWorld) player.getEntityWorld(),
                        player.getX(), landedY, player.getZ(), java.util.Set.of(), player.getYaw(), player.getPitch(), true);
                player.setVelocity(Vec3d.ZERO);
                source.sendFeedback(() -> Text.literal(String.format(
                        "Driven fall: y %.2f -> %.2f ticks=%d landed=true",
                        startY,
                        landedY,
                        ticks)), true);
                return true;
            }

            player.teleport((net.minecraft.server.world.ServerWorld) player.getEntityWorld(),
                    player.getX(), nextY, player.getZ(), java.util.Set.of(), player.getYaw(), player.getPitch(), true);
            player.setVelocity(0.0D, velocityY, 0.0D);

            if (ticks >= TOTAL_TICKS) {
                source.sendFeedback(() -> Text.literal(String.format(
                        "Driven fall: y %.2f -> %.2f ticks=%d landed=false",
                        startY,
                        player.getY(),
                        ticks)), true);
                return true;
            }

            return false;
        }

        private OptionalDouble findGroundY(double x, double y, double z) {
            int startY = (int) Math.ceil(y);
            int minY = Math.max(-64, startY - 8);
            for (int blockY = startY; blockY >= minY; blockY--) {
                BlockPos pos = BlockPos.ofFloored(x, blockY - 0.05D, z);
                if (!player.getEntityWorld().getBlockState(pos).getCollisionShape(player.getEntityWorld(), pos).isEmpty()) {
                    return OptionalDouble.of(pos.getY() + 1.0D);
                }
            }

            return OptionalDouble.empty();
        }
    }

    private static final class DrivenVelocityTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 12;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final Vec3d startPos;
        private Vec3d velocity;
        private int ticks;

        private DrivenVelocityTask(ServerPlayerEntity player, ServerCommandSource source, Vec3d velocity) {
            this.player = player;
            this.source = source;
            this.velocity = velocity;
            this.startPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            Vec3d horizontalVelocity = new Vec3d(velocity.x, 0.0D, velocity.z);
            Vec3d next = new Vec3d(player.getX(), player.getY(), player.getZ()).add(horizontalVelocity);
            if (isBlockedAt(next)) {
                player.setVelocity(Vec3d.ZERO);
                sendResult();
                return true;
            }

            player.teleport((net.minecraft.server.world.ServerWorld) player.getEntityWorld(),
                    next.x, player.getY(), next.z, java.util.Set.of(), player.getYaw(), player.getPitch(), true);
            player.setVelocity(horizontalVelocity);
            velocity = new Vec3d(velocity.x * 0.65D, 0.0D, velocity.z * 0.65D);

            if (ticks >= TOTAL_TICKS) {
                player.setVelocity(Vec3d.ZERO);
                sendResult();
                return true;
            }

            return false;
        }

        private boolean isBlockedAt(Vec3d pos) {
            BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
            BlockPos head = feet.up();
            return !player.getEntityWorld().getBlockState(feet).getCollisionShape(player.getEntityWorld(), feet).isEmpty()
                    || !player.getEntityWorld().getBlockState(head).getCollisionShape(player.getEntityWorld(), head).isEmpty();
        }

        private void sendResult() {
            if (source == null) {
                return;
            }

            Vec3d endPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            source.sendFeedback(() -> Text.literal(String.format(
                    "Driven velocity: pos %.2f %.2f %.2f -> %.2f %.2f %.2f ticks=%d",
                    startPos.x,
                    startPos.y,
                    startPos.z,
                    endPos.x,
                    endPos.y,
                    endPos.z,
                    ticks)), true);
        }
    }

    private static final class KnockbackHopTask implements BehaviorTask {
        private static final int TOTAL_TICKS = 10;
        private static final double HOP_HEIGHT = 0.28D;

        private final ServerPlayerEntity player;
        private final ServerCommandSource source;
        private final Vec3d startPos;
        private Vec3d velocity;
        private int ticks;

        private KnockbackHopTask(ServerPlayerEntity player, ServerCommandSource source, Vec3d velocity) {
            this.player = player;
            this.source = source;
            this.velocity = velocity;
            this.startPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        }

        @Override
        public boolean tick() {
            if (player.isRemoved()) {
                return true;
            }

            ticks++;
            double progress = ticks / (double) TOTAL_TICKS;
            Vec3d horizontal = new Vec3d(velocity.x, 0.0D, velocity.z);
            Vec3d next = new Vec3d(player.getX(), startPos.y, player.getZ()).add(horizontal);
            if (isBlockedAt(next)) {
                player.setVelocity(Vec3d.ZERO);
                sendResult();
                return true;
            }

            double nextY = startPos.y + Math.sin(Math.PI * progress) * HOP_HEIGHT;
            player.teleport((net.minecraft.server.world.ServerWorld) player.getEntityWorld(),
                    next.x, nextY, next.z, java.util.Set.of(), player.getYaw(), player.getPitch(), true);
            player.setVelocity(horizontal);
            velocity = new Vec3d(velocity.x * 0.72D, 0.0D, velocity.z * 0.72D);

            if (ticks >= TOTAL_TICKS) {
                player.teleport((net.minecraft.server.world.ServerWorld) player.getEntityWorld(),
                        player.getX(), startPos.y, player.getZ(), java.util.Set.of(), player.getYaw(), player.getPitch(), true);
                player.setVelocity(Vec3d.ZERO);
                sendResult();
                return true;
            }

            return false;
        }

        private boolean isBlockedAt(Vec3d pos) {
            BlockPos feet = BlockPos.ofFloored(pos.x, pos.y, pos.z);
            BlockPos head = feet.up();
            return !player.getEntityWorld().getBlockState(feet).getCollisionShape(player.getEntityWorld(), feet).isEmpty()
                    || !player.getEntityWorld().getBlockState(head).getCollisionShape(player.getEntityWorld(), head).isEmpty();
        }

        private void sendResult() {
            if (source == null) {
                return;
            }

            Vec3d endPos = new Vec3d(player.getX(), player.getY(), player.getZ());
            source.sendFeedback(() -> Text.literal(String.format(
                    "Knockback hop: pos %.2f %.2f %.2f -> %.2f %.2f %.2f ticks=%d",
                    startPos.x,
                    startPos.y,
                    startPos.z,
                    endPos.x,
                    endPos.y,
                    endPos.z,
                    ticks)), true);
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
}
