package dev.jumpbear.minecraft_ai_companion.task;

/**
 * The lifecycle result of a {@link CompanionTask}.
 *
 * <p>{@link #RUNNING} is the only "keep going" value returned from {@code tick}. The three terminal
 * values tell the {@link CompanionTaskManager} to stop and release control back to the Life System.
 */
public enum TaskStatus {
    /** The task is still executing and wants another tick. */
    RUNNING,
    /** The objective was completed cleanly. */
    SUCCESS,
    /** The task could not complete its objective. */
    FAILURE,
    /** The task was cancelled externally (debug command, reassignment, companion removed). */
    CANCELLED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
