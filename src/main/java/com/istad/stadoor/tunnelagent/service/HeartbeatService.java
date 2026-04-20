package com.istad.stadoor.tunnelagent.service;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    private final AgentSessionHolder session;
    private final AgentWebSocketClient wsClient;
    private final AgentProperties properties;

    public HeartbeatService(AgentSessionHolder session,
                            AgentWebSocketClient wsClient,
                            AgentProperties properties) {
        this.session = session;
        this.wsClient = wsClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${agent.heartbeat-interval:30}000")
    public void sendHeartbeat() {
        if (!session.isRegistered() || !wsClient.isConnected()) {
            return;
        }

        try {
            wsClient.sendAsync("heartbeat", Map.of("ttl", properties.getHeartbeatInterval()));
            log.debug("♥ Heartbeat sent for client {}", session.getClientId());
        } catch (Exception e) {
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }
}
