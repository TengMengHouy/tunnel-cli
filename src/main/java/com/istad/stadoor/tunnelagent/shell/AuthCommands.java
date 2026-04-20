package com.istad.stadoor.tunnelagent.shell;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.client.TunnelServerClient;
import com.istad.stadoor.tunnelagent.service.AgentSessionHolder;
import com.istad.stadoor.tunnelagent.service.HostInfoResolver;
import org.springframework.shell.standard.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@ShellComponent
@ShellCommandGroup("Authentication")
public class AuthCommands {

    private final TunnelServerClient client;
    private final AgentSessionHolder session;
    private final AgentWebSocketClient wsClient;
    private final HostInfoResolver hostInfo;

    public AuthCommands(TunnelServerClient client,
                        AgentSessionHolder session,
                        AgentWebSocketClient wsClient,
                        HostInfoResolver hostInfo) {
        this.client = client;
        this.session = session;
        this.wsClient = wsClient;
        this.hostInfo = hostInfo;
    }

    @ShellMethod(key = "login", value = "Login with IAM token and auto-register agent")
    public String login(@ShellOption("--token") String token) {
        return client.login(token)
                .flatMap(r -> {
                    session.login(r.userId(), r.token());
                    String shortToken = r.token().substring(0, Math.min(12, r.token().length())) + "...";
                    String loginInfo = String.format("✓ Logged in%n  User ID: %s%n  Token:   %s", r.userId(), shortToken);

                    if (session.isRegistered()) {
                        return Mono.just(loginInfo + String.format("%n✓ Already registered%n  Client ID: %s", session.getClientId()));
                    }

                    return wsClient.connect()
                            .then(wsClient.sendAndWait("register", Map.of(
                                    "token", session.getToken(),
                                    "hostName", hostInfo.getHostName(),
                                    "hostPort", hostInfo.getHostPort(),
                                    "ipAddress", hostInfo.getIpAddress(),
                                    "osType", hostInfo.getOsType()
                            ), 10))
                            .flatMap(msg -> {
                                if ("error".equals(msg.getType())) {
                                    return Mono.just(loginInfo + String.format(
                                            "%n✗ Auto-register failed: %s%n  Run 'register' manually to retry.",
                                            msg.getPayload().get("error")));
                                }
                                UUID clientId = UUID.fromString(msg.getPayload().get("clientId").toString());
                                session.register(clientId);
                                return Mono.just(loginInfo + String.format(
                                        "%n✓ Agent registered%n  Client ID: %s%n  Host: %s (%s)%n  WebSocket: CONNECTED ●",
                                        clientId, hostInfo.getHostName(), hostInfo.getIpAddress()));
                            })
                            .onErrorResume(e -> {
                                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                                return wsClient.disconnect().thenReturn(loginInfo + String.format(
                                        "%n✗ Auto-register failed: %s%n  Run 'register' manually to retry.", msg));
                            });
                })
                .onErrorResume(e -> Mono.just("✗ Login failed: " + e.getMessage()))
                .block();
    }

    @ShellMethod(key = "logout", value = "Logout and disconnect agent")
    public String logout() {
        if (!session.isLoggedIn()) return "Not logged in.";
        String userId = session.getUserId().toString();
        if (wsClient.isConnected()) wsClient.disconnect().block();
        session.logout();
        return String.format("✓ Logged out (User: %s).", userId);
    }

    @ShellMethod(key = "whoami", value = "Show session info")
    public String whoami() {
        if (!session.isLoggedIn()) return "Not logged in.";
        return String.format(
                "User ID:   %s%nClient ID: %s%nStatus:    %s%nWebSocket: %s",
                session.getUserId(),
                session.isRegistered() ? session.getClientId() : "(not registered)",
                session.isRegistered() ? "REGISTERED" : "LOGGED_IN_ONLY",
                wsClient.isConnected() ? "CONNECTED ●" : "DISCONNECTED ○"
        );
    }
}