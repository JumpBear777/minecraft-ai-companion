package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public final class CompanionLifeSystem {
    private static final double LOOK_AT_PLAYER_RANGE = 8.0D;
    private static final double NODE_REACHED_DISTANCE_SQUARED = 0.45D * 0.45D;
    private static final int WANDER_HORIZONTAL_RANGE = 16;
    private static final int WANDER_VERTICAL_RANGE = 7;
    private static final int WANDER_ANCHOR_RANGE = 24;
    private static final Map<UUID, LifeState> STATES = new HashMap<>();

    private CompanionLifeSystem() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Optional<ServerPlayerEntity> companion = FakeCompanionSpawner.find(server);
            if (companion.isEmpty()) {
                STATES.clear();
                return;
            }

            tick(companion.get());
        });
    }

    private static void tick(ServerPlayerEntity player) {
        LifeState state = STATES.computeIfAbsent(player.getUuid(), uuid -> new LifeState(player));
        state.refreshWorld(player);

        if (shouldYieldToActiveTask(player)) {
            state.mode = LifeMode.PAUSE;
            state.timer = Math.max(state.timer, 20);
            return;
        }

        if (shouldStayQuiet(player)) {
            CompanionInputController.releaseInput(player);
            state.mode = LifeMode.PAUSE;
            state.timer = Math.max(state.timer, 20);
            return;
        }

        if (state.timer <= 0) {
            chooseNextBehavior(player, state);
        }

        switch (state.mode) {
            case PAUSE -> tickPause(player, state);
            case LOOK_AROUND -> tickLookAround(player, state);
            case LOOK_AT_PLAYER -> tickLookAtPlayer(player, state);
            case WANDER -> tickWander(player, state);
        }
    }

    private static boolean shouldStayQuiet(ServerPlayerEntity player) {
        return player.isRemoved()
                || !player.isAlive()
                || player.isSleeping()
                || player.hasVehicle()
                || player.isUsingItem()
                || player.currentScreenHandler != player.playerScreenHandler;
    }

    private static boolean shouldYieldToActiveTask(ServerPlayerEntity player) {
        return dev.jumpbear.minecraft_ai_companion.task.CompanionTaskManager.hasActiveTask(player)
                || CompanionMiningTasks.hasTask(player)
                || CompanionBehaviorTestTasks.hasTask(player);
    }

    private static void chooseNextBehavior(ServerPlayerEntity player, LifeState state) {
        Optional<ServerPlayerEntity> nearbyPlayer = findNearbyRealPlayer(player);
        int roll = state.random.nextInt(100);
        if (roll < 85 && startWander(player, state)) {
            return;
        }

        if (nearbyPlayer.isPresent() && roll < 93) {
            startLookAtPlayer(state, nearbyPlayer.get());
        } else if (roll < 98) {
            startLookAround(player, state);
        } else {
            startPause(state);
        }
    }

    private static void tickPause(ServerPlayerEntity player, LifeState state) {
        CompanionInputController.releaseInput(player);
        state.timer--;
    }

    private static void tickLookAround(ServerPlayerEntity player, LifeState state) {
        CompanionInputController.releaseInput(player);
        CompanionInputController.lookAt(player, state.lookTarget);
        state.timer--;
    }

    private static void tickLookAtPlayer(ServerPlayerEntity player, LifeState state) {
        CompanionInputController.releaseInput(player);
        if (state.targetPlayerUuid == null) {
            startPause(state);
            return;
        }

        ServerPlayerEntity target = ((ServerWorld) player.getEntityWorld()).getServer().getPlayerManager().getPlayer(state.targetPlayerUuid);
        if (target == null || target.isRemoved() || !target.isAlive()
                || target.getEntityWorld() != player.getEntityWorld()
                || target.squaredDistanceTo(player) > LOOK_AT_PLAYER_RANGE * LOOK_AT_PLAYER_RANGE) {
            startPause(state);
            return;
        }

        CompanionInputController.lookAt(player, target);
        state.timer--;
    }

    private static void tickWander(ServerPlayerEntity player, LifeState state) {
        if (state.path == null || state.path.isFinished()) {
            CompanionInputController.releaseInput(player);
            clearPathState(state);
            startPause(state);
            return;
        }

        if (state.timer-- <= 0) {
            CompanionInputController.releaseInput(player);
            clearPathState(state);
            startPause(state);
            return;
        }

        advanceReachedNodes(player, state);
        if (state.path == null || state.path.isFinished()) {
            CompanionInputController.releaseInput(player);
            clearPathState(state);
            startPause(state);
            return;
        }

        Vec3d nodePos = state.path.getNodePosition(player);
        CompanionInputController.lookAt(player, new Vec3d(nodePos.x, player.getEyeY(), nodePos.z));
        if (shouldJumpToward(player, state, nodePos) && state.jumpInputTicks == 0) {
            state.jumpInputTicks = 8;
        }

        boolean jumpInput = state.jumpInputTicks > 0;
        CompanionInputController.applyServerTravelForward(player, jumpInput);
        if (state.jumpInputTicks > 0) {
            state.jumpInputTicks--;
        }
    }

    private static void startPause(LifeState state) {
        state.mode = LifeMode.PAUSE;
        state.timer = 8 + state.random.nextInt(18);
        state.targetPlayerUuid = null;
    }

    private static void startLookAround(ServerPlayerEntity player, LifeState state) {
        state.mode = LifeMode.LOOK_AROUND;
        state.timer = 20 + state.random.nextInt(25);
        state.targetPlayerUuid = null;
        double angle = Math.PI * 2.0D * state.random.nextDouble();
        double distance = 3.0D + state.random.nextDouble() * 4.0D;
        state.lookTarget = player.getEyePos().add(
                Math.cos(angle) * distance,
                state.random.nextDouble() - 0.25D,
                Math.sin(angle) * distance);
    }

    private static void startLookAtPlayer(LifeState state, ServerPlayerEntity target) {
        state.mode = LifeMode.LOOK_AT_PLAYER;
        state.timer = 25 + state.random.nextInt(25);
        state.targetPlayerUuid = target.getUuid();
        clearPathState(state);
    }

    private static boolean startWander(ServerPlayerEntity player, LifeState state) {
        if (!(player.getEntityWorld() instanceof ServerWorld world) || !player.isOnGround()) {
            return false;
        }

        VillagerEntity proxy = EntityType.VILLAGER.create(world, SpawnReason.COMMAND);
        if (proxy == null) {
            return false;
        }

        proxy.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        proxy.setAiDisabled(true);
        proxy.setNoGravity(true);
        proxy.setOnGround(player.isOnGround());
        if (state.wanderAnchor == null || !player.getBlockPos().isWithinDistance(state.wanderAnchor, WANDER_ANCHOR_RANGE)) {
            state.wanderAnchor = player.getBlockPos();
        }
        proxy.setPositionTarget(state.wanderAnchor, WANDER_ANCHOR_RANGE);

        Vec3d target = NoPenaltyTargeting.find(proxy, WANDER_HORIZONTAL_RANGE, WANDER_VERTICAL_RANGE);
        if (target == null) {
            return false;
        }

        Path path = proxy.getNavigation().findPathTo(target.x, target.y, target.z, 1);
        if (path == null || path.getLength() == 0) {
            return false;
        }

        state.mode = LifeMode.WANDER;
        state.timer = 220 + state.random.nextInt(120);
        state.targetPlayerUuid = null;
        state.path = path;
        state.lastProgressPos = player.getEntityPos();
        state.stuckTicks = 0;
        state.jumpInputTicks = 0;
        return true;
    }

    private static Optional<ServerPlayerEntity> findNearbyRealPlayer(ServerPlayerEntity companion) {
        Box box = companion.getBoundingBox().expand(LOOK_AT_PLAYER_RANGE, 3.0D, LOOK_AT_PLAYER_RANGE);
        List<ServerPlayerEntity> players = companion.getEntityWorld().getEntitiesByClass(
                ServerPlayerEntity.class,
                box,
                player -> player != companion && !FakeCompanionSpawner.isCompanion(player) && player.isAlive());
        return players.stream().min(Comparator.comparingDouble(companion::squaredDistanceTo));
    }

    private static void advanceReachedNodes(ServerPlayerEntity player, LifeState state) {
        while (state.path != null && !state.path.isFinished()) {
            Vec3d nodePos = state.path.getNodePosition(player);
            double dx = player.getX() - nodePos.x;
            double dz = player.getZ() - nodePos.z;
            boolean closeHorizontally = dx * dx + dz * dz <= NODE_REACHED_DISTANCE_SQUARED;
            boolean closeVertically = Math.abs(player.getY() - nodePos.y) < 1.1D;
            if (!closeHorizontally || !closeVertically) {
                return;
            }

            state.path.next();
        }
    }

    private static boolean shouldJumpToward(ServerPlayerEntity player, LifeState state, Vec3d nodePos) {
        Vec3d currentPos = player.getEntityPos();
        double movedX = currentPos.x - state.lastProgressPos.x;
        double movedZ = currentPos.z - state.lastProgressPos.z;
        double movedSquared = movedX * movedX + movedZ * movedZ;
        if (movedSquared < 0.0004D) {
            state.stuckTicks++;
        } else {
            state.stuckTicks = 0;
            state.lastProgressPos = currentPos;
        }

        boolean nextNodeIsHigher = nodePos.y > player.getY() + 0.5D;
        return player.isOnGround() && (nextNodeIsHigher || player.horizontalCollision || state.stuckTicks >= 8);
    }

    private static void clearPathState(LifeState state) {
        state.path = null;
        state.stuckTicks = 0;
        state.jumpInputTicks = 0;
    }

    private enum LifeMode {
        PAUSE,
        LOOK_AROUND,
        LOOK_AT_PLAYER,
        WANDER
    }

    private static final class LifeState {
        private final Random random;
        private String worldId;
        private BlockPos wanderAnchor;
        private Vec3d lookTarget;
        private Vec3d lastProgressPos;
        private Path path;
        private UUID targetPlayerUuid;
        private LifeMode mode = LifeMode.PAUSE;
        private int timer;
        private int stuckTicks;
        private int jumpInputTicks;

        private LifeState(ServerPlayerEntity player) {
            this.random = new Random(player.getUuid().getLeastSignificantBits() ^ System.nanoTime());
            this.worldId = player.getEntityWorld().getRegistryKey().getValue().toString();
            this.wanderAnchor = player.getBlockPos();
            this.lookTarget = player.getEyePos().add(1.0D, 0.0D, 0.0D);
            this.lastProgressPos = player.getEntityPos();
            startPause(this);
        }

        private void refreshWorld(ServerPlayerEntity player) {
            String currentWorldId = player.getEntityWorld().getRegistryKey().getValue().toString();
            if (!currentWorldId.equals(this.worldId)) {
                this.worldId = currentWorldId;
                this.mode = LifeMode.PAUSE;
                this.timer = 40;
                this.targetPlayerUuid = null;
                this.wanderAnchor = player.getBlockPos();
                clearPathState(this);
            }
        }
    }
}
