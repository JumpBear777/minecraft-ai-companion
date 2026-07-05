package dev.jumpbear.minecraft_ai_companion.task;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * A single long-running, autonomous objective the companion executes to completion.
 *
 * <p>The contract is intentionally shaped after vanilla {@code net.minecraft.entity.ai.goal.Goal}
 * ({@code canStart}/{@code start}/{@code tick}/{@code stop}). That gives us a familiar,
 * planner-friendly lifecycle without importing the whole goal-selector machinery, which does not
 * fit a {@link ServerPlayerEntity} body.
 *
 * <p>Design rules:
 * <ul>
 *   <li>A task owns its execution: it decides when it is done by returning a terminal
 *       {@link TaskStatus} from {@link #tick}.</li>
 *   <li>A task must not scatter movement/mining/attack logic of its own. It composes existing,
 *       already-validated vanilla adapters (e.g. {@link CompanionNavigator}). This keeps every task
 *       reusable across FollowPlayer/ChopTree/AttackTarget/Explore and future modded skills.</li>
 *   <li>A future planner only needs to {@code assign} a task. It never inspects task internals.</li>
 * </ul>
 */
public interface CompanionTask {
    /**
     * Called once when the task becomes the companion's current task. Use for one-time setup.
     */
    void start(ServerPlayerEntity companion);

    /**
     * Called every server tick while the task is current.
     *
     * @return {@link TaskStatus#RUNNING} to keep executing, or a terminal status to finish.
     */
    TaskStatus tick(ServerPlayerEntity companion);

    /**
     * Called exactly once after {@link #tick} returns a terminal status, or when the task is
     * cancelled/replaced. Must release any control the task took (movement input, navigation) so the
     * Life System can resume a clean companion body.
     *
     * @param finalStatus the terminal status the task ended with
     */
    void stop(ServerPlayerEntity companion, TaskStatus finalStatus);

    /**
     * A short human-readable description for debug output (e.g. {@code /aicompanion task current}).
     */
    String describe();
}
