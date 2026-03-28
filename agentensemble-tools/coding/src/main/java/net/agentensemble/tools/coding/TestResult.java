package net.agentensemble.tools.coding;

import java.util.List;

/**
 * Structured result from a test run.
 *
 * @param success  whether all tests passed
 * @param passed   number of passing tests
 * @param failed   number of failing tests
 * @param skipped  number of skipped tests
 * @param failures details of each failure
 */
public record TestResult(boolean success, int passed, int failed, int skipped, List<TestFailure> failures) {}
