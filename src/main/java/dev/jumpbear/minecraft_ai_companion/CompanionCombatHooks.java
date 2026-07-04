package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class CompanionCombatHooks {
    private CompanionCombatHooks() {
    }

    public static void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity attacker && isCompanion(entity)) {
                applyManualAttackKnockback(attacker, (ServerPlayerEntity) entity, world);
            }

            return ActionResult.PASS;
        });
    }

    private static boolean isCompanion(Entity entity) {
        return entity instanceof ServerPlayerEntity target
                && FakeCompanionSpawner.COMPANION_NAME.equals(target.getName().getString());
    }

    private static void applyManualAttackKnockback(ServerPlayerEntity attacker, ServerPlayerEntity companion, World world) {
        Vec3d direction = new Vec3d(
                companion.getX() - attacker.getX(),
                0.0D,
                companion.getZ() - attacker.getZ());
        if (direction.lengthSquared() < 0.0001D) {
            direction = Vec3d.fromPolar(0.0F, attacker.getYaw());
        }

        Vec3d knockback = direction.normalize().multiply(0.22D);
        CompanionBehaviorTestTasks.applyKnockbackHop(companion, knockback);
    }
}
