package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CompanionMiningTasks {
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
        return start(player, target, null, "Mining");
    }

    public static boolean start(ServerPlayerEntity player, BlockPos target, ServerCommandSource source, String label) {
        if (player.getEntityWorld().getBlockState(target).isAir()) {
            return false;
        }

        TASKS.put(player.getUuid(), new MiningTask(player, target, source, label));
        return true;
    }

    public static boolean hasTask(ServerPlayerEntity player) {
        return TASKS.containsKey(player.getUuid());
    }

    private static final class MiningTask {
        private final ServerPlayerEntity player;
        private final BlockPos target;
        private final ServerCommandSource source;
        private final String label;
        private final String initialBlockName;
        private int ticks;
        private int lastProgress = -1;

        private MiningTask(ServerPlayerEntity player, BlockPos target, ServerCommandSource source, String label) {
            this.player = player;
            this.target = target;
            this.source = source;
            this.label = label;
            this.initialBlockName = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
            player.interactionManager.processBlockBreakingAction(
                    target,
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP,
                    player.getEntityWorld().getTopYInclusive(),
                    0);
        }

        private boolean tick() {
            if (player.isRemoved() || player.getEntityWorld().getBlockState(target).isAir()) {
                clearProgress();
                reportFinished(true);
                return true;
            }

            if (ticks % 5 == 0) {
                player.swingHand(Hand.MAIN_HAND);
            }

            BlockState state = player.getEntityWorld().getBlockState(target);
            float breakingDelta = state.calcBlockBreakingDelta(player, player.getEntityWorld(), target) * (ticks + 1);
            int progress = Math.min(9, (int) (breakingDelta * 10.0F));
            if (progress != lastProgress) {
                player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, progress);
                lastProgress = progress;
            }
            ticks++;

            if (breakingDelta >= 1.0F) {
                clearProgress();
                player.interactionManager.processBlockBreakingAction(
                        target,
                        PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                        Direction.UP,
                        player.getEntityWorld().getTopYInclusive(),
                        1);
                reportFinished(player.getEntityWorld().getBlockState(target).isAir());
                return true;
            }

            return false;
        }

        private void clearProgress() {
            player.getEntityWorld().setBlockBreakingInfo(player.getId(), target, -1);
        }

        private void reportFinished(boolean success) {
            if (source == null) {
                return;
            }

            String after = player.getEntityWorld().getBlockState(target).getBlock().getName().getString();
            source.sendFeedback(() -> Text.literal(label
                    + ": block " + initialBlockName + " -> " + after
                    + " ticks=" + ticks
                    + " success=" + success), true);
        }
    }
}
