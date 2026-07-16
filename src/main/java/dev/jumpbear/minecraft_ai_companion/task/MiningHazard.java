package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/**
 * The <b>single shared "is it safe to open/stand at this block" judgment</b> for mining. Both the safe
 * staircase descent ({@link SafeDescentTask}) and — stage 2 — the fishbone/branch miner drive their
 * hazard decisions through this one class, so the "did I just breach a cave / lava / water pocket"
 * rule lives in <em>one</em> place and the two tasks can never drift apart on it. This is the mining
 * analogue of {@link TreeChopStep}'s single sight model.
 *
 * <h2>Vanilla-first classification, no hard-coded block list</h2>
 * Every decision reads vanilla state directly:
 * <ul>
 *   <li><b>Lava / water</b>: {@code world.getFluidState(pos).isIn(FluidTags.LAVA/WATER)} — true for both
 *       source and flowing fluid, so a breached pocket is caught however it presents. This is checked on
 *       the faces a dig would newly expose, mirroring Numen's flood-direction gate (UP + the four
 *       horizontals) so "safe to route" and "safe to mine" never disagree.</li>
 *   <li><b>Cave</b>: an air block <em>underground</em> where solid rock was expected. Opening next to it
 *       (or standing on it) means walking into a pre-existing cavity — exactly what "avoid entering
 *       caves" forbids. Air <em>above</em> the column's surface is open sky, not a cave (see
 *       {@link #isOpenSky}), so a descent can start on flat ground without its own head-space air being
 *       mistaken for a cavity.</li>
 * </ul>
 *
 * <h2>Geometry and policy belong to the caller, not here</h2>
 * This class never decides <em>which</em> neighbours count — that depends on the dig shape and approach
 * direction (a downward staircase must not treat the tunnel it just came down as a cave). The caller
 * assembles the exact cells to probe and picks which check applies to each; this only classifies the
 * single cell it is handed. Same division of labour as {@link TreeChopStep} (judgment) vs
 * {@link FellNaturalTreeTask} (shape/orchestration).
 */
public final class MiningHazard {
    private MiningHazard() {
    }

    /** What a single probed block is, from a mining-safety point of view. */
    public enum Hazard {
        /** Solid, mineable, nothing dangerous exposed. */
        SAFE,
        /** A pre-existing air pocket where solid rock was expected — a cave/cavity. */
        CAVE,
        /** Lava (source or flowing). */
        LAVA,
        /** Water (source or flowing). */
        WATER
    }

    /** A hazard found at a specific position; {@link #SAFE} carries a null position. */
    public record Finding(Hazard hazard, BlockPos pos) {
        public static final Finding SAFE = new Finding(Hazard.SAFE, null);

        public boolean isSafe() {
            return hazard == Hazard.SAFE;
        }
    }

    /**
     * Full classification: fluid first (so a flowing-fluid cell, whose block state is not itself air, is
     * reported as the fluid rather than miscounted as a cave), then any air as a cave. Use this where
     * <em>any</em> air is a cavity to avoid (e.g. inside solid rock); use {@link #fluidHazard} +
     * {@link #isOpenSky} where surface sky must be told apart from an underground cavity.
     */
    public static Hazard classify(World world, BlockPos pos) {
        Hazard fluid = fluidHazard(world, pos);
        if (fluid != Hazard.SAFE) {
            return fluid;
        }
        return world.getBlockState(pos).isAir() ? Hazard.CAVE : Hazard.SAFE;
    }

    /** Fluid-only classification: {@link Hazard#LAVA}/{@link Hazard#WATER}, or {@link Hazard#SAFE}. */
    public static Hazard fluidHazard(World world, BlockPos pos) {
        if (world.getFluidState(pos).isIn(FluidTags.LAVA)) {
            return Hazard.LAVA;
        }
        if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
            return Hazard.WATER;
        }
        return Hazard.SAFE;
    }

    /**
     * True if {@code pos} is at or above its column's surface — open sky, where air is expected and must
     * <em>not</em> be treated as a cave. Below the surface, air means a pre-existing cavity. Uses the
     * vanilla {@code WORLD_SURFACE} heightmap (highest non-air block + 1), so a descent starting on flat
     * ground does not mistake the normal air in front of / beside the companion's head for a cavern.
     */
    public static boolean isOpenSky(World world, BlockPos pos) {
        return pos.getY() >= world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
    }
}
