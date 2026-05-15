package kh.edu.istad.stadoor.tunnelagent;

import picocli.CommandLine.Help.Ansi;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Terminal UI toolkit for Stadoor CLI.
 *
 * All colour output goes through picocli's {@link Ansi} renderer so it
 * automatically degrades to plain text when the terminal does not support
 * ANSI (CI, redirected output, Windows CMD without VT).
 *
 * Colour palette used (named after picocli's @|fg(X) …|@ markup):
 *   • cyan        – brand / primary
 *   • bold cyan   – headings / usernames
 *   • green       – success
 *   • yellow      – warning / info labels
 *   • red         – errors
 *   • bold white  – highlight values
 *   • fg(245)     – muted / secondary (256-colour grey)
 */
public final class Term {

    private Term() {}

    // ── picocli ANSI renderer (auto-detects terminal capabilities) ──────────
    private static final Ansi ANSI = Ansi.AUTO;

    // ── Spinner frames ───────────────────────────────────────────────────────
    private static final String[] SPINNER = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" };

    // ── Progress-bar characters ──────────────────────────────────────────────
    private static final String BAR_FILL  = "█";
    private static final String BAR_EMPTY = "░";
    private static final int    BAR_WIDTH = 28;

    // ════════════════════════════════════════════════════════════════════════
    //  BANNER
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Prints the Stadoor ASCII banner in cyan.
     * Called once at the start of any interactive session.
     */
    public static void banner() {
        print("@|bold,cyan " +
                " ___  _____  __   ____   ___   ___  ___ \n" +
                "/ __||_   _|/  \\ |  _ \\ / _ \\ / _ \\| _ \\\n" +
                "\\__ \\  | | / /\\ \\| | | | | | | | | |   /\n" +
                "|___/  |_|/_/  \\_\\_|_/ \\___/ \\___/|_|_\\\n" +
                "|@");
        print("@|fg(245)  Secure tunnel client  •  v0.2.0|@\n");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  WELCOME  (shown after successful login)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Prints a coloured welcome card after login.
     *
     * @param username  IAM username
     * @param email     user email
     * @param sessionId session UUID
     * @param expiresAt ISO-8601 expiry string (may be null/blank)
     * @param server    server URL the session was created against
     */
    public static void welcome(String username, String email,
                               String sessionId, String expiresAt,
                               String server) {
        String line = "─".repeat(44);
        nl();
        print("@|bold,cyan  ╔" + line + "╗|@");
        print("@|bold,cyan  ║|@" +
                "  @|bold,white Welcome to Stadoor,|@ " +
                "@|bold,cyan " + pad(username, 22) + "|@" +
                "@|bold,cyan  ║|@");
        print("@|bold,cyan  ╠" + line + "╣|@");
        infoRow("  User",    username,  44);
        infoRow("  Email",   email,     44);
        infoRow("  Session", shorten(sessionId, 36), 44);
        infoRow("  Server",  server,    44);
        if (expiresAt != null && !expiresAt.isBlank()) {
            infoRow("  Expires", formatExpiry(expiresAt), 44);
        }
        print("@|bold,cyan  ╚" + line + "╝|@");
        nl();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  TUNNEL OPEN  (shown after a tunnel becomes live)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Prints a live-tunnel card — URL, port, status badge.
     */
    public static void tunnelLive(String subdomain, String keygen,
                                  String tunnelUrl, int localPort,
                                  boolean basicAuth) {
        String line = "─".repeat(44);
        nl();
        print("@|bold,green  ╔" + line + "╗|@");
        print("@|bold,green  ║|@  @|bold,white Tunnel is LIVE|@  " +
                "@|bold,green ✔|@" + spaces(25) + "@|bold,green ║|@");
        print("@|bold,green  ╠" + line + "╣|@");
        infoRowGreen("  Name",      subdomain + "/" + keygen, 44);
        infoRowGreen("  Public URL",shorten(tunnelUrl, 38),   44);
        infoRowGreen("  Local",     "localhost:" + localPort,  44);
        infoRowGreen("  Auth",      basicAuth ? "basic-auth enabled" : "none", 44);
        print("@|bold,green  ╠" + line + "╣|@");
        print("@|bold,green  ║|@  @|fg(245) Press Ctrl-C to stop the tunnel.|@" +
                spaces(13) + "@|bold,green ║|@");
        print("@|bold,green  ╚" + line + "╝|@");
        nl();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SPINNER  (for HTTP calls)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Starts an animated spinner on the current line.
     * Returns a {@link SpinnerHandle} — call {@code handle.done("message")} or
     * {@code handle.fail("message")} to stop it.
     *
     * Example:
     * <pre>
     *   var sp = Term.spinner("Contacting IAM server");
     *   JsonNode result = post(...);
     *   sp.done("Authenticated");
     * </pre>
     */
    public static SpinnerHandle spinner(String label) {
        AtomicInteger frame    = new AtomicInteger(0);
        AtomicBoolean running  = new AtomicBoolean(true);
        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stadoor-spinner");
            t.setDaemon(true);
            return t;
        });

        ScheduledFuture<?> task = sched.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            String tick = SPINNER[frame.getAndIncrement() % SPINNER.length];
            // \r moves to start of line, \033[K clears to end
            System.out.print(ANSI.string(
                    "\r@|cyan " + tick + "|@ @|fg(245) " + label + "...|@\033[K"));
            System.out.flush();
        }, 0, 80, TimeUnit.MILLISECONDS);

        return new SpinnerHandle(running, task, sched, label);
    }

    public static final class SpinnerHandle {
        private final AtomicBoolean       running;
        private final ScheduledFuture<?>  task;
        private final ScheduledExecutorService sched;
        private final String              label;

        SpinnerHandle(AtomicBoolean running, ScheduledFuture<?> task,
                      ScheduledExecutorService sched, String label) {
            this.running = running;
            this.task    = task;
            this.sched   = sched;
            this.label   = label;
        }

        /** Stop spinner and print a green ✔ line. */
        public void done(String message) {
            stop();
            System.out.println(ANSI.string(
                    "\r@|bold,green ✔|@ @|bold,white " + label + "|@" +
                            " @|fg(245)—|@ @|green " + message + "|@\033[K"));
        }

        /** Stop spinner and print a red ✘ line. */
        public void fail(String message) {
            stop();
            System.out.println(ANSI.string(
                    "\r@|bold,red ✘|@ @|bold,white " + label + "|@" +
                            " @|fg(245)—|@ @|red " + message + "|@\033[K"));
        }

        /** Stop spinner and print a yellow ⚠ line. */
        public void warn(String message) {
            stop();
            System.out.println(ANSI.string(
                    "\r@|bold,yellow ⚠|@ @|bold,white " + label + "|@" +
                            " @|fg(245)—|@ @|yellow " + message + "|@\033[K"));
        }

        private void stop() {
            running.set(false);
            task.cancel(false);
            sched.shutdown();
            System.out.flush();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PROGRESS BAR  (for SSH reconnect countdown)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Blocks for {@code totalSeconds}, drawing a progress bar that counts down.
     * Can be interrupted by the caller's thread.
     *
     * @param label        text shown beside the bar, e.g. "Reconnecting in"
     * @param totalSeconds how long to wait
     */
    public static void countdown(String label, int totalSeconds) throws InterruptedException {
        for (int remaining = totalSeconds; remaining >= 0; remaining--) {
            int filled = (int) Math.round((double)(totalSeconds - remaining) / totalSeconds * BAR_WIDTH);
            int empty  = BAR_WIDTH - filled;
            String bar = BAR_FILL.repeat(Math.max(0, filled)) +
                    BAR_EMPTY.repeat(Math.max(0, empty));
            System.out.print(ANSI.string(
                    "\r@|cyan " + label + "|@  " +
                            "@|bold,cyan [|@@|cyan " + bar + "|@@|bold,cyan ]|@" +
                            "  @|bold,white " + remaining + "s|@ \033[K"));
            System.out.flush();
            if (remaining > 0) Thread.sleep(1000);
        }
        // clear line
        System.out.print("\r\033[K");
        System.out.flush();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  STATUS LINE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** @|bold,green ✔|@  message */
    public static void ok(String msg) {
        println("@|bold,green ✔|@  @|white " + msg + "|@");
    }

    /** @|bold,red ✘|@  message */
    public static void error(String msg) {
        println("@|bold,red ✘|@  @|red " + msg + "|@");
    }

    /** @|bold,yellow ⚠|@  message */
    public static void warn(String msg) {
        println("@|bold,yellow ⚠|@  @|yellow " + msg + "|@");
    }

    /** @|cyan ℹ|@  message */
    public static void info(String msg) {
        println("@|cyan ℹ|@  @|fg(245) " + msg + "|@");
    }

    /** Bold cyan section header with a divider line. */
    public static void section(String title) {
        nl();
        println("@|bold,cyan " + title + "|@");
        println("@|fg(245) " + "─".repeat(title.length()) + "|@");
    }

    /** A key=value pair, key in yellow, value in white. */
    public static void kv(String key, String value) {
        println("  @|yellow " + padRight(key + ":", 14) + "|@  @|white " + value + "|@");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SSH STATUS LINES  (printed during tunnel lifecycle)
    // ════════════════════════════════════════════════════════════════════════

    public static void sshConnecting(String user, String host, int port, int localPort) {
        println("@|cyan ⟶|@  @|fg(245) ssh -R 0:localhost:|@@|bold,white " + localPort +
                "|@  @|fg(245) " + user + "@" + host + " -p " + port + "|@");
    }

    public static void sshConnected() {
        println("@|bold,green ✔|@  @|green SSH tunnel established|@");
    }

    public static void sshDropped(int exitCode) {
        println("@|yellow ⚠|@  @|yellow SSH connection dropped|@ @|fg(245) (exit " + exitCode + ")|@");
    }

    public static void sshStopped() {
        println("@|fg(245) SSH tunnel stopped.|@");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LOW-LEVEL PRINT
    // ════════════════════════════════════════════════════════════════════════

    /** Print with ANSI markup, no newline. */
    public static void print(String markup) {
        System.out.print(ANSI.string(markup));
    }

    /** Print with ANSI markup + newline. */
    public static void println(String markup) {
        System.out.println(ANSI.string(markup));
    }

    /** Plain newline. */
    public static void nl() {
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INTERNAL LAYOUT HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /** One row inside the welcome/tunnel box — cyan border, yellow label, white value. */
    private static void infoRow(String label, String value, int totalWidth) {
        String content = " @|yellow " + padRight(label + ":", 10) + "|@  @|white " + value + "|@";
        // strip markup to measure plain text width
        int contentLen = stripMarkup(label + ":").length() + 2 + stripMarkup(value).length() + 2;
        int pad = totalWidth - contentLen - 1;
        print("@|bold,cyan  ║|@" + ANSI.string(content) + spaces(Math.max(0, pad)) + ANSI.string("@|bold,cyan  ║|@"));
        nl();
    }

    private static void infoRowGreen(String label, String value, int totalWidth) {
        String content = " @|yellow " + padRight(label + ":", 12) + "|@  @|white " + value + "|@";
        int contentLen = stripMarkup(label + ":").length() + 2 + stripMarkup(value).length() + 2;
        int pad = totalWidth - contentLen - 1;
        print("@|bold,green  ║|@" + ANSI.string(content) + spaces(Math.max(0, pad)) + ANSI.string("@|bold,green  ║|@"));
        nl();
    }

    private static String pad(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static String padRight(String s, int width) {
        return pad(s == null ? "" : s, width);
    }

    private static String spaces(int n) {
        return n <= 0 ? "" : " ".repeat(n);
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Strip @|...|@ markup tags to measure plain-text length. */
    private static String stripMarkup(String s) {
        return s == null ? "" : s.replaceAll("@\\|[^|]*\\|", "").replaceAll("\\|@", "");
    }

    private static String formatExpiry(String iso) {
        try {
            Instant i = Instant.parse(iso);
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
                    .withZone(ZoneId.systemDefault())
                    .format(i);
        } catch (Exception e) {
            return iso;
        }
    }
}