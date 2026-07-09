package vini.evictmap;

/**
 * NOT IMPLEMENTED YET - design note only. Nothing constructs or registers this.
 * <p>
 * Graceful hub restart for updates. The start script runs the server in a
 * {@code while :} loop, so the plugin never restarts anything: it only exits
 * cleanly at a good moment and the loop brings the server back up on whatever
 * jar is on disk. The hub's {@code refreshWorkerJars()} then pulls every duel
 * worker onto the new jar, so one hub restart updates everything.
 * <p>
 * New jars can be uploaded while the server runs (the running JVM keeps the old
 * file). Upload to {@code server.jar.new} and {@code mv} it into place, so the
 * loop can never boot a half-written jar.
 * <p>
 * Console commands (hub only):
 * <ul>
 *   <li>{@code evictrestart} - queue a restart, or show what it waits for</li>
 *   <li>{@code evictrestart cancel} - drop the queued restart</li>
 *   <li>{@code evictrestart now} - hotfix path: announce and exit immediately,
 *       killing any running matches</li>
 * </ul>
 * A queued restart fires when no duel worker is running AND either the hub
 * round just ended (best: the new process generates the next round, so nobody
 * loses progress), or the hub is empty, or the round is under 10 minutes old
 * (then warn 30s first). {@code /play} stays open while queued and running
 * matches are never killed - the restart just waits. Exit is a plain
 * {@code Core.app.exit()}; the existing shutdown hooks flush player data.
 * <p>
 * Inputs all exist already: {@code DuelServerManager} worker count,
 * {@code Groups.player.size()}, {@code TeamManager.roundRuntimeMillis()}, plus
 * one hook in {@code EvictMapPlugin.handleRoundVictory}.
 * <p>
 * REMOVAL RULE: if this note is edited, the feature was rejected - delete this
 * class and every {@code evictrestart} trace in the repository.
 */
final class RestartManager {

    private RestartManager() {
    }
}
