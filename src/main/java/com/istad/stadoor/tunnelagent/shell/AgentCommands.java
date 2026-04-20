package com.istad.stadoor.tunnelagent.shell;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import com.istad.stadoor.tunnelagent.service.AgentSessionHolder;
import com.istad.stadoor.tunnelagent.service.HostInfoResolver;
import org.springframework.shell.Availability;
import org.springframework.shell.standard.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@ShellComponent
@ShellCommandGroup("Agent")
public class AgentCommands {

    private final AgentWebSocketClient wsClient;
    private final AgentSessionHolder session;
    private final HostInfoResolver hostInfo;

    public AgentCommands(AgentWebSocketClient wsClient,
                         AgentSessionHolder session,
                         HostInfoResolver hostInfo) {
        this.wsClient = wsClient;
        this.session = session;
        this.hostInfo = hostInfo;
    }

    @ShellMethod(key = "register", value = "Register this client runtime")
    @ShellMethodAvailability("loginRequired")
    public String register() {
        if (session.isRegistered()) {
            return "Already registered: " + session.getClientId();
        }

        return wsClient.connect()
                .then(wsClient.sendAndWait(
                        "register",
                        Map.of(
                                "token", session.getToken(),
                                "hostName", hostInfo.getHostName(),
                                "hostPort", hostInfo.getHostPort(),
                                "ipAddress", hostInfo.getIpAddress(),
                                "osType", hostInfo.getOsType()
                        ),
                        10
                ))
                .flatMap(this::handleRegisterResponse)
                .onErrorResume(e -> {
                    e.printStackTrace();
                    String message = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                    return wsClient.disconnect()
                            .thenReturn("✗ Failed: " + message);
                })
                .block();
    }

    @ShellMethod(key = "disconnect", value = "Disconnect client")
    @ShellMethodAvailability("registrationRequired")
    public String disconnect() {
        UUID id = session.getClientId();

        return wsClient.disconnect()
                .then(Mono.fromRunnable(session::unregister))
                .thenReturn("✓ Disconnected: " + id)
                .onErrorResume(e -> Mono.just("✗ Failed: " + e.getMessage()))
                .block();
    }

    @ShellMethod(key = "status", value = "Show client runtime status")
    public String status() {
        return String.format(
                "Client ID: %s%nConnected: %s%nUser ID: %s",
                session.isRegistered() ? session.getClientId() : "(none)",
                wsClient.isConnected() ? "YES ●" : "NO ○",
                session.isLoggedIn() ? session.getUserId() : "(not logged in)"
        );
    }

    private Mono<String> handleRegisterResponse(WsMessage r) {
        if ("error".equals(r.getType())) {
            return Mono.just("✗ " + r.getPayload().get("error"));
        }

        UUID clientId = UUID.fromString(r.getPayload().get("clientId").toString());
        session.register(clientId);

        return Mono.just(String.format(
                "✓ Client registered%n" +
                        "  Client ID: %s%n" +
                        "  Host: %s (%s)%n" +
                        "  Port: %s (no listener)%n" +
                        "  WebSocket: CONNECTED ●",
                clientId,
                hostInfo.getHostName(),
                hostInfo.getIpAddress(),
                hostInfo.getHostPort()
        ));
    }

    public Availability loginRequired() {
        return session.isLoggedIn()
                ? Availability.available()
                : Availability.unavailable("login first");
    }

    public Availability registrationRequired() {
        return session.isRegistered()
                ? Availability.available()
                : Availability.unavailable("not registered");
    }
}