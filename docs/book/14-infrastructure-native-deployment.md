# Chapter 14: Infrastructure-Native Deployment

## Let Kubernetes Do Its Job

A common mistake in framework design is rebuilding infrastructure capabilities that already
exist. Custom service registries when K8s DNS is available. Custom health check systems when
K8s probes are available. Custom auto-scalers when HPA is available. Custom load balancers
when K8s Services are available.

The Ensemble Network does not make this mistake. It is **infrastructure-native**: designed
from the ground up to run on Kubernetes, leveraging K8s for everything K8s is good at and
providing only what K8s does not.

| Concern | K8s provides | Framework provides |
|---|---|---|
| Service discovery | DNS (`kitchen.hotel-ns.svc.cluster.local`) | Nothing -- use K8s DNS |
| Health management | Liveness/readiness probes | Health endpoints on the ensemble server |
| Load balancing | K8s Service (round-robin) | Nothing |
| Auto-scaling | HPA with custom metrics | Metrics (queue depth, capacity utilization) |
| Compartmentalization | Namespaces | Realm concept maps to namespace |
| Network security | Network policies + service mesh mTLS | SPI for auth (delegates to infra) |
| Secret management | K8s Secrets, env vars | Nothing -- secrets stay in K8s |
| Graceful lifecycle | SIGTERM, terminationGracePeriod | Drain mode, preStop hook endpoint |

## Each Ensemble Is a Deployment

The deployment unit is simple: one Kubernetes Deployment per ensemble, fronted by a Service.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kitchen
  namespace: hotel-downtown
  labels:
    app: kitchen
    agentensemble.io/role: ensemble
spec:
  replicas: 2
  selector:
    matchLabels:
      app: kitchen
  template:
    metadata:
      labels:
        app: kitchen
    spec:
      terminationGracePeriodSeconds: 300
      containers:
      - name: kitchen
        image: hotel/kitchen-ensemble:latest
        ports:
        - name: ws
          containerPort: 7329
        env:
        - name: ENSEMBLE_NAME
          value: "kitchen"
        - name: REDIS_URL
          valueFrom:
            secretKeyRef:
              name: redis-credentials
              key: url
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: llm-credentials
              key: openai-key
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "2Gi"
            cpu: "2000m"
        livenessProbe:
          httpGet:
            path: /api/health/live
            port: 7329
          initialDelaySeconds: 10
          periodSeconds: 15
        readinessProbe:
          httpGet:
            path: /api/health/ready
            port: 7329
          initialDelaySeconds: 5
          periodSeconds: 5
        lifecycle:
          preStop:
            httpGet:
              path: /api/lifecycle/drain
              port: 7329
```

### Health Endpoints

The ensemble provides three HTTP endpoints for Kubernetes integration:

**`GET /api/health/live`** -- Liveness probe. Returns 200 if the JVM is alive and the
ensemble process has not deadlocked. If this fails, K8s restarts the pod.

**`GET /api/health/ready`** -- Readiness probe. Returns 200 only when the ensemble is in
the READY state (not STARTING, not DRAINING). When this returns non-200, K8s removes the
pod from the Service's endpoint list: new connections and requests are not routed to it.

**`POST /api/lifecycle/drain`** -- Triggers the DRAINING state. Called by the `preStop`
hook when K8s is about to terminate the pod. The ensemble stops accepting new work, finishes
in-flight tasks, and delivers results before the pod exits.

### Graceful Shutdown Sequence

1. K8s decides to terminate the pod (scale-down, rolling update, node drain)
2. K8s calls the `preStop` hook: `POST /api/lifecycle/drain`
3. Ensemble transitions to DRAINING state
4. Readiness probe returns non-200; K8s stops routing new connections
5. Ensemble stops pulling from the durable queue
6. Scheduled tasks stop running
7. In-flight tasks continue until completion or drain timeout
8. Results for completed tasks are delivered
9. K8s sends SIGTERM
10. Ensemble stops (STOPPED state)
11. If drain timeout has not expired, K8s waits (`terminationGracePeriodSeconds`)
12. If drain timeout expires, K8s sends SIGKILL

`terminationGracePeriodSeconds` must be at least as long as the ensemble's `drainTimeout`.
For ensembles that process long tasks (procurement, research), this may be several minutes.

### The Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: kitchen
  namespace: hotel-downtown
spec:
  selector:
    app: kitchen
  ports:
  - name: ws
    port: 7329
    targetPort: 7329
```

Other ensembles connect to `ws://kitchen:7329/ws` (K8s DNS resolves within the namespace)
or `ws://kitchen.hotel-downtown:7329/ws` (cross-namespace). K8s Service load-balances
across healthy replicas.

### Auto-Scaling

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: kitchen-hpa
  namespace: hotel-downtown
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: kitchen
  minReplicas: 1
  maxReplicas: 10
  metrics:
  - type: Pods
    pods:
      metric:
        name: agentensemble_queued_requests
      target:
        type: AverageValue
        averageValue: 5
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Pods
        value: 1
        periodSeconds: 60
```

The HPA watches `agentensemble_queued_requests` (exposed by the framework via Micrometer).
When the average queue depth exceeds 5 per pod, HPA adds replicas. When load drops, it
removes replicas (with a stabilization window to avoid flapping).

The custom metric reaches Kubernetes via the Prometheus adapter (or KEDA, or any metrics
pipeline that feeds the K8s custom metrics API). The framework exposes the metric; the
infrastructure handles the plumbing.

## What the Framework Provides for K8s

The framework provides:
1. **Health endpoints** (`/api/health/live`, `/api/health/ready`)
2. **Drain endpoint** (`/api/lifecycle/drain`)
3. **Prometheus metrics** (queue depth, active tasks, capacity utilization, token counts)
4. **Lifecycle state machine** (STARTING -> READY -> DRAINING -> STOPPED)
5. **Stateless execution** (external queue + external result store = pods are disposable)

The framework does not provide:
- Helm charts (organizational choice)
- Terraform/Pulumi definitions (infrastructure-as-code choice)
- CI/CD pipelines (tooling choice)
- Monitoring dashboards (Grafana templates are a nice-to-have but not framework scope)

The framework is a good Kubernetes citizen. It follows the established patterns (probes,
graceful shutdown, custom metrics, stateless pods). Operators who know K8s can deploy
ensembles using the same tools and practices they use for any other K8s workload.

## Local Development

For development, none of the K8s infrastructure is needed. A developer runs ensembles
directly:

```java
Ensemble kitchen = Ensemble.builder()
    .name("kitchen")
    .chatLanguageModel(model)
    .shareTask("prepare-meal", mealTask)
    .build();

kitchen.start(7329);  // localhost:7329, simple WebSocket transport
```

Simple transport (in-process queues, direct WebSocket) is the default. No Redis, no Kafka,
no K8s. Two ensembles running in the same JVM (or two JVMs on the same machine) can
communicate directly.

The transition from local development to K8s production is a configuration change
(transport mode, Redis URL, K8s manifests), not a code change. The ensemble code is the
same in both environments.
