package com.ecomagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 验证：
 * 1) Spring 上下文能干净加载（多 ChatModel Bean 下 ChatClient.Builder 装配无歧义）；
 * 2) Web 层可达：/chat/health 与 /actuator/health 正常响应。
 * 不依赖外部 PG / API Key（测试用 H2 内存库 + 空 key）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringAgentApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoads() {
        // 上下文加载失败（如 Bean 歧义、缺依赖）此处会抛异常
    }

    @Test
    void chatHealthEndpointUp() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/chat/health", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"status\":\"UP\""),
                "期望 chatClient 已初始化: " + resp.getBody());
    }

    @Test
    void actuatorHealthUp() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody().contains("\"status\":\"UP\""),
                "期望 actuator 健康为 UP: " + resp.getBody());
    }
}
