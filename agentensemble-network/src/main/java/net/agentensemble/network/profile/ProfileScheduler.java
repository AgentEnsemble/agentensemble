package net.agentensemble.network.profile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schedules operational profiles to be applied at regular intervals.
 */
public class ProfileScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProfileScheduler.class);

    private final ProfileApplier applier;
    private final ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> futures = new ArrayList<>();

    public ProfileScheduler(ProfileApplier applier) {
        this.applier = Objects.requireNonNull(applier);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "profile-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Schedule a profile to be applied at a fixed interval.
     *
     * @param profile      the profile to apply
     * @param initialDelay delay before first application
     * @param interval     interval between applications
     */
    public void schedule(NetworkProfile profile, Duration initialDelay, Duration interval) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(initialDelay);
        Objects.requireNonNull(interval);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                () -> {
                    try {
                        applier.apply(profile);
                    } catch (Exception e) {
                        log.warn("Failed to apply scheduled profile '{}': {}", profile.name(), e.getMessage());
                    }
                },
                initialDelay.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
        futures.add(future);
        log.debug("Scheduled profile '{}' with interval {}", profile.name(), interval);
    }

    /** Schedule a profile to be applied once after a delay. */
    public void scheduleOnce(NetworkProfile profile, Duration delay) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(delay);
        ScheduledFuture<?> future = executor.schedule(
                () -> {
                    try {
                        applier.apply(profile);
                    } catch (Exception e) {
                        log.warn("Failed to apply scheduled profile '{}': {}", profile.name(), e.getMessage());
                    }
                },
                delay.toMillis(),
                TimeUnit.MILLISECONDS);
        futures.add(future);
    }

    @Override
    public void close() {
        futures.forEach(f -> f.cancel(false));
        executor.shutdown();
    }
}
