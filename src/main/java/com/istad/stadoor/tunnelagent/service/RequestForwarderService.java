package com.istad.stadoor.tunnelagent.service;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    // ── Init ──────────────────────────────────────────────────────
    @PostConstruct
    public void init() {
        wsClient.onPush("http_request", this::handleRequest);
        log.info("✓ RequestForwarderService ready");
    }

    // ── Handle Request from Server ────────────────────────────────
    private void handleRequest(WsMessage message) {
        Map<String, Object> payload = message.getPayload();

        String requestId = message.getRequestId();
        String method    = str(payload, "method");
        String path      = str(payload, "path");
        int    localPort = Integer.parseInt(str(payload, "localPort"));
        String body      = str(payload, "body");

        log.info("📨 {} {} -> localhost:{}", method, path, localPort);

        try {
            // ✅ Build WebClient for local app
            WebClient local = WebClient.builder()
                    .baseUrl("http://localhost:" + localPort)
                    .defaultHeader("Accept", "*/*")
                    .defaultHeader("Accept-Encoding", "identity")
                    .codecs(configurer -> configurer
                            .defaultCodecs()
                            .maxInMemorySize(10 * 1024 * 1024) // 10MB
                    )
                    .build();

            // ✅ Call local app
            String response = local
                    .method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(path)
                    .headers(headers -> {
                        if (!body.isEmpty()) {
                            headers.setContentType(MediaType.APPLICATION_JSON);
                        }
                    })
                    .bodyValue(body.isEmpty() ? "" : body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(WebClientResponseException.class, e -> {
                        log.error("❌ HTTP Error: {} {}",
                                e.getStatusCode(), e.getMessage());
                        return reactor.core.publisher.Mono.just(
                                e.getResponseBodyAsString()
                        );
                    })
                    .onErrorResume(e -> {
                        log.error("❌ Error: {}", e.getMessage());
                        return reactor.core.publisher.Mono.just(
                                "{\"error\":\"" + e.getMessage() + "\"}"
                        );
                    })
                    .block();

            log.info("✅ Response from localhost:{}", localPort);

            // ✅ Send back to server with requestId
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", 200);
            resp.put("body",   response);

            wsClient.sendAsyncWithId("http_response", requestId, resp);
            log.info("✅ Sent back: requestId={}", requestId);

        } catch (Exception e) {
            log.error("❌ Forward failed: {}", e.getMessage());
            try {
                wsClient.sendAsyncWithId("http_response", requestId, Map.of(
                        "status", 500,
                        "body",   "{\"error\":\"" + e.getMessage() + "\"}"
                ));
            } catch (Exception ignored) {
                log.error("❌ Failed to send error response");
            }
        }
    }

    // ── Helper ────────────────────────────────────────────────────
    private String str(Map<String, Object> map, String key) {
        Object val = map != null ? map.get(key) : null;
        return val != null ? val.toString() : "";
    }
}