# Durable Transport

The default `Transport.websocket()` uses in-process queues. For production deployments
that require durability and horizontal scaling, use the `agentensemble-transport-kafka`
module or implement the transport SPIs against your infrastructure.

## Transport Modes

| Mode | Factory | Backing | Use case |
|------|---------|---------|----------|
| Simple | `Transport.websocket(name)` | In-process queues + maps | Development, testing |
| Simple + delivery | `Transport.simple(name, registry)` | In-process queues + `DeliveryRegistry` | Multi-delivery local dev |
| Kafka | Custom wiring (see below) | Kafka topics | Production |

## Kafka Transport Setup

Add the `agentensemble-transport-kafka` module to your project. All Kafka components
share a single `KafkaTransportConfig`:

```java
KafkaTransportConfig config = KafkaTransportConfig.builder()
    .bootstrapServers("kafka:9092")
    .consumerGroupId("kitchen-ensemble")
    .topicPrefix("agentensemble.")  // default
    .build();
```

### KafkaRequestQueue

Kafka-backed `RequestQueue`. Produces work requests to Kafka topics and consumes them
with manual offset commits.

```java
KafkaRequestQueue queue = KafkaRequestQueue.create(config);

// Enqueue a work request
queue.enqueue("kitchen", workRequest);

// Dequeue (blocking with timeout)
WorkRequest received = queue.dequeue("kitchen", Duration.ofSeconds(30));

// Acknowledge after processing (commits offsets)
queue.acknowledge("kitchen", received.requestId());
```

Topic names follow the pattern `<topicPrefix><queueName>` (e.g., `agentensemble.kitchen`).

### KafkaTopicDelivery

Kafka-backed `DeliveryHandler` for the `TOPIC` delivery method. Publishes work responses
to the Kafka topic specified in the `DeliverySpec.address()`.

```java
KafkaTopicDelivery topicDelivery = new KafkaTopicDelivery(config);
```

### KafkaTopicIngress

Kafka-backed `IngressSource`. Subscribes to a Kafka topic on a virtual thread and pushes
deserialized `WorkRequest` objects to a consumer sink.

```java
KafkaTopicIngress ingress = new KafkaTopicIngress(config, "work-requests");
ingress.start(transport::send);
```

## DeliveryRegistry Wiring

Register Kafka delivery alongside the default handlers:

```java
DeliveryRegistry registry = DeliveryRegistry.withDefaults(ResultStore.inMemory());
registry.register(new KafkaTopicDelivery(config));
registry.register(new WebhookDeliveryHandler());
registry.register(new QueueDeliveryHandler((queueName, response) -> {
    kafkaQueue.enqueue(queueName, toWorkRequest(response));
}));

Transport transport = Transport.simple("kitchen", registry);
```

`DeliveryRegistry.withDefaults()` pre-registers `StoreDeliveryHandler` and
`NoneDeliveryHandler`. Additional handlers are registered one per `DeliveryMethod`.

## IngressCoordinator Wiring

Accept work from multiple sources simultaneously:

```java
IngressCoordinator ingress = IngressCoordinator.builder()
    .add(new HttpIngress(8080))
    .add(new QueueIngress(kafkaQueue, "kitchen"))
    .add(new KafkaTopicIngress(config, "kitchen-requests"))
    .build();

ingress.startAll(transport::send);
```

All sources push into the same sink. Call `ingress.stopAll()` (or use try-with-resources)
to shut down all sources.

## Idempotency & Caching

With durable transport, duplicate delivery is possible. Use `IdempotencyGuard` and
`ResultCache` to handle this:

```java
IdempotencyGuard guard = IdempotencyGuard.inMemory();
ResultCache cache = ResultCache.inMemory();
RequestHandler handler = new CachingRequestHandler(baseHandler, guard, cache);
```

The `IdempotencyGuard` tracks request IDs to prevent re-execution. The `ResultCache`
caches results by semantic cache key so that identical requests share results.

Both provide `inMemory()` factories for development. Implement the interfaces against
Redis or a database for production.

## Full Production Example

```java
// Kafka config
KafkaTransportConfig kafkaConfig = KafkaTransportConfig.builder()
    .bootstrapServers("kafka:9092")
    .consumerGroupId("kitchen-ensemble")
    .build();

// Request queue (durable)
KafkaRequestQueue queue = KafkaRequestQueue.create(kafkaConfig);

// Delivery registry
DeliveryRegistry deliveryRegistry = DeliveryRegistry.withDefaults(ResultStore.inMemory());
deliveryRegistry.register(new KafkaTopicDelivery(kafkaConfig));

// Transport with pluggable delivery
Transport transport = Transport.simple("kitchen", deliveryRegistry);

// Multi-source ingress
IngressCoordinator ingress = IngressCoordinator.builder()
    .add(new HttpIngress(8080))
    .add(new QueueIngress(queue, "kitchen"))
    .add(new KafkaTopicIngress(kafkaConfig, "kitchen-requests"))
    .build();

ingress.startAll(transport::send);

// Idempotency
IdempotencyGuard guard = IdempotencyGuard.inMemory();
ResultCache cache = ResultCache.inMemory();

// Ensemble with scheduled tasks
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .scheduledTask(ScheduledTask.builder()
        .name("inventory-report")
        .task(Task.of("Check inventory levels"))
        .schedule(Schedule.every(Duration.ofHours(1)))
        .broadcastTo("hotel.inventory")
        .build())
    .broadcastHandler((topic, result) -> {
        log.info("Broadcast to {}: {}", topic, result);
    })
    .webDashboard(WebDashboard.builder().port(7329).build())
    .build();

kitchen.start(7329);
```

## Related

- [Cross-Ensemble Delegation](cross-ensemble-delegation.md)
- [Scheduled Tasks](scheduled-tasks.md)
- [Long-Running Ensembles](long-running-ensembles.md)
