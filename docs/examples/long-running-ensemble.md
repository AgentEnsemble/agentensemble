
# Long-Running Ensemble Example

This example shows how to configure and start an ensemble in long-running mode with
shared tasks and tools.

## Basic Long-Running Ensemble

Long-running mode requires a `WebDashboard` configured with the desired port.
The `webDashboard()` builder method starts the server; `start()` transitions the lifecycle
to READY and registers the JVM shutdown hook.

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.web.WebDashboard;

import java.time.Duration;

public class LongRunningExample {

    public static void main(String[] args) {
        // Configure your LLM provider -- for example:
        // ChatModel model = OpenAiChatModel.builder().apiKey(...).build();

        // Create the WebDashboard bound to the desired port
        WebDashboard dashboard = WebDashboard.builder().port(7329).build();

        // Define tasks to share with the network
        Task prepareMeal = Task.builder()
                .description("Prepare a meal as specified")
                .expectedOutput("Confirmation with preparation details and timing")
                .build();

        // Build the ensemble with the dashboard wired in
        Ensemble kitchen = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("Manage kitchen operations"))
                .shareTask("prepare-meal", prepareMeal)
                .webDashboard(dashboard)  // required for long-running mode; also starts server
                .drainTimeout(Duration.ofMinutes(2))
                .build();

        // Transition to READY; port is advisory for error messages / logs
        kitchen.start(7329);
        System.out.println("State: " + kitchen.getLifecycleState());
        // Output: State: READY

        // The ensemble runs until stop() is called or the JVM shuts down.
        // A shutdown hook is registered automatically.
    }
}
```

## Checking Lifecycle State

```java
// Create dashboard and build ensemble (see above for full setup)
WebDashboard dashboard = WebDashboard.builder().port(7329).build();
Ensemble ensemble = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Manage operations"))
        .webDashboard(dashboard)
        .build();

ensemble.start(7329);

// Check the current state
EnsembleLifecycleState state = ensemble.getLifecycleState();
System.out.println(state); // READY

// Graceful shutdown
ensemble.stop();
System.out.println(ensemble.getLifecycleState()); // STOPPED
```

## Shared Capabilities

```java
// Share both tasks and tools
WebDashboard dashboard = WebDashboard.builder().port(7329).build();

Ensemble kitchen = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Manage kitchen operations"))

        // Full task delegation -- other ensembles hand off work (EN-004)
        .shareTask("prepare-meal", Task.builder()
                .description("Prepare a meal as specified")
                .expectedOutput("Confirmation with prep time and details")
                .build())

        // Lightweight tool sharing -- other agents call directly (EN-005)
        .shareTool("check-inventory", inventoryTool)
        .shareTool("dietary-check", allergyCheckTool)

        .webDashboard(dashboard)
        .build();
```

## See Also

- [Long-Running Ensembles Guide](../guides/long-running-ensembles.md)
- [Ensemble Configuration Reference](../reference/ensemble-configuration.md)
