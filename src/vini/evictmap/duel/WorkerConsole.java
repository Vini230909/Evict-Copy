package vini.evictmap.duel;

import arc.Core;
import arc.util.Log;
import mindustry.server.ServerControl;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hub-only: sends the server console to a running match server and back.
 *
 * <p>{@code evictattach <port>} attaches, after which every line typed into the
 * hub console is written to that worker's stdin - the same pipe the hub already
 * uses for {@code config port} and {@code host} - and everything the worker
 * prints is relayed back into the hub console, prefixed with its port.
 * {@code evictattach} with no argument detaches again.
 *
 * <p>The interception replaces {@link ServerControl#serverInput}, which is a
 * plain mutable field started only on {@code ServerLoadEvent} - two lines after
 * {@code mods.eachClass(Mod::init)} in {@code ServerLauncher}, so the plugin
 * always gets there first and vanilla's own stdin loop never runs. There is
 * exactly one reader either way.
 *
 * <p>Never getting stuck on a worker is the whole design constraint:
 * <ul>
 *   <li>the keyword is matched on the raw line <b>before</b> anything is
 *       forwarded, so the way back never depends on the worker, on the hub's
 *       command handler, or on the game loop being alive;</li>
 *   <li>attaching and detaching happen on the console thread itself, not on a
 *       posted main-thread task;</li>
 *   <li>any exception in the relay detaches and keeps the console reading -
 *       a broken relay must never cost the server its console;</li>
 *   <li>a worker that exits detaches the console out loud, so no command is
 *       ever typed into a pipe nobody reads.</li>
 * </ul>
 *
 * <p>Relayed lines are printed raw rather than through {@link Log}: they
 * already carry the worker's own timestamp, level tag and colours, so its
 * console keeps looking exactly like itself. What was typed is logged
 * normally, so the hub's log file keeps the audit trail.
 */
public final class WorkerConsole {

    /** Attach/detach keyword; also the console command in {@code ConsoleCommands}. */
    public static final String COMMAND = "evictattach";

    /** Detach-only spelling, for when the port number is the muscle memory. */
    private static final String DETACH_COMMAND = "evictdetach";

    private static final String WORKER_LOG_FILE = "worker.log";
    private static final long TAIL_POLL_MILLIS = 200L;
    private static final int MAX_TAIL_BYTES = 128 * 1024;

    /** Port -> attachable worker. Written from the spawn thread, read anywhere. */
    private final Map<Integer, Link> links = new ConcurrentHashMap<>();

    private volatile Link attached;
    private volatile Thread tail;

    /** True once this process' console is the hub's; false on a match server. */
    private volatile boolean installed;

    /** One attachable worker: its process, its stdin pipe and its folder. */
    private static final class Link {
        final int port;
        final Process process;
        final OutputStream stdin;
        final File dir;

        Link(int port, Process process, OutputStream stdin, File dir) {
            this.port = port;
            this.process = process;
            this.stdin = stdin;
            this.dir = dir;
        }

        boolean alive() {
            return process != null && process.isAlive();
        }
    }

    /**
     * Takes over the console's stdin loop. Hub only, and only from the plugin's
     * {@code init()} - later than that the vanilla loop already owns stdin.
     */
    public void install() {
        ServerControl control = ServerControl.instance;

        if (control == null) {
            Log.err("[EvictMapGenerator] No ServerControl: console attach is unavailable.");
            return;
        }

        control.serverInput = this::readConsole;
        installed = true;
    }

    /** A worker went live and can be attached to. */
    public void register(int port, Process process, OutputStream stdin, File dir) {
        if (process == null || stdin == null) {
            return;
        }

        links.put(port, new Link(port, process, stdin, dir));
    }

    /** A worker's slot was released; detach rather than type into a dead pipe. */
    public void unregister(int port) {
        Link link = links.remove(port);

        if (link != null) {
            detach(link, "the match server stopped");
        }
    }

    /**
     * The {@code evictattach} command: a port attaches, no argument detaches
     * (or lists what is attachable when the console is on the hub anyway).
     * Called both from the stdin interception and from the registered console
     * command, hence synchronized.
     */
    public synchronized void command(String argument) {
        String arg = argument == null ? "" : argument.trim();

        if (!installed) {
            Log.info(
                    "[EvictMapGenerator] This console belongs to a match server; attaching works from the hub console only."
            );
            return;
        }

        if (arg.isEmpty() || arg.equalsIgnoreCase("off")) {
            if (attached != null) {
                detach("you asked");
            } else {
                logAttachable();
            }

            return;
        }

        int port = parsePort(arg);
        Link link = port < 0 ? null : links.get(port);

        if (link == null || !link.alive()) {
            Log.err("[EvictMapGenerator] No running match server on port @.", arg);
            logAttachable();
            return;
        }

        attach(link);
    }

    /**
     * Vanilla's stdin loop with one line added in front of it: the interception.
     * Kept token-compatible with {@code ServerControl.serverInput} so console
     * behaviour is otherwise byte for byte what it was.
     */
    private void readConsole() {
        // Never closed, exactly like vanilla's: closing it would close System.in
        // and leave the server without a console for good.
        Scanner scan = new Scanner(System.in);

        while (scan.hasNext()) {
            String line = scan.nextLine();
            boolean consumed;

            try {
                consumed = handleLine(line);
            } catch (Throwable error) {
                // The console thread is the only way to talk to this server;
                // a relay bug must cost the attachment, never the console.
                Log.err("[EvictMapGenerator] Console attach failed; back on the hub.", error);
                detach("an error");
                consumed = true;
            }

            if (!consumed) {
                Core.app.post(() -> ServerControl.instance.handleCommandString(line));
            }
        }
    }

    /** True when the line was the keyword or went to an attached worker. */
    private boolean handleLine(String raw) {
        String line = raw.trim();

        if (line.isEmpty()) {
            return false;
        }

        String lower = line.toLowerCase(Locale.ROOT);

        // First of all, and never forwarded: this is the way back.
        if (lower.equals(COMMAND) || lower.equals(DETACH_COMMAND)) {
            command("");
            return true;
        }

        if (lower.startsWith(COMMAND + " ")) {
            command(line.substring(COMMAND.length()).trim());
            return true;
        }

        Link link = attached;

        if (link == null) {
            return false;
        }

        forward(link, raw);
        return true;
    }

    private void forward(Link link, String line) {
        if (!link.alive()) {
            detach(link, "the match server stopped");
            return;
        }

        try {
            link.stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            link.stdin.flush();

            // The hub's own log keeps what was typed; the worker's answer comes
            // back through the tail below.
            Log.info("[EvictMapGenerator] console -> port @: @", link.port, line);
        } catch (IOException exception) {
            detach(link, "its console pipe broke");
        }
    }

    private void attach(Link link) {
        if (attached != null) {
            stopTail();
        }

        attached = link;
        startTail(link);

        Log.info(
                "[EvictMapGenerator] Console attached to the match server on port @. "
                        + "Every line now goes there; type '@' alone to come back.",
                link.port,
                COMMAND
        );
    }

    /** Detaches whatever the console is on. */
    private void detach(String reason) {
        detach(null, reason);
    }

    /**
     * Detaches, but only from {@code expected} when one is named. A tail thread
     * of an attachment that has already been replaced must not send the console
     * back from the new one.
     */
    private synchronized void detach(Link expected, String reason) {
        Link link = attached;

        if (link == null || (expected != null && expected != link)) {
            return;
        }

        attached = null;
        stopTail();

        Log.info(
                "[EvictMapGenerator] Console detached from port @ (@); back on the hub.",
                link.port,
                reason
        );
    }

    private void startTail(Link link) {
        File file = new File(link.dir, WORKER_LOG_FILE);

        // Only what happens from now on: the log holds the whole match.
        long offset = file.exists() ? file.length() : 0L;

        Thread thread = new Thread(
                () -> tailLoop(link, file, offset),
                "evict-worker-console"
        );

        thread.setDaemon(true);
        tail = thread;
        thread.start();
    }

    private void stopTail() {
        Thread thread = tail;
        tail = null;

        if (thread != null) {
            thread.interrupt();
        }
    }

    private void tailLoop(Link link, File file, long start) {
        Thread self = Thread.currentThread();
        long offset = start;

        while (tail == self && attached == link) {
            offset = relay(link.port, file, offset);

            if (!link.alive()) {
                // Its dying words first, then step back to the hub.
                relay(link.port, file, offset);
                detach(link, "the match server stopped");
                return;
            }

            try {
                Thread.sleep(TAIL_POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    /** Prints the log's new complete lines and returns the new read offset. */
    private long relay(int port, File file, long start) {
        if (!file.exists()) {
            return start;
        }

        long offset = start;
        long length = file.length();

        if (length < offset) {
            // Truncated by a respawn on the same port; start over.
            offset = 0L;
        }

        if (length <= offset) {
            return offset;
        }

        byte[] data;
        int read;

        try (RandomAccessFile handle = new RandomAccessFile(file, "r")) {
            handle.seek(offset);
            data = new byte[(int) Math.min(length - offset, MAX_TAIL_BYTES)];
            read = handle.read(data);
        } catch (Exception exception) {
            return offset;
        }

        if (read <= 0) {
            return offset;
        }

        int consumed = lastNewlineIndex(data, read) + 1;

        if (consumed <= 0) {
            // A half-written line; leave it for the next poll.
            return offset;
        }

        String block = new String(data, 0, consumed, StandardCharsets.UTF_8);

        for (String raw : block.split("\n")) {
            String line = raw.replace("\r", "");

            if (!line.isBlank()) {
                System.out.println("[" + port + "] " + line);
            }
        }

        return offset + consumed;
    }

    private static int lastNewlineIndex(byte[] data, int read) {
        for (int index = read - 1; index >= 0; index--) {
            if (data[index] == '\n') {
                return index;
            }
        }

        return -1;
    }

    private void logAttachable() {
        List<Integer> ports = new ArrayList<>();

        for (Link link : links.values()) {
            if (link.alive()) {
                ports.add(link.port);
            }
        }

        if (ports.isEmpty()) {
            Log.info("[EvictMapGenerator] Console is on the hub; no match server is running.");
            return;
        }

        ports.sort(Integer::compareTo);

        StringBuilder list = new StringBuilder();

        for (int port : ports) {
            if (!list.isEmpty()) {
                list.append(", ");
            }

            list.append(port);
        }

        Log.info(
                "[EvictMapGenerator] Console is on the hub. Attach with '@ <port>' - running: @",
                COMMAND,
                list
        );
    }

    private static int parsePort(String argument) {
        try {
            return Integer.parseInt(argument.trim());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
