package org.deepsymmetry.beatlink;

import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.beatlink.dbserver.StringField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Represents rekordbox metadata (title, artist, etc.) about tracks loaded into players on a DJ Link network.
 *
 * @author James Elliott
 */
public class TrackMetadata {

    private static final Logger logger = LoggerFactory.getLogger(TrackMetadata.class.getName());

    /**
     * The unique track identifier that was used to request this track metadata.
     */
    @SuppressWarnings("WeakerAccess")
    public final TrackReference trackReference;

    /**
     * The raw dbserver messages containing the metadata when it was read over the network.
     * Can be used to analyze fields that have not yet been reliably understood,
     * and is also used for storing the metadata in a cache file.
     */
    @SuppressWarnings("WeakerAccess")
    public final List<Message> rawItems;

    /**
     * The album on which the track was released.
     */
    private String album;

    /**
     * The artist that created the track.
     */
    private String artist;

    /**
     * The color assigned to the track.
     */
    private int color;

    /**
     * The comment assigned to the track.
     */
    private String comment;

    /**
     * The date the track was added to the collection.
     */
    private String dateAdded;

    /**
     * The length, in seconds, of the track, when played at 100% pitch.
     */
    private int duration;

    /**
     * The musical genre of the track.
     */
    private String genre;

    /**
     * The dominant key of the track.
     */
    private String key;

    // TODO: Add Label information to my SD card so I can see how this is transmitted and add it. Same with color.
    /**
     * The label of the track.
     */
    private String label;

    /**
     * The rating assigned to the track.
     */
    private int rating;

    /**
     * The initial tempo of the track, BPM times 100.
     */
    private int tempo;

    /**
     * The title of the track.
     */
    private String title;

    /**
     * The value by which the track's artwork image can be requested.
     */
    private int artworkId;

    /**
     * The cue list, if any, associated with the track.
     */
    private final CueList cueList;

    // TODO: Add fields like artist ID, genre ID, key ID, etc.

    /**
     * Constructor for when reading from the network or from a cache file.
     * Sets all the interpreted fields based on the received response messages.
     *
     * @param reference the unique track reference that was used to request this track metadata
     * @param items the menu item responses that were received in response to the render menu request
     * @param cueList the cues associated with the track, if any
     */
    TrackMetadata(TrackReference reference, List<Message> items, CueList cueList) {
        trackReference = reference;
        this.cueList = cueList;
        rawItems = Collections.unmodifiableList(new LinkedList<Message>(items));
        for (Message item : items) {
            switch (item.getMenuItemType()) {
                case TRACK_TITLE:
                    title = ((StringField)item.arguments.get(3)).getValue();
                    artworkId = (int)((NumberField)item.arguments.get(8)).getValue();
                    break;

                case ARTIST:
                    artist = ((StringField)item.arguments.get(3)).getValue();
                    break;

                case ALBUM_TITLE:
                    album = ((StringField)item.arguments.get(3)).getValue();
                    break;

                case DURATION:
                    duration = (int)((NumberField)item.arguments.get(1)).getValue();
                    break;

                case TEMPO:
                    tempo = (int)((NumberField)item.arguments.get(1)).getValue();
                    break;

                case COMMENT:
                    comment = ((StringField)item.arguments.get(3)).getValue();
                    break;

                case MY_TAG_1:
                case MY_TAG_3:
                    comment = ((StringField)item.arguments.get(3)).getValue();  // TODO: This may need to be a separate text field!
                    color = (int)((NumberField)item.arguments.get(1)).getValue();  // And this may need to be a separate color field.
                    break;

                case KEY:
                    key = ((StringField)item.arguments.get(3)).getValue();
                    break;

                case RATING:
                    rating = (int)((NumberField)item.arguments.get(1)).getValue();
                    break;

                case COLOR:
                    color = (int)((NumberField)item.arguments.get(1)).getValue();
                    break;

                case GENRE:
                    genre = ((StringField)item.arguments.get(3)).getValue();
                    break;

                case DATE_ADDED:
                    dateAdded = ((StringField)item.arguments.get(3)).getValue();
                    break;

                default:
                    logger.warn("Ignoring track metadata item with unknown type: {}", item);
            }
        }
    }

    @Override
    public String toString() {
        return "Track Metadata[trackReference: " + trackReference +
                ", Title: " + title + ", Artist: " + artist + ", Album: " + album +
                ", Duration: " + duration + ", Tempo: " + tempo + ", Comment: " + comment + ", Key: " + key +
                ", Rating: " + rating + ", Color: " + color + ", Genre: " + genre + ", Label: " + label +
                ", Artwork ID: " + artworkId +"]";
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
     * Get the artwork ID for the track. Will be zero for tracks that have no artwork.
     *
     * @return the value that can be used to request the artwork image, if any, associated with the track
     */
    public int getArtworkId() {
        return artworkId;
    }

    /**
     * Get the color assigned to the track.
     *
     * @return the track color
     */
    public int getColor() {
        return color;
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
     * Get the cue list associated with the track. Will be {@code null} if no hot cues, loops, or memory points are
     * found in the track.
     *
     * @return the hot cues, loops and memory points stored for the track, if any
     */
    public CueList getCueList() {
        return cueList;
    }

    /**
     * Get the date the track was added to the collection. This information seems to propagate from iTunes.
     *
     * @return the date the track was added to the collection, in the form "YYYY-MM-DD"
     */
    public String getDateAdded() {
        return dateAdded;
    }

    /**
     * Get the duration of the track, in seconds.
     *
     * @return the track length in seconds, when played at 100% pitch
     */
    public int getDuration() {
        return duration;
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
     * Get the rating assigned the track.
     *
     * @return the track rating
     */
    public int getRating() {
        return rating;
    }

    /**
     * Get the starting tempo of the track.
     *
     * @return the initial track tempo, BPM times 100.
     */
    public int getTempo() {
        return tempo;
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
