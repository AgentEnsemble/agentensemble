# Changelog

## [2.6.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.5.0...v2.6.0) (2026-03-11)


### Features

* deterministic tasks - Task.builder().handler() for non-AI task execution ([#182](https://github.com/AgentEnsemble/agentensemble/issues/182)) ([93e6c47](https://github.com/AgentEnsemble/agentensemble/commit/93e6c478cffa8a83e9ac57c7a1138154a7d8d387))

## [2.5.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.4.0...v2.5.0) (2026-03-10)


### Features

* **viz/web:** multi-run support -- stacked timelines and per-run trace export ([#180](https://github.com/AgentEnsemble/agentensemble/issues/180)) ([d739b14](https://github.com/AgentEnsemble/agentensemble/commit/d739b148e8fbe1a89895f0ee88f5b3dc7fc1673b))

## [2.4.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.3.0...v2.4.0) (2026-03-10)


### Features

* **viz:** Timeline view By Task / By Agent grouping toggle ([#177](https://github.com/AgentEnsemble/agentensemble/issues/177)) ([ea3c4d4](https://github.com/AgentEnsemble/agentensemble/commit/ea3c4d4ba477f8817926b07736c210bfdc022a97))

## [2.3.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.2.2...v2.3.0) (2026-03-09)


### Features

* rate limiting at Task/Agent/Ensemble levels (Issue [#59](https://github.com/AgentEnsemble/agentensemble/issues/59)) ([#173](https://github.com/AgentEnsemble/agentensemble/issues/173)) ([7b0dc01](https://github.com/AgentEnsemble/agentensemble/commit/7b0dc017302624736f2015b0568c552d0174273a))


### Bug Fixes

* auto-stop WebDashboard after ensemble run to allow JVM exit ([#175](https://github.com/AgentEnsemble/agentensemble/issues/175)) ([a65b39b](https://github.com/AgentEnsemble/agentensemble/commit/a65b39bc72637be772f28f8e4edbfe99a1954c53))

## [2.2.2](https://github.com/AgentEnsemble/agentensemble/compare/v2.2.1...v2.2.2) (2026-03-06)


### Bug Fixes

* resolve port conflict between agentensemble-viz CLI and WebDashboard ([de9e5f4](https://github.com/AgentEnsemble/agentensemble/commit/de9e5f4ea00cc4afaf7b2bac0f2d0fd33a5906a0))

## [2.2.1](https://github.com/AgentEnsemble/agentensemble/compare/v2.2.0...v2.2.1) (2026-03-06)


### Bug Fixes

* add agentensemble-web to the BOM constraints ([e3bf756](https://github.com/AgentEnsemble/agentensemble/commit/e3bf756508eee0ae70ff9dc4f2af12019680141e))

## [2.2.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.1.0...v2.2.0) (2026-03-06)


### Features

* Playwright E2E tests for live dashboard and review gate ([#160](https://github.com/AgentEnsemble/agentensemble/issues/160)) ([f2610e8](https://github.com/AgentEnsemble/agentensemble/commit/f2610e8fb0214000b9654febc9bb2a573733bb76))


### Bug Fixes

* task-first synthesis fallback and template role extraction ([#162](https://github.com/AgentEnsemble/agentensemble/issues/162)) ([437856a](https://github.com/AgentEnsemble/agentensemble/commit/437856a4f8727cb286be117c872ab17b151d2c2f))

## [2.1.0](https://github.com/AgentEnsemble/agentensemble/compare/v2.0.0...v2.1.0) (2026-03-06)


### Features

* **#130:** agentensemble-web module -- WebSocket server + protocol (v2.1.0) ([#138](https://github.com/AgentEnsemble/agentensemble/issues/138)) ([4f7497b](https://github.com/AgentEnsemble/agentensemble/commit/4f7497bb1c0099da7f10e5fbb1fe303cdda7a312))
* **#131:** WebSocketStreamingListener -- bridge callbacks to WebSocket (v2.1.0) ([#142](https://github.com/AgentEnsemble/agentensemble/issues/142)) ([97de9f5](https://github.com/AgentEnsemble/agentensemble/commit/97de9f5c2aa24991b787aff21c8a35bdfbc8bb42))
* **#132:** WebReviewHandler -- real implementation replacing stub (v2.1.0) ([#143](https://github.com/AgentEnsemble/agentensemble/issues/143)) ([f9d4641](https://github.com/AgentEnsemble/agentensemble/commit/f9d464185f4d7806c730214686c9958aafadd6e3))
* explicit tool pipeline / chaining (issue [#74](https://github.com/AgentEnsemble/agentensemble/issues/74)) ([#140](https://github.com/AgentEnsemble/agentensemble/issues/140)) ([118d3fc](https://github.com/AgentEnsemble/agentensemble/commit/118d3fcd7835c4fdd91b664d7ece7f4b1992bcc8))
* token-by-token streaming via StreamingChatModel ([#61](https://github.com/AgentEnsemble/agentensemble/issues/61)) ([#146](https://github.com/AgentEnsemble/agentensemble/issues/146)) ([3cc1cf6](https://github.com/AgentEnsemble/agentensemble/commit/3cc1cf6f0611240e32c911dc36732f7e3b7b25b1))
* viz review approval UI (v2.1.0) ([#151](https://github.com/AgentEnsemble/agentensemble/issues/151)) ([01f3514](https://github.com/AgentEnsemble/agentensemble/commit/01f3514a23640713964738d5358d5f8001ca12ee))
* **viz:** live mode WebSocket client + live timeline/flow ([#133](https://github.com/AgentEnsemble/agentensemble/issues/133), [#134](https://github.com/AgentEnsemble/agentensemble/issues/134)) ([#144](https://github.com/AgentEnsemble/agentensemble/issues/144)) ([cd014a5](https://github.com/AgentEnsemble/agentensemble/commit/cd014a55036c92eb900e4e698859c8edbfddfdcb))


### Bug Fixes

* address Copilot review comments on ToolPipeline (PR [#140](https://github.com/AgentEnsemble/agentensemble/issues/140)) ([#141](https://github.com/AgentEnsemble/agentensemble/issues/141)) ([624857c](https://github.com/AgentEnsemble/agentensemble/commit/624857cc0ddda70a6d1fe9705e3d353fea81d499))
* **core:** hard runtime deps, context identity remap, and null-safe agent role ([#147](https://github.com/AgentEnsemble/agentensemble/issues/147) [#148](https://github.com/AgentEnsemble/agentensemble/issues/148) [#149](https://github.com/AgentEnsemble/agentensemble/issues/149)) ([#150](https://github.com/AgentEnsemble/agentensemble/issues/150)) ([f099b20](https://github.com/AgentEnsemble/agentensemble/commit/f099b2032a7ebf050bfd8cfb649eb2611a4cf2ee))
* **viz:** embed dist assets into compiled binary so Homebrew binary serves UI correctly ([#145](https://github.com/AgentEnsemble/agentensemble/issues/145)) ([366bf88](https://github.com/AgentEnsemble/agentensemble/commit/366bf8844c7d07dd5da3c846bc64337c9679c9c4))

## [2.0.0](https://github.com/AgentEnsemble/agentensemble/compare/v1.5.0...v2.0.0) (2026-03-06)


### ⚠ BREAKING CHANGES

* Agents are no longer declared on Ensemble.builder(). Tasks are the primary abstraction; the framework auto-synthesises agents via AgentSynthesizer. Ensemble.builder().agent() removed. Memory API changed from EnsembleMemory to MemoryStore. agentensemble-memory and agentensemble-review are now separate optional Gradle modules. MemoryEntry fields restructured. See docs/migration/v1-to-v2.md for the full guide.

### Features

* **#113:** MapReduceEnsemble task-first API and zero-ceremony factory ([#128](https://github.com/AgentEnsemble/agentensemble/issues/128)) ([1665ffa](https://github.com/AgentEnsemble/agentensemble/commit/1665ffa4d08e6b093ecdd63a753389090dccb8e7))
* agentensemble-bom and v1-to-v2 migration guide ([#114](https://github.com/AgentEnsemble/agentensemble/issues/114), [#115](https://github.com/AgentEnsemble/agentensemble/issues/115)) ([#137](https://github.com/AgentEnsemble/agentensemble/issues/137)) ([d671a3a](https://github.com/AgentEnsemble/agentensemble/commit/d671a3a7776a82920dca838d1f7e2238ff9ef6cb))
* agentensemble-memory module and task-scoped cross-execution memory ([#106](https://github.com/AgentEnsemble/agentensemble/issues/106), [#107](https://github.com/AgentEnsemble/agentensemble/issues/107)) ([#123](https://github.com/AgentEnsemble/agentensemble/issues/123)) ([233b466](https://github.com/AgentEnsemble/agentensemble/commit/233b46614144324ab65598a88f77452079ba94b8))
* EnsembleOutput partial results + workflow inference ([#111](https://github.com/AgentEnsemble/agentensemble/issues/111), [#112](https://github.com/AgentEnsemble/agentensemble/issues/112)) ([#127](https://github.com/AgentEnsemble/agentensemble/issues/127)) ([d6a3bc3](https://github.com/AgentEnsemble/agentensemble/commit/d6a3bc3080c03a6ebb906a96f9c2ef139582f69c))
* human-in-the-loop review system ([#108](https://github.com/AgentEnsemble/agentensemble/issues/108), [#109](https://github.com/AgentEnsemble/agentensemble/issues/109), [#110](https://github.com/AgentEnsemble/agentensemble/issues/110)) ([#125](https://github.com/AgentEnsemble/agentensemble/issues/125)) ([d3e504e](https://github.com/AgentEnsemble/agentensemble/commit/d3e504ed00281c9f021aa585b439f21502308abb))
* Task-first core + AgentSynthesizer SPI (issues [#104](https://github.com/AgentEnsemble/agentensemble/issues/104) + [#105](https://github.com/AgentEnsemble/agentensemble/issues/105)) ([#122](https://github.com/AgentEnsemble/agentensemble/issues/122)) ([f600913](https://github.com/AgentEnsemble/agentensemble/commit/f600913aebe52fbc5e26ae325acbb70d84bc9402))
* tool-level approval gates via ReviewHandler ([#126](https://github.com/AgentEnsemble/agentensemble/issues/126)) ([#136](https://github.com/AgentEnsemble/agentensemble/issues/136)) ([7b23dd6](https://github.com/AgentEnsemble/agentensemble/commit/7b23dd6a61ba4eba369ae6284c5782eaff5db900))

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
