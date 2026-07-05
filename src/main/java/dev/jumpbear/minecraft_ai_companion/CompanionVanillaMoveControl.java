package dev.jumpbear.minecraft_ai_companion;

import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

public final class CompanionVanillaMoveControl {
    private static final float MAX_TURN_DEGREES = 90.0F;
    private static final float REACHED_DESTINATION_DISTANCE_SQUARED = 2.5000003E-7F;
    private static final double JUMP_TRIGGER_Y_OFFSET = 0.001D;

    private final ServerPlayerEntity player;
    private double targetX;
    private double targetY;
    private double targetZ;
    private double speed;
    private State state = State.WAIT;

    public CompanionVanillaMoveControl(ServerPlayerEntity player) {
        this.player = player;
    }

    public void moveTo(double x, double y, double z, double speed) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.speed = speed;
        if (this.state != State.JUMPING) {
            this.state = State.MOVE_TO;
        }
    }

    public void tick() {
        if (this.state == State.MOVE_TO) {
            this.state = State.WAIT;
            double x = this.targetX - this.player.getX();
            double z = this.targetZ - this.player.getZ();
            double y = this.targetY - this.player.getY();
            double distanceSquared = x * x + y * y + z * z;
            if (distanceSquared < REACHED_DESTINATION_DISTANCE_SQUARED) {
                this.player.forwardSpeed = 0.0F;
                return;
            }

            float targetYaw = (float)(MathHelper.atan2(z, x) * 180.0F / (float)Math.PI) - 90.0F;
            setYaw(wrapDegrees(this.player.getYaw(), targetYaw, MAX_TURN_DEGREES));

            BlockPos blockPos = this.player.getBlockPos();
            BlockState blockState = this.player.getEntityWorld().getBlockState(blockPos);
            VoxelShape voxelShape = blockState.getCollisionShape(this.player.getEntityWorld(), blockPos);
            boolean shouldJump = y > this.player.getStepHeight() && x * x + z * z < Math.max(1.0F, this.player.getWidth())
                    || !voxelShape.isEmpty()
                    && this.player.getY() < voxelShape.getMax(Direction.Axis.Y) + blockPos.getY()
                    && !blockState.isIn(BlockTags.DOORS)
                    && !blockState.isIn(BlockTags.FENCES);
            applyForwardInputAndMove(shouldJump);
            if (shouldJump) {
                this.state = State.JUMPING;
            }
        } else if (this.state == State.JUMPING) {
            applyForwardInputAndMove(false);
            if (this.player.isOnGround() || this.player.isInFluid() && this.player.shouldSwimInFluids()) {
                this.state = State.WAIT;
            }
        } else {
            this.player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(PlayerInput.DEFAULT));
            this.player.forwardSpeed = 0.0F;
            this.player.setJumping(false);
        }
    }

    public void stop() {
        this.state = State.WAIT;
        this.player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(PlayerInput.DEFAULT));
        this.player.forwardSpeed = 0.0F;
        this.player.sidewaysSpeed = 0.0F;
        this.player.upwardSpeed = 0.0F;
        this.player.setJumping(false);
    }

    private void applyForwardInputAndMove(boolean jump) {
        this.player.networkHandler.onPlayerInput(new PlayerInputC2SPacket(
                new PlayerInput(true, false, false, false, jump, false, false)));
        float movementSpeed = (float)(this.speed * this.player.getAttributeValue(EntityAttributes.MOVEMENT_SPEED));
        this.player.setMovementSpeed(movementSpeed);
        this.player.forwardSpeed = 1.0F;
        this.player.sidewaysSpeed = 0.0F;
        this.player.upwardSpeed = 0.0F;
        this.player.setJumping(jump);
        boolean shouldTriggerVanillaJump = jump && this.player.isOnGround();

        double distance = CompanionInputController.BASE_WALK_STEP
                * (movementSpeed / CompanionInputController.DEFAULT_PLAYER_MOVEMENT_SPEED);
        double yawRadians = Math.toRadians(this.player.getYaw());
        double x = this.player.getX() - Math.sin(yawRadians) * distance;
        double y = this.player.getY();
        double z = this.player.getZ() + Math.cos(yawRadians) * distance;
        if (shouldTriggerVanillaJump) {
            y += JUMP_TRIGGER_Y_OFFSET;
        }

        this.player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.Full(
                x,
                y,
                z,
                this.player.getYaw(),
                this.player.getPitch(),
                this.player.isOnGround() && !shouldTriggerVanillaJump,
                this.player.horizontalCollision));
    }

    private void setYaw(float yaw) {
        this.player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.LookAndOnGround(
                yaw,
                this.player.getPitch(),
                this.player.isOnGround(),
                this.player.horizontalCollision));
    }

    private static float wrapDegrees(float from, float to, float max) {
        float delta = MathHelper.wrapDegrees(to - from);
        if (delta > max) {
            delta = max;
        }

        if (delta < -max) {
            delta = -max;
        }

        float result = from + delta;
        if (result < 0.0F) {
            result += 360.0F;
        } else if (result > 360.0F) {
            result -= 360.0F;
        }

        return result;
    }

    private enum State {
        WAIT,
        MOVE_TO,
        JUMPING
    }
}
