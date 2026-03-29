package net.agentensemble.callback;

import java.time.Instant;

/**
 * Fired when a coding tool modifies a file in the workspace.
 *
 * @param agentRole   the role of the agent whose tool changed the file
 * @param filePath    relative path of the changed file within the workspace
 * @param changeType  type of change: "CREATED", "MODIFIED", or "DELETED"
 * @param linesAdded  number of lines added (0 for deletions)
 * @param linesRemoved number of lines removed (0 for creations)
 * @param timestamp   server-side timestamp of the change
 */
public record FileChangedEvent(
        String agentRole, String filePath, String changeType, int linesAdded, int linesRemoved, Instant timestamp) {}
