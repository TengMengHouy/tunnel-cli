package com.istad.stadoor.tunnelagent.shell;

import com.istad.stadoor.tunnelagent.client.TunnelServerClient;
import com.istad.stadoor.tunnelagent.model.TargetResponse;
import com.istad.stadoor.tunnelagent.service.HostInfoResolver;
import org.springframework.shell.standard.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@ShellComponent
@ShellCommandGroup("Target")
public class TargetCommands {

    private final TunnelServerClient client;
    private final HostInfoResolver hostInfoResolver; // Inject HostInfo

    public TargetCommands(TunnelServerClient client, HostInfoResolver hostInfoResolver) {
        this.client = client;
        this.hostInfoResolver = hostInfoResolver;
    }

    @ShellMethod(key = "target add", value = "Add target to tunnel")
    public String add(String tunnelId, int port) {
        UUID tunnelUuid = UUID.fromString(tunnelId);
        String localIp = hostInfoResolver.getIpAddress(); // Read auto

        return client.addTarget(tunnelUuid, localIp, port)
                .map(r -> String.format(
                        "✓ Target added%n" +
                                "  ID:   %s%n" +
                                "  IP:   %s%n" + // Print IP
                                "  Port: %d%n" +
                                "  URL:  %s%n" +
                                "  Key:  %s",
                        r.targetId(),
                        r.ipAddress(),
                        r.localPort(),
                        r.publicUrl(),
                        r.key()
                ))
                .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                .block();
    }

    @ShellMethod(key = "target list", value = "List targets")
    public String list(String tunnelId) {
        UUID tunnelUuid = UUID.fromString(tunnelId);

        return client.listTargets(tunnelUuid)
                .map(this::format)
                .onErrorResume(e -> Mono.just("✗ " + e.getMessage()))
                .block();
    }

    private String format(List<TargetResponse> targets) {
        if (targets.isEmpty()) return "No targets.";
        var sb = new StringBuilder(String.format("%-36s  %-6s  %-15s  %-10s  %-40s%n", "ID", "PORT", "IP", "KEY", "URL"));
        sb.append("─".repeat(110)).append("\n");

        targets.forEach(t -> sb.append(String.format("%-36s  %-6d  %-15s  %-10s  %-40s%n",
                t.targetId(), t.localPort(), t.ipAddress(), t.key(), t.publicUrl())));

        sb.append(String.format("%nTotal: %d", targets.size()));
        return sb.toString();
    }
}