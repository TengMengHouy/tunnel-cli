package com.istad.stadoor.tunnelagent.shell;

import com.istad.stadoor.tunnelagent.client.AgentWebSocketClient;
import com.istad.stadoor.tunnelagent.client.TunnelServerClient;
import com.istad.stadoor.tunnelagent.model.TunnelResponse;
import com.istad.stadoor.tunnelagent.model.WsMessage;
import com.istad.stadoor.tunnelagent.service.AgentSessionHolder;
import org.springframework.shell.Availability;
import org.springframework.shell.standard.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ShellComponent
@ShellCommandGroup("Tunnel")
public class TunnelCommands {

    private final TunnelServerClient client;
    private final AgentWebSocketClient wsClient;
    private final AgentSessionHolder session;

    public TunnelCommands(TunnelServerClient client,
                          AgentWebSocketClient wsClient,
                          AgentSessionHolder session) {
        this.client = client;
        this.wsClient = wsClient;
        this.session = session;
    }

    @ShellMethod(key = "tunnel create", value = "Create one or more tunnels (space-separated base paths)")
    @ShellMethodAvailability("regRequired")
    public String create(String... basePaths) {
        if (basePaths == null || basePaths.length == 0) {
            return "✗ Provide at least one base path. Usage: tunnel create /api [/web ...]";
        }

        if (basePaths.length == 1) {
            return wsClient.sendAndWait("tunnel_create", Map.of("basePath", basePaths[0]), 10)
                    .flatMap(r -> handleCreateResponse(basePaths[0], r))
                    .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                    .block();
        }

        return Flux.fromArray(basePaths)
                .flatMap(path ->
                        wsClient.sendAndWait("tunnel_create", Map.of("basePath", path), 10)
                                .flatMap(r -> handleCreateResponse(path, r))
                                .onErrorResume(e -> Mono.just("✗ [" + path + "] " + e.getMessage()))
                )
                .collectList()
                .map(results -> {
                    var sb = new StringBuilder(String.format("Created %d tunnel(s):%n", results.size()));
                    sb.append("─".repeat(60)).append("\n");
                    results.forEach(line -> sb.append(line).append("\n"));
                    return sb.toString().stripTrailing();
                })
                .block();
    }

    private Mono<String> handleCreateResponse(String basePath, WsMessage r) {
        if ("error".equals(r.getType())) {
            return Mono.just("✗ [" + basePath + "] " + r.getPayload().get("error"));
        }
        return Mono.just(String.format(
                "✓ Tunnel created%n  Base Path:  %s%n  Tunnel ID:  %s",
                basePath, r.getPayload().get("tunnelId")
        ));
    }

    @ShellMethod(key = "tunnel list", value = "List tunnels")
    @ShellMethodAvailability("loginRequired")
    public String list() {
        return client.listTunnels(session.getUserId())
                .map(this::format)
                .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                .block();
    }

    @ShellMethod(key = "tunnel info", value = "Tunnel details")
    @ShellMethodAvailability("loginRequired")
    public String info(@ShellOption("--id") String id) {
        return client.getTunnel(UUID.fromString(id))
                .map(t -> String.format(
                        "┌──────────────────────────────────────────────────┐%n" +
                                "│ Tunnel: %-42s│%n" +
                                "│ User:   %-42s│%n" +
                                "│ Path:   %-42s│%n" +
                                "│ Active: %-42s│%n" +
                                "│ Since:  %-42s│%n" +
                                "└──────────────────────────────────────────────────┘",
                        t.tunnelId(), t.userId(), t.basePath(),
                        t.active() ? "YES ●" : "NO ○", t.createdAt()
                ))
                .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                .block();
    }

    @ShellMethod(key = "tunnel deactivate", value = "Deactivate tunnel")
    @ShellMethodAvailability("loginRequired")
    public String deactivate(@ShellOption("--id") String id) {
        return client.deactivateTunnel(UUID.fromString(id))
                .thenReturn("✓ Deactivated: " + id)
                .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                .block();
    }

    private String format(List<TunnelResponse> tunnels) {
        if (tunnels.isEmpty()) return "No tunnels.";
        var sb = new StringBuilder(String.format("%-36s  %-15s  %-8s  %-20s%n", "ID", "PATH", "ACTIVE", "CREATED"));
        sb.append("─".repeat(85)).append("\n");
        tunnels.forEach(t -> sb.append(String.format("%-36s  %-15s  %-8s  %-20s%n",
                t.tunnelId(), t.basePath(), t.active() ? "● YES" : "○ NO", t.createdAt())));
        sb.append(String.format("%nTotal: %d", tunnels.size()));
        return sb.toString();
    }

    public Availability loginRequired() {
        return session.isLoggedIn() ? Availability.available() : Availability.unavailable("login first");
    }

    public Availability regRequired() {
        return session.isRegistered() ? Availability.available() : Availability.unavailable("not registered");
    }
}