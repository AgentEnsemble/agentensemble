# Changelog

## [1.5.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.4.1...v1.5.0) (2026-03-05)


### Features

* adaptive MapReduceEnsemble with targetTokenBudget (issue [#99](https://github.com/AgentEnsemble/agentensemble/issues/99)) ([#119](https://github.com/AgentEnsemble/agentensemble/issues/119)) ([ad9e668](https://github.com/AgentEnsemble/agentensemble/commit/ad9e668ad11eed218f40356eb13b6065166d6043))
* MapReduceEnsemble short-circuit optimization for small inputs (v2.0.0) ([#121](https://github.com/AgentEnsemble/agentensemble/issues/121)) ([a18d087](https://github.com/AgentEnsemble/agentensemble/commit/a18d087fa228618949b43b86f530e78b2b57fc3c))

## [1.4.1](https://github.com/AgentEnsemble/agentensemble/compare/v1.4.0...v1.4.1) (2026-03-05)


### Bug Fixes

* use ORG_RELEASE_TOKEN to bypass branch protection on SNAPSHOT bump ([#117](https://github.com/AgentEnsemble/agentensemble/issues/117)) ([21b9941](https://github.com/AgentEnsemble/agentensemble/commit/21b99411dae03f5df70dafa62bc90cb1484882f0))

## [1.4.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.3.0...v1.4.0) (2026-03-05)


### Features

* **#42:** execution metrics, token tracking, cost estimation, and execution trace ([#88](https://github.com/AgentEnsemble/agentensemble/issues/88)) ([b85f735](https://github.com/AgentEnsemble/agentensemble/commit/b85f73539f75888161ae3aa6111d9f5036db0016))
* **#89:** CaptureMode -- transparent debug/capture mode for complete execution recording ([#93](https://github.com/AgentEnsemble/agentensemble/issues/93)) ([0438232](https://github.com/AgentEnsemble/agentensemble/commit/0438232b875e8c18e033d5bd65837ba8083c3fff))
* **core:** static MapReduceEnsemble with chunkSize (issue [#98](https://github.com/AgentEnsemble/agentensemble/issues/98)) ([#102](https://github.com/AgentEnsemble/agentensemble/issues/102)) ([6cf0f7d](https://github.com/AgentEnsemble/agentensemble/commit/6cf0f7d6c0ddec2059feceaf8380e61064783f59))
* distribute agentensemble-viz via Homebrew tap ([#94](https://github.com/AgentEnsemble/agentensemble/issues/94)) ([#96](https://github.com/AgentEnsemble/agentensemble/issues/96)) ([495f728](https://github.com/AgentEnsemble/agentensemble/commit/495f7280a512037c0197521c8821745c12769de5))
* interactive execution graph visualization ([#44](https://github.com/AgentEnsemble/agentensemble/issues/44)) ([#95](https://github.com/AgentEnsemble/agentensemble/issues/95)) ([b8c84cd](https://github.com/AgentEnsemble/agentensemble/commit/b8c84cd91a19bf0f88268f7f42f06d800e0dd48e))


### Bug Fixes

* address Copilot review comments from PR [#96](https://github.com/AgentEnsemble/agentensemble/issues/96) ([#97](https://github.com/AgentEnsemble/agentensemble/issues/97)) ([86bdc4f](https://github.com/AgentEnsemble/agentensemble/commit/86bdc4f78430896de51ffe402bb7125c1d20e98f))

## [1.3.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.2.0...v1.3.0) (2026-03-05)


### Features

* **#81:** constrained hierarchical mode - HierarchicalConstraints, ConstraintViolationException, enforcer ([#86](https://github.com/AgentEnsemble/agentensemble/issues/86)) ([132d6e5](https://github.com/AgentEnsemble/agentensemble/commit/132d6e5e9fa9563cfb9b98391e5e65e3268b91ba))

## [1.2.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.1.0...v1.2.0) (2026-03-05)


### Features

* delegation policy hooks ([#78](https://github.com/AgentEnsemble/agentensemble/issues/78)) and lifecycle events ([#79](https://github.com/AgentEnsemble/agentensemble/issues/79)) ([#84](https://github.com/AgentEnsemble/agentensemble/issues/84)) ([abfe488](https://github.com/AgentEnsemble/agentensemble/commit/abfe48869e5e403a291d1368838cdf739640726e))

## [1.1.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.0.1...v1.1.0) (2026-03-05)


### Features

* structured delegation contracts ([#77](https://github.com/AgentEnsemble/agentensemble/issues/77)) and manager prompt extension hook ([#80](https://github.com/AgentEnsemble/agentensemble/issues/80)) ([#82](https://github.com/AgentEnsemble/agentensemble/issues/82)) ([e9b1cdc](https://github.com/AgentEnsemble/agentensemble/commit/e9b1cdcec19c870109c0f3db6dd1a353d7a92afb))

## [1.0.1](https://github.com/AgentEnsemble/agentensemble/compare/v1.0.0...v1.0.1) (2026-03-04)


### Bug Fixes

* expose langchain4j dependencies as api for downstream consumers ([ed071f7](https://github.com/AgentEnsemble/agentensemble/commit/ed071f7ff6d90dec540a28ae01b3211b65c31b45))

## [1.0.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.9.0...v1.0.0) (2026-03-04)


### Features

* enhanced tool model - AbstractAgentTool, per-tool modules, remote tools, metrics (Closes [#60](https://github.com/AgentEnsemble/agentensemble/issues/60), Closes [#73](https://github.com/AgentEnsemble/agentensemble/issues/73)) ([#72](https://github.com/AgentEnsemble/agentensemble/issues/72)) ([706305e](https://github.com/AgentEnsemble/agentensemble/commit/706305e122c9e7ef607052276a3d9357c5aa81dc))


### Miscellaneous Chores

* target 1.0.0 release ([13b39eb](https://github.com/AgentEnsemble/agentensemble/commit/13b39ebdc6b71a6e1f4990d14e1561bdb5ac561e))

## [0.9.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.8.0...v0.9.0) (2026-03-03)


### Features

* guardrails -- pre/post execution validation hooks ([#58](https://github.com/AgentEnsemble/agentensemble/issues/58)) ([#70](https://github.com/AgentEnsemble/agentensemble/issues/70)) ([ff48d5c](https://github.com/AgentEnsemble/agentensemble/commit/ff48d5c0c7fbde043d9e23fad4793537e9d601d7))

## [0.8.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.7.1...v0.8.0) (2026-03-03)


### Features

* add .input() builder method for template variable inputs ([#68](https://github.com/AgentEnsemble/agentensemble/issues/68)) ([8524707](https://github.com/AgentEnsemble/agentensemble/commit/85247078a6e0ea01426af33971ef6a867e0335db))

## [0.7.1](https://github.com/AgentEnsemble/agentensemble/compare/v0.7.0...v0.7.1) (2026-03-03)


### Bug Fixes

* resolve javadoc [@link](https://github.com/link) errors and add javadoc to CI ([#66](https://github.com/AgentEnsemble/agentensemble/issues/66)) ([27f472b](https://github.com/AgentEnsemble/agentensemble/commit/27f472b17dad8d1abec840da70fbcfc049c27738))

## [0.7.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.6.1...v0.7.0) (2026-03-03)


### Features

* ExecutionContext refactor, callback system, ToolResolver extraction ([#57](https://github.com/AgentEnsemble/agentensemble/issues/57)) ([#63](https://github.com/AgentEnsemble/agentensemble/issues/63)) ([dbcad6e](https://github.com/AgentEnsemble/agentensemble/commit/dbcad6ed159fd2655af783676f4af8e229a2316b))

## [0.6.1](https://github.com/AgentEnsemble/agentensemble/compare/v0.6.0...v0.6.1) (2026-03-03)


### Bug Fixes

* update release workflow and javadoc for vanniktech-publish 0.36.0 ([097ea2b](https://github.com/AgentEnsemble/agentensemble/commit/097ea2b3f99d801e50d5caa33a7c1c3d464ab36a))

## [0.6.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.5.0...v0.6.0) (2026-03-03)


### Features

* **structured-output:** typed output parsing and schema injection (Issue [#19](https://github.com/AgentEnsemble/agentensemble/issues/19)) ([#48](https://github.com/AgentEnsemble/agentensemble/issues/48)) ([1d69c5c](https://github.com/AgentEnsemble/agentensemble/commit/1d69c5c2ed1afed111d959034ff671cd669a7135))

## [0.5.0](https://github.com/AgentEnsemble/agentensemble/compare/v0.4.2...v0.5.0) (2026-03-03)


### Features

* parallel workflow with DAG-based concurrent execution (Issue [#18](https://github.com/AgentEnsemble/agentensemble/issues/18)) ([#45](https://github.com/AgentEnsemble/agentensemble/issues/45)) ([7535576](https://github.com/AgentEnsemble/agentensemble/commit/75355762a1a0fc4508b6d31942abf7df6557b5b1))
