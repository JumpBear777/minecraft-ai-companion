package dev.jumpbear.minecraft_ai_companion.task;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.List;
import java.util.function.Predicate;

/**
 * Reusable held-item adapter: bring a wanted item from the companion's inventory into the main hand
 * using the vanilla selection path, exactly as a real player would (change the selected hotbar slot,
 * or swap an inventory item into the hotbar first). Reusable by any task that must bring a specific item into the main hand
 * (future BuildStructure/equipment behaviors).
 *
 * <p>Vanilla-first: this never fabricates or force-sets the main-hand stack. It uses
 * {@code PlayerInventory.setSelectedSlot} (hotbar item already in a hotbar slot) or
 * {@code swapSlotWithHotbar} (item in the lower inventory), so no item is duplicated or lost — the
 * same guarantees the vanilla number-key / swap actions give. The companion is a real
 * {@link ServerPlayerEntity}, so its inventory is already server-authoritative; only the boundary a
 * real client would normally handle — telling nearby viewers the held item changed — is simulated.
 */
public final class CompanionHotbar {
    private CompanionHotbar() {
    }

    /** Number of hotbar slots (0..8 of the main inventory). */
    private static final int HOTBAR_SIZE = 9;

    /** Select any placeable block ({@link BlockItem}) from the inventory. */
    public static boolean selectBlock(ServerPlayerEntity companion) {
        return selectItem(companion, stack -> stack.getItem() instanceof BlockItem);
    }

    /**
     * Select a block usable as scaffolding to pillar up on: it must be a {@link BlockItem} whose block
     * is a full cube, so the companion can actually stand on it after placing. This excludes saplings,
     * torches, slabs, carpets, crops and other non-full blocks — a sapling in hand was being used as a
     * pillar block and then could not be stood on. {@code isFullCube} is the same vanilla shape signal
     * used to decide whether a block is a solid step, so this works for modded full blocks too.
     */
    public static boolean selectScaffoldBlock(ServerPlayerEntity companion) {
        return selectItem(companion, CompanionHotbar::isScaffoldBlock);
    }

    private static boolean isScaffoldBlock(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        // Never burn harvest, valuables, or collapsing blocks as disposable scaffold: logs would also
        // distort the log count the harvest task watches; falling blocks drop out from under the
        // companion; block-entity blocks (chests, furnaces, hoppers, ...) carry contents/state.
        if (stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.SAPLINGS)) {
            return false;
        }
        Block block = blockItem.getBlock();
        if (block instanceof FallingBlock || block instanceof BlockEntityProvider) {
            return false;
        }
        // Evaluate the block's default shape against an empty view: a position-independent "is this a
        // full cube" test, the same predicate vanilla uses for solid-block/step decisions.
        return Block.isShapeFullCube(
                block.getDefaultState().getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN));
    }

    /** Select a specific item type from the inventory. */
    public static boolean selectItem(ServerPlayerEntity companion, Item item) {
        return selectItem(companion, stack -> stack.isOf(item));
    }

    /**
     * Bring the inventory tool that mines {@code state} fastest into the main hand, exactly as a real
     * player picking the right tool for the job. "Fastest" is the vanilla {@link
     * ItemStack#getMiningSpeedMultiplier(BlockState)} signal, so this needs no hard-coded
     * block→tool table and works for modded tools and blocks: an axe wins on logs, a shovel on dirt,
     * shears/sword on leaves. Ties are broken toward a tool that {@link ItemStack#isSuitableFor} the
     * block (so it drops properly rather than just breaking fast).
     *
     * <p>Returns false (and changes nothing) when no inventory item beats bare hands — the caller
     * keeps whatever it is already holding rather than pointlessly switching to an empty hand. This is
     * deliberate: clearing a leaf with an axe already in hand is fine, so we do not force an empty-hand
     * swap just because no faster tool exists.
     *
     * @return true if a strictly-faster-than-hand tool is now in the main hand; false otherwise.
     */
    public static boolean selectBestToolFor(ServerPlayerEntity companion, BlockState state) {
        PlayerInventory inventory = companion.getInventory();

        int bestSlot = -1;
        float bestSpeed = 1.0F; // bare-hand baseline; only switch for something strictly faster
        boolean bestSuitable = false;
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getMiningSpeedMultiplier(state);
            boolean suitable = stack.isSuitableFor(state);
            // Strictly faster wins; on a tie prefer a tool that actually drops the block.
            if (speed > bestSpeed || (speed == bestSpeed && suitable && !bestSuitable && bestSlot != -1)) {
                bestSlot = slot;
                bestSpeed = speed;
                bestSuitable = suitable;
            }
        }

        if (bestSlot == -1) {
            return false;
        }

        // Already holding it? Done. Otherwise bring it in via the same vanilla select/swap path.
        if (inventory.getSelectedSlot() == bestSlot) {
            return true;
        }
        if (bestSlot < HOTBAR_SIZE) {
            inventory.setSelectedSlot(bestSlot);
        } else {
            // swapSlotWithHotbar puts the item into the currently selected hotbar slot.
            inventory.swapSlotWithHotbar(bestSlot);
        }
        onHeldItemChanged(companion);
        return true;
    }

    /**
     * Bring the first inventory stack matching {@code match} into the main hand.
     *
     * @return true if the main hand now holds a matching item (including if it already did); false if
     *         no matching item exists anywhere in the inventory.
     */
    public static boolean selectItem(ServerPlayerEntity companion, Predicate<ItemStack> match) {
        PlayerInventory inventory = companion.getInventory();

        // Already holding a match: nothing to do.
        if (matches(inventory.getSelectedStack(), match)) {
            return true;
        }

        // Prefer an item already in the hotbar: just change the selected slot.
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            if (matches(inventory.getStack(slot), match)) {
                inventory.setSelectedSlot(slot);
                onHeldItemChanged(companion);
                return true;
            }
        }

        // Otherwise find it in the lower inventory and swap it into the hotbar (vanilla swap action).
        for (int slot = HOTBAR_SIZE; slot < PlayerInventory.MAIN_SIZE; slot++) {
            if (matches(inventory.getStack(slot), match)) {
                inventory.swapSlotWithHotbar(slot);
                onHeldItemChanged(companion);
                return true;
            }
        }

        return false;
    }

    private static boolean matches(ItemStack stack, Predicate<ItemStack> match) {
        return !stack.isEmpty() && match.test(stack);
    }

    /**
     * Single convergence point for everything that must happen after the held item changes. Today it
     * only tells nearby viewers the new main-hand item (the fake client has no client to send this),
     * which is the boundary a real client would normally cover. This is the seam a future
     * "view the companion's inventory" feature extends — inventory/screen synchronization would be
     * added here so every caller (select, refuel, build) benefits without changing them.
     */
    public static void onHeldItemChanged(ServerPlayerEntity companion) {
        if (!(companion.getEntityWorld() instanceof ServerWorld world)) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> equipment = List.of(
                Pair.of(EquipmentSlot.MAINHAND, companion.getEquippedStack(EquipmentSlot.MAINHAND).copy()));
        EntityEquipmentUpdateS2CPacket packet = new EntityEquipmentUpdateS2CPacket(companion.getId(), equipment);
        for (ServerPlayerEntity viewer : world.getPlayers()) {
            if (viewer.squaredDistanceTo(companion) <= 4096.0D) {
                viewer.networkHandler.sendPacket(packet);
            }
        }
    }
}
