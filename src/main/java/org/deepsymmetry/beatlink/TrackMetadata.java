package org.deepsymmetry.beatlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Represents rekordbox metadata (title, artist, etc.) about tracks loaded into players on a DJ Link network.
 *
 * @author James Elliott
 */
public class TrackMetadata {


    private static final Logger logger = LoggerFactory.getLogger(TrackMetadata.class.getName());

    /**
     * The raw packet data containing the metadata.
     */
    private final List<byte[]> rawFields;

    /**
     * Get the raw packet data, to analyze fields that have not yet been reliably understood.
     *
     * @return the raw bytes we received from the CDJ when asked for metadata
     */
    public List<byte[]> getRawFields() {
        List<byte[]> result = new LinkedList<byte[]>();
        for (byte[] field: rawFields) {
            byte[] copy = new byte[field.length];
            System.arraycopy(field, 0, copy, 0, field.length);
            result.add(copy);
        }
        return result;
    }

    /**
     * The title of the track.
     */
    private final String title;

    /**
     * The artist that created the track.
     */
    private final String artist;

    /**
     * The length, in seconds, of the track.
     */
    private final int length;

    /**
     * The album on which the track was released.
     */
    private final String album;

    /**
     * The comment assigned to the track.
     */
    private final String comment;

    /**
     * The dominant key of the track.
     */
    private final String key;

    /**
     * The musical genre of the track.
     */
    private final String genre;

    /**
     * The label of the track.
     */
    private final String label;

    /**
     * The value by which the track's artwork image can be requested.
     */
    private final int artworkId;

    /**
     * The actual track artwork, if any was available.
     */
    private final BufferedImage artwork;

    /**
     * Extracts the value of a string field from the metadata.
     *
     * @param field the field known to contain a string value.
     * @return the value present in that field.
     *
     * @throws UnsupportedEncodingException if there is a problem interpreting the metadata
     */
    private String extractString(byte[] field) throws UnsupportedEncodingException {
        if (field.length > 46) {
            int length = (int) Util.bytesToNumber(field, 42, 4);
            if (length > 0) {
                return new String(field, 46, (2 * (length - 1)), "UTF-16");
            }
        }
        return "";
    }

    /**
     * Constructor sets all the immutable interpreted fields based on the received content.
     *
     * @param fields the metadata that was received
     * @param artwork the track artwork image, if any, that was loaded
     *
     * @throws UnsupportedEncodingException if there is a problem interpreting the metadata
     */
    public TrackMetadata(List<byte[]> fields, BufferedImage artwork) throws UnsupportedEncodingException {
        rawFields = fields;
        this.artwork = artwork;

        Iterator<byte[]> iterator = fields.iterator();
        iterator.next();
        iterator.next();
        byte[] field = iterator.next();
        title = extractString(field);
        artworkId = (int)Util.bytesToNumber(field, field.length - 19, 4);

        artist = extractString(iterator.next());
        album = extractString(iterator.next());
        length = (int)Util.bytesToNumber(iterator.next(), 32, 4);
        iterator.next();
        comment = extractString(iterator.next());
        key = extractString(iterator.next());
        iterator.next();
        iterator.next();
        genre = extractString(iterator.next());
        String potentialLabel = null;
        try {
            potentialLabel = extractString(iterator.next());
        } catch (NoSuchElementException e) {
            logger.warn("Label field missing, old CDJ firmware?", e);
        }
        label = potentialLabel;
    }

    @Override
    public String toString() {
        return "Track Metadata: Title: " + title + ", Artist: " + artist + ", Album: " + album +
                ", Length: " + length + ", Comment: " + comment + ", Key: " + key +
                ", Genre: " + genre + ", Label: " + label + ", artwork ID: " + artworkId;
    }

    /**
     * Get the artist of the track.
     *
     * @return the track artist
     */
    public String getArtist() {
        return artist;
    }

    /**
     * Get the track artwork.
     *
     * @return the artwork image associated with the track, if any
     */
    public BufferedImage getArtwork() {
        return artwork;
    }

    /**
     * Get the artwork ID for the track. Will be zero for tracks that have no artwork.
     *
     * @return the value that can be used to request the artwork image, if any, associated with the track
     */
    public int getArtworkId() {
        return artworkId;
    }

    /**
     * Get the comment assigned to the track.
     *
     * @return the track comment
     */
    public String getComment() {
        return comment;
    }

    /**
     * Get the genre of the track.
     *
     * @return the track genre
     */
    public String getGenre() {
        return genre;
    }

    /**
     * Get the musical key of the track.
     *
     * @return the track key
     */
    public String getKey() {
        return key;
    }

    /**
     * Get the label assigned the track.
     *
     * @return the track label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Get the length of the track, in seconds.
     *
     * @return the track length in seconds.
     */
    public int getLength() {
        return length;
    }

    /**
     * Get the title of the track.
     *
     * @return the track title
     */
    public String getTitle() {
        return title;
    }
}
