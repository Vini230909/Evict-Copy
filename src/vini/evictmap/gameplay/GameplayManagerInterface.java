package vini.evictmap.gameplay;

public interface GameplayManagerInterface {
    /**
     * Called at the beginning of every round.
     */
    void beginRound();
    
    /**
     * Called every update event.
     */
    void update();

    /**
     * Called at the end of every round.
     */
    void endRound();
}
