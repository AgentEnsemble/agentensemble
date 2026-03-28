package net.agentensemble.tools.coding;

/**
 * Represents a single test failure extracted from test runner output.
 *
 * @param testName   the fully qualified test name or short name
 * @param message    the failure message or assertion error
 * @param stackTrace the stack trace excerpt, if available
 */
public record TestFailure(String testName, String message, String stackTrace) {}
