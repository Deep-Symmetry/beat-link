package org.deepsymmetry.beatlink.data;

import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.beatlink.dbserver.StringField;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.Collections;
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
     * The unique track identifier that was used to request this track metadata.
     */
    @SuppressWarnings("WeakerAccess")
    public final DataReference trackReference;

    /**
     * The type of track described by this metadata.
     */
    @SuppressWarnings("WeakerAccess")
    public final CdjStatus.TrackType trackType;

    /**
     * The raw dbserver messages containing the metadata when it was read over the network.
     * Can be used to analyze fields that have not yet been reliably understood,
     * and is also used for storing the metadata in a cache file. Will be {@code null} if
     * the metadata was constructed from a {@link org.deepsymmetry.cratedigger.pdb.RekordboxPdb.TrackRow}.
     *
     * @see #rawRow
     */
    @SuppressWarnings("WeakerAccess")
    public final List<Message> rawItems;

    /**
     * The raw row within a rekordbox database export from which this metadata was created,
     * if any. Will be {@code null} if it was read from a dbserver metadata response.
     *
     * @see #rawItems
     */
    @SuppressWarnings("WeakerAccess")
    public final RekordboxPdb.TrackRow rawRow;

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
    private SearchableItem genre;

    /**
     * The dominant key of the track.
     */
    private SearchableItem key;

    /**
     * The recording label that issued the track.
     */
    private SearchableItem label;

    /**
     * The artist that originally recorded the track.
     */
    private SearchableItem originalArtist;

    /**
     * The rating assigned to the track.
     */
    private int rating;

    /**
     * The producer who remixed the track.
     */
    private SearchableItem remixer;

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
     * The cue list, if any, associated with the track. Will be {@code null} if no hot cues, loops, or memory points are
     * found in the track.
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
     * Creates a color item that represents a color field found for a track based on a dbserver message.
     *
     * @param menuItem the rendered menu item containing the color metadata field
     *
     * @return the color metadata field
     */
    private ColorItem buildColorItem(Message menuItem) {
        final int colorId = (int) ((NumberField) menuItem.arguments.get(1)).getValue();
        final String label = ((StringField) menuItem.arguments.get(3)).getValue();
        return buildColorItem(colorId, label);
    }

    /**
     * Creates a color item that represents a color field fond for a track.
     *
     * @param colorId the key of the color as found in the database
     * @param label the corresponding color label as found in the database (or sent in the dbserver message)
     *
     * @return the color metadata field
     */
    private ColorItem buildColorItem(int colorId, String label) {
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
        return new ColorItem(colorId, label, color, colorName);
    }

    /**
     * Helper method to extract the name from an artist row, since it can be found one of two places.
     *
     * @param artistRow the row whose name is desired.
     *
     * @return the best name that was found.
     */
    private String getName(RekordboxPdb.ArtistRow artistRow) {
        if (artistRow.longName() !=  null) {
            return Database.getText(artistRow.longName());
        }
        return Database.getText(artistRow.name());
    }

    /**
     * Constructor for when reading from a rekordbox export file. Finds the desired track in the exported
     * database, along with all of the related records, and sets all the interpreted fields based on the parsed
     * database structures.
     *
     * @param reference the unique track reference for which track metadata is desired
     * @param database the database from which the row was loaded, in order to find related rows
     * @param cueList the cues associated with the track, if any
     *
     * @throws NoSuchElementException if the specified track is not found in the database
     */
    public TrackMetadata(DataReference reference, Database database, CueList cueList) {
        rawItems = null;  // We did not create this from a dbserver response.
        rawRow = database.trackIndex.get((long) reference.rekordboxId);
        if (rawRow == null) {
            throw new NoSuchElementException("Track " + reference.rekordboxId + " not found in PDB file.");
        }
        trackReference = reference;
        trackType = CdjStatus.TrackType.REKORDBOX;  // These are the only kind of tracks in a rekordbox database.
        this.cueList = cueList;

        title = Database.getText(rawRow.title());
        artworkId = (int)rawRow.artworkId();

        // Look up the track artist, if there is one.
        RekordboxPdb.ArtistRow artistRow = database.artistIndex.get(rawRow.artistId());
        if (artistRow != null) {
            artist = new SearchableItem((int) artistRow.id(), getName(artistRow));
        }

        // Look up the original artist, if there is one.
        artistRow = database.artistIndex.get(rawRow.originalArtistId());
        if (artistRow != null) {
            originalArtist = new SearchableItem((int) artistRow.id(), getName(artistRow));
        }

        // Look up the remixer, if there is one.
        artistRow = database.artistIndex.get(rawRow.originalArtistId());
        if (artistRow != null) {
            remixer = new SearchableItem((int) artistRow.id(), getName(artistRow));
        }

        // Look up the album, if there is one.
        RekordboxPdb.AlbumRow albumRow = database.albumIndex.get(rawRow.albumId());
        if (albumRow !=  null) {
            album = new SearchableItem((int) albumRow.id(), Database.getText(albumRow.name()));
        }

        // Look up the label, if there is one.
        RekordboxPdb.LabelRow labelRow = database.labelIndex.get(rawRow.labelId());
        if (labelRow != null) {
            label = new SearchableItem((int) labelRow.id(), Database.getText(labelRow.name()));
        }

        duration = rawRow.duration();
        tempo = (int)rawRow.tempo();
        comment = Database.getText(rawRow.comment());

        // Look up the musical key, if there is one.
        RekordboxPdb.KeyRow keyRow = database.musicalKeyIndex.get(rawRow.keyId());
        if (keyRow != null) {
            key = new SearchableItem((int) keyRow.id(), Database.getText(keyRow.name()));
        }

        rating = rawRow.rating();

        // Associate the track color, if there is one.
        RekordboxPdb.ColorRow colorRow = database.colorIndex.get((long)rawRow.colorId());
        if (colorRow != null) {
            color = buildColorItem(rawRow.colorId(), Database.getText(colorRow.name()));
        } else {
            color = buildColorItem(rawRow.colorId(), "");  // For backwards compatibility with "No Color".
        }

        // Look up the genre, if there is one.
        RekordboxPdb.GenreRow genreRow = database.genreIndex.get(rawRow.genreId());
        if (genreRow != null) {
            genre = new SearchableItem((int) genreRow.id(), Database.getText((genreRow.name())));
        }

        dateAdded = Database.getText(rawRow.dateAdded());
    }

    /**
     * Constructor for when reading from the network or from a cache file.
     * Sets all the interpreted fields based on the received response messages.
     *
     * @param reference the unique track reference that was used to request this track metadata
     * @param trackType the type of track described by this metadata
     * @param items the menu item responses that were received in response to the render menu request
     * @param cueList the cues associated with the track, if any
     */
    TrackMetadata(DataReference reference, CdjStatus.TrackType trackType, List<Message> items, CueList cueList) {
        rawRow = null;  // We did not create this from a PDB file.
        trackReference = reference;
        this.trackType = trackType;
        this.cueList = cueList;
        rawItems = Collections.unmodifiableList(new LinkedList<Message>(items));
        for (Message item : items) {
            parseMetadataItem(item);
        }
    }

    /**
     * Processes one of the menu responses that jointly constitute the track metadata, updating our
     * fields accordingly.
     *
     * @param item the menu response to be considered
     */
    private void parseMetadataItem(Message item) {
        switch (item.getMenuItemType()) {
            case TRACK_TITLE:
                title = ((StringField) item.arguments.get(3)).getValue();
                artworkId = (int) ((NumberField) item.arguments.get(8)).getValue();
                break;

            case ARTIST:
                artist = buildSearchableItem(item);
                break;

            case ORIGINAL_ARTIST:
                originalArtist = buildSearchableItem(item);
                break;

            case REMIXER:
                remixer = buildSearchableItem(item);

            case ALBUM_TITLE:
                album = buildSearchableItem(item);
                break;

            case LABEL:
                label = buildSearchableItem(item);
                break;

            case DURATION:
                duration = (int) ((NumberField) item.arguments.get(1)).getValue();
                break;

            case TEMPO:
                tempo = (int) ((NumberField) item.arguments.get(1)).getValue();
                break;

            case COMMENT:
                comment = ((StringField) item.arguments.get(3)).getValue();
                break;

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
                dateAdded = ((StringField) item.arguments.get(3)).getValue();
                break;

            case UNANALYZED_UNKNOWN:  // Don't yet know what to do with this.
                break;

            default:
                logger.warn("Ignoring track metadata item with unknown type: {}", item);
        }
    }

    /**
     * Get the album of the track.
     *
     * @return the track album
     */
    public SearchableItem getAlbum() {
        return album;
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
    public String getComment() {
        return comment;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TrackMetadata metadata = (TrackMetadata) o;

        if (trackReference != metadata.trackReference) return false;
        if (trackType != metadata.trackType) return false;
        if (duration != metadata.duration) return false;
        if (rating != metadata.rating) return false;
        if (tempo != metadata.tempo) return false;
        if (artworkId != metadata.artworkId) return false;
        if (album != null ? !album.equals(metadata.album) : metadata.album != null) return false;
        if (artist != null ? !artist.equals(metadata.artist) : metadata.artist != null) return false;
        if (color != null ? !color.equals(metadata.color) : metadata.color != null) return false;
        if (comment != null ? !comment.equals(metadata.comment) : metadata.comment != null) return false;
        if (dateAdded != null ? !dateAdded.equals(metadata.dateAdded) : metadata.dateAdded != null) return false;
        if (genre != null ? !genre.equals(metadata.genre) : metadata.genre != null) return false;
        if (key != null ? !key.equals(metadata.key) : metadata.key != null) return false;
        if (label != null ? !label.equals(metadata.label) : metadata.label != null) return false;
        if (originalArtist != null ? !originalArtist.equals(metadata.originalArtist) : metadata.originalArtist != null)
            return false;
        if (remixer != null ? !remixer.equals(metadata.remixer) : metadata.remixer != null) return false;
        if (title != null ? !title.equals(metadata.title) : metadata.title != null) return false;
        return cueList != null ? cueList.equals(metadata.cueList) : metadata.cueList == null;
    }

    @Override
    public int hashCode() {
        int result = album != null ? album.hashCode() : 0;
        result = 31 * result + (artist != null ? artist.hashCode() : 0);
        result = 31 * result + (color != null ? color.hashCode() : 0);
        result = 31 * result + (comment != null ? comment.hashCode() : 0);
        result = 31 * result + (dateAdded != null ? dateAdded.hashCode() : 0);
        result = 31 * result + duration;
        result = 31 * result + (genre != null ? genre.hashCode() : 0);
        result = 31 * result + (key != null ? key.hashCode() : 0);
        result = 31 * result + (label != null ? label.hashCode() : 0);
        result = 31 * result + (originalArtist != null ? originalArtist.hashCode() : 0);
        result = 31 * result + rating;
        result = 31 * result + (remixer != null ? remixer.hashCode() : 0);
        result = 31 * result + tempo;
        result = 31 * result + (title != null ? title.hashCode() : 0);
        result = 31 * result + artworkId;
        result = 31 * result + (cueList != null ? cueList.hashCode() : 0);
        result = 31 * result + trackReference.hashCode();
        result = 31 * result + trackType.hashCode();
        return result;
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
     * Get the label that released the track.
     *
     * @return the track recording label
     */
    public SearchableItem getLabel() {
        return label;
    }

    /**
     * Get the track's original artist.
     *
     * @return the artist that originally released the track
     */
    public SearchableItem getOriginalArtist() {
        return originalArtist;
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
     * Get the producer who remixed the track.
     *
     * @return the track remixer
     */
    public SearchableItem getRemixer() {
        return remixer;
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
        return "Track Metadata[trackReference: " + trackReference + ", TrackType: " + trackType +
                ", Title: " + title + ", Artist: " + artist + ", Album: " + album + ", Remixer: " + remixer +
                ", Label: " + label + ", Original Artist: " + originalArtist + ", Date Added: " + dateAdded +
                ", Duration: " + duration + ", Tempo: " + tempo + ", Comment: " + comment + ", Key: " + key +
                ", Rating: " + rating + ", Color: " + color + ", Genre: " + genre +
                ", Artwork ID: " + artworkId + ", Cue List: " + cueList +"]";
    }
}
