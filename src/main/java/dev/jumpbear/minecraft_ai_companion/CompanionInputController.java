package dev.jumpbear.minecraft_ai_companion;

import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;

public final class CompanionInputController {
    public static final double DEFAULT_PLAYER_MOVEMENT_SPEED = 0.1D;
    public static final double BASE_WALK_STEP = 0.12D;
    public static final double SNEAK_STEP_MULTIPLIER = 0.3D;
    private CompanionInputController() {
    }

    public static void lookAt(ServerPlayerEntity player, Entity target) {
        lookAt(player, target.getEyePos());
    }

    public static void lookAt(ServerPlayerEntity player, Vec3d target) {
        Vec3d delta = target.subtract(player.getEyePos());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontal)));

        player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw,
                pitch,
                player.isOnGround(),
                player.horizontalCollision));
    }

    public static void stepForward(ServerPlayerEntity player, double distance) {
        sendForwardMove(player, distance, 0.0D, player.isOnGround());
    }

    public static void pressForward(ServerPlayerEntity player, boolean jump) {
        pressForward(player, jump, false, false);
    }

    public static void pressForward(ServerPlayerEntity player, boolean jump, boolean sneak, boolean sprint) {
        pressForward(player, jump, jump, sneak, sprint);
    }

    public static void pressForward(
            ServerPlayerEntity player,
            boolean jumpInput,
            boolean triggerJumpMove,
            boolean sneak,
            boolean sprint) {
        if (sprint) {
            setSprinting(player, true);
        }

        PlayerInput input = new PlayerInput(true, false, false, false, jumpInput, sneak, sprint);
        player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(input));
        if (triggerJumpMove && player.isOnGround()) {
            player.jump();
        }

        sendForwardMove(player, getForwardStep(player, sneak), 0.0D, player.isOnGround() && !jumpInput);
    }

    public static void pressJumpOnly(ServerPlayerEntity player) {
        PlayerInput input = new PlayerInput(false, false, false, false, true, false, false);
        player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(input));
        sendMove(player, player.getX(), player.getY() + 0.12D, player.getZ(), false);
    }

    public static void releaseInput(ServerPlayerEntity player) {
        player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(PlayerInput.DEFAULT));
        player.forwardSpeed = 0.0F;
        player.sidewaysSpeed = 0.0F;
        player.upwardSpeed = 0.0F;
        player.setJumping(false);
        setSprinting(player, false);
    }

    /**
     * Level the view to the horizon (pitch 0), keeping the current yaw. A task that pointed the head
     * up or down (e.g. mining a log overhead) must call this when it finishes, otherwise the fake
     * player has no client to recentre the camera and the companion is left staring up/down until some
     * other behavior happens to move its head.
     */
    public static void resetPitch(ServerPlayerEntity player) {
        player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.LookAndOnGround(
                player.getYaw(),
                0.0F,
                player.isOnGround(),
                player.horizontalCollision));
    }

    public static void applyServerTravelForward(ServerPlayerEntity player, boolean jump) {
        player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(
                new PlayerInput(true, false, false, false, jump, false, false)));
        player.setMovementSpeed((float) player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED));
        player.forwardSpeed = 1.0F;
        player.sidewaysSpeed = 0.0F;
        player.upwardSpeed = 0.0F;
        player.setJumping(jump);
    }

    public static void setSprinting(ServerPlayerEntity player, boolean sprinting) {
        ClientCommandC2SPacket.Mode mode = sprinting
                ? ClientCommandC2SPacket.Mode.START_SPRINTING
                : ClientCommandC2SPacket.Mode.STOP_SPRINTING;
        player.networkHandler.onClientCommand(new ClientCommandC2SPacket(player, mode));
    }

    public static double getForwardStep(ServerPlayerEntity player, boolean sneak) {
        double step = BASE_WALK_STEP * (player.getMovementSpeed() / DEFAULT_PLAYER_MOVEMENT_SPEED);
        return sneak ? step * SNEAK_STEP_MULTIPLIER : step;
    }

    private static void sendForwardMove(ServerPlayerEntity player, double distance, double yOffset, boolean onGround) {
        double yawRadians = Math.toRadians(player.getYaw());
        double x = player.getX() - Math.sin(yawRadians) * distance;
        double y = player.getY() + yOffset;
        double z = player.getZ() + Math.cos(yawRadians) * distance;

        sendMove(player, x, y, z, onGround);
    }

    private static void sendMove(ServerPlayerEntity player, double x, double y, double z, boolean onGround) {
        player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.Full(
                x,
                y,
                z,
                player.getYaw(),
                player.getPitch(),
                onGround,
                player.horizontalCollision));
    }
}
