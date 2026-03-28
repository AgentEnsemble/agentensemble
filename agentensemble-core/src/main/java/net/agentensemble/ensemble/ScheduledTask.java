package net.agentensemble.ensemble;

import java.util.Objects;
import net.agentensemble.Task;

/**
 * A task that runs on a schedule and optionally broadcasts its result to a topic.
 *
 * <p>Used with {@link net.agentensemble.Ensemble.EnsembleBuilder#scheduledTask(ScheduledTask)}
 * to register proactive tasks on a long-running ensemble.
 *
 * @param name        human-readable name for this scheduled task; must not be null
 * @param task        the task to execute; must not be null
 * @param schedule    when to fire; must not be null
 * @param broadcastTo optional topic name to broadcast results to; may be null
 *
 * @see Schedule
 */
public record ScheduledTask(String name, Task task, Schedule schedule, String broadcastTo) {

    public ScheduledTask {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(schedule, "schedule must not be null");
        // broadcastTo is optional (nullable)
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private Task task;
        private Schedule schedule;
        private String broadcastTo;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder task(Task task) {
            this.task = task;
            return this;
        }

        public Builder schedule(Schedule schedule) {
            this.schedule = schedule;
            return this;
        }

        public Builder broadcastTo(String broadcastTo) {
            this.broadcastTo = broadcastTo;
            return this;
        }

        public ScheduledTask build() {
            return new ScheduledTask(name, task, schedule, broadcastTo);
        }
    }
}
