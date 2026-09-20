// The Sandbox room on a worker: its owner, guests promoted in with /invite, their /s and /die.
package Extinction;

import java.util.List;

import mindustry.gen.Player;

public final class Sandbox {

    private final Referee referee;

    // The player who started the room (the sole launch participant); guests are never the owner.
    private String ownerUuid = "";

    public Sandbox(Referee referee) {
        this.referee = referee;
    }

    public String ownerUuid() {
        return ownerUuid;
    }

    void claimOwner(String uuid) {
        this.ownerUuid = uuid;
    }

    // True on a Sandbox worker (a persistent build room rather than a gated match).
    public boolean isSandbox() {
        return referee.isActive()
                && referee.handshakeLoaded()
                && referee.matchMode() == MatchMode.SANDBOX;
    }

    // The owner ends the room with /die; guests only leave with /s.
    public boolean isOwner(String uuid) {
        return uuid != null && !uuid.isEmpty() && uuid.equals(ownerUuid);
    }

    // Promotes a spectator into the sandbox roster; re-clears an earlier /s "out" mark.
    public void addParticipant(Player player) {
        if (
                !referee.isActive()
                        || !referee.handshakeLoaded()
                        || !referee.matchMode().allowsSpectatorInvites()
                        || player == null
                        || referee.rosterTeams().isEmpty()
        ) {
            return;
        }

        referee.outUuids().remove(player.uuid());

        if (referee.participantUuids().add(player.uuid())) {
            referee.rosterTeams().get(0).add(player.uuid());
        }
    }

    // A guest leaving with /s: off the roster, marked out so the hub stops bouncing them back.
    private void leave(Player player) {
        String uuid = player.uuid();

        referee.participantUuids().remove(uuid);
        referee.outUuids().add(uuid);

        for (List<String> roster : referee.rosterTeams()) {
            roster.remove(uuid);
        }

        referee.exit.returnSpectatorToHub(player);
    }

    // /s by a sandbox participant. True when handled; false means "not a sandbox, refuse normally".
    public boolean handleLeave(Player player) {
        if (!isSandbox() || player == null) {
            return false;
        }

        if (isOwner(player.uuid())) {
            player.sendMessage(
                    "[accent]You own this sandbox - end it with [white]/die[accent], or just leave the server.[]"
            );
            return true;
        }

        leave(player);
        return true;
    }

    // /die on a sandbox worker. True when handled; false means "run the normal surrender path".
    public boolean handleDie(Player player) {
        if (!isSandbox() || player == null) {
            return false;
        }

        if (referee.resolved() || !referee.isParticipant(player.uuid())) {
            return true;
        }

        if (isOwner(player.uuid())) {
            referee.endSoloSession("sandbox-ended");
        } else {
            player.sendMessage(
                    "[accent]Only the sandbox owner can end it. Use [white]/s[accent] to leave.[]"
            );
        }

        return true;
    }
}
