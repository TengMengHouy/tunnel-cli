package kh.edu.istad.stadoor.tunnelagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Command(
        name = "authgate",
        description = "Authgate tunnel client.",
        mixinStandardHelpOptions = true,
        version = "authgate-cli 0.6.0",
        subcommands = {
                AuthgateCli.LoginCommand.class,
                AuthgateCli.LogoutCommand.class,
                AuthgateCli.StatusCommand.class,
                AuthgateCli.ConfigCommand.class,
                AuthgateCli.TunnelCommand.class,
                AuthgateCli.AgentCommand.class
        }
)
public class AuthgateCli implements Callable<Integer> {

    static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Path    DEFAULT_CONFIG_DIR =
            Path.of(System.getProperty("user.home"), ".authgate");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    static final int RECONNECT_DELAY_SEC = 5;
    static final int REFRESH_MARGIN_SEC  = 300;

    @Option(names = "--server", description = "Tunnel server base URL.")
    String serverUrl;

    @Option(names = "--config-dir", description = "Config directory. Defaults to ~/.authgate.")
    Path configDir = DEFAULT_CONFIG_DIR;

    // ── server / SSH resolution ──────────────────────────────────────────────
    String resolvedServer() throws IOException {
        if (serverUrl != null && !serverUrl.isBlank()) return normalizeUrl(serverUrl);
        String saved = readConfig().path("serverUrl").asText(null);
        return (saved != null && !saved.isBlank()) ? saved : "http://192.168.1.248:8080";
    }

    String resolvedSshHost() throws IOException {
        String saved = readConfig().path("sshHost").asText(null);
        if (saved != null && !saved.isBlank()) return saved;
        URI uri = URI.create(resolvedServer());
        return uri.getHost() != null ? uri.getHost() : "192.168.1.248";
    }

    int resolvedSshPort() throws IOException {
        return readConfig().path("sshPort").asInt(2222);
    }

    // ── config helpers ───────────────────────────────────────────────────────
    Path configFile()  { return configDir.resolve("config.json"); }
    Path sessionFile() { return configDir.resolve("session.json"); }

    JsonNode readConfig() throws IOException {
        return Files.exists(configFile()) ? JSON.readTree(configFile().toFile())
                : JSON.createObjectNode();
    }

    void writeConfig(Map<String, Object> updates) throws IOException {
        Files.createDirectories(configDir);
        Map<String, Object> merged = new LinkedHashMap<>();
        if (Files.exists(configFile()))
            JSON.readTree(configFile().toFile()).fields()
                    .forEachRemaining(e -> merged.put(e.getKey(), e.getValue().asText()));
        merged.putAll(updates);
        JSON.writerWithDefaultPrettyPrinter().writeValue(configFile().toFile(), merged);
    }

    // ── session helpers ──────────────────────────────────────────────────────
    JsonNode readSession() throws IOException {
        if (!Files.exists(sessionFile()))
            throw new IllegalStateException("Run `authgate login` first.");

        JsonNode session = JSON.readTree(sessionFile().toFile());
        String expiresAt = session.path("expiresAt").asText(null);
        if (expiresAt != null && !expiresAt.isBlank()) {
            Instant expiry = Instant.parse(expiresAt);
            if (expiry.isBefore(Instant.now()))
                throw new IllegalStateException("Session expired. Run `authgate login` again.");
            if (expiry.minusSeconds(REFRESH_MARGIN_SEC).isBefore(Instant.now())) {
                Term.info("Session nearing expiry — refreshing...");
                try { session = silentRefresh(session); }
                catch (Exception e) {
                    Term.warn("Refresh failed (" + e.getMessage() + "), continuing.");
                }
            }
        }
        return session;
    }

    private JsonNode silentRefresh(JsonNode session) throws Exception {
        String username = session.path("username").asText(null);
        String pat      = session.path("pat").asText(null);
        if (username == null || pat == null) return session;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("token",    pat);
        body.put("hostInfo", hostInfo());

        JsonNode fresh = post(endpoint("/api/auth/login"), body, null);
        JSON.writerWithDefaultPrettyPrinter().writeValue(sessionFile().toFile(), fresh);
        Term.ok("Session refreshed.");
        return fresh;
    }

    String authorizationHeader() throws IOException {
        JsonNode s = readSession();
        String username = s.path("username").asText(null);
        String token    = s.path("pat").asText(null);
        if (token == null || token.isBlank()) token = s.path("token").asText(null);
        if (username == null || username.isBlank() || token == null || token.isBlank())
            throw new IllegalStateException("Session missing credentials. Run `authgate login` again.");
        return basicAuth(username, token);
    }

    String endpoint(String path) throws IOException {
        return normalizeUrl(resolvedServer()) + path;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        List<String> argList = Arrays.asList(args);

        // Show banner only on `login` or `--help` / `-h`
        boolean showBanner = argList.contains("login")
                || argList.contains("--help")
                || argList.contains("-h");
        if (showBanner) Term.banner();

        int code = new CommandLine(new AuthgateCli()).execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() {
        Term.banner(); // show banner when bare `authgate` is run (same as --help)
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LOGIN  — git-style: prompts for username & token interactively
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "login",
            description = "Login with IAM username and personal access token.",
            mixinStandardHelpOptions = true)
    static class LoginCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        // Both options are optional — we prompt when absent (git-style)
        @Option(names = {"-u", "--username"},
                description = "IAM username. Prompts when omitted.")
        String username;

        @Option(names = {"-t", "--token"},
                description = "IAM personal access token. Prompts when omitted.")
        String token;

        @Override
        public Integer call() throws Exception {
            java.io.Console console = System.console();

            // ── username prompt ───────────────────────────────────────────
            if (username == null || username.isBlank()) {
                if (console != null) {
                    username = console.readLine("Username: ");
                } else {
                    System.out.print("Username: ");
                    username = new java.util.Scanner(System.in).nextLine();
                }
            }

            // ── token prompt (hidden, like git password) ──────────────────
            if (token == null || token.isBlank()) {
                if (console != null) {
                    char[] pwd = console.readPassword("Token: ");
                    token = pwd != null ? new String(pwd) : "";
                } else {
                    // Fallback when no real console (IDE, CI): visible input
                    System.out.print("Token: ");
                    token = new java.util.Scanner(System.in).nextLine();
                }
            }

            if (username.isBlank() || token.isBlank()) {
                Term.error("Username and token are required.");
                return 1;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("token",    token);
            body.put("hostInfo", hostInfo());

            Term.SpinnerHandle sp = Term.spinner("Contacting IAM server");
            JsonNode response;
            try {
                response = post(parent.endpoint("/api/auth/login"), body, null);
                sp.done("Authenticated");
            } catch (Exception e) {
                sp.fail(e.getMessage());
                return 1;
            }

            Files.createDirectories(parent.configDir);
            JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(parent.sessionFile().toFile(), response);

            // ── welcome card ─────────────────────────────────────────────
            Term.welcome(
                    response.path("username").asText(username),
                    response.path("email").asText(""),
                    response.path("sessionId").asText(""),
                    response.path("expiresAt").asText(null),
                    parent.resolvedServer()
            );
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LOGOUT
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "logout", description = "Remove the saved local session.",
            mixinStandardHelpOptions = true)
    static class LogoutCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        @Override
        public Integer call() throws Exception {
            Files.deleteIfExists(parent.sessionFile());
            Term.ok("Logged out. Goodbye.");
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STATUS
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "status", description = "Show the saved local session.",
            mixinStandardHelpOptions = true)
    static class StatusCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        @Override
        public Integer call() throws Exception {
            JsonNode s = parent.readSession();
            Term.section("Session");
            Term.kv("User",    s.path("username").asText(""));
            Term.kv("Email",   s.path("email").asText(""));
            Term.kv("Session", s.path("sessionId").asText(""));
            Term.kv("Device",  s.path("hostInfo").path("deviceId").asText(""));
            Term.kv("Expires", s.path("expiresAt").asText(""));
            Term.kv("Server",  parent.resolvedServer());
            Term.nl();
            return 0;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CONFIG
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "config",
            description = "Read or write persistent client configuration.",
            mixinStandardHelpOptions = true,
            subcommands = { ConfigCommand.SaveCommand.class, ConfigCommand.ShowCommand.class })
    static class ConfigCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        @Override
        public Integer call() { CommandLine.usage(this, System.out); return 0; }

        @Command(name = "save", description = "Persist server URL and SSH info.",
                mixinStandardHelpOptions = true)
        static class SaveCommand implements Callable<Integer> {

            @ParentCommand ConfigCommand config;

            @Option(names = "--server",   required = true) String serverUrl;
            @Option(names = "--ssh-host")                  String sshHost;
            @Option(names = "--ssh-port", defaultValue = "2222") int sshPort;

            @Override
            public Integer call() throws Exception {
                String norm = normalizeUrl(serverUrl);
                Map<String, Object> updates = new LinkedHashMap<>();
                updates.put("serverUrl", norm);
                updates.put("sshHost",   sshHost != null ? sshHost : URI.create(norm).getHost());
                updates.put("sshPort",   sshPort);
                config.parent.writeConfig(updates);
                Term.section("Config saved");
                Term.kv("serverUrl", (String) updates.get("serverUrl"));
                Term.kv("sshHost",   (String) updates.get("sshHost"));
                Term.kv("sshPort",   String.valueOf(updates.get("sshPort")));
                Term.nl();
                return 0;
            }
        }

        @Command(name = "show", description = "Print current config.",
                mixinStandardHelpOptions = true)
        static class ShowCommand implements Callable<Integer> {

            @ParentCommand ConfigCommand config;

            @Override
            public Integer call() throws Exception {
                Term.section("Config");
                config.parent.readConfig().fields()
                        .forEachRemaining(e -> Term.kv(e.getKey(), e.getValue().asText()));
                Term.nl();
                return 0;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TUNNEL
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "tunnel",
            description = "Manage tunnel routes.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    TunnelCommand.OpenCommand.class,
                    TunnelCommand.CreateCommand.class,
                    TunnelCommand.ConnectCommand.class,
                    TunnelCommand.ListCommand.class,
                    TunnelCommand.ActiveCommand.class,
                    TunnelCommand.DisconnectCommand.class
            })
    static class TunnelCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        @Override
        public Integer call() { CommandLine.usage(this, System.out); return 0; }

        // ── open (create + SSH) ───────────────────────────────────────────

        @Command(name = "open",
                description = "Create a tunnel AND start SSH. Stays alive until Ctrl-C.",
                mixinStandardHelpOptions = true)
        static class OpenCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Option(names = {"-s", "--subdomain"}, required = true) String subdomain;
            @Option(names = {"-p", "--local-port"}, required = true) int localPort;
            @Option(names = "--route-count")   Integer routeCount;
            @Option(names = "--route-key", split = ",") List<String> routeKeys;
            @Option(names = "--basic-auth")    boolean basicAuth;
            @Option(names = "--auth-username") String authUsername;
            @Option(names = "--auth-password", interactive = true, arity = "0..1") String authPassword;
            @Option(names = "--no-reconnect")  boolean noReconnect;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = tunnel.parent;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("subdomain",    subdomain);
                body.put("localPort",    localPort);
                body.put("basicAuth",    basicAuth);
                body.put("authUsername", authUsername);
                body.put("authPassword", authPassword);
                body.put("routeCount",   routeCount);
                body.put("routeKeys",    routeKeys);

                Term.SpinnerHandle sp = Term.spinner("Creating tunnel route");
                JsonNode response;
                try {
                    response = post(root.endpoint("/api/tunnels/generate"), body,
                            root.authorizationHeader());
                    sp.done("Route created");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }

                String keygen = response.path("keygen").asText("");
                String url    = response.path("tunnelUrl").asText("");

                Term.tunnelLive(subdomain, keygen, url, localPort, basicAuth);

                String pat = root.readSession().path("pat").asText(
                        root.readSession().path("token").asText(""));
                runSshLoop(root, subdomain + "-" + keygen, pat, localPort, !noReconnect);
                return 0;
            }
        }

        // ── create (register only) ────────────────────────────────────────

        @Command(name = "create",
                description = "Register a tunnel without starting SSH.",
                mixinStandardHelpOptions = true)
        static class CreateCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Option(names = {"-s", "--subdomain"}, required = true) String subdomain;
            @Option(names = {"-p", "--local-port"}, required = true) int localPort;
            @Option(names = "--route-count")   Integer routeCount;
            @Option(names = "--route-key", split = ",") List<String> routeKeys;
            @Option(names = "--basic-auth")    boolean basicAuth;
            @Option(names = "--auth-username") String authUsername;
            @Option(names = "--auth-password", interactive = true, arity = "0..1") String authPassword;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = tunnel.parent;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("subdomain",    subdomain);
                body.put("localPort",    localPort);
                body.put("basicAuth",    basicAuth);
                body.put("authUsername", authUsername);
                body.put("authPassword", authPassword);
                body.put("routeCount",   routeCount);
                body.put("routeKeys",    routeKeys);

                Term.SpinnerHandle sp = Term.spinner("Registering tunnel");
                JsonNode response;
                try {
                    response = post(root.endpoint("/api/tunnels/generate"), body,
                            root.authorizationHeader());
                    sp.done("Registered");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }

                String keygen = response.path("keygen").asText("");
                Term.section("Tunnel registered");
                Term.kv("Name",    subdomain + "/" + keygen);
                Term.kv("URL",     response.path("tunnelUrl").asText(""));
                Term.kv("Status",  response.path("status").asText(""));
                Term.kv("SSH cmd", response.path("sshCommand").asText(""));
                Term.nl();
                Term.info("Run: authgate tunnel connect --tunnel-name " + subdomain
                        + " --keygen " + keygen + " --local-port " + localPort);
                Term.nl();
                return 0;
            }
        }

        // ── connect (SSH only) ────────────────────────────────────────────

        @Command(name = "connect",
                description = "Start SSH for an existing tunnel. Stays alive until Ctrl-C.",
                mixinStandardHelpOptions = true)
        static class ConnectCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Option(names = "--tunnel-name", required = true) String tunnelName;
            @Option(names = "--keygen",      required = true) String keygen;
            @Option(names = {"-p", "--local-port"}, required = true) int localPort;
            @Option(names = "--no-reconnect") boolean noReconnect;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = tunnel.parent;
                String pat = root.readSession().path("pat").asText(
                        root.readSession().path("token").asText(""));
                Term.info("Connecting SSH for " + tunnelName + "/" + keygen
                        + " → localhost:" + localPort);
                Term.nl();
                runSshLoop(root, tunnelName + "-" + keygen, pat, localPort, !noReconnect);
                return 0;
            }
        }

        // ── list ──────────────────────────────────────────────────────────

        @Command(name = "list", description = "List tunnels owned by the logged-in user.",
                mixinStandardHelpOptions = true)
        static class ListCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = tunnel.parent;
                Term.SpinnerHandle sp = Term.spinner("Fetching tunnels");
                JsonNode response;
                try {
                    response = get(root.endpoint("/api/tunnels"), root.authorizationHeader());
                    sp.done("Done");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }

                if (!response.isArray() || response.isEmpty()) {
                    Term.info("No tunnels found."); return 0;
                }

                Term.section("Your tunnels");
                for (JsonNode item : response) {
                    Term.kv(item.path("tunnelName").asText("") + "/" + item.path("keygen").asText(""),
                            item.path("status").asText("") + "  " + item.path("tunnelUrl").asText(""));
                }
                Term.nl();
                return 0;
            }
        }

        // ── active ────────────────────────────────────────────────────────

        @Command(name = "active", description = "List active in-memory tunnels on the server.",
                mixinStandardHelpOptions = true)
        static class ActiveCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Override
            public Integer call() throws Exception {
                Term.SpinnerHandle sp = Term.spinner("Fetching active tunnels");
                JsonNode response;
                try {
                    response = get(tunnel.parent.endpoint("/api/tunnels/active"), null);
                    sp.done("Done");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }

                if (!response.isArray() || response.isEmpty()) {
                    Term.info("No active tunnels."); return 0;
                }

                Term.section("Active tunnels");
                for (JsonNode item : response) {
                    Term.kv(item.path("tunnelName").asText("") + "/" + item.path("keygen").asText(""),
                            item.path("host").asText("") + ":" + item.path("port").asText("")
                                    + "  connected " + item.path("connectedAt").asText(""));
                }
                Term.nl();
                return 0;
            }
        }

        // ── disconnect ────────────────────────────────────────────────────

        @Command(name = "disconnect", description = "Disconnect and deactivate a tunnel.",
                mixinStandardHelpOptions = true)
        static class DisconnectCommand implements Callable<Integer> {

            @ParentCommand TunnelCommand tunnel;

            @Option(names = "--tunnel-name", required = true) String tunnelName;
            @Option(names = "--keygen",      required = true) String keygen;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = tunnel.parent;
                Term.SpinnerHandle sp = Term.spinner("Disconnecting " + tunnelName + "/" + keygen);
                try {
                    delete(root.endpoint("/api/tunnels/" + tunnelName + "/" + keygen),
                            root.authorizationHeader());
                    sp.done("Disconnected");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }
                return 0;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  AGENT
    // ════════════════════════════════════════════════════════════════════════

    @Command(name = "agent",
            description = "Manage distributed tunnel agent nodes.",
            mixinStandardHelpOptions = true,
            subcommands = {
                    AgentCommand.RegisterCommand.class,
                    AgentCommand.HeartbeatCommand.class,
                    AgentCommand.AllocateCommand.class
            })
    static class AgentCommand implements Callable<Integer> {

        @ParentCommand AuthgateCli parent;

        @Override
        public Integer call() { CommandLine.usage(this, System.out); return 0; }

        @Command(name = "register", description = "Register this machine as an agent node.",
                mixinStandardHelpOptions = true)
        static class RegisterCommand implements Callable<Integer> {

            @ParentCommand AgentCommand agentCmd;

            @Option(names = "--node-id")                    String nodeId;
            @Option(names = "--host")                       String host;
            @Option(names = "--port", defaultValue = "8080") int port;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = agentCmd.parent;
                String id = nodeId != null ? nodeId : UUID.randomUUID().toString();
                if (host == null) host = InetAddress.getLocalHost().getHostAddress();

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("nodeId", id);
                body.put("host",   host);
                body.put("port",   port);

                Term.SpinnerHandle sp = Term.spinner("Registering agent node");
                JsonNode response;
                try {
                    response = post(root.endpoint("/api/agents/register"), body,
                            root.authorizationHeader());
                    sp.done("Registered");
                } catch (Exception e) {
                    sp.fail(e.getMessage());
                    return 1;
                }

                Term.section("Agent node");
                Term.kv("Node ID", response.path("nodeId").asText(id));
                Term.kv("Status",  response.path("status").asText(""));
                Term.nl();
                Term.info("Run heartbeats: authgate agent heartbeat --node-id " + id + " --loop 30");
                Term.nl();
                return 0;
            }
        }

        @Command(name = "heartbeat", description = "Send a heartbeat for a registered node.",
                mixinStandardHelpOptions = true)
        static class HeartbeatCommand implements Callable<Integer> {

            @ParentCommand AgentCommand agentCmd;

            @Option(names = "--node-id", required = true) String nodeId;
            @Option(names = "--loop",
                    description = "Send every N seconds continuously.") Integer loopSeconds;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = agentCmd.parent;

                if (loopSeconds != null && loopSeconds > 0) {
                    Term.info("Heartbeat for node " + nodeId + " every " + loopSeconds + "s. Ctrl-C to stop.");
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> Term.ok("Heartbeat stopped.")));
                    int beat = 0;
                    while (true) {
                        beat++;
                        Term.SpinnerHandle sp = Term.spinner("Heartbeat #" + beat);
                        try { sendHeartbeat(root, nodeId); sp.done("OK"); }
                        catch (Exception e) { sp.fail(e.getMessage()); }
                        Thread.sleep(loopSeconds * 1000L);
                    }
                } else {
                    Term.SpinnerHandle sp = Term.spinner("Sending heartbeat");
                    try { sendHeartbeat(root, nodeId); sp.done("OK  node " + nodeId); }
                    catch (Exception e) { sp.fail(e.getMessage()); return 1; }
                }
                return 0;
            }

            private void sendHeartbeat(AuthgateCli root, String nodeId) throws Exception {
                HttpRequest.Builder req = HttpRequest.newBuilder()
                        .uri(URI.create(root.endpoint("/api/agents/" + nodeId + "/heartbeat")))
                        .POST(HttpRequest.BodyPublishers.noBody());
                HttpResponse<String> resp = send(req, root.authorizationHeader());
                if (resp.statusCode() >= 400)
                    throw new IllegalStateException(
                            "Server returned " + resp.statusCode() + ": " + resp.body());
            }
        }

        @Command(name = "allocate", description = "Allocate an available agent node.",
                mixinStandardHelpOptions = true)
        static class AllocateCommand implements Callable<Integer> {

            @ParentCommand AgentCommand agentCmd;

            @Override
            public Integer call() throws Exception {
                AuthgateCli root = agentCmd.parent;
                Term.SpinnerHandle sp = Term.spinner("Allocating node");
                JsonNode response;
                try {
                    response = get(root.endpoint("/api/agents/allocate"), root.authorizationHeader());
                    sp.done("Allocated");
                } catch (Exception e) { sp.fail(e.getMessage()); return 1; }
                Term.kv("Node ID", response.path("nodeId").asText(""));
                Term.kv("Status",  response.path("status").asText(""));
                Term.nl();
                return 0;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SSH KEEP-ALIVE LOOP
    // ════════════════════════════════════════════════════════════════════════

    static void runSshLoop(AuthgateCli root, String sshUser, String pat,
                           int localPort, boolean reconnect) throws Exception {

        String sshHost = root.resolvedSshHost();
        int    sshPort = root.resolvedSshPort();

        AtomicReference<Process> current = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = current.get();
            if (p != null && p.isAlive()) { Term.nl(); p.destroy(); }
            Term.sshStopped();
        }));

        Path askpass = writeAskpassScript(pat);
        askpass.toFile().setExecutable(true);
        AtomicInteger attempt = new AtomicInteger(0);

        try {
            do {
                int n = attempt.incrementAndGet();
                if (n > 1) {
                    Term.sshDropped(0);
                    Term.countdown("Reconnecting in", RECONNECT_DELAY_SEC);
                    Term.info("Reconnect attempt #" + n);
                }

                List<String> cmd = new ArrayList<>(Arrays.asList(
                        "ssh",
                        "-N",
                        "-T",
                        "-R", "0:localhost:" + localPort,
                        "-p", String.valueOf(sshPort),
                        "-o", "StrictHostKeyChecking=no",
                        "-o", "ServerAliveInterval=30",
                        "-o", "ServerAliveCountMax=3",
                        "-o", "ExitOnForwardFailure=yes",
                        "-o", "BatchMode=no",
                        "-o", "PasswordAuthentication=yes",
                        sshUser + "@" + sshHost
                ));

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();
                pb.environment().put("SSH_ASKPASS",         askpass.toString());
                pb.environment().put("SSH_ASKPASS_REQUIRE", "force");
                pb.environment().put("DISPLAY",             ":0");

                Term.sshConnecting(sshUser, sshHost, sshPort, localPort);
                Process p = pb.start();
                current.set(p);

                Thread.sleep(800);
                if (p.isAlive()) Term.sshConnected();

                int exitCode = p.waitFor();
                if (exitCode != 0 && reconnect) Term.sshDropped(exitCode);

            } while (reconnect && !Thread.currentThread().isInterrupted());
        } finally {
            Files.deleteIfExists(askpass);
        }
    }

    static Path writeAskpassScript(String pat) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            Path bat = Files.createTempFile("authgate-askpass-", ".bat");
            Files.writeString(bat, "@echo off\r\necho " + pat + "\r\n");
            return bat;
        }
        Path sh = Files.createTempFile("authgate-askpass-", ".sh");
        Files.writeString(sh, "#!/bin/sh\nprintf '%s' '" + pat.replace("'", "'\\''") + "'\n");
        return sh;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HTTP HELPERS
    // ════════════════════════════════════════════════════════════════════════

    static JsonNode post(String uri, Map<String, Object> body, String authorization) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return sendJson(req, authorization);
    }

    static JsonNode get(String uri, String authorization) throws Exception {
        return sendJson(HttpRequest.newBuilder().uri(URI.create(uri)).GET(), authorization);
    }

    static void delete(String uri, String authorization) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder().uri(URI.create(uri)).DELETE();
        HttpResponse<String> response = send(req, authorization);
        if (response.statusCode() >= 400)
            throw new IllegalStateException(
                    "Server returned " + response.statusCode() + ": " + response.body());
    }

    static JsonNode sendJson(HttpRequest.Builder request, String authorization) throws Exception {
        HttpResponse<String> response = send(request, authorization);
        if (response.statusCode() >= 400)
            throw new IllegalStateException(
                    "Server returned " + response.statusCode() + ": " + response.body());
        if (response.body() == null || response.body().isBlank())
            return JSON.createObjectNode();
        return JSON.readTree(response.body());
    }

    static HttpResponse<String> send(HttpRequest.Builder request, String authorization) throws Exception {
        request.header("Accept", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ════════════════════════════════════════════════════════════════════════

    static String basicAuth(String username, String token) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8));
    }

    static String normalizeUrl(String url) {
        if (url == null) return "";
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static Map<String, Object> hostInfo() throws Exception {
        InetAddress local = InetAddress.getLocalHost();
        String hostName   = local.getHostName();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("deviceId",  stableDeviceId(hostName));
        info.put("hostName",  hostName);
        info.put("osName",    System.getProperty("os.name"));
        info.put("osVersion", System.getProperty("os.version"));
        info.put("osArch",    System.getProperty("os.arch"));
        info.put("ipAddress", local.getHostAddress());
        return info;
    }

    static String stableDeviceId(String hostName) {
        String seed = hostName + ":" + System.getProperty("user.name")
                + ":" + System.getProperty("os.name");
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
}