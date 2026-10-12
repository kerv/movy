package sx.movy.tv;

/** A held remote key is not a second press; callers filter key repeats. */
final class PlaybackExitGuard {
    static final long WINDOW_MS=2500;
    private long firstPress=-1;
    boolean press(long now){if(firstPress>=0&&now>=firstPress&&now-firstPress<=WINDOW_MS){reset();return true;}firstPress=now;return false;}
    void reset(){firstPress=-1;}
}
