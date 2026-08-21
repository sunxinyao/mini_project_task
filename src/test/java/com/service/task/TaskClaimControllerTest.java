package com.service.task;

import com.service.task.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Worker 领取任务接口测试：领取字段、空队列 204、FIFO 顺序、并发竞争下最多一个成功。
 */
class TaskClaimControllerTest extends IntegrationTestBase {

    private static final Map<String, Object> PAYLOAD = Map.of("report_id", "report-123");

    @Test
    void claimTask_success_marksRunningWithTokenAndLease() {
        JsonNode created = createTask("claim-001", "generate-report", PAYLOAD, 3);

        Instant before = Instant.now();
        JsonNode resp = claim("worker-001");
        Instant after = Instant.now();

        assertThat(resp.path("code").asInt()).isEqualTo(0);
        JsonNode data = resp.path("data");
        assertThat(data.path("task_id").asText()).isEqualTo(created.path("task_id").asText());
        assertThat(data.path("type").asText()).isEqualTo("generate-report");
        assertThat(data.path("payload").path("report_id").asText()).isEqualTo("report-123");
        assertThat(data.path("status").asText()).isEqualTo("RUNNING");
        assertThat(data.path("attempt_count").asInt()).isEqualTo(1);
        assertThat(data.path("claimed_by").asText()).isEqualTo("worker-001");
        assertThat(data.path("claim_token").asText()).isNotBlank();

        // test profile 下租约为 1 秒：lease_expires_at 应落在 (before, before+3s] 内
        Instant lease = Instant.parse(data.path("lease_expires_at").asText());
        assertThat(lease).isAfter(before);
        assertThat(lease).isBefore(before.plusSeconds(3));

        // Worker 首次领取自动注册
        Integer workerRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM worker WHERE worker_id = 'worker-001'", Integer.class);
        assertThat(workerRows).isEqualTo(1);
    }

    @Test
    void claimTask_whenQueueEmpty_returns204NoTask() {
        JsonNode resp = claim("worker-001");

        assertThat(resp.path("code").asInt()).isEqualTo(204);
        assertThat(resp.path("msg").asText()).isEqualTo("No task");
        assertThat(resp.path("data").isNull()).isTrue();
    }

    @Test
    void claimTask_deliversTasksInFifoOrder() {
        String first = createTask("fifo-1", "job-a", PAYLOAD, 3).path("task_id").asText();
        String second = createTask("fifo-2", "job-b", PAYLOAD, 3).path("task_id").asText();
        String third = createTask("fifo-3", "job-c", PAYLOAD, 3).path("task_id").asText();

        String claim1 = claim("worker-001").path("data").path("task_id").asText();
        String claim2 = claim("worker-002").path("data").path("task_id").asText();
        String claim3 = claim("worker-003").path("data").path("task_id").asText();

        assertThat(List.of(claim1, claim2, claim3)).containsExactly(first, second, third);
    }

    /**
     * 多worker竞争
     * @throws Exception
     */
    @Test
    void concurrentClaim_twoWorkers_exactlyOneSucceeds() throws Exception {
        String taskId = createTask("concurrent-001", "generate-report", PAYLOAD, 3).path("task_id").asText();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<JsonNode> workerA = pool.submit(claimConcurrently("worker-a", barrier));
            Future<JsonNode> workerB = pool.submit(claimConcurrently("worker-b", barrier));

            JsonNode respA = workerA.get(15, TimeUnit.SECONDS);
            JsonNode respB = workerB.get(15, TimeUnit.SECONDS);

            // 只有一个任务：两个 Worker 并发领取，必须恰好一个成功、一个拿到 204 No task
            List<Integer> codes = Stream.of(respA, respB).map(r -> r.path("code").asInt()).sorted().toList();
            assertThat(codes).containsExactly(0, 204);

            JsonNode winner = respA.path("code").asInt() == 0 ? respA : respB;
            JsonNode loser = respA.path("code").asInt() == 0 ? respB : respA;
            assertThat(loser.path("msg").asText()).isEqualTo("No task");

            assertThat(winner.path("data").path("task_id").asText()).isEqualTo(taskId);
            assertThat(winner.path("data").path("status").asText()).isEqualTo("RUNNING");
            assertThat(winner.path("data").path("attempt_count").asInt()).isEqualTo(1);

            JsonNode task = getTaskData(taskId);
            assertThat(task.path("status").asText()).isEqualTo("RUNNING");
            assertThat(task.path("attempt_count").asInt()).isEqualTo(1);
            assertThat(task.path("claimed_by").asText()).isIn("worker-a", "worker-b");

            Integer dbAttempts = jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM task WHERE task_id = ?", Integer.class, taskId);
            assertThat(dbAttempts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<JsonNode> claimConcurrently(String workerId, CyclicBarrier barrier) {
        return () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return claim(workerId);
        };
    }
}
