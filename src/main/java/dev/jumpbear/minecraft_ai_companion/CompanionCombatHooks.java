package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.MathHelper;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CompanionCombatHooks {
    private static final Map<UUID, PendingKnockback> PENDING_KNOCKBACKS = new LinkedHashMap<>();

    private CompanionCombatHooks() {
    }

    public static void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient() && player instanceof ServerPlayerEntity attacker && FakeCompanionSpawner.isCompanionEntity(entity)) {
                ServerPlayerEntity companion = (ServerPlayerEntity) entity;
                PENDING_KNOCKBACKS.put(companion.getUuid(), new PendingKnockback(companion, attacker.getYaw()));
            }

            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<PendingKnockback> iterator = PENDING_KNOCKBACKS.values().iterator();
            while (iterator.hasNext()) {
                PendingKnockback pending = iterator.next();
                pending.apply();
                iterator.remove();
            }
        });
    }

    private record PendingKnockback(ServerPlayerEntity companion, float attackerYaw) {
        private void apply() {
            if (companion.isRemoved()) {
                return;
            }

            companion.takeKnockback(
                    0.4D,
                    MathHelper.sin(attackerYaw * (float) (Math.PI / 180.0)),
                    -MathHelper.cos(attackerYaw * (float) (Math.PI / 180.0)));
        }
    }
}
