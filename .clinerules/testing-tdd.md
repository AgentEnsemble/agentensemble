# .clinerules/testing-tdd.md
# Rule: Test-Driven Development + Full Testing Pyramid Coverage

## Core policy
- Default to **Test-Driven Development (TDD)**:
  1) Write a failing test that expresses the desired behavior
  2) Implement the smallest change to pass
  3) Refactor with tests staying green
- Any non-trivial change must include tests at **all relevant levels**:
  - **Unit tests** (fast, isolated)
  - **Integration tests** (real boundaries: DB, HTTP, filesystem, queues, etc.)
  - **Feature/E2E tests** (user-visible behavior across the stack)

## Proactive coverage expansion (required)
When making changes, the assistant must **look for nearby areas that lack sufficient tests** and opportunistically add coverage, even if not strictly required by the immediate change:
- Search for:
- touched or adjacent modules with low/no tests
- branches and error paths in the edited code that are not exercised
- historical bug patterns in the same area (validation, parsing, time, retries, permissions)
- previously skipped/ignored tests or TODO/FIXME markers related to the area
- when changing repository query logic (e.g., NULL handling or date boundaries), add a repository-level
  or integration test that asserts the new edge case
- Add at least **1-3 additional targeted tests** that increase confidence without bloating suites.
- Prefer tests that:
  - reproduce an implied edge case
  - lock down a boundary contract
  - cover a previously-untested branch

## What "complete coverage" means (practical definition)
- **Unit:** Cover all new/changed functions, branches, and edge cases. Include at least:
  - happy path
  - at least one failure/exception path
  - boundary conditions (empty/null/zero/large input; timeouts; retries when applicable)
- **Integration:** Cover the request/response or boundary contract with real components (or realistic test doubles if truly necessary). Include at least:
  - one success scenario
  - one failure scenario (e.g., 4xx/5xx, constraint violation, timeout)
- **Feature/E2E:** One or more tests that verify the end-user outcome. Include at least:
  - one happy path that matches acceptance criteria
  - one negative case if the feature has meaningful validation/permissions/error states

## Workflow requirements for the assistant
- Before coding: propose **test cases first** (brief list).
- Implement tests before production code for new behavior.
- When extracting shared logic into a new service/module, add dedicated unit tests for that new service,
  and refactor dependent tests to mock the service (avoid duplicating business logic in controller tests).
  Still keep at least one integration test path to validate wiring.
- Keep tests deterministic:
  - no real network calls to external services
  - stable time (freeze/clock injection), stable randomness (seed)
  - no reliance on test order
- Prefer clear Arrange/Act/Assert structure; keep test names behavior-oriented.

## When a level can be skipped (rare, must be justified in output)
Skipping a level is allowed only if it is **not applicable**, and the assistant must explicitly say why.
Examples:
- Pure refactor with no behavior change: unit tests may be sufficient if integration/feature coverage already exists and still passes.
- Change is limited to internal implementation detail with no boundary impact: integration/feature may be "already covered"; reference which existing tests cover it.

## Definition of Done for any change
- Added/updated tests for relevant levels (unit + integration + feature)
- Added **opportunistic coverage** for nearby weak spots (unless truly none found--say so)
- All tests **and CI-equivalent quality checks** pass locally -- not just the test task.
  Run the same command CI uses (`./gradlew build :module:javadoc --continue`). This catches
  formatting violations, Javadoc errors, and coverage failures that the bare `test` task misses.
- **Documentation updated proactively** -- do not wait for the user to ask. For any user-facing
  feature or API change, update all relevant documentation before marking the task done:
  - User guides (`docs/guides/`) -- new guide if the feature warrants one
  - Example pages (`docs/examples/`) -- with runnable Java code where applicable
  - Reference tables (`docs/reference/`) -- e.g., ensemble-configuration.md for new builder fields
  - Design docs (`docs/design/`) -- for architecture, execution flow, logging, or config changes
  - README.md -- when adding major features visible to first-time readers
  - Navigation config (`mkdocs.yml`) -- when adding new pages
  - Runnable example class in `agentensemble-examples/` + Gradle task when the feature has a demo
  - Memory bank (`memory-bank/`) -- activeContext.md, progress.md, changelog.md after significant changes
- Include in final response:
  - what tests were added (file names or suite names)
  - which level(s) they cover
  - what extra coverage was added and why
  - any skip justification (if applicable)

## Output format expectation
In the final response, include:

### Tests Added
- Unit:
- Integration:
- Feature/E2E:

### Opportunistic Coverage Added
- (area -> what was added -> why)
