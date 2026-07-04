package dev.jumpbear.minecraft_ai_companion.mixin;

import dev.jumpbear.minecraft_ai_companion.FakeCompanionSpawner;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {
    @Redirect(
            method = "tickMovement",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerPlayerEntity;updatePositionAndAngles(DDDFF)V"))
    private void minecraft_ai_companion$keepVanillaPhysicsPosition(
            ServerPlayerEntity player,
            double x,
            double y,
            double z,
            float yaw,
            float pitch) {
        if (FakeCompanionSpawner.isCompanion(player)) {
            return;
        }

        player.updatePositionAndAngles(x, y, z, yaw, pitch);
    }
}
