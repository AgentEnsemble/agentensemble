package net.agentensemble.ensemble;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal scheduler for proactive/scheduled tasks.
 *
 * <p>Each scheduled task fires at its configured interval. Before execution, the scheduler
 * checks the ensemble's lifecycle state: if the state is not {@link EnsembleLifecycleState#READY},
 * the firing is silently skipped.
 */
public class EnsembleScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EnsembleScheduler.class);

    private final ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();
    private final Supplier<EnsembleLifecycleState> stateProvider;

    public EnsembleScheduler(Supplier<EnsembleLifecycleState> stateProvider) {
        this.stateProvider = Objects.requireNonNull(stateProvider);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ensemble-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a task to fire according to its schedule.
     *
     * @param scheduledTask the task definition
     * @param action        the action to execute on each firing
     */
    public void schedule(ScheduledTask scheduledTask, Runnable action) {
        Objects.requireNonNull(scheduledTask);
        Objects.requireNonNull(action);

        Runnable guarded = () -> {
            if (stateProvider.get() != EnsembleLifecycleState.READY) {
                log.debug("Skipping scheduled task '{}' (state is not READY)", scheduledTask.name());
                return;
            }
            try {
                log.info("Firing scheduled task: {}", scheduledTask.name());
                action.run();
            } catch (Exception e) {
                log.warn("Scheduled task '{}' failed: {}", scheduledTask.name(), e.getMessage(), e);
            }
        };

        Schedule schedule = scheduledTask.schedule();
        if (schedule instanceof Schedule.IntervalSchedule interval) {
            long millis = interval.interval().toMillis();
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(guarded, millis, millis, TimeUnit.MILLISECONDS);
            futures.add(future);
        } else if (schedule instanceof Schedule.CronSchedule cron) {
            throw new UnsupportedOperationException(
                    "Cron scheduling is not yet supported; task '" + scheduledTask.name()
                            + "' with expression '" + cron.expression() + "'");
        }
    }

    /** Stop all scheduled tasks. Does not interrupt running tasks. */
    public void stop() {
        for (ScheduledFuture<?> future : futures) {
            future.cancel(false);
        }
        executor.shutdown();
    }

    @Override
    public void close() {
        stop();
    }

    /** Package-private for testing. */
    boolean isShutdown() {
        return executor.isShutdown();
    }
}
