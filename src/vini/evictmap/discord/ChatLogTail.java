package vini.evictmap.discord;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The hub's side of the chat mirror: reads each worker's {@code chat.log}
 * from a remembered byte offset and hands the new complete lines to the
 * reporter, stripping the worker's timestamp prefix.
 *
 * <p>Called from two places - the ~2s background status poll, and the
 * main-thread final read when a worker exits (so the last words land before
 * the match-end embed). Every method is synchronized and the drain both
 * advances the offset and delivers under the same lock, so those two callers
 * can never double-read a range or deliver it out of order.
 *
 * <p>Offsets are reset when a port's worker spawns (the hub deletes the old
 * file then) and otherwise kept: a drain that races a slot release simply
 * finds the offset at end-of-file and reads nothing.
 */
public final class ChatLogTail {

    /** More than a chat file ever holds; guards against reading junk whole. */
    private static final int MAX_DRAIN_BYTES = 256 * 1024;

    private final java.util.Map<Integer, Long> offsetsByPort =
            new java.util.HashMap<>();

    /** A fresh worker spawned on this port; its file starts over. */
    public synchronized void reset(int port) {
        offsetsByPort.remove(port);
    }

    /**
     * Reads the file's new complete lines and hands each to the sink, minus
     * the timestamp prefix. A trailing half-written line stays for the next
     * drain, so a line is never delivered torn.
     */
    public synchronized void drain(
            int port,
            File chatFile,
            Consumer<String> lineSink
    ) {
        if (chatFile == null || !chatFile.exists()) {
            return;
        }

        long offset = offsetsByPort.getOrDefault(port, 0L);
        long length = chatFile.length();

        if (length < offset) {
            // Recreated behind our back; start over rather than read garbage.
            offset = 0L;
        }

        if (length <= offset) {
            return;
        }

        byte[] data;
        int read;

        try (RandomAccessFile file = new RandomAccessFile(chatFile, "r")) {
            file.seek(offset);
            data = new byte[(int) Math.min(length - offset, MAX_DRAIN_BYTES)];
            read = file.read(data);
        } catch (Exception exception) {
            return;
        }

        if (read <= 0) {
            return;
        }

        int consumed = lastNewlineIndex(data, read) + 1;

        if (consumed <= 0) {
            return;
        }

        offsetsByPort.put(port, offset + consumed);

        String block = new String(data, 0, consumed, StandardCharsets.UTF_8);

        for (String raw : block.split("\n")) {
            String line = stripTimestamp(raw.replace("\r", "")).trim();

            if (!line.isEmpty()) {
                lineSink.accept(line);
            }
        }
    }

    private static int lastNewlineIndex(byte[] data, int read) {
        for (int index = read - 1; index >= 0; index--) {
            if (data[index] == '\n') {
                return index;
            }
        }

        return -1;
    }

    /** Drops the {@code MM-dd-yyyy HH:mm:ss<TAB>} prefix the worker wrote. */
    private static String stripTimestamp(String line) {
        int tab = line.indexOf('\t');

        return tab < 0 ? line : line.substring(tab + 1);
    }
}
