package vini.evictmap.commands;

import arc.util.CommandHandler;
import vini.evictmap.core.cmd.Commands;
import vini.evictmap.core.cmd.Perm;
import vini.evictmap.core.text.Text;
import vini.evictmap.gameplay.WaveExtinction;

/**
 * {@code /extinction} - the test switch for {@link WaveExtinction}.
 *
 * <p>The wave has no automatic trigger yet; this command is the only thing that
 * starts it, on the hub and on a match server alike, in any mode. Admin-only
 * and hidden from {@code /help}, because it eats the map.
 */
public final class ExtinctionCommands {

    private final WaveExtinction wave;

    public ExtinctionCommands(WaveExtinction wave) {
        this.wave = wave;
    }

    public void registerClientCommands(CommandHandler handler) {
        Commands commands = new Commands();

        commands.command("extinction").client()
                .args("action:string?")
                .perm(Perm.ADMIN)
                .description("Test the wave extinction: [seconds] | stop | status.")
                .run(ctx -> {
                    String action = ctx.has("action") ? ctx.str("action").trim() : "";

                    if (action.equalsIgnoreCase("stop") || action.equalsIgnoreCase("off")) {
                        if (!wave.isRunning()) {
                            ctx.fail(Text.of().scarlet("The wave is not running."));
                            return;
                        }

                        wave.stop();
                        ctx.success("Wave extinction stopped. Converted terrain stays space.");
                        return;
                    }

                    if (action.equalsIgnoreCase("status")) {
                        ctx.reply(Text.of().accent(wave.status()));
                        return;
                    }

                    int seconds = WaveExtinction.DEFAULT_DURATION_SECONDS;

                    if (!action.isEmpty()) {
                        try {
                            seconds = Integer.parseInt(action);
                        } catch (NumberFormatException e) {
                            ctx.fail(
                                    Text.of()
                                            .white(action)
                                            .scarlet(" is not seconds, 'stop' or 'status'.")
                            );
                            return;
                        }

                        if (
                                seconds < WaveExtinction.MIN_DURATION_SECONDS
                                        || seconds > WaveExtinction.MAX_DURATION_SECONDS
                        ) {
                            ctx.fail(
                                    Text.of().scarlet("Duration must be ")
                                            .white(WaveExtinction.MIN_DURATION_SECONDS)
                                            .scarlet(" to ")
                                            .white(WaveExtinction.MAX_DURATION_SECONDS)
                                            .scarlet(" seconds.")
                            );
                            return;
                        }
                    }

                    String failure = wave.start(seconds);

                    if (failure != null) {
                        ctx.fail(Text.of().scarlet(failure));
                        return;
                    }

                    ctx.success(
                            "Wave extinction started over " + seconds
                                    + " s. The center hex is the prize."
                    );
                });

        commands.installClient(handler);
    }
}
