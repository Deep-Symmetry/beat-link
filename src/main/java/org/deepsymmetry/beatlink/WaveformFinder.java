package org.deepsymmetry.beatlink;

/**
 * TODO: Document and implement, i.e. everything. :)
 */
public class WaveformFinder extends LifecycleParticipant {

    /**
     * Holds the singleton instance of this class.
     */
    private static final WaveformFinder ourInstance = new WaveformFinder();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static WaveformFinder getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private WaveformFinder() {
    }

    @Override
    public String toString() {
        return "WaveformFinder[]";  // TODO: Flesh out!
    }

    @Override
    public boolean isRunning() {
        return false;  // TODO: Make real!
    }
}
