package com.service.task.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Controller 集成测试基类：真实 HTTP 端口 + test profile（H2 内存库）。
 * 每个用例前清空 task / worker 表，保证用例独立、可重复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected final TestHttpClient http = new TestHttpClient();

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.execute("DELETE FROM task");
        jdbcTemplate.execute("DELETE FROM worker");
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** 创建任务并断言成功，返回 data 部分 */
    protected JsonNode createTask(String idempotencyKey, String type, Map<String, Object> payload, int maxAttempts) {
        JsonNode resp = http.post(url("/tasks"),
                Map.of("type", type, "payload", payload, "max_attempts", maxAttempts), idempotencyKey);
        assertThat(resp.path("code").asInt()).as("create task should succeed: %s", resp).isEqualTo(0);
        return resp.path("data");
    }

    /** Worker 抢任务，返回完整响应 {code, msg, data} */
    protected JsonNode claim(String workerId) {
        return http.post(url("/workers/" + workerId + "/tasks/claim"), Map.of());
    }

    /** 查询任务，返回 data 部分 */
    protected JsonNode getTaskData(String taskId) {
        return http.get(url("/tasks/" + taskId)).path("data");
    }

    /** 轮询等待任务进入指定状态（默认 5 秒上限），用于租约过期等异步场景 */
    protected JsonNode awaitTaskStatus(String taskId, String expectedStatus) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        JsonNode data = null;
        while (System.currentTimeMillis() < deadline) {
            data = getTaskData(taskId);
            if (expectedStatus.equals(data.path("status").asText())) {
                return data;
            }
            Thread.sleep(100);
        }
        return fail("task %s did not become %s within 5s, last state=%s", taskId, expectedStatus, data);
    }
}
