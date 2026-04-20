package com.istad.stadoor.tunnelagent.service;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class AutoReconnectService {

    private final AgentWebSocketClient wsClient;
    private final AgentSessionHolder session;
    private final HostInfoResolver hostInfo;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public AutoReconnectService(AgentWebSocketClient wsClient,
                                AgentSessionHolder session,
                                HostInfoResolver hostInfo) {
        this.wsClient = wsClient;
        this.session = session;
        this.hostInfo = hostInfo;
    }

    @PostConstruct
    public void init() {
        wsClient.onDisconnect(this::attemptReconnect);
    }

    private void attemptReconnect() {
        if (!session.isLoggedIn()) {
            return;
        }

        scheduleReconnect(1);
    }

    private void scheduleReconnect(int attempt) {
        if (attempt > 10) {
            System.out.println("\n⚠ Auto-reconnect failed. Use register again.");
            return;
        }

        int delay = Math.min(2 * (int) Math.pow(2, attempt - 1), 60);
        System.out.printf("\n🔄 Reconnecting in %ds (attempt %d/10)...%n", delay, attempt);

        scheduler.schedule(() -> {
            try {
                wsClient.connect().block();
                var response = wsClient.sendAndWait(
                    "register",
                    Map.of(
                        "token", session.getToken(),
                        "hostName", hostInfo.getHostName(),
                        "hostPort", hostInfo.getHostPort(),
                        "ipAddress", hostInfo.getIpAddress(),
                        "osType", hostInfo.getOsType()
                    ),
                    10
                ).block();

                if (response != null && "registered".equals(response.getType())) {
                    UUID clientId = UUID.fromString(response.getPayload().get("clientId").toString());
                    session.register(clientId);
                    System.out.println("\n✓ Reconnected automatically!");
                }
            } catch (Exception e) {
                scheduleReconnect(attempt + 1);
            }
        }, delay, TimeUnit.SECONDS);
    }
}
