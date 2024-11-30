package org.deepsymmetry.beatlink;

import org.apiguardian.api.API;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Provides the abstract skeleton for all the classes that can be started and stopped in Beat Link, and for which
 * other classes may have a need to know when they start or stop.
 */
@API(status = API.Status.STABLE)
public abstract class LifecycleParticipant {

    /**
     * Keeps track of the registered device announcement listeners.
     */
    private final List<WeakReference<LifecycleListener>> lifecycleListeners = new LinkedList<>();

    /**
     * <p>Adds the specified life cycle listener to receive announcements when the component starts and stops.
     * If {@code listener} is {@code null} or already present in the list
     * of registered listeners, no exception is thrown and no action is performed. Presence on a listener list does not
     * prevent an object from being garbage-collected if it has no other references.</p>
     *
     * <p>Lifecycle announcements are delivered to listeners on a separate thread to avoid worries about deadlock in
     * synchronized start and stop methods. The called function should still be fast, or delegate long operations to
     * its own separate thread.</p>
     *
     * @param listener the device announcement listener to add
     */
    @API(status = API.Status.STABLE)
    public synchronized void addLifecycleListener(LifecycleListener listener) {
        Util.addListener(lifecycleListeners, listener);
    }

    /**
     * Removes the specified life cycle listener so that it no longer receives announcements when
     * the component starts or stops. If {@code listener} is {@code null} or not present
     * in the list of registered listeners, no exception is thrown and no action is performed.
     *
     * @param listener the life cycle listener to remove
     */
    @API(status = API.Status.STABLE)
    public synchronized void removeLifecycleListener(LifecycleListener listener) {
        Util.removeListener(lifecycleListeners, listener);
    }

    /**
     * Get the set of lifecycle listeners that are currently registered.
     *
     * @return the currently registered lifecycle listeners
     */
    @API(status = API.Status.STABLE)
    public synchronized Set<LifecycleListener> getLifecycleListeners() {
        // Make a copy so the caller gets an immutable snapshot of the current moment in time.
        return Collections.unmodifiableSet(Util.gatherListeners(lifecycleListeners));
    }

    /**
     * Send a lifecycle announcement to all registered listeners.
     *
     * @param logger the logger to use, so the log entry shows as belonging to the proper subclass.
     * @param starting will be {@code true} if the DeviceFinder is starting, {@code false} if it is stopping.
     */
    protected void deliverLifecycleAnnouncement(final Logger logger, final boolean starting) {
        new Thread(() -> {
            for (final LifecycleListener listener : getLifecycleListeners()) {
                try {
                    if (starting) {
                        listener.started(LifecycleParticipant.this);
                    } else {
                        listener.stopped(LifecycleParticipant.this);
                    }
                } catch (Throwable t) {
                    logger.warn("Problem delivering lifecycle announcement to listener", t);
                }
            }
        }, "Lifecycle announcement delivery").start();
    }

    /**
     * Check whether this component has been started.
     *
     * @return the component has started successfully and is ready to perform any service it offers.
     */
    @API(status = API.Status.STABLE)
    abstract public boolean isRunning();

    /**
     * Helper method to throw an {@link IllegalStateException} if we are not currently running.
     *
     * @throws IllegalStateException if the component is not running
     */
    protected void ensureRunning() {
        if (!isRunning()) {
            throw new IllegalStateException(this.getClass().getName() + " is not running");
        }
    }
}
