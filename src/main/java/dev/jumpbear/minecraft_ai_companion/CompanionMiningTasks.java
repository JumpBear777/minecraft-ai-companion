package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CompanionMiningTasks {
    private static final int TOTAL_TICKS = 40;
    private static final Map<UUID, MiningTask> TASKS = new LinkedHashMap<>();

    private CompanionMiningTasks() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<MiningTask> iterator = TASKS.values().iterator();
            while (iterator.hasNext()) {
                MiningTask task = iterator.next();
                if (task.tick()) {
                    iterator.remove();
                }
            }
        });
    }

    public static boolean start(ServerPlayerEntity player, BlockPos target) {
        if (player.getEntityWorld().getBlockState(target).isAir()) {
            return false;
        }

        TASKS.put(player.getUuid(), new MiningTask(player, target));
        return true;
    }

    private static final class MiningTask {
        private final ServerPlayerEntity player;
        private final BlockPos target;
        private int ticks;

        private MiningTask(ServerPlayerEntity player, BlockPos target) {
            this.player = player;
            this.target = target;
        }

        private boolean tick() {
            if (player.isRemoved() || player.getEntityWorld().getBlockState(target).isAir()) {
                clearProgress();
                return true;
            }

            if (ticks % 5 == 0) {
                player.swingHand(Hand.MAIN_HAND);
            }

            int progress = Math.min(9, (ticks * 10) / TOTAL_TICKS);
            player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, progress);
            ticks++;

            if (ticks > TOTAL_TICKS) {
                clearProgress();
                player.interactionManager.tryBreakBlock(target);
                return true;
            }

            return false;
        }

        private void clearProgress() {
            player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, -1);
        }
    }
}
