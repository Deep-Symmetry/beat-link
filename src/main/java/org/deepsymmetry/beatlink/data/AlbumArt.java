package org.deepsymmetry.beatlink.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * <p>Represents album artwork associated with tracks loaded into players on a DJ Link network, and provides a
 * convenience method for getting a buffered image for drawing the art.</p>
 *
 * @author James Elliott
 */
public class AlbumArt {

    private static final Logger logger = LoggerFactory.getLogger(AlbumArt.class);

    /**
     * The unique artwork identifier that was used to request this album art. Even though it is not a track, the
     * same pieces of information are used.
     */
    @SuppressWarnings("WeakerAccess")
    public final DataReference artReference;

    /**
     * The raw bytes of the artwork as loaded from the player.
     */
    private final ByteBuffer rawBytes;

    /**
     * Get the raw bytes of the artwork image as returned by the player.
     *
     * @return the bytes that make up the album art
     */
    public ByteBuffer getRawBytes() {
        rawBytes.rewind();
        return rawBytes.slice();
    }

    /**
     * Given the byte buffer containing album art, build an actual image from it for easy rendering.
     *
     * @return the newly-created image, ready to be drawn
     */
    public BufferedImage getImage() {
        ByteBuffer artwork = getRawBytes();
        artwork.rewind();
        byte[] imageBytes = new byte[artwork.remaining()];
        artwork.get(imageBytes);
        try {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            logger.error("Weird! Caught exception creating image from artwork bytes", e);
            return null;
        }
    }

    /**
     * Constructor simply sets the immutable value fields.
     *
     * @param artReference the unique database reference that was used to request this artwork
     * @param rawBytes the bytes of image data as loaded from the player
     */
    AlbumArt(DataReference artReference, ByteBuffer rawBytes) {
        this.artReference = artReference;
        this.rawBytes = rawBytes;
    }
}
