package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import net.agentensemble.web.protocol.ClientMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.RunRequestMessage;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RunRequestMessage}: serialization, deserialization, and type dispatch.
 */
class RunRequestMessageTest {

    private final MessageSerializer serializer = new MessageSerializer();
    private final ObjectMapper mapper = new ObjectMapper();

    // ========================
    // Serialization round-trip
    // ========================

    @Test
    void serialize_withAllFields_includesTypeDiscriminator() throws Exception {
        var msg = new RunRequestMessage("req-1", Map.of("topic", "AI safety"), null, null, null, Map.of("env", "test"));

        String json = serializer.toJson(msg);

        assertThat(json).contains("\"type\":\"run_request\"");
        assertThat(json).contains("\"requestId\":\"req-1\"");
        assertThat(json).contains("\"topic\"");
        assertThat(json).contains("\"AI safety\"");
    }

    @Test
    void deserialize_asClientMessage_givesRunRequestMessage() throws Exception {
        String json =
                """
                {
                  "type": "run_request",
                  "requestId": "req-42",
                  "inputs": {"topic": "EU regulation"}
                }
                """;

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);

        assertThat(msg).isInstanceOf(RunRequestMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;
        assertThat(rrm.requestId()).isEqualTo("req-42");
        assertThat(rrm.inputs()).containsEntry("topic", "EU regulation");
        assertThat(rrm.tasks()).isNull();
        assertThat(rrm.taskOverrides()).isNull();
    }

    @Test
    void deserialize_withTasks_parsesTaskList() throws Exception {
        String json =
                """
                {
                  "type": "run_request",
                  "requestId": "req-3",
                  "inputs": {"product": "AgentEnsemble"},
                  "tasks": [
                    {
                      "name": "researcher",
                      "description": "Research {product} competitors",
                      "expectedOutput": "Competitor analysis",
                      "maxIterations": 20
                    },
                    {
                      "name": "writer",
                      "description": "Write executive brief",
                      "expectedOutput": "Brief",
                      "context": ["$researcher"]
                    }
                  ]
                }
                """;

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;

        assertThat(rrm.requestId()).isEqualTo("req-3");
        assertThat(rrm.tasks()).hasSize(2);
        assertThat(rrm.tasks().get(0)).containsEntry("name", "researcher");
        assertThat(rrm.tasks().get(0)).containsEntry("maxIterations", 20);
        assertThat(rrm.tasks().get(1).get("context")).isEqualTo(List.of("$researcher"));
    }

    @Test
    void deserialize_withTaskOverrides_parsesOverrideMap() throws Exception {
        String json =
                """
                {
                  "type": "run_request",
                  "requestId": "req-4",
                  "inputs": {"topic": "AI safety"},
                  "taskOverrides": {
                    "researcher": {
                      "description": "Research {topic} focusing on EU regulation",
                      "maxIterations": 15,
                      "model": "opus"
                    }
                  }
                }
                """;

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;

        assertThat(rrm.taskOverrides()).containsKey("researcher");
        Map<String, Object> researcherOverride = rrm.taskOverrides().get("researcher");
        assertThat(researcherOverride).containsEntry("model", "opus");
        assertThat(researcherOverride.get("maxIterations")).isEqualTo(15);
    }

    @Test
    void deserialize_withOptions_parsesOptionsMap() throws Exception {
        String json =
                """
                {
                  "type": "run_request",
                  "inputs": {},
                  "options": {
                    "maxToolOutputLength": 5000,
                    "toolLogTruncateLength": 100
                  }
                }
                """;

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;

        assertThat(rrm.options()).containsEntry("maxToolOutputLength", 5000);
        assertThat(rrm.options()).containsEntry("toolLogTruncateLength", 100);
    }

    @Test
    void deserialize_withTags_parsesTagsMap() throws Exception {
        String json =
                """
                {
                  "type": "run_request",
                  "inputs": {},
                  "tags": {"env": "staging", "requestedBy": "ci-pipeline"}
                }
                """;

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;

        assertThat(rrm.tags()).containsEntry("env", "staging");
        assertThat(rrm.tags()).containsEntry("requestedBy", "ci-pipeline");
    }

    @Test
    void deserialize_minimalMessage_onlyRequiredFieldsNull() throws Exception {
        String json = "{\"type\": \"run_request\"}";

        ClientMessage msg = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage rrm = (RunRequestMessage) msg;

        assertThat(rrm.requestId()).isNull();
        assertThat(rrm.inputs()).isNull();
        assertThat(rrm.tasks()).isNull();
        assertThat(rrm.taskOverrides()).isNull();
        assertThat(rrm.options()).isNull();
        assertThat(rrm.tags()).isNull();
    }

    @Test
    void serialize_nullFieldsOmitted() throws Exception {
        var msg = new RunRequestMessage("req-99", Map.of("k", "v"), null, null, null, null);

        String json = serializer.toJson(msg);

        assertThat(json).doesNotContain("tasks");
        assertThat(json).doesNotContain("taskOverrides");
        assertThat(json).doesNotContain("options");
        assertThat(json).doesNotContain("tags");
    }

    @Test
    void roundTrip_level3TaskDefs_preservedExactly() throws Exception {
        var taskDef = Map.<String, Object>of(
                "name", "writer",
                "description", "Write a report",
                "expectedOutput", "A report",
                "context", List.of("$researcher"));

        var original = new RunRequestMessage("req-100", Map.of("topic", "AI"), List.of(taskDef), null, null, null);

        String json = serializer.toJson(original);
        ClientMessage parsed = serializer.fromJson(json, ClientMessage.class);
        RunRequestMessage restored = (RunRequestMessage) parsed;

        assertThat(restored.requestId()).isEqualTo("req-100");
        assertThat(restored.tasks()).hasSize(1);
        assertThat(restored.tasks().get(0)).containsEntry("name", "writer");
        assertThat(restored.tasks().get(0).get("context")).isEqualTo(List.of("$researcher"));
    }
}
