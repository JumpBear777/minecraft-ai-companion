package dev.jumpbear.minecraft_ai_companion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;

/**
 * Keep the companion from drowning: whenever it is submerged (head underwater), surface it and steer
 * it to the nearest dry ground, then release once it is safely back on land. Without this the fake
 * player sinks and never comes up - vanilla only surfaces a player while its jump input is held (see
 * {@code LivingEntity.tickMovement} -> {@code swimUpward}), and the client-less companion never sets
 * that input on its own, so a task that walks it into a river (chasing a floated sapling/log) left it
 * stuck underwater.
 *
 * <p><b>Vanilla-first, self-rescue only (by design):</b> this does not add buoyancy physics or change
 * pathfinding. It only sets the same jump input a real swimming player holds - vanilla's own
 * {@code swimUpward} does the lifting - and points the body at the closest shore so the existing
 * server-travel path carries it out. It deliberately does <em>not</em> stop the companion from
 * entering water in the first place; it just guarantees it always gets back out.
 *
 * <p><b>Trigger is submerged, not merely touching water:</b> an earlier version fired on
 * {@code isTouchingWater()}, which is true the instant a foot touches water. That made self-rescue
 * seize the body every tick while a task was working at the water's edge - yanking it out of mining
 * reach mid-break (inducing the mining "too far" failure) and fighting approach/collect near rivers.
 * Now it fires only on {@code isSubmergedInWater()} (head underwater, a real drowning risk), so
 * shallow water and water's-edge work stay under task control; the moment the companion actually
 * starts to drown, surfacing takes priority again.
 *
 * <p>Registered last in {@link MinecraftAiCompanionMod}, so on any tick the companion is submerged
 * this ticker's input wins over whatever a task or the Life System set - surfacing takes priority
 * over every other behaviour. In shallow water and on dry land it does nothing and leaves control
 * with the task / Life System.
 */
public final class CompanionWaterSafety {
    /** How far around the companion to look for a dry standable spot to head toward. */
    private static final int SHORE_SEARCH_RADIUS = 8;
    /** Vertical band around the companion's feet to consider when looking for shore. */
    private static final int SHORE_SEARCH_UP = 1;
    private static final int SHORE_SEARCH_DOWN = 2;

    private CompanionWaterSafety() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Optional<ServerPlayerEntity> companion = FakeCompanionSpawner.find(server);
            companion.ifPresent(CompanionWaterSafety::tick);
        });
    }

    private static void tick(ServerPlayerEntity companion) {
        // Self-rescue only when genuinely submerged (head underwater): ankle-deep / water-edge contact
        // is left to the task in control, so a waterside tree or a floated drop stays reachable.
        if (companion.isRemoved() || !companion.isAlive() || !companion.isSubmergedInWater()) {
            return; // not submerged - leave all control to tasks / the Life System
        }

        // Point at the nearest dry ground so the forward travel below carries the body toward it.
        // If no shore is in range (open water), skip steering and just surface in place.
        Vec3d shore = findNearestShore(companion);
        if (shore != null) {
            CompanionInputController.lookAt(companion,
                    new Vec3d(shore.x, companion.getEyeY(), shore.z));
        }

        // Hold the jump input: in water vanilla reads this as "swim up" (swimUpward), and it also
        // drives forward travel toward the shore we are now facing. This both surfaces the companion
        // and climbs it out over the 1-block bank.
        CompanionInputController.applyServerTravelForward(companion, true);
    }

    /**
     * Find the nearest position the companion can stand on out of the water: a solid block with an
     * open, water-free column above it, near the companion's own level. Returns the centre of that
     * block's top surface, or null if none is within {@link #SHORE_SEARCH_RADIUS} (open water - the
     * caller then just surfaces in place rather than swimming nowhere).
     */
    private static Vec3d findNearestShore(ServerPlayerEntity companion) {
        World world = companion.getEntityWorld();
        BlockPos origin = companion.getBlockPos();
        BlockPos.Mutable cursor = new BlockPos.Mutable();

        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (int dx = -SHORE_SEARCH_RADIUS; dx <= SHORE_SEARCH_RADIUS; dx++) {
            for (int dz = -SHORE_SEARCH_RADIUS; dz <= SHORE_SEARCH_RADIUS; dz++) {
                for (int dy = SHORE_SEARCH_UP; dy >= -SHORE_SEARCH_DOWN; dy--) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!isDryStandable(world, cursor)) {
                        continue;
                    }
                    double sq = origin.getSquaredDistance(cursor);
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = cursor.toImmutable();
                    }
                    break; // topmost standable spot in this column is enough
                }
            }
        }
        return best == null ? null : Vec3d.ofCenter(best).add(0.0D, 0.5D, 0.0D);
    }

    /**
     * True if {@code pos} is dry ground the companion can stand on: a solid block underneath, and the
     * two blocks of body space (pos and pos.up()) free of fluid and passable. This is what tells
     * "bank" apart from "still in the river".
     */
    private static boolean isDryStandable(World world, BlockPos pos) {
        if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) {
            return false;
        }
        BlockPos head = pos.up();
        return world.getFluidState(pos).isEmpty()
                && world.getFluidState(head).isEmpty()
                && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }
}
