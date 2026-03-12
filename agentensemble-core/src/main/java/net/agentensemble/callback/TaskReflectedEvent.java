package net.agentensemble.callback;

import net.agentensemble.reflection.TaskReflection;

/**
 * Event fired after a task reflection completes successfully.
 *
 * <p>Delivered to all registered {@link EnsembleListener}s via
 * {@link EnsembleListener#onTaskReflected(TaskReflectedEvent)} after a task's
 * reflection analysis finishes and the result is stored in the {@code ReflectionStore}.
 *
 * @param taskDescription   the original compile-time task description; never null
 * @param reflection        the {@link TaskReflection} that was produced and stored; never null
 * @param isFirstReflection true if no prior reflection existed for this task — this is
 *                          the first time the task has been reflected on
 */
public record TaskReflectedEvent(String taskDescription, TaskReflection reflection, boolean isFirstReflection) {}
