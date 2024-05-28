package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;

/**
 * <p>The listener interface for receiving updates when a Beat Link component is started or stopped.</p>
 *
 * <p>Classes that depend on something (like the {@link DeviceFinder}, {@link VirtualCdj}, or
 * {@link org.deepsymmetry.beatlink.data.MetadataFinder}) can implement this interface so they know to shut themselves
 * down when the subsystem they are reliant upon has done so.</p>
 *
 * @author James Elliott
 */
@API(status = API.Status.STABLE)
public interface LifecycleListener {

    /**
     * Called when the subsystem has started up.
     *
     * @param sender the subsystem reporting this event, in case you want to use a single listener to hear from all
     *               the components you depend on
     */
    @API(status = API.Status.STABLE)
    void started(LifecycleParticipant sender);

    /**
     * Called when the subsystem has shut down.
     *
     * @param sender the subsystem reporting this event, in case you want to use a single listener to hear from all
     *               the components you depend on
     */
    @API(status = API.Status.STABLE)
    void stopped(LifecycleParticipant sender);
}
