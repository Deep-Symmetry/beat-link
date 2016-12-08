package org.deepsymmetry.beatlink;

import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Represents rekordbox metadata (title, artist, etc.) about tracks loaded into players on a DJ Link network.
 *
 * @author James Elliott
 */
public class TrackMetadata {

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
     * @param device the device number from which the metadata was received
     * @param fields the metadata that was received
     *
     * @throws UnsupportedEncodingException if there is a problem interpreting the metadata
     */
    public TrackMetadata(int device, List<byte[]> fields) throws UnsupportedEncodingException {
        rawFields = fields;

        Iterator<byte[]> iterator = fields.iterator();
        iterator.next();  // TODO: Consider extracting artwork
        iterator.next();
        title = extractString(iterator.next());
        artist = extractString(iterator.next());
        album = extractString(iterator.next());
        length = (int)Util.bytesToNumber(iterator.next(), 32, 4);
        iterator.next();
        comment = extractString(iterator.next());
        key = extractString(iterator.next());
        iterator.next();
        iterator.next();
        genre = extractString(iterator.next());
        label = extractString(iterator.next());
    }

    @Override
    public String toString() {
        return "Track Metadata: Title: " + title + ", Artist: " + artist + ", Album: " + album +
                ", Length: " + length + ", Comment: " + comment + ", Key: " + key +
                ", Genre: " + genre + ", Label: " + label;
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
    public String getlabel() {
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
