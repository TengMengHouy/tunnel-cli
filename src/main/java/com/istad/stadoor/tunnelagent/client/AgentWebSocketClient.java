package com.istad.stadoor.tunnelagent.client;

import com.istad.stadoor.tunnelagent.config.AgentProperties;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class AgentWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketClient.class);

    private final AgentProperties properties;
    private final ObjectMapper    mapper;

    private WebSocketSession session;
    private volatile boolean connected             = false;
    private volatile boolean intentionalDisconnect = false;

    private final Map<String, CompletableFuture<WsMessage>> pendingRequests
            = new ConcurrentHashMap<>();
    private final Map<String, Consumer<WsMessage>> pushListeners
            = new ConcurrentHashMap<>();

    private Runnable onDisconnectCallback;

    public AgentWebSocketClient(AgentProperties properties) {
        this.properties = properties;
        this.mapper     = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
    }

    // ── Connect ───────────────────────────────────────────────────
    public Mono<Void> connect() {
        return Mono.fromCallable(() -> {
            intentionalDisconnect = false;

            String baseUrl = properties.getServerUrl().replaceAll("/+$", "");
            String wsUrl   = baseUrl
                    .replace("http://",  "ws://")
                    .replace("https://", "wss://")
                    + "/agent-ws"; // ✅ Changed from /ws/agent to /agent-ws

            System.out.println("Connecting WebSocket to: " + wsUrl);

            try {
                var client = new StandardWebSocketClient();
                this.session = client.execute(
                        new Handler(),
                        new WebSocketHttpHeaders(),
                        URI.create(wsUrl)
                ).get(10, TimeUnit.SECONDS);

                this.connected = true;
                System.out.println("WebSocket connected successfully.");
                return (Void) null;

            } catch (Exception e) {
                System.err.println("WebSocket connection failed:");
                e.printStackTrace();
                throw e;
            }
        });
    }

    // ── Send and Wait ─────────────────────────────────────────────
    public Mono<WsMessage> sendAndWait(
            String              type,
            Map<String, Object> payload,
            long                timeoutSec
    ) {
        return Mono.create(sink -> {
            String reqId  = UUID.randomUUID().toString();
            var    future = new CompletableFuture<WsMessage>();
            pendingRequests.put(reqId, future);

            try {
                String json = mapper.writeValueAsString(
                        new WsMessage(type, reqId, payload)
                );
                session.sendMessage(new TextMessage(json));

                future.orTimeout(timeoutSec, TimeUnit.SECONDS)
                        .whenComplete((r, e) -> {
                            pendingRequests.remove(reqId);
                            if (e != null) sink.error(e);
                            else           sink.success(r);
                        });

            } catch (Exception e) {
                pendingRequests.remove(reqId);
                sink.error(e);
            }
        });
    }

    // ── Send Async ────────────────────────────────────────────────
    public void sendAsync(
            String              type,
            Map<String, Object> payload
    ) throws Exception {
        session.sendMessage(new TextMessage(
                mapper.writeValueAsString(
                        new WsMessage(type, null, payload)
                )
        ));
    }

    // ── Send Async With RequestId ─────────────────────────────────
    public void sendAsyncWithId(
            String              type,
            String              requestId,
            Map<String, Object> payload
    ) throws Exception {
        String json = mapper.writeValueAsString(
                new WsMessage(type, requestId, payload)
        );
        session.sendMessage(new TextMessage(json));
        log.debug("✓ sendAsyncWithId: type={} | requestId={}", type, requestId);
    }

    // ── Push Listener ─────────────────────────────────────────────
    public void onPush(String type, Consumer<WsMessage> listener) {
        pushListeners.put(type, listener);
    }

    // ── Disconnect Callback ───────────────────────────────────────
    public void onDisconnect(Runnable callback) {
        this.onDisconnectCallback = callback;
    }

    // ── Disconnect ────────────────────────────────────────────────
    public Mono<Void> disconnect() {
        return Mono.fromRunnable(() -> {
            intentionalDisconnect = true;
            if (session != null && session.isOpen()) {
                try { session.close(); } catch (Exception ignored) {}
            }
            connected = false;
        });
    }

    // ── Is Connected ──────────────────────────────────────────────
    public boolean isConnected() {
        return connected && session != null && session.isOpen();
    }

    // ── WebSocket Handler ─────────────────────────────────────────
    private class Handler extends TextWebSocketHandler {

        @Override
        protected void handleTextMessage(
                WebSocketSession s,
                TextMessage      message
        ) throws Exception {
            WsMessage msg = mapper.readValue(
                    message.getPayload(), WsMessage.class
            );

            // Check pending requests first
            if (msg.getRequestId() != null) {
                var future = pendingRequests.get(msg.getRequestId());
                if (future != null) {
                    future.complete(msg);
                    return;
                }
            }

            // Check push listeners
            var listener = pushListeners.get(msg.getType());
            if (listener != null) {
                listener.accept(msg);
            } else {
                log.debug("Unhandled push: {}", msg.getType());
            }
        }

        @Override
        public void afterConnectionClosed(
                WebSocketSession s,
                CloseStatus      status
        ) {
            connected = false;
            log.info("WS closed: {}", status);
            if (!intentionalDisconnect && onDisconnectCallback != null) {
                onDisconnectCallback.run();
            }
        }
    }
}