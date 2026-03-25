# Chapter 3: Cross-Ensemble Delegation

## Borrowing a Tool Versus Hiring a Department

When you need a hole drilled in a wall, you have two options. You can borrow a drill from
your neighbor, drill the hole yourself, and return the drill. Or you can hire a contractor,
tell them where you want the hole, and let them handle it. You get the same result -- a hole
in the wall -- but the process is fundamentally different.

Borrowing the drill is a **tool call**. You maintain control of the process. You decide
the angle, the depth, the bit size. The drill is a capability you temporarily acquire and
operate yourself.

Hiring the contractor is a **task delegation**. You hand off the entire process. The
contractor brings their own drill, their own experience, their own judgment about the right
approach. They might discover the wall is concrete and switch to a masonry bit. They might
notice a pipe behind the wall and adjust the position. You do not micromanage. You get the
hole.

This distinction maps precisely to the two sharing primitives in the Ensemble Network.

**NetworkTool** is borrowing the drill. An agent in one ensemble calls a specific tool
hosted by another ensemble. The calling agent stays in control of its reasoning loop. It
uses the tool result to inform its next step.

**NetworkTask** is hiring the contractor. An agent in one ensemble delegates an entire task
to another ensemble. The target ensemble runs its full process -- agent synthesis, ReAct
loop, tool calls, memory access, review gates, potentially sub-delegations to other
ensembles -- and returns the output. The calling agent receives only the final result.

Most existing cross-service AI communication is tool-level. MCP provides tool calls between
services. LangChain tool integrations are function calls. The Ensemble Network provides
both tool calls and task delegation. The task delegation is what makes it fundamentally
different.

## What Happens During a Cross-Ensemble Task Delegation

Let us trace the exact sequence of events when a maintenance ensemble delegates parts
ordering to a procurement ensemble.

**Step 1**: The maintenance agent is working on a task: "Fix the boiler in building 2." It
has diagnosed the problem -- a faulty valve -- and needs a replacement part.

**Step 2**: The agent's LLM decides to call the `purchase-parts` tool. From the agent's
perspective, this is just another tool in its ReAct loop, no different from calling a
local calculator or web search tool.

```
Thought: I need to order a replacement valve. I'll use the purchase-parts tool.
Action: purchase-parts
Action Input: "Order replacement valve model X-420 for building 2 boiler, urgent priority"
```

**Step 3**: The `NetworkTask` implementation serializes this into a WorkRequest:

```json
{
  "type": "task_request",
  "requestId": "maint-7721-valve-order",
  "from": "maintenance",
  "task": "purchase-parts",
  "context": "Order replacement valve model X-420 for building 2 boiler, urgent priority",
  "priority": "HIGH",
  "deadline": "PT30M",
  "delivery": { "method": "WEBSOCKET", "address": "ws://maintenance:7329/ws" },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  }
}
```

**Step 4**: The WorkRequest is sent to the procurement ensemble (via WebSocket or durable
queue). The maintenance agent's thread blocks, waiting for the response (in AWAIT mode).

**Step 5**: The procurement ensemble receives the request. It looks up the "purchase-parts"
shared task definition.

**Step 6**: Procurement synthesizes an agent for the task (using the configured
`AgentSynthesizer`, which might produce a "Procurement Specialist" persona). The agent
receives the context: "Order replacement valve model X-420 for building 2 boiler, urgent
priority."

**Step 7**: The procurement agent executes its own ReAct loop. It might:
- Call a vendor search tool to find suppliers for model X-420
- Call a price comparison tool to compare quotes
- Check the budget allocation tool to verify funds are available
- Call a purchase order tool to place the order
- Maybe even delegate shipping logistics to a logistics ensemble (another cross-ensemble
  delegation, creating a chain)

**Step 8**: The procurement agent completes. The output is: "Ordered from SupplyCo, PO
#4821, estimated delivery Thursday. Total cost: $342.00, charged to maintenance budget
code M-2026-B2."

**Step 9**: The procurement ensemble sends the response back to maintenance:

```json
{
  "type": "task_response",
  "requestId": "maint-7721-valve-order",
  "status": "COMPLETED",
  "result": "Ordered from SupplyCo, PO #4821, estimated delivery Thursday. Total cost: $342.00, charged to maintenance budget code M-2026-B2."
}
```

**Step 10**: The maintenance agent's `NetworkTask` tool call unblocks. The agent receives
the result and continues its ReAct loop:

```
Observation: Ordered from SupplyCo, PO #4821, estimated delivery Thursday.
Thought: The replacement valve is ordered and arriving Thursday. I should schedule the
repair for Thursday afternoon and notify the building manager.
Action: ...
```

From the maintenance agent's perspective, it called a tool and got a result. It does not
know that behind that tool call, an entire procurement ensemble ran a multi-step process
involving vendor search, price comparison, budget verification, and order placement. This
is the power of the abstraction.

## The Delegation Chain

Cross-ensemble delegation is naturally recursive. In step 7 above, the procurement agent
might delegate shipping to a logistics ensemble. The logistics ensemble might delegate
customs clearance to a compliance ensemble. Each ensemble in the chain runs its own
complete process.

```
maintenance
  -> procurement (purchase-parts)
       -> logistics (arrange-shipping)
            -> compliance (customs-clearance)
            <- clearance approved
       <- shipping arranged, tracking #SH-8891
  <- ordered, PO #4821, delivery Thursday
```

The maintenance ensemble sees only the final result. It does not know about the delegation
chain underneath. Each ensemble in the chain sees only its direct caller and its direct
delegates.

This mirrors how organizations work. The maintenance manager asks procurement to order
parts. She does not need to know that procurement used a specific vendor, that the vendor
ships from overseas, or that customs clearance was required. She needs to know: will the
part arrive, and when?

### Depth Limiting

Delegation chains need bounds. The existing `maxDelegationDepth` configuration (used for
agent-to-agent delegation within a single ensemble) extends to cross-ensemble delegation.
The depth counter propagates with the trace context. If the chain exceeds the configured
maximum, the framework returns an error rather than allowing infinite recursion.

## NetworkTool: The Lighter Alternative

Not every cross-ensemble interaction needs full task delegation. Sometimes you just need
to check something.

The room service agent is assembling an order for a guest. Before calling the kitchen, it
wants to verify that the requested dish is available:

```
Thought: Guest wants wagyu steak. Let me check if kitchen has it in stock.
Action: check-inventory
Action Input: "wagyu beef"
```

The `NetworkTool` serializes this as a tool request (not a task request):

```json
{
  "type": "tool_request",
  "requestId": "rs-8801",
  "from": "room-service",
  "tool": "check-inventory",
  "input": "wagyu beef"
}
```

The kitchen ensemble receives this, executes the `inventoryTool` directly (no agent
synthesis, no ReAct loop, no task pipeline), and returns:

```json
{
  "type": "tool_response",
  "requestId": "rs-8801",
  "result": "Yes, 3 portions available"
}
```

The room service agent continues:

```
Observation: Yes, 3 portions available
Thought: Wagyu is in stock. I can proceed with the order.
Action: prepare-meal
Action Input: "Wagyu steak, medium-rare, room 403"
```

Notice the pattern: the agent used a `NetworkTool` (check-inventory) for a quick lookup,
then a `NetworkTask` (prepare-meal) for the actual delegation. Both are tools in its ReAct
loop. The agent chose which to use based on what it needed: a fact check versus a full
process delegation.

## Transparent to the Agent

The most important design property of cross-ensemble delegation is that it is transparent
to the agent.

Both `NetworkTask` and `NetworkTool` implement the existing `AgentTool` interface. They
have names, descriptions, and execute methods. The LLM sees them as tools in its available
tool list. It decides when to call them based on the descriptions, just like it decides
when to call a local calculator tool or a web search tool.

```java
// These three tools are indistinguishable to the agent:
Task.builder()
    .description("Handle guest request")
    .tools(
        calculatorTool,                                    // local tool
        NetworkTool.from("kitchen", "check-inventory"),    // remote tool
        NetworkTask.from("kitchen", "prepare-meal"))       // remote task delegation
    .build();
```

The agent does not know which tools are local, which are remote tool calls, and which are
full task delegations. It does not need to know. The LLM reasons about what it needs to do,
selects the appropriate tool, calls it, and uses the result.

This transparency means that the entire existing infrastructure -- the ReAct loop, tool
executor, metrics, tracing, error handling, guardrails -- works unchanged with network
tools. A `NetworkTask` call appears in the `ExecutionTrace` the same way a local tool call
does. Metrics record its duration. The trace captures its input and output. Guardrails
can validate its result.

## When to Use Which

| Situation | Use | Hotel analogy |
|---|---|---|
| Need a quick fact | `NetworkTool` | "Is the restaurant open?" |
| Need a specific calculation or lookup | `NetworkTool` | "What's the room rate for tonight?" |
| Need a complete process handled | `NetworkTask` | "Prepare a meal for room 403" |
| Need a complex, multi-step workflow | `NetworkTask` | "Order spare parts for the boiler" |
| Need to maintain control of reasoning | `NetworkTool` | Borrow the drill |
| Need to hand off responsibility | `NetworkTask` | Hire the contractor |

The choice is the agent's. The LLM, guided by the tool descriptions, decides whether it
needs a fact (tool) or a delegation (task). The framework provides both options; the agent
selects based on its reasoning.
