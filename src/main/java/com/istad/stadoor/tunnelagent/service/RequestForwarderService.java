package com.istad.stadoor.tunnelagent.service;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class RequestForwarderService {

    private static final Logger log = LoggerFactory.getLogger(
            RequestForwarderService.class
    );

    private final AgentWebSocketClient wsClient;

    public RequestForwarderService(AgentWebSocketClient wsClient) {
        this.wsClient = wsClient;
    }

    // ── Init ────────────────────────────────────────────────────
    @PostConstruct
    public void init() {
        wsClient.onPush("http_request", this::handleRequest);
        log.info("✓ RequestForwarderService ready");
    }

    // ── Handle Incoming Request from Server ─────────────────────
    private void handleRequest(WsMessage message) {
        Map<String, Object> payload = message.getPayload();

        // Extract info
        String requestId = message.getRequestId();  // ✅ Keep requestId
        String method    = str(payload, "method");
        String path      = str(payload, "path");
        int    localPort = Integer.parseInt(str(payload, "localPort"));
        String body      = str(payload, "body");

        log.info("📨 Incoming: {} {} -> localhost:{}", method, path, localPort);

        try {
            // ✅ Call local Spring Boot
            WebClient localClient = WebClient.builder()
                    .baseUrl("http://localhost:" + localPort)
                    .build();

            String response = localClient
                    .method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path)
                    .bodyValue(body.isEmpty() ? "" : body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorReturn("Local server error")
                    .block();

            log.info("✅ Got response from localhost:{}", localPort);

            // ✅ Send back with requestId so server can match!
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", 200);
            resp.put("body",   response);

            wsClient.sendAsyncWithId("http_response", requestId, resp);

            log.info("✅ Response sent back: requestId={}", requestId);

        } catch (Exception e) {
            log.error("❌ Forward failed: {}", e.getMessage());

            // ✅ Send error back with requestId
            try {
                wsClient.sendAsyncWithId("http_response", requestId, Map.of(
                        "status", 500,
                        "body",   "Error: " + e.getMessage()
                ));
            } catch (Exception ignored) {
                log.error("❌ Failed to send error response");
            }
        }
    }

    // ── Helper ──────────────────────────────────────────────────
    private String str(Map<String, Object> map, String key) {
        Object val = map != null ? map.get(key) : null;
        return val != null ? val.toString() : "";
    }
}