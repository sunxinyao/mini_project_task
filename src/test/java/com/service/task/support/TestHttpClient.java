package com.service.task.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 测试用极简 HTTP 客户端：直接走真实 HTTP 端口，断言基于原始 JsonNode，
 * 从而完整校验收发双方的 JSON 契约（snake_case 字段、包装结构等）。
 */
public class TestHttpClient {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public JsonNode post(String url, Object body, String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return exchange(builder.build());
    }

    public JsonNode post(String url, Object body) {
        return post(url, body, null);
    }

    public JsonNode get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return exchange(request);
    }

    private JsonNode exchange(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("http request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http request interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("unexpected http status " + response.statusCode()
                    + " from " + request.uri() + ", body=" + response.body());
        }
        return mapper.readTree(response.body());
    }
}
