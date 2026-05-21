package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.net.URI;
import java.util.UUID;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.web.WebDashboard;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.publisher.WebSocketLiveEventPublisher;

/**
 * Runs an ensemble in publisher mode, streaming its live events to a {@link
 * net.agentensemble.web.hub.LiveEventHub} on the URL supplied as the first argument.
 *
 * <p>Run via Gradle:
 * <pre>
 * # First terminal:
 * ./gradlew :agentensemble-examples:runDistributedDashboardHub
 *
 * # Second terminal (svc-a):
 * ./gradlew :agentensemble-examples:runDistributedDashboardPublisher \\
 *     --args="ws://localhost:7400/ingress svc-a instance-1 \"Research AI trends\""
 *
 * # Third terminal (svc-b):
 * ./gradlew :agentensemble-examples:runDistributedDashboardPublisher \\
 *     --args="ws://localhost:7400/ingress svc-b instance-2 \"Write a summary\""
 * </pre>
 *
 * <p>Browser: open <code>http://localhost:7400/hub?server=ws://localhost:7400/ws</code>.
 */
public final class DistributedDashboardPublisherMain {

    private DistributedDashboardPublisherMain() {}

    public static void main(String[] args) {
        String hubUrl = args.length > 0 ? args[0] : "ws://localhost:7400/ingress";
        String serviceName = args.length > 1 ? args[1] : "svc-default";
        String instanceId = args.length > 2 ? args[2] : "instance-" + UUID.randomUUID();
        String topic = args.length > 3 ? args[3] : "Research the latest AI trends";

        ProducerInfo info = ProducerInfo.of(serviceName + "-" + instanceId, serviceName, instanceId, hostname());

        WebSocketLiveEventPublisher publisher = WebSocketLiveEventPublisher.connect(URI.create(hubUrl), info);

        // OPENAI_API_KEY must be set in the environment for this example to actually run.
        var model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"))
                .build();

        WebDashboard dashboard = WebDashboard.builder()
                .port(0) // Required by the builder but ignored in publisher mode.
                .publisher(publisher)
                .build();

        System.out.printf(
                "Publisher %s connecting to %s (service=%s instance=%s)%n",
                info.producerId(), hubUrl, serviceName, instanceId);

        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .webDashboard(dashboard)
                .task(Task.of(topic))
                .build();
        try {
            ensemble.run();
        } finally {
            publisher.stop();
        }

        System.out.println("Publisher finished.");
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown";
        }
    }
}
