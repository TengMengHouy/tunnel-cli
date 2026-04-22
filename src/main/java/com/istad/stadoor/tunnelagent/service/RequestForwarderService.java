package com.istad.stadoor.tunnelagent.service;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequestForwarderService {

    private final AgentWebSocketClient wsClient;

    @PostConstruct
    public void init() {
        wsClient.onPush("http_request", this::handleRequest);
        log.info("✓ RequestForwarderService ready");
    }

    private void handleRequest(WsMessage message) {
        Map<String, Object> payload = message.getPayload();
        String requestId = message.getRequestId();

        String method    = str(payload, "method");
        String path      = str(payload, "path");
        int    localPort = parsePort(payload);   // ✅ Fixed
        String body      = str(payload, "body");

        @SuppressWarnings("unchecked")
        Map<String, String> inHeaders = payload.get("headers") instanceof Map
                ? (Map<String, String>) payload.get("headers")
                : Map.of();

        log.info("📨 {} {} -> localhost:{}", method, path, localPort);

        try {
            WebClient local = WebClient.builder()
                    .baseUrl("http://localhost:" + localPort)
                    .codecs(c -> c.defaultCodecs()
                            .maxInMemorySize(50 * 1024 * 1024)) // 50MB
                    .build();

            Set<String> skipHeaders = Set.of(
                    "host", "content-length",
                    "transfer-encoding", "connection", "upgrade"
            );

            // Build request spec
            var requestSpec = local
                    .method(HttpMethod.valueOf(method))
                    .uri(path)
                    .headers(h -> inHeaders.forEach((k, v) -> {
                        if (!skipHeaders.contains(k.toLowerCase())) {
                            h.set(k, v);
                        }
                    }));

            // Add body if present
            if (!body.isEmpty()) {
                requestSpec.bodyValue(body);
            }

            // Execute request
            ResponseEntity<byte[]> response = requestSpec
                    .retrieve()
                    .toEntity(byte[].class)
                    .onErrorResume(WebClientResponseException.class, e -> {
                        log.warn("⚠️ HTTP error: {} {}", e.getStatusCode(), path);

                        // ✅ Fixed: use getHeaders() instead of getResponseHeaders()
                        return reactor.core.publisher.Mono.just(
                                ResponseEntity.status(e.getStatusCode())
                                        .headers(e.getHeaders())   // ✅ Fixed
                                        .body(e.getResponseBodyAsByteArray())
                        );
                    })
                    .onErrorResume(e -> {
                        log.error("❌ Forward error: {}", e.getMessage());
                        byte[] errBody = ("{\"error\":\"" + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8);
                        return reactor.core.publisher.Mono.just(
                                ResponseEntity.status(502).body(errBody)
                        );
                    })
                    .block();

            if (response == null) {
                sendError(requestId, 502, "Null response from local app");
                return;
            }

            int    statusCode    = response.getStatusCode().value();
            byte[] responseBody  = response.getBody();

            // Collect response headers
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            response.getHeaders().forEach((k, values) -> {
                // ✅ Fixed: use getFirst() instead of get(0)
                String first = response.getHeaders().getFirst(k);
                if (first != null) {
                    responseHeaders.put(k, first);
                }
            });

            // Detect content type
            String contentType = responseHeaders.getOrDefault(
                    "content-type",
                    responseHeaders.getOrDefault("Content-Type", "")
            );
            boolean isBinary = isBinaryContent(contentType);

            log.info("✅ Response: status={} | size={}bytes | binary={}",
                    statusCode,
                    responseBody != null ? responseBody.length : 0,
                    isBinary);

            // Build response payload
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("requestId", requestId);
            resp.put("status",    statusCode);
            resp.put("headers",   responseHeaders);
            resp.put("isBinary",  isBinary);

            if (isBinary && responseBody != null) {
                resp.put("bodyBase64", Base64.getEncoder().encodeToString(responseBody));
                resp.put("body",       null);
            } else {
                resp.put("body", responseBody != null
                        ? new String(responseBody, StandardCharsets.UTF_8)
                        : "");
                resp.put("bodyBase64", null);
            }

            wsClient.sendAsyncWithId("http_response", requestId, resp);
            log.info("✅ Sent response | requestId={}", requestId);

        } catch (Exception e) {
            log.error("❌ Unexpected error: {}", e.getMessage(), e);
            sendError(requestId, 500, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private boolean isBinaryContent(String contentType) {
        if (contentType == null || contentType.isEmpty()) return false;
        String ct = contentType.toLowerCase();
        return ct.startsWith("image/")
                || ct.startsWith("audio/")
                || ct.startsWith("video/")
                || ct.contains("octet-stream")
                || ct.contains("pdf")
                || ct.contains("zip")
                || ct.contains("font");
    }

    private void sendError(String requestId, int status, String message) {
        try {
            wsClient.sendAsyncWithId("http_response", requestId, Map.of(
                    "requestId",  requestId,
                    "status",     status,
                    "headers",    Map.of("content-type", "application/json"),
                    "body",       "{\"error\":\"" + message + "\"}",
                    "bodyBase64", "",
                    "isBinary",   false
            ));
        } catch (Exception ex) {
            log.error("❌ Failed to send error response: {}", ex.getMessage());
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map != null ? map.get(key) : null;
        return val != null ? val.toString() : "";
    }

    // ✅ Fixed: dedicated method for parsing localPort
    private int parsePort(Map<String, Object> payload) {
        try {
            Object val = payload != null ? payload.get("localPort") : null;
            return val != null ? Integer.parseInt(val.toString()) : 3000;
        } catch (NumberFormatException e) {
            log.warn("⚠️ Invalid localPort, defaulting to 3000");
            return 3000;
        }
    }
}