package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.beatlink.dbserver.StringField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
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
    public final DataReference trackReference;

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
    private SearchableItem album;

    /**
     * The artist that created the track.
     */
    private SearchableItem artist;

    /**
     * The color assigned to the track.
     */
    private ColorItem color;

    /**
     * The comment assigned to the track.
     */
    private SearchableItem comment;

    /**
     * The date the track was added to the collection.
     */
    private SearchableItem dateAdded;

    /**
     * The length, in seconds, of the track, when played at 100% pitch.
     */
    private int duration;

    /**
     * The musical genre of the track.
     */
    private SearchableItem genre;

    /**
     * The dominant key of the track.
     */
    private SearchableItem key;

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

    /**
     * Creates a searchable item that represents a metadata field found for a track.
     *
     * @param menuItem the rendered menu item containing the searchable metadata field
     * @return the searchable metadata field
     */
    private SearchableItem buildSearchableItem(Message menuItem) {
        return new SearchableItem((int) ((NumberField) menuItem.arguments.get(1)).getValue(),
                ((StringField) menuItem.arguments.get(3)).getValue());
    }

    /**
     * Creates a color item that represents a color field found for a track.
     *
     * @param menuItem the rendered menu item containing the color metadata field
     * @return the color metadata field
     */
    private ColorItem buildColorItem(Message menuItem) {
        int colorId = (int) ((NumberField) menuItem.arguments.get(1)).getValue();
        Color color;
        String colorName;
        switch (colorId) {
            case 0:
                color = new Color(0, 0, 0, 0);
                colorName = "No Color";
                break;
            case 1:
                color = Color.PINK;
                colorName = "Pink";
                break;
            case 2:
                color = Color.RED;
                colorName = "Red";
                break;
            case 3:
                color = Color.ORANGE;
                colorName = "Orange";
                break;
            case 4:
                color = Color.YELLOW;
                colorName = "Yellow";
                break;
            case 5:
                color = Color.GREEN;
                colorName = "Green";
                break;
            case 6:
                color = Color.CYAN;
                colorName = "Aqua";
                break;
            case 7:
                color = Color.BLUE;
                colorName = "Blue";
                break;
            case 8:
                color = new Color(128, 0, 128);
                colorName = "Purple";
                break;
            default:
                color = new Color(0, 0, 0, 0);
                colorName = "Unknown Color";
        }
        return new ColorItem(colorId, ((StringField) menuItem.arguments.get(3)).getValue(), color, colorName);
    }

    /**
     * Constructor for when reading from the network or from a cache file.
     * Sets all the interpreted fields based on the received response messages.
     *
     * @param reference the unique track reference that was used to request this track metadata
     * @param items the menu item responses that were received in response to the render menu request
     * @param cueList the cues associated with the track, if any
     */
    TrackMetadata(DataReference reference, List<Message> items, CueList cueList) {
        trackReference = reference;
        this.cueList = cueList;
        rawItems = Collections.unmodifiableList(new LinkedList<Message>(items));
        for (Message item : items) {
            switch (item.getMenuItemType()) {
                case TRACK_TITLE:
                    title = ((StringField) item.arguments.get(3)).getValue();
                    artworkId = (int) ((NumberField) item.arguments.get(8)).getValue();
                    break;

                case ARTIST:
                    artist = buildSearchableItem(item);
                    break;

                case ALBUM_TITLE:
                    album = buildSearchableItem(item);
                    break;

                case DURATION:
                    duration = (int) ((NumberField) item.arguments.get(1)).getValue();
                    break;

                case TEMPO:
                    tempo = (int) ((NumberField) item.arguments.get(1)).getValue();
                    break;

                case COMMENT:
                    comment = buildSearchableItem(item);
                    break;

//                case MY_TAG_1:
//                    comment = ((StringField)item.arguments.get(3)).getValue();  // TODO: This may need to be a separate text field!
//                    color = (int)((NumberField)item.arguments.get(1)).getValue();  // And this may need to be a separate color field.
//                    break;

                case KEY:
                    key = buildSearchableItem(item);
                    break;

                case RATING:
                    rating = (int) ((NumberField)item.arguments.get(1)).getValue();
                    break;

                case COLOR_NONE:
                case COLOR_AQUA:
                case COLOR_BLUE:
                case COLOR_GREEN:
                case COLOR_ORANGE:
                case COLOR_PINK:
                case COLOR_PURPLE:
                case COLOR_RED:
                case COLOR_YELLOW:
                    color = buildColorItem(item);
                    break;

                case GENRE:
                    genre = buildSearchableItem(item);
                    break;

                case DATE_ADDED:
                    dateAdded = buildSearchableItem(item);
                    break;

                default:
                    logger.warn("Ignoring track metadata item with unknown type: {}", item);
            }
        }
    }

    /**
     * Get the artist of the track.
     *
     * @return the track artist
     */
    public SearchableItem getArtist() {
        return artist;
    }

    /**
     * Get the artwork ID for the track. Will be zero for tracks that have no artwork.
     *
     * @return the value that can be used to request the artwork image, if any, associated with the track
     */
    @SuppressWarnings("WeakerAccess")
    public int getArtworkId() {
        return artworkId;
    }

    /**
     * Get the color assigned to the track.
     *
     * @return the track color
     */
    public ColorItem getColor() {
        return color;
    }

    /**
     * Get the comment assigned to the track.
     *
     * @return the track comment
     */
    public SearchableItem getComment() {
        return comment;
    }

    /**
     * Get the cue list associated with the track. Will be {@code null} if no hot cues, loops, or memory points are
     * found in the track.
     *
     * @return the hot cues, loops and memory points stored for the track, if any
     */
    @SuppressWarnings("WeakerAccess")
    public CueList getCueList() {
        return cueList;
    }

    /**
     * Get the date the track was added to the collection. This information seems to propagate from iTunes.
     *
     * @return the date the track was added to the collection, in the form "YYYY-MM-DD"
     */
    public SearchableItem getDateAdded() {
        return dateAdded;
    }

    /**
     * Get the duration of the track, in seconds.
     *
     * @return the track length in seconds, when played at 100% pitch
     */
    @SuppressWarnings("WeakerAccess")
    public int getDuration() {
        return duration;
    }

    /**
     * Get the genre of the track.
     *
     * @return the track genre
     */
    public SearchableItem getGenre() {
        return genre;
    }

    /**
     * Get the musical key of the track.
     *
     * @return the track key
     */
    public SearchableItem getKey() {
        return key;
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

    @Override
    public String toString() {
        return "Track Metadata[trackReference: " + trackReference +
                ", Title: " + title + ", Artist: " + artist + ", Album: " + album + ", Date Added: " + dateAdded +
                ", Duration: " + duration + ", Tempo: " + tempo + ", Comment: " + comment + ", Key: " + key +
                ", Rating: " + rating + ", Color: " + color + ", Genre: " + genre +
                ", Artwork ID: " + artworkId +"]";
    }
}
