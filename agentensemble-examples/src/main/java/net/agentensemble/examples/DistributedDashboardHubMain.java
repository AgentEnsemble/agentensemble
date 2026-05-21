package net.agentensemble.examples;

import java.util.concurrent.CountDownLatch;
import net.agentensemble.web.hub.LiveEventHub;

/**
 * Boots a {@link LiveEventHub} on port 7400 and blocks until the JVM is interrupted.
 *
 * <p>Pair with {@link DistributedDashboardPublisherMain} (which can be launched multiple times
 * with different identities) to demonstrate the distributed live dashboard end-to-end. Browser
 * connects at {@code http://localhost:7400/hub?server=ws://localhost:7400/ws}.
 *
 * <p>Run via Gradle:
 * <pre>./gradlew :agentensemble-examples:runDistributedDashboardHub</pre>
 */
public final class DistributedDashboardHubMain {

    private DistributedDashboardHubMain() {}

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7400;
        LiveEventHub hub = LiveEventHub.builder()
                .port(port)
                .host("localhost")
                .maxRetainedProducers(50)
                .maxRetainedRunsPerProducer(10)
                .build();
        hub.start();

        System.out.printf(
                "%n  LiveEventHub started on port %d%n"
                        + "    Browser : ws://localhost:%d/ws%n"
                        + "    Ingress : ws://localhost:%d/ingress%n"
                        + "    REST    : http://localhost:%d/api/hub/producers%n%n"
                        + "  Run DistributedDashboardPublisherMain in another terminal with the same port.%n"
                        + "  Press Ctrl-C to stop.%n%n",
                port, port, port, port);

        CountDownLatch forever = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            hub.stop();
                            forever.countDown();
                        },
                        "hub-shutdown"));
        forever.await();
    }
}
