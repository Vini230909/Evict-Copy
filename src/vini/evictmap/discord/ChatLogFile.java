package vini.evictmap.discord;

import vini.evictmap.core.util.PluginLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A worker's side of the chat mirror: appends every captured line to
 * {@code chat.log} in the worker folder, where the hub tails it on its normal
 * ~2s status poll and relays it into the port's Discord channel. The worker
 * itself never talks to Discord - same rule as every other worker report.
 *
 * <p>One line per event, {@code MM-dd-yyyy HH:mm:ss<TAB>text}: the timestamp
 * matches the console log's own format so the file cross-references it, and
 * the hub simply strips everything through the first tab. The hub deletes a
 * stale file at spawn; the worker only ever appends.
 */
public final class ChatLogFile {

    private static final File FILE = new File("chat.log");

    private static final DateTimeFormatter CONSOLE_TIME =
            DateTimeFormatter.ofPattern("MM-dd-yyyy HH:mm:ss");

    private boolean writeFailed;

    public synchronized void append(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        String record = CONSOLE_TIME.format(LocalDateTime.now())
                + "\t"
                + line.replace('\n', ' ').replace('\r', ' ')
                + "\n";

        try {
            Files.write(
                    FILE.toPath(),
                    record.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            writeFailed = false;
        } catch (Exception exception) {
            // Once per streak: a full disk must not flood the worker log with
            // one error per chat message.
            if (!writeFailed) {
                writeFailed = true;
                PluginLog.err(
                        "Could not append to chat.log: @",
                        exception.getMessage()
                );
            }
        }
    }
}
