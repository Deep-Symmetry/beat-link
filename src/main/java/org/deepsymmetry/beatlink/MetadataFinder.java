package org.deepsymmetry.beatlink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches for new tracks to be loaded on players, and queries the
 * appropriate player for the metadata information when that happens.<p>
 *
 * <strong>THIS CLASS IS NOT YET READY FOR USE.</strong><p>
 *
 * Although it worked great for an entire weekend during which my
 * network configuration remained constant, as soon as I reconfigured
 * the network to remove the managed switch I was using to watch
 * traffic between CDJs, the particular packets which had been working
 * started to crash the process in the CDJs which responds to metadata
 * queries, meaning they would need to be turned off and back on
 * before any other CDJ could get Link Info from them.<p>
 *
 * We need to figure out how the byte patterns below need to change
 * based on the network configuration, or based on values found in the
 * device announcement or status packets, or in earlier response
 * packets, in order to make this reliable and safe to use.<p>
 *
 * This goes along with the comment on the {@code usbPacketTemplates}
 * array; perhaps if we can understand what is different about the
 * packets that need to be sent to each player, we can understand how
 * to accommodate the network configuration, and how to construct
 * packets that always work.
 *
 * @author James Elliott
 */
public class MetadataFinder {

    private static final Logger logger = LoggerFactory.getLogger(MetadataFinder.class.getName());

    /**
     * The port on which we contact players to ask them for metadata information.
     */
    public static final int METADATA_PORT = 1051;

    /**
     * Given a status update from a CDJ, find the metadata for the track that it has loaded, if any.
     *
     * @param status the CDJ status update that will be used to determine the loaded track and ask the appropriate
     *               player for metadata about it
     * @return the metadata that was obtained, if any
     */
    public static TrackMetadata requestMetadataFrom(CdjStatus status) {
        if (status.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK || status.getRekordboxId() == 0) {
            return null;
        }
        return requestMetadataFrom(status.getTrackSourcePlayer(), status.getTrackSourceSlot(), status.getRekordboxId());
    }

    /**
     * The series of packets needed to ask a particular player for metadata about a track in its USB slot. The outer
     * array is for sending requests to players numbered 1 through 4, and the inner array is the series of three packets
     * to send to request metadata. The middle packet will get the track ID added as a final four bytes.
     *
     * I would be much happier if we could figure out how to properly construct these packets ourselves rather than
     * having to replay a custom packet capture for each configuration.
     */
    private static byte[][][] usbPacketTemplates = {
            {  // Player 1 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x68, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11 // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x69, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }

            },
            {  // Player 2 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0x59, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11  // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0x5a, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }

            },
            {  // Player 3 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x02
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0x4b, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x02, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11  // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0x4c, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x02, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            },
            {  // Player 4 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03,
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x48, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11  // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x49, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x03, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            }
    };

    /**
     * The series of packets needed to ask a particular player for metadata about a track in its SD card slot. The outer
     * array is for sending requests to players numbered 1 through 4, and the inner array is the series of three packets
     * to send to request metadata. The middle packet will get the track ID added as a final four bytes.
     *
     * I would be much happier if we could figure out how to properly construct these packets ourselves rather than
     * having to replay a custom packet capture for each configuration.
     */
    private static byte[][][] sdPacketTemplates = {
            { // Player 1 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0xfe, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11  // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0xff, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            },
            {  // Player 2 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0xab, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11  // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x00, (byte)0xac, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            },
            {  // Player 3 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x02
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x02, (byte)0x34, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x02, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11 // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x02, (byte)0x35, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x02, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            },
            {  // Player 4 packets
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0xff, (byte)0xff,
                            (byte)0xff, (byte)0xfe, (byte)0x10, (byte)0x00, (byte)0x00, (byte)0x0f, (byte)0x01, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x03
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x24, (byte)0x10, (byte)0x20, (byte)0x02, (byte)0x0f, (byte)0x02, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11 // Track ID (4 bytes) go here
                    },
                    {
                            (byte)0x11, (byte)0x87, (byte)0x23, (byte)0x49, (byte)0xae, (byte)0x11, (byte)0x03, (byte)0x80,
                            (byte)0x01, (byte)0x25, (byte)0x10, (byte)0x30, (byte)0x00, (byte)0x0f, (byte)0x06, (byte)0x14,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0c, (byte)0x06, (byte)0x06, (byte)0x06, (byte)0x06,
                            (byte)0x06, (byte)0x06, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x11, (byte)0x03, (byte)0x01, (byte)0x02, (byte)0x01, (byte)0x11, (byte)0x00, (byte)0x00,
                            (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x0a, (byte)0x11,
                            (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00,
                            (byte)0x0a, (byte)0x11, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00
                    }
            }
    };

    /**
     * The first packet that gets sent to any player when setting up to request metadata.
     */
    private static byte[] initialPacket = {0x11, 0x00, 0x00, 0x00, 0x01};

    /**
     * Receive some bytes from the player we are requesting metadata from.
     *
     * @param is the input stream associated with the player metadata socket
     * @return the bytes read
     *
     * @throws IOException if there is a problem reading the response
     */
    private static byte[] receiveBytes(InputStream is) throws IOException {
        byte[] buffer = new byte[8192];
        int len = (is.read(buffer));
        byte[] result = new byte[len];
        System.arraycopy(buffer, 0, result, 0, len);
        return result;
    }

    /**
     * Ask the specified player for metadata about the track in the specified slot with the specified rekordbox ID.
     *
     * @param player the player number whose track is of interest
     * @param slot the slot in which the track can be found
     * @param rekordboxId the track of interest
     * @return the metadata, if any
     */
    public static TrackMetadata requestMetadataFrom(int player, CdjStatus.TrackSourceSlot slot, int rekordboxId) {
        final DeviceAnnouncement deviceAnnouncement = DeviceFinder.getLatestAnnouncementFrom(player);
        if (deviceAnnouncement == null || player < 1 || player > 4) {
            return null;
        }
        byte[][] templates;
        if (slot == CdjStatus.TrackSourceSlot.USB_SLOT) {
            templates = usbPacketTemplates[player - 1];
        } else if (slot == CdjStatus.TrackSourceSlot.SD_SLOT) {
            templates = sdPacketTemplates[player - 1];
        } else {
            return null;
        }

        Socket socket = null;
        try {
            socket = new Socket(deviceAnnouncement.getAddress(), METADATA_PORT);
            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();
            socket.setSoTimeout(3000);

            // Send the first two packets
            os.write(initialPacket);
            receiveBytes(is);
            os.write(templates[0]);
            receiveBytes(is);

            // Set up the packet that specifies the track we are interested in
            byte[] buffer = new byte[templates[1].length + 4];
            System.arraycopy(templates[1], 0, buffer, 0, templates[1].length);
            buffer[buffer.length - 4] = (byte)(rekordboxId >> 24);
            buffer[buffer.length - 3] = (byte)(rekordboxId >> 16);
            buffer[buffer.length - 2] = (byte)(rekordboxId >>8);
            buffer[buffer.length - 1] = (byte)rekordboxId;

            // Send the last two packets
            os.write(buffer);
            receiveBytes(is);
            os.write(templates[2]);
            byte[] result = receiveBytes(is);

            return new TrackMetadata(player, result);
        } catch (Exception e) {
            logger.warn("Problem requesting metadata", e);
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.warn("Problem closing metadata request socket", e);
                }
            }
        }
        return null;
    }

    /**
     * Keeps track of the current metadata known for each player.
     */
    private static final Map<Integer, TrackMetadata> metadata = new HashMap<Integer, TrackMetadata>();

    /**
     * Keeps track of the previous update from each player that we retrieved metadata about, to check whether a new
     * track has been loaded.
     */
    private static final Map<InetAddress, CdjStatus> lastUpdates = new HashMap<InetAddress, CdjStatus>();

    /**
     * A queue used to hold CDJ status updates we receive from the {@link VirtualCdj} so we can process them on a
     * lower priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static LinkedBlockingDeque<CdjStatus> pendingUpdates = new LinkedBlockingDeque<CdjStatus>(100);

    /**
     * Our listener method just puts appropriate device updates on our queue, so we can process them on a lower
     * priority thread, and not hold up delivery to more time-sensitive listeners.
     */
    private static DeviceUpdateListener listener = new DeviceUpdateListener() {
        @Override
        public void received(DeviceUpdate update) {
            //logger.log(Level.INFO, "Received: " + update);
            if (update instanceof CdjStatus) {
                //logger.log(Level.INFO, "Queueing");
                if (!pendingUpdates.offerLast((CdjStatus)update)) {
                    logger.warn("Discarding CDJ update because our queue is backed up.");
                }
            }
        }
    };

    /**
     * Keep track of whether we are running
     */
    private static boolean running = false;

    /**
     * Check whether we are currently running.
     *
     * @return true if track metadata is being sought for all active players
     */
    public static synchronized boolean isRunning() {
        return running;
    }

    /**
     * We process our updates on a separate thread so as not to slow down the high-priority update delivery thread;
     * we perform potentially slow I/O.
     */
    private static Thread queueHandler;

    /**
     * We have received an update that invalidates any previous metadata for that player, so clear it out.
     *
     * @param update the update which means we can have no metadata for the associated player.
     */
    private static synchronized void clearMetadata(CdjStatus update) {
        metadata.remove(update.deviceNumber);
        lastUpdates.remove(update.address);
        // TODO: Add update listener
    }

    /**
     * We have obtained metadata for a device, so store it.
     *
     * @param update the update which caused us to retrieve this metadata
     * @param data the metadata which we received
     */
    private static synchronized void updateMetadata(CdjStatus update, TrackMetadata data) {
        metadata.put(update.deviceNumber, data);
        lastUpdates.put(update.address, update);
        // TODO: Add update listener
    }

    /**
     * Get all currently known metadata.
     *
     * @return the track information reported by all current players
     */
    public static synchronized Map<Integer, TrackMetadata> getLatestMetadata() {
        return Collections.unmodifiableMap(new TreeMap<Integer, TrackMetadata>(metadata));
    }

    /**
     * Look up the track metadata we have for a given player number.
     *
     * @param player the device number whose track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static synchronized TrackMetadata getLatestMetadataFor(int player) {
        return metadata.get(player);
    }

    /**
     * Look up the track metadata we have for a given player, identified by a status update received from that player.
     *
     * @param update a status update from the player for which track metadata is desired
     * @return information about the track loaded on that player, if available
     */
    public static TrackMetadata getLatestMetadataFor(DeviceUpdate update) {
        return getLatestMetadataFor(update.deviceNumber);
    }

    /**
     * Process an update packet from one of the CDJs. See if it has a valid track loaded; if not, clear any
     * metadata we had stored for that player. If so, see if it is the same track we already know about; if not,
     * request the metadata associated with that track.
     *
     * @param update an update packet we received from a CDJ
     */
    private static void handleUpdate(CdjStatus update) {
        if (update.getTrackSourcePlayer() >= 1 && update.getTrackSourcePlayer() <= 4) {  // We only know how to talk to these devices
            if (update.getTrackType() != CdjStatus.TrackType.REKORDBOX ||
                    update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.NO_TRACK ||
                    update.getTrackSourceSlot() == CdjStatus.TrackSourceSlot.UNKNOWN ||
                    update.getRekordboxId() == 0) {  // We no longer have metadata for this device
                clearMetadata(update);
            } else {  // We can gather metadata for this device; check if we already looked up this track
                CdjStatus lastStatus = lastUpdates.get(update.address);
                if (lastStatus == null || lastStatus.getTrackSourceSlot() != update.getTrackSourceSlot() ||
                        lastStatus.getTrackSourcePlayer() != update.getTrackSourcePlayer() ||
                        lastStatus.getRekordboxId() != update.getRekordboxId()) {  // We have something new!
                    try {
                        TrackMetadata data = requestMetadataFrom(update);
                        if (data != null) {
                            updateMetadata(update, data);
                        }
                    } catch (Exception e) {
                        logger.warn("Problem requesting track metadata from update" + update, e);
                    }
                }
            }

        }
    }

    /**
     * Start finding track metadata for all active players. Starts the {@link VirtualCdj} if it is not already
     * running, because we need it to send us device status updates to notice when new tracks are loaded.
     *
     * @throws Exception if there is a problem starting the required components
     */
    public static synchronized void start() throws Exception {
        if (!running) {
            VirtualCdj.start();
            VirtualCdj.addUpdateListener(listener);
            queueHandler = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (isRunning()) {
                        try {
                            handleUpdate(pendingUpdates.take());
                        } catch (InterruptedException e) {
                            // Interrupted due to MetadataFinder shutdown, presumably
                        }
                    }
                }
            });
            running = true;
            queueHandler.start();
        }
    }

    /**
     * Stop finding track metadata for all active players.
     */
    public static synchronized void stop() {
        if (running) {
            VirtualCdj.removeUpdateListener(listener);
            running = false;
            pendingUpdates.clear();
            queueHandler.interrupt();
            queueHandler = null;
            lastUpdates.clear();
            metadata.clear();
        }
    }
}
