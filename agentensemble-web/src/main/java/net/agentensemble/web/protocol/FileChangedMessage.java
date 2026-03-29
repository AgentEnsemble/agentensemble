package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Wire message sent when a coding tool modifies a file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileChangedMessage(
        String agentRole, String filePath, String changeType, int linesAdded, int linesRemoved, Instant timestamp)
        implements ServerMessage {}
