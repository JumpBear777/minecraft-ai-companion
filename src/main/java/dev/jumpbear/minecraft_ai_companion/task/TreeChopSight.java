package dev.jumpbear.minecraft_ai_companion.task;

import dev.jumpbear.minecraft_ai_companion.CompanionInputController;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;

/**
 * 挖掘前的<b>视线门控</b>：同伴当前能否「看见并够得着」目标方块——即真人玩家此刻能不能挖它。
 *
 * <p>为什么需要它：{@code CompanionMiningTasks} 只继承了 vanilla 服务端挖掘的<em>距离</em>校验，没有视线
 * 校验。真人靠客户端准星射线，只能挖看得见的块；假客户端没有这一层，会「隔着泥土/树叶挖穿目标」。本类补上。
 *
 * <p>度量严格对齐 vanilla（不硬编码、不自造）：距离用 {@code canInteractWithBlockAt(target, 1.0)}——与服务端
 * 挖掘的距离判据同一个方法（眼→方块 AABB 最近点 &lt; 交互距离+1.0，vanilla 给挖掘留的抗延迟余量）。视线用
 * 眼睛→目标中心的<b>线段射线</b>（两点式 {@code world.raycast}，与朝向无关，避免「发包设朝向同 tick 读不到」
 * 的时序坑），OUTLINE 形状、不含流体，复现真人准星 pick 的语义。
 */
public final class TreeChopSight {

    private TreeChopSight() {
    }

    /**
     * @return true 表示够得着且视线通（射线命中的正是目标）；false 表示够不着、或被别的方块挡住——都不应挖。
     */
    public static boolean hasLineOfSight(ServerPlayerEntity player, BlockPos target) {
        // 距离：直接复用 vanilla 挖掘判据（眼→AABB 最近点 < 交互距离+1.0）。
        if (!player.canInteractWithBlockAt(target, 1.0D)) {
            return false;
        }
        RaycastContext ctx = new RaycastContext(
                player.getEyePos(),
                target.toCenterPos(),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player);
        BlockHitResult hit = player.getEntityWorld().raycast(ctx);
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
    }

    /** 便捷：先面向目标中心，再判视线。用于挖前一次性摆好朝向 + 门控。 */
    public static boolean lookAndCheck(ServerPlayerEntity player, BlockPos target) {
        CompanionInputController.lookAt(player, target.toCenterPos());
        return hasLineOfSight(player, target);
    }
}