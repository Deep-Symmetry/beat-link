package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.dbserver.Client;
import org.deepsymmetry.beatlink.dbserver.ConnectionManager;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provides support for navigating the menu hierarchy offered by the dbserver on a player for a particular media slot.
 * Note that for historical reasons, loading track metadata, playlists, and the full track list are performed by the
 * {@link MetadataFinder}, even though those are technically menu operations.
 *
 * @since 0.4.0
 *
 * @author James Elliott
 */
public class MenuLoader {

    private static final Logger logger = LoggerFactory.getLogger(MenuLoader.class);

    /**
     * Ask the specified player for its top-level menu of menus. This is only believed to work for media slots that
     * contain a rekordbox database; a different form of message is used for those that do not. We need to figure out
     * how to tell in advance whether one exists; there seems to be a packet type that causes a player to send some
     * sort of media-information response that may well be the key.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 6.11.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on the root menu
     *
     * @return the entries in the top level menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    public List<Message> requestRootMenuFrom(final SlotReference slotReference, final int sortOrder)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
                if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                    try {
                        logger.debug("Requesting root menu.");
                        Message response = client.menuRequest(Message.KnownType.ROOT_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                                new NumberField(sortOrder), new NumberField(0xffffff));
                        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                    } finally {
                        client.unlockForMenuOperations();
                    }
                } else {
                    throw new TimeoutException("Unable to lock player for menu operations.");
                }
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting root menu");
    }

    /**
     * Ask the specified player for a History menu.
     *
     * @param slotReference the player and slot for which the menu is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 6.11.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details, although it does not seem to have an effect on the history menu
     *
     * @return the entries in the history menu
     *
     * @throws Exception if there is a problem obtaining the menu
     */
    public List<Message> requestHistoryMenuFrom(final SlotReference slotReference, final int sortOrder)
        throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
                if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                    try {
                        logger.debug("Requesting History menu.");
                        Message response = client.menuRequest(Message.KnownType.HISTORY_MENU_REQ, Message.MenuIdentifier.MAIN_MENU, slotReference.slot,
                                new NumberField(sortOrder));
                        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                    } finally {
                        client.unlockForMenuOperations();
                    }
                } else {
                    throw new TimeoutException("Unable to lock player for menu operations.");
                }
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting history menu");
    }

    /**
     * Ask the specified player a History playlist.
     *
     * @param slotReference the player and slot for which the playlist is desired
     * @param sortOrder the order in which responses should be sorted, 0 for default, see Section 6.11.1 of the
     *                  <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis
     *                  document</a> for details
     * @param historyId identifies which history session's playlist is desired
     *
     * @return the entries in the history playlist
     *
     * @throws Exception if there is a problem obtaining the playlist
     */
    public List<Message> requestHistoryPlaylistFrom(final SlotReference slotReference, final int sortOrder, final int historyId)
            throws Exception {

        ConnectionManager.ClientTask<List<Message>> task = new ConnectionManager.ClientTask<List<Message>>() {
            @Override
            public List<Message> useClient(Client client) throws Exception {
                if (client.tryLockingForMenuOperations(MetadataFinder.MENU_TIMEOUT, TimeUnit.SECONDS)) {
                    try {
                        logger.debug("Requesting History menu.");
                        Message response = client.menuRequest(Message.KnownType.TRACK_MENU_FOR_HISTORY_REQ, Message.MenuIdentifier.MAIN_MENU,
                                slotReference.slot, new NumberField(sortOrder), new NumberField(historyId));
                        return client.renderMenuItems(Message.MenuIdentifier.MAIN_MENU, slotReference.slot, CdjStatus.TrackType.REKORDBOX, response);
                    } finally {
                        client.unlockForMenuOperations();
                    }
                } else {
                    throw new TimeoutException("Unable to lock player for menu operations.");
                }
            }
        };

        return ConnectionManager.getInstance().invokeWithClientSession(slotReference.player, task, "requesting history menu");
    }

    /**
     * Holds the singleton instance of this class.
     */
    private static final MenuLoader ourInstance = new MenuLoader();

    /**
     * Get the singleton instance of this class.
     *
     * @return the only instance of this class which exists.
     */
    public static MenuLoader getInstance() {
        return ourInstance;
    }

    /**
     * Prevent direct instantiation.
     */
    private MenuLoader() {
        // Nothing to do.
    }
}
