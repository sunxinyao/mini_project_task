package com.service.task;

import com.service.task.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务创建 / 查询 / 幂等提交 接口测试。
 */
class TaskControllerTest extends IntegrationTestBase {

    private static final Map<String, Object> PAYLOAD = Map.of("report_id", "report-123");

    @Test
    void createTask_thenQuery_returnsQueuedTask() {
        JsonNode created = createTask("request-001", "generate-report", PAYLOAD, 3);

//        assertThat(created.path("task_id").asText()).startsWith("task-");
//        assertThat(created.path("type").asText()).isEqualTo("generate-report");
//        assertThat(created.path("payload").path("report_id").asText()).isEqualTo("report-123");
//        assertThat(created.path("status").asText()).isEqualTo("QUEUED");
//        assertThat(created.path("attempt_count").asInt()).isZero();
//        assertThat(created.path("max_attempts").asInt()).isEqualTo(3);
//        assertThat(created.path("created_at").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");

        JsonNode queried = getTaskData(created.path("task_id").asText());
//        assertThat(queried.path("task_id").asText()).isEqualTo(created.path("task_id").asText());
        assertThat(queried.path("status").asText()).isEqualTo("QUEUED");
//        assertThat(queried.path("attempt_count").asInt()).isZero();
//        assertThat(queried.path("last_error").isNull()).isTrue();
//        assertThat(queried.path("created_at").isTextual()).isTrue();
//        assertThat(queried.path("updated_at").isTextual()).isTrue();
    }

    @Test
    void createTask_withSameIdempotencyKeyAndSameBody_returnsOriginalTaskWithoutDuplicate() {
        JsonNode first = createTask("idem-001", "generate-report", PAYLOAD, 3);

        JsonNode replay = http.post(url("/tasks"),
                Map.of("type", "generate-report", "payload", PAYLOAD, "max_attempts", 3), "idem-001").path("data");

        assertThat(replay.path("task_id").asText()).isEqualTo(first.path("task_id").asText());
        assertThat(replay.path("created_at").asText()).isEqualTo(first.path("created_at").asText());
        Integer taskRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        assertThat(taskRows).isEqualTo(1);
    }

    @Test
    void createTask_withSameIdempotencyKeyButDifferentBody_returnsConflict() {
        createTask("idem-002", "generate-report", PAYLOAD, 3);

        JsonNode conflict = http.post(url("/tasks"),
                Map.of("type", "generate-report", "payload", PAYLOAD, "max_attempts", 5), "idem-002");

        assertThat(conflict.path("code").asInt()).isEqualTo(409);
        assertThat(conflict.path("msg").asText()).contains("Idempotency");
        Integer taskRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM task", Integer.class);
        assertThat(taskRows).isEqualTo(1);
    }

    @Test
    void createTask_withoutIdempotencyKey_rejected() {
        JsonNode resp = http.post(url("/tasks"),
                Map.of("type", "generate-report", "payload", PAYLOAD, "max_attempts", 3));

        assertThat(resp.path("code").asInt()).isEqualTo(400);
        assertThat(resp.path("msg").asText()).contains("Idempotency-Key");
    }

    @Test
    void createTask_withBlankType_rejected() {
        JsonNode resp = http.post(url("/tasks"),
                Map.of("type", "", "payload", PAYLOAD, "max_attempts", 3), "idem-003");

        assertThat(resp.path("code").asInt()).isEqualTo(400);
    }

    @Test
    void getTask_unknownTask_returnsNotFound() {
        JsonNode resp = http.get(url("/tasks/task-not-exist"));

        assertThat(resp.path("code").asInt()).isEqualTo(404);
        assertThat(resp.path("msg").asText()).contains("not found");
    }
}
