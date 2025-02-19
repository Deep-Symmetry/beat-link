package org.deepsymmetry.beatlink.data;

import org.apiguardian.api.API;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.dbserver.Message;
import org.deepsymmetry.beatlink.dbserver.NumberField;
import org.deepsymmetry.beatlink.dbserver.StringField;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Represents rekordbox metadata (title, artist, etc.) about tracks loaded into players on a DJ Link network.
 *
 * @author James Elliott
 */
@SuppressWarnings("DeprecatedIsStillUsed")  // We still offer the fields for now, someday they may be removed.
@API(status = API.Status.STABLE)
public class TrackMetadata {

    private static final Logger logger = LoggerFactory.getLogger(TrackMetadata.class.getName());

    /**
     * The unique track identifier that was used to request this track metadata.
     */
    @API(status = API.Status.STABLE)
    public final DataReference trackReference;

    /**
     * The type of track described by this metadata.
     */
    @API(status = API.Status.STABLE)
    public final CdjStatus.TrackType trackType;

    /**
     * The raw dbserver messages containing the metadata when it was read over the network.
     * Can be used to analyze fields that have not yet been reliably understood,
     * and was previously used for storing the metadata in a file. Will be {@code null} if
     * the metadata was constructed from a {@link org.deepsymmetry.cratedigger.pdb.RekordboxPdb.TrackRow}
     * or a Device Library Plus SQLite connection.
     *
     * @deprecated This information is rarely available since the development of Crate Digger.
     * @see #rawRow
     */
    @API(status = API.Status.DEPRECATED)
    public final List<Message> rawItems;

    /**
     * The raw row within a rekordbox database export from which this metadata was created,
     * if any. Will be {@code null} if it was read from a dbserver metadata response or a
     * Device Library Plus SQLite connection.
     *
     * @deprecated This information is not always available, so should not be relied on.
     * @see #rawItems
     */
    @API(status = API.Status.DEPRECATED)
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
     * The year the track was created.
     */
    private int year;

    /**
     * The audio bit rate of the track, in kilobits per second.
     */
    private int bitRate;

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
     * Constructor for when reading from a rekordbox export file. Finds the desired track in the exported
     * database, along with all the related records, and sets all the interpreted fields based on the parsed
     * database structures.
     *
     * @param reference the unique track reference for which track metadata is desired
     * @param database the database from which the row was loaded, in order to find related rows
     * @param cueList the cues associated with the track, if any
     *
     * @throws NoSuchElementException if the specified track is not found in the database
     */
    @API(status = API.Status.STABLE)
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
            artist = new SearchableItem((int) artistRow.id(), Database.getText(artistRow.name()));
        }

        // Look up the original artist, if there is one.
        artistRow = database.artistIndex.get(rawRow.originalArtistId());
        if (artistRow != null) {
            originalArtist = new SearchableItem((int) artistRow.id(), Database.getText(artistRow.name()));
        }

        // Look up the remixer, if there is one.
        artistRow = database.artistIndex.get(rawRow.originalArtistId());
        if (artistRow != null) {
            remixer = new SearchableItem((int) artistRow.id(), Database.getText(artistRow.name()));
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
            color = new ColorItem(rawRow.colorId(), Database.getText(colorRow.name()));
        } else {
            color = new ColorItem(rawRow.colorId(), "");  // For backwards compatibility with "No Color".
        }

        // Look up the genre, if there is one.
        RekordboxPdb.GenreRow genreRow = database.genreIndex.get(rawRow.genreId());
        if (genreRow != null) {
            genre = new SearchableItem((int) genreRow.id(), Database.getText((genreRow.name())));
        }

        dateAdded = Database.getText(rawRow.dateAdded());
        bitRate = (int)rawRow.bitrate();
        year = rawRow.year();
    }

    /**
     * Constructor for when reading from a Device Library Plus SQLite export database.
     * Finds the desired track in the exported database, along with all the related records,
     * and sets all the interpreted fields based on the queried database structures.
     *
     * @param reference the unique track reference that was used to request this track metadata
     * @param connection the JDBC connection to the exported database
     * @param cueList the cues associated with the track, if any
     *
     * @throws SQLException if there is a problem querying the database
     */
    @API(status = API.Status.EXPERIMENTAL)
    TrackMetadata(DataReference reference, Connection connection, CueList cueList) throws SQLException {
        rawRow = null;    // We did not create this from a PDB file.
        rawItems = null;  // Nor did it come from a DeviceSQL dbserver network connection.
        trackReference = reference;
        trackType = CdjStatus.TrackType.REKORDBOX;  // These are the only kind of tracks in a rekordbox database.
        this.cueList = cueList;
        try (Statement statement = connection.createStatement();
             ResultSet trackSet = statement.executeQuery("select * from contents where content_id=" + trackReference.rekordboxId)) {
            if (!trackSet.next()) {
                throw new SQLException("Track " + trackReference.rekordboxId + " not found in export database");
            }
            artworkId = trackSet.getInt("image_id");
            title = trackSet.getString("title");
            duration = trackSet.getInt("length");
            //noinspection SpellCheckingInspection
            tempo = trackSet.getInt("bpmx100");
            comment = trackSet.getString("djComment");
            dateAdded = trackSet.getString("dateAdded");
            //noinspection SpellCheckingInspection
            bitRate = trackSet.getInt("bitrate");
            year = trackSet.getInt("releaseYear");

            // And load any related rows that are supposed to be present.
            artist = extractRelatedName(connection, "artist", trackSet.getInt("artist_id_artist"));
            originalArtist = extractRelatedName(connection, "artist", trackSet.getInt("artist_id_originalArtist"));
            remixer = extractRelatedName(connection, "artist", trackSet.getInt("artist_id_remixer"));

            album = extractRelatedName(connection, "album", trackSet.getInt("album_id"));
            label = extractRelatedName(connection, "label", trackSet.getInt("label_id"));
            key = extractRelatedName(connection, "key", trackSet.getInt("key_id"));
            genre = extractRelatedName(connection, "genre", trackSet.getInt("genre_id"));

            final SearchableItem colorTemp = extractRelatedName(connection, "color", trackSet.getInt("color_id"));
            if (colorTemp != null) {
                color = new ColorItem(colorTemp.id, colorTemp.label);
            }
        }
    }

    /**
     * Helper method to load a related row name when loading a track using SQLite.
     *
     * @param connection the used to talk to the database
     * @param table      the name of the table in which the row should be sought
     * @param id         the ID of the related row, or zero if none is expected
     * @return the loaded album, if one was found
     */
    @SuppressWarnings("LoggingSimilarMessage")
    private SearchableItem extractRelatedName(Connection connection, String table, int id) {
        if (id != 0) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select * from " + table + " where " + table + "_id=" + id)) {
                if (resultSet.next()) {
                    return new SearchableItem(id, resultSet.getString("name"));
                } else {
                    logger.warn("{} row with id {} not found when loading track", table, id);
                }
            } catch (SQLException e) {
                logger.warn("{} row with id {} not found when loading track", table, id);
            }
        }
        return null;
    }

    /**
     * Constructor for when reading from the network or from a file.
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
        rawItems = Collections.unmodifiableList(new LinkedList<>(items));
        for (Message item : items) {
            parseMetadataItem(item);
        }
        // Protect against missing string elements
        if (title == null) {
            title = "";
        }
        if (comment == null) {
            comment = "";
        }
        if (dateAdded == null) {
            dateAdded = "";
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
                final int colorId = (int) ((NumberField) item.arguments.get(1)).getValue();
                final String label = ((StringField) item.arguments.get(3)).getValue();
                color = new ColorItem(colorId, label);
                break;

            case GENRE:
                genre = buildSearchableItem(item);
                break;

            case DATE_ADDED:
                dateAdded = ((StringField) item.arguments.get(3)).getValue();
                break;

            case YEAR:
                year = (int) ((NumberField) item.arguments.get(1)).getValue();
                break;

            case BIT_RATE:
                bitRate = (int) ((NumberField) item.arguments.get(1)).getValue();
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
    @API(status = API.Status.STABLE)
    public SearchableItem getAlbum() {
        return album;
    }

    /**
     * Get the artist of the track.
     *
     * @return the track artist
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getArtist() {
        return artist;
    }

    /**
     * Get the artwork ID for the track. Will be zero for tracks that have no artwork.
     *
     * @return the value that can be used to request the artwork image, if any, associated with the track
     */
    @API(status = API.Status.STABLE)
    public int getArtworkId() {
        return artworkId;
    }

    /**
     * Get the color assigned to the track.
     *
     * @return the track color
     */
    @API(status = API.Status.STABLE)
    public ColorItem getColor() {
        return color;
    }

    /**
     * Get the comment assigned to the track.
     *
     * @return the track comment
     */
    @API(status = API.Status.STABLE)
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
        if (!Objects.equals(album, metadata.album)) return false;
        if (!Objects.equals(artist, metadata.artist)) return false;
        if (!Objects.equals(color, metadata.color)) return false;
        if (!Objects.equals(comment, metadata.comment)) return false;
        if (!Objects.equals(dateAdded, metadata.dateAdded)) return false;
        if (!Objects.equals(genre, metadata.genre)) return false;
        if (!Objects.equals(key, metadata.key)) return false;
        if (!Objects.equals(label, metadata.label)) return false;
        if (!Objects.equals(originalArtist, metadata.originalArtist)) return false;
        if (!Objects.equals(remixer, metadata.remixer)) return false;
        if (!Objects.equals(title, metadata.title)) return false;
        return Objects.equals(cueList, metadata.cueList);
    }

    @Override
    public int hashCode() {
        int result = album != null ? album.hashCode() : 0;
        result = 31 * result + (artist != null ? artist.hashCode() : 0);
        result = 31 * result + bitRate;
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
        result = 31 * result + year;
        return result;
    }

    /**
     * Get the bit rate of the track, if known.
     *
     * @return the audio bit rate of the track, in kilobits per second , or 0 if unknown or variable.
     */
    @API(status = API.Status.STABLE)
    public int getBitRate() {
        return bitRate;
    }

    /**
     * Get the cue list associated with the track. Will be {@code null} if no hot cues, loops, or memory points are
     * found in the track.
     *
     * @return the hot cues, loops and memory points stored for the track, if any
     */
    @API(status = API.Status.STABLE)
    public CueList getCueList() {
        return cueList;
    }

    /**
     * Get the date the track was added to the collection. This information seems to propagate from iTunes.
     *
     * @return the date the track was added to the collection, in the form "YYYY-MM-DD"
     */
    @API(status = API.Status.STABLE)
    public String getDateAdded() {
        return dateAdded;
    }

    /**
     * Get the duration of the track, in seconds.
     *
     * @return the track length in seconds, when played at 100% pitch
     */
    @API(status = API.Status.STABLE)
    public int getDuration() {
        return duration;
    }

    /**
     * Get the genre of the track.
     *
     * @return the track genre
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getGenre() {
        return genre;
    }

    /**
     * Get the musical key of the track.
     *
     * @return the track key
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getKey() {
        return key;
    }

    /**
     * Get the label that released the track.
     *
     * @return the track recording label
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getLabel() {
        return label;
    }

    /**
     * Get the track's original artist.
     *
     * @return the artist that originally released the track
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getOriginalArtist() {
        return originalArtist;
    }

    /**
     * Get the rating assigned the track.
     *
     * @return the track rating
     */
    @API(status = API.Status.STABLE)
    public int getRating() {
        return rating;
    }

    /**
     * Get the producer who remixed the track.
     *
     * @return the track remixer
     */
    @API(status = API.Status.STABLE)
    public SearchableItem getRemixer() {
        return remixer;
    }

    /**
     * Get the starting tempo of the track.
     *
     * @return the initial track tempo, BPM times 100.
     */
    @API(status = API.Status.STABLE)
    public int getTempo() {
        return tempo;
    }

    /**
     * Get the title of the track.
     *
     * @return the track title
     */
    @API(status = API.Status.STABLE)
    public String getTitle() {
        return title;
    }

    /**
     * Get the year of the track, if known.
     *
     * @return the year the track was created, or 0 if rekordbox is not indexing by year.
     */
    @API(status = API.Status.STABLE)
    public int getYear() {
        return year;
    }

    @Override
    public String toString() {
        return "Track Metadata[trackReference: " + trackReference + ", TrackType: " + trackType +
                ", Title: " + title + ", Artist: " + artist + ", Album: " + album + ", Remixer: " + remixer +
                ", Label: " + label + ", Original Artist: " + originalArtist + ", Date Added: " + dateAdded +
                ", Duration: " + duration + ", Tempo: " + tempo + ", Comment: " + comment + ", Key: " + key +
                ", Rating: " + rating + ", Color: " + color + ", Genre: " + genre + ", Year: " + year +
                ", Bit Rate: " + bitRate + ", Artwork ID: " + artworkId + ", Cue List: " + cueList +"]";
    }
}
