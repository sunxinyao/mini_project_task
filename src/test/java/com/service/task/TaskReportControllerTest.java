package com.service.task;

import com.service.task.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务结果上报接口测试：成功上报、claim_token 校验、失败重试状态机、租约过期重入队。
 */
class TaskReportControllerTest extends IntegrationTestBase {

    private static final Map<String, Object> PAYLOAD = Map.of("report_id", "report-123");

    @Test
    void completeTask_marksSucceededAndStoresResult() {
        String taskId = createTask("report-001", "generate-report", PAYLOAD, 3).path("task_id").asText();
        JsonNode claimData = claim("worker-001").path("data");

        Map<String, Object> body = new HashMap<>();
        body.put("worker_id", "worker-001");
        body.put("claim_token", claimData.path("claim_token").asText());
        body.put("result", Map.of("file_url", "https://example.test/report-123"));
        JsonNode resp = http.post(url("/tasks/" + taskId + "/complete"), body);

        assertThat(resp.path("code").asInt()).isEqualTo(0);
        JsonNode data = resp.path("data");
        assertThat(data.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(data.path("result").path("file_url").asText()).isEqualTo("https://example.test/report-123");
        assertThat(data.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(data.path("claimed_by").asText()).isEqualTo("worker-001");

        // 终态后不再可被领取
        assertThat(claim("worker-002").path("code").asInt()).isEqualTo(204);
    }

    @Test
    void completeTask_withWrongToken_rejectedAndTaskStillRunning() {
        String taskId = createTask("report-002", "generate-report", PAYLOAD, 3).path("task_id").asText();
        JsonNode claimData = claim("worker-001").path("data");
        String realToken = claimData.path("claim_token").asText();

        JsonNode resp = http.post(url("/tasks/" + taskId + "/complete"), Map.of(
                "worker_id", "worker-001",
                "claim_token", "wrong-token",
                "result", Map.of("file_url", "https://example.test/report-123")));

        assertThat(resp.path("code").asInt()).isEqualTo(409);
        JsonNode task = getTaskData(taskId);
        assertThat(task.path("status").asText()).isEqualTo("RUNNING");
        assertThat(task.path("claim_token").asText()).isEqualTo(realToken);
    }

    @Test
    void completeTask_unknownTask_notFound() {
        JsonNode resp = http.post(url("/tasks/task-not-exist/complete"), Map.of(
                "worker_id", "worker-001",
                "claim_token", "any-token"));

        assertThat(resp.path("code").asInt()).isEqualTo(404);
    }

    @Test
    void failTask_requeuesForRetry_andPersistsAttemptAndLastError() {
        String taskId = createTask("report-003", "generate-report", PAYLOAD, 3).path("task_id").asText();
        String firstToken = claim("worker-001").path("data").path("claim_token").asText();

        JsonNode failResp = http.post(url("/tasks/" + taskId + "/fail"), Map.of(
                "worker_id", "worker-001",
                "claim_token", firstToken,
                "error", Map.of("code", "TEMPORARY_ERROR", "message", "Temporary dependency failure")));

        assertThat(failResp.path("code").asInt()).isEqualTo(0);
        JsonNode data = failResp.path("data");
        assertThat(data.path("status").asText()).isEqualTo("QUEUED");
        assertThat(data.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(data.path("last_error").path("code").asText()).isEqualTo("TEMPORARY_ERROR");
        assertThat(data.path("last_error").path("message").asText()).isEqualTo("Temporary dependency failure");
        assertThat(data.path("claim_token").isNull()).isTrue();

        // 重试：可被再次领取，尝试次数 +1，且使用新的 claim_token
        JsonNode reclaim = claim("worker-002");
        assertThat(reclaim.path("code").asInt()).isEqualTo(0);
        assertThat(reclaim.path("data").path("attempt_count").asInt()).isEqualTo(2);
        assertThat(reclaim.path("data").path("claim_token").asText())
                .isNotEqualTo(firstToken)
                .isNotBlank();
    }

    @Test
    void failTask_afterMaxAttempts_marksFailedAndNeverReclaimed() {
        String taskId = createTask("report-004", "generate-report", PAYLOAD, 3).path("task_id").asText();

        // 第 1、2 次失败：重回 QUEUED
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode claimData = claim("worker-001").path("data");
            assertThat(claimData.path("attempt_count").asInt()).isEqualTo(attempt);

            JsonNode failResp = http.post(url("/tasks/" + taskId + "/fail"), Map.of(
                    "worker_id", "worker-001",
                    "claim_token", claimData.path("claim_token").asText(),
                    "error", Map.of("code", "TEMPORARY_ERROR", "message", "attempt " + attempt + " failed")));
            assertThat(failResp.path("data").path("status").asText()).isEqualTo("QUEUED");
        }

        // 第 3 次（达到 max_attempts）失败：FAILED，终止状态
        JsonNode lastClaim = claim("worker-001").path("data");
        assertThat(lastClaim.path("attempt_count").asInt()).isEqualTo(3);

        JsonNode finalFail = http.post(url("/tasks/" + taskId + "/fail"), Map.of(
                "worker_id", "worker-001",
                "claim_token", lastClaim.path("claim_token").asText(),
                "error", Map.of("code", "TEMPORARY_ERROR", "message", "attempt 3 failed")));

        assertThat(finalFail.path("code").asInt()).isEqualTo(0);
        JsonNode data = finalFail.path("data");
        assertThat(data.path("status").asText()).isEqualTo("FAILED");
        assertThat(data.path("attempt_count").asInt()).isEqualTo(3);
        assertThat(data.path("last_error").path("code").asText()).isEqualTo("TEMPORARY_ERROR");

        // 终态任务不再可领取
        assertThat(claim("worker-002").path("code").asInt()).isEqualTo(204);
    }

    @Test
    void leaseExpiry_requeuesTask_andNewWorkerReclaimsWithNewToken() throws Exception {
        String taskId = createTask("report-005", "generate-report", PAYLOAD, 3).path("task_id").asText();
        String firstToken = claim("worker-001").path("data").path("claim_token").asText();

        // test profile 租约 1 秒 + 巡检 200ms：等待定时任务将任务重置回 QUEUED
        JsonNode requeued = awaitTaskStatus(taskId, "QUEUED");
        assertThat(requeued.path("claim_token").isNull()).isTrue();
        assertThat(requeued.path("claimed_by").isNull()).isTrue();
        assertThat(requeued.path("attempt_count").asInt()).isEqualTo(1);

        // 新 Worker 用新 token 重新领取，尝试次数继续累加
        JsonNode reclaim = claim("worker-002");
        assertThat(reclaim.path("code").asInt()).isEqualTo(0);
        assertThat(reclaim.path("data").path("attempt_count").asInt()).isEqualTo(2);
        assertThat(reclaim.path("data").path("claim_token").asText()).isNotEqualTo(firstToken);
    }

    @Test
    void staleTokenAfterLeaseRequeue_cannotCompleteTask() throws Exception {
        String taskId = createTask("report-006", "generate-report", PAYLOAD, 3).path("task_id").asText();
        String staleToken = claim("worker-001").path("data").path("claim_token").asText();

        awaitTaskStatus(taskId, "QUEUED");

        // 原 Worker 携带过期 token 上报成功：被拒绝
        JsonNode resp = http.post(url("/tasks/" + taskId + "/complete"), Map.of(
                "worker_id", "worker-001",
                "claim_token", staleToken,
                "result", Map.of("file_url", "https://example.test/report-123")));

        assertThat(resp.path("code").asInt()).isEqualTo(409);
    }
}
