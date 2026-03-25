
# Long-Running Ensemble Example

This example shows how to configure and start an ensemble in long-running mode with
shared tasks and tools.

## Basic Long-Running Ensemble

```java
import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleLifecycleState;

import java.time.Duration;

public class LongRunningExample {

    public static void main(String[] args) {
        // Configure your LLM provider -- for example:
        // ChatModel model = OpenAiChatModel.builder().apiKey(...).build();
        ChatModel model = /* your LLM provider */ null;

        // Define a task the kitchen handles internally
        Task managementTask = Task.of("Manage kitchen operations");

        // Define a task to share with the network
        Task prepareMeal = Task.builder()
                .description("Prepare a meal as specified")
                .expectedOutput("Confirmation with preparation details and timing")
                .build();

        // Build and start the ensemble
        Ensemble kitchen = Ensemble.builder()
                .chatLanguageModel(model)
                .task(managementTask)
                .shareTask("prepare-meal", prepareMeal)
                .drainTimeout(Duration.ofMinutes(2))
                .build();

        kitchen.start(7329);
        System.out.println("Kitchen ensemble running on port 7329");
        System.out.println("State: " + kitchen.getLifecycleState());
        // Output: State: READY

        // The ensemble runs until stop() is called or the JVM shuts down.
        // A shutdown hook is registered automatically.
    }
}
```

## Checking Lifecycle State

```java
// Build and start (configure dashboard separately in production code)
Ensemble ensemble = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Manage operations"))
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
Ensemble kitchen = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Manage kitchen operations"))

        // Full task delegation -- other ensembles hand off work
        .shareTask("prepare-meal", Task.builder()
                .description("Prepare a meal as specified")
                .expectedOutput("Confirmation with prep time and details")
                .build())

        // Lightweight tool sharing -- other agents call directly
        .shareTool("check-inventory", inventoryTool)
        .shareTool("dietary-check", allergyCheckTool)

        .build();
```

## See Also

- [Long-Running Ensembles Guide](../guides/long-running-ensembles.md)
- [Ensemble Configuration Reference](../reference/ensemble-configuration.md)
