# EN-028: Capability-based discovery and NetworkToolCatalog

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: EN-003, Phase 2 complete
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Section 9

## Description

Implement dynamic capability discovery so ensembles can discover tools and tasks by name or tag at execution time, not just at build time.

## Acceptance Criteria

- [ ] NetworkTool.discover(String toolName) -- find any provider
- [ ] NetworkToolCatalog.all() -- all tools on the network
- [ ] NetworkToolCatalog.tagged(String tag) -- filtered by tag
- [ ] Tag support on shareTask() and shareTool()
- [ ] capability_query and capability_response protocol messages
- [ ] Resolution at task execution time (not build time)

## Tests

- [ ] Unit tests for discovery and catalog
- [ ] Unit test: tagged discovery filters correctly
- [ ] Unit test: new ensemble's tools appear immediately
- [ ] Integration test: new ensemble comes online, its tools are immediately discoverable
