package org.deepsymmetry.beatlink.dbserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.*;

/**
 * <p>Encapsulates a full dbserver message, made up of a list of {@link Field} objects,
 * and having a particular structure, as described in the
 * <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis</a> paper.</p>
 *
 * <p>Known message types are found in {@link Message.KnownType}. Many requests return a series of messages that
 * represent menu items; the known versions of these are reflected in {@link Message.MenuItemType}.</p>
 *
 * @author James Elliott
 */
public class Message {

    private static final Logger logger = LoggerFactory.getLogger(Client.class.getName());

    /**
     * The special field that marks the start of a new message.
     */
    @SuppressWarnings("WeakerAccess")
    public static final NumberField MESSAGE_START = new NumberField(0x872349ae, 4);

    /**
     * Defines all the message types we know about, with any information we know about their arguments.
     */
    public enum KnownType {
        /**
         * The special initial request that is sent as the second action after opening a new connection, to enable
         * further database queries.
         */
        SETUP_REQ        (0x0000, "setup request", "requesting player"),
        /**
         * A response indicating that a request could not be fulfilled for some reason.
         */
        INVALID_DATA     (0x0001, "invalid data"),
        /**
         * The special final request that is sent before closing the connection.
         */
        TEARDOWN_REQ     (0x0100, "teardown request"),
        /**
         * Asks for the top-level menu of the player.
         */
        ROOT_MENU_REQ    (0x1000, "root menu request", "r:m:s:t", "sort order", "magic constant?"),
        /**
         * Asks for a list of genres in the specified media slot.
         */
        GENRE_MENU_REQ   (0x1001, "genre menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of artists in the specified media slot.
         */
        ARTIST_MENU_REQ  (0x1002, "artist menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of albums in the specified media slot.
         */
        ALBUM_MENU_REQ   (0x1003, "album menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of all the tracks in the specified media slot.
         */
        TRACK_MENU_REQ   (0x1004, "track menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of track tempos found in the specified media slot.
         */
        BPM_MENU_REQ     (0x1006, "bpm menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of ratings in the specified media slot.
         */
        RATING_MENU_REQ  (0x1007, "rating menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of years (at the decade level) in the specified media slot.
         */
        YEAR_MENU_REQ  (0x1008, "year menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of record labels in the specified media slot.
         */
        LABEL_MENU_REQ (0x100a, "label menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of colors in the specified media slot.
         */
        COLOR_MENU_REQ (0x100d, "color menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of track times (in minutes) in the specified media slot.
         */
        TIME_MENU_REQ  (0x1010, "time menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of track bit rates (in kilobits per second) in the specified media slot.
         */
        BIT_RATE_MENU_REQ (0x1011, "bit rate menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of performance histories found in the specified media slot.
         */
        HISTORY_MENU_REQ  (0x1012, "history menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of track file names found in the specified media slot.
         */
        FILENAME_MENU_REQ (0x1013, "filename menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a list of track keys found in the specified media slot.
         */
        KEY_MENU_REQ      (0x1014, "key menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for an artist menu for a particular genre in the specified media slot.
         */
        ARTIST_MENU_FOR_GENRE_REQ (0x1101, "artist menu for genre request", "r:m:s:t", "sort", "genre ID"),
        /**
         * Asks for an album menu for a particular artist in the specified media slot.
         */
        ALBUM_MENU_FOR_ARTIST_REQ (0x1102, "album menu for artist request", "r:m:s:t", "sort", "artist ID"),
        /**
         * Asks for a track menu for a particular album in the specified media slot.
         */
        TRACK_MENU_FOR_ALBUM_REQ (0x1103, "track menu for album request", "r:m:s:t", "sort", "album ID"),
        /**
         * Asks for a playlist or folder by ID.
         */
        PLAYLIST_REQ     (0x1105, "playlist/folder request", "r:m:s:t", "sort order", "playlist/folder ID", "0=playlist, 1=folder"),
        /**
         * Asks for a list of tempo ranges around a given tempo.
         */
        BPM_RANGE_REQ    (0x1106, "bpm range request", "r:m:s:t", "sort order", "tempo"),
        /**
         * Asks for a track menu for a particular rating in the specified media slot.
         */
        TRACK_MENU_FOR_RATING_REQ (0x1107, "track menu for rating request", "r:m:s:t", "sort", "rating ID"),
        /**
         * Asks for a year menu for a particular decade in the specified media slot.
         */
        YEAR_MENU_FOR_DECADE_REQ  (0x1108, "year menu for decade request", "r:m:s:t", "sort", "decade"),
        /**
         * Asks for an artist menu for a particular label in the specified media slot.
         */
        ARTIST_MENU_FOR_LABEL_REQ (0x110a, "artist menu for genre request", "r:m:s:t", "sort", "label ID, or -1 for ALL"),
        /**
         * Asks for a track menu for a particular color in the specified media slot.
         */
        TRACK_MENU_FOR_COLOR_REQ (0x110d, "track menu for color request", "r:m:s:t", "sort", "color ID"),
        /**
         * Asks for a track menu for a particular track time (length in minutes) in the specified media slot.
         */
        TRACK_MENU_FOR_TIME_REQ (0x1110, "track menu for time request", "r:m:s:t", "sort", "minutes"),
        /**
         * Asks for a track menu for a particular track bit rate (in Kbps) in the specified media slot.
         */
        TRACK_MENU_FOR_BIT_RATE_REQ (0x1111, "track menu for bit rate request", "r:m:s:t", "sort", "bit rate"),
        /**
         * Asks for a track menu for a particular rating in the specified media slot.
         */
        TRACK_MENU_FOR_HISTORY_REQ (0x1112, "track menu for history entry request", "r:m:s:t", "sort", "history ID"),
        /**
         * Asks for a key neighbor menu (showing harmonically compatible keys and their distance,
         * ranging from 0 to 2) available in the specified media slot.
         */
        NEIGHBOR_MENU_FOR_KEY(0x1114, "neighbor menu for key request", "r:m:s:t", "sort", "key ID"),
        /**
         * Asks for an album menu by genre and artist, can specify all artists by passing -1 for artist ID.
         */
        ALBUM_MENU_FOR_GENRE_AND_ARTIST (0x1201, "album menu for genre and artist request", "r:m:s:t:", "sort",
                "genre ID", "artist ID, or -1 for ALL"),
        /**
         * Asks for an track menu by artist and album, can specify all albums by passing -1 for album ID.
         */
        TRACK_MENU_FOR_ARTIST_AND_ALBUM (0x1202, "track menu for artist and album request", "r:m:s:t:", "sort",
                "artist ID", "album ID, or -1 for ALL"),
        /**
         * Asks for an track menu by BPM and distance, which represents a percentage tolerance by which the BPM
         * can differ, ranging from 0 to 6.
         */
        TRACK_MENU_FOR_BPM_AND_DISTANCE (0x1206, "track menu for BPM and distance request", "r:m:s:t:", "sort",
                "bpm ID", "distance (+/- %, can range from 0-6)"),
        /**
         * Asks for a track menu for a particular decade and year in the specified media slot.
         */
        TRACK_MENU_FOR_DECADE_YEAR_REQ (0x1208, "track menu for decade and year request", "r:m:s:t", "sort",
                "decade", "year, or -1 for ALL"),
        /**
         * Asks for an album menu by label and artist, can specify all artists by passing -1 for artist ID.
         */
        ALBUM_MENU_FOR_LABEL_AND_ARTIST (0x120a, "album menu for label and artist request", "r:m:s:t:", "sort",
                "label ID", "artist ID, or -1 for ALL"),
        /**
         * Asks for an track menu by key and distance (which represents harmonic compatibility as allowed movement
         * around the circle of fifths), ranging from 0 to 2.
         */
        TRACK_MENU_FOR_KEY_AND_DISTANCE (0x1214, "track menu for key and distance request", "r:m:s:t:", "sort",
                "key ID", "distance (around circle of fifths)"),
        /**
         * Asks to search the database for records matching the specified text (artist, album, track names,
         * possibly other things). Returns a variety of different menu item types.
         */
        SEARCH_MENU      (0x1300, "search by substring request", "r:m:s:t", "sort", "search string byte size",
                "search string (must be uppercase", "unknown (0)"),
        /**
         * Asks for a track menu by genre, artist, and album, can specify all artists and/or albums by passing -1 for
         * the artist and/or album IDs.
         */
        TRACK_MENU_FOR_GENRE_ARTIST_AND_ALBUM (0x1301, "track menu for genre, artist and album request", "r:m:s:t:", "sort",
                "genre ID", "artist ID, or -1 for ALL", "album ID, or -1 for ALL"),
        /**
         * Asks for the original artist menu for the specified media slot.
         */
        ORIGINAL_ARTIST_MENU_REQ  (0x1302, "original artist menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for a track menu by genre, artist, and album, can specify all artists and/or albums by passing -1 for
         * the artist and/or album IDs.
         */
        TRACK_MENU_FOR_LABEL_ARTIST_AND_ALBUM (0x130a, "track menu for label, artist and album request", "r:m:s:t:", "sort",
                "label ID", "artist ID, or -1 for ALL", "album ID, or -1 for ALL"),
        /**
         * Asks for an album menu for a particular original artist in the specified media slot.
         */
        ALBUM_MENU_FOR_ORIGINAL_ARTIST_REQ (0x1402, "album menu for original artist request", "r:m:s:t", "sort", "artist ID"),
        /**
         * Asks for an track menu by original artist and album, can specify all albums by passing -1 for the album ID.
         */
        TRACK_MENU_FOR_ORIGINAL_ARTIST_AND_ALBUM (0x1502, "track menu for original artist and album request", "r:m:s:t:", "sort",
                "artist ID", "album ID, or -1 for ALL"),
        /**
         * Asks for the remixer menu for the specified media slot.
         */
        REMIXER_MENU_REQ  (0x1602, "remixer menu request", "r:m:s:t", "sort order"),
        /**
         * Asks for an album menu for a particular original artist in the specified media slot.
         */
        ALBUM_MENU_FOR_REMIXER_REQ (0x1702, "album menu for remixer request", "r:m:s:t", "sort", "artist ID"),
        /**
         * Asks for an track menu by remixer and album, can specify all albums by passing -1 for the album ID.
         */
        TRACK_MENU_FOR_REMIXER_AND_ALBUM (0x1802, "track menu for remixer and album request", "r:m:s:t:", "sort",
                "artist ID", "album ID, or -1 for ALL"),
        /**
         * Asks for the metadata associated with a particular track, by rekordbox ID.
         */
        REKORDBOX_METADATA_REQ (0x2002, "rekordbox track metadata request", "r:m:s:t", "rekordbox id"),
        /**
         * Asks for an album artwork image, by artwork ID.
         */
        ALBUM_ART_REQ    (0x2003, "album art request", "r:m:s:t", "artwork id"),
        /**
         * Asks for the preview (summary) waveform data for a track, by rekordbox ID.
         */
        WAVE_PREVIEW_REQ (0x2004, "track waveform preview request", "r:m:s:t", "unknown (4)", "rekordbox id", "unknown (0)"),
        /**
         * Asks for a folder menu, of the raw media filesystem in the specified slot.
         */
        FOLDER_MENU_REQ  (0x2006, "folder menu request", "r:m:s:t", "sort order?", "folder id (-1 for root)", "unknown (0)"),
        // 0x2102 seems to ask about track data file information.
        /**
         * Asks for the memory points, loops, and hot cues of a track, by rekordbox ID.
         */
        CUE_LIST_REQ     (0x2104, "track cue list request", "r:m:s:t", "rekordbox id"),
        /**
         * Asks for metadata about a CD track, by track number.
         */
        UNANALYZED_METADATA_REQ  (0x2202, "unanalyzed track metadata request", "r:m:s:t", "track number"),
        /**
         * Asks for the beat grid of a track, by rekordbox id.
         */
        BEAT_GRID_REQ    (0x2204, "beat grid request", "r:m:s:t", "rekordbox id"),
        // 0x2504 when loading track?!
        /**
         * Asks for the detailed waveform data for a track, by rekordbox ID.
         */
        WAVE_DETAIL_REQ  (0x2904, "track waveform detail request", "r:m:s:t", "rekordbox id"),
        /**
         * Asks for the memory points, loops, and hot cues of a track, including comments and colors, by rekordbox ID.
         */
        CUE_LIST_EXT_REQ (0x2b04, "track extended cue list request", "r:m:s:t", "rekordbox id", "unknown (0)"),
        /**
         * This is a multipurpose request added for the nxs2 players which allows a specific tagged element of an
         * ANLZnnnn.DAT or ANLZnnnn.EXT file to be retrieved. The tag type is the four-character-code identifying the
         * desired file section (with bytes in reverse order), and the file extension is the same for the extension
         * identifying the desired file. (For an EXT file they are 00 54 58 45, or "EXT" padded with a NUL and reversed.
         */
        ANLZ_TAG_REQ     (0x2c04, "anlz file tag content request", "r:m:s:t", "rekordbox id", "tag type", "file extension"),
        /**
         * Once a specific type of request has been made and acknowledged, this allows the results to be retrieved,
         * possibly in paginated chunks starting at <em>offset</em>, returning up to <em>limit</em> results.
         */
        RENDER_MENU_REQ  (0x3000, "render items from last requested menu", "r:m:s:t", "offset", "limit", "unknown (0)", "len_a (=limit)?", "unknown (0)"),
        /**
         * This response indicates that a query has been accepted, and reports how many results are available. They are
         * now ready to be retrieved using {@link #RENDER_MENU_REQ}.
         */
        MENU_AVAILABLE   (0x4000, "requested menu is available", "request type", "# items available"),
        /**
         * When {@link #RENDER_MENU_REQ} is used to retrieve a set of results, this message will be sent as the first
         * response, followed by as many {@link #MENU_ITEM} messages as were requested.
         */
        MENU_HEADER      (0x4001, "rendered menu header"),
        /**
         * This response contains the binary image data of requested album art.
         */
        ALBUM_ART        (0x4002, "album art", "request type", "unknown (0)", "image length", "image bytes"),
        /**
         * Indicates that the item that was just requested cannot be found.
         */
        UNAVAILABLE      (0x4003, "requested media unavailable", "request type"),
        /**
         * A series of messages of this type are the payload returned in response to {@link #RENDER_MENU_REQ}. The
         * number requested will be delivered, in between a {@link #MENU_HEADER} and a {@link #MENU_FOOTER} message.
         * Each message will be of a particular subtype, which is identified by the value of the 7th argument; see
         * {@link MenuItemType} for known values.
         */
        MENU_ITEM        (0x4101, "rendered menu item", "numeric 1 (parent id, e.g. artist for track)", "numeric 2 (this id)",
                "label 1 byte size", "label 1", "label 2 byte size", "label 2", "item type", "flags? byte 3 is 1 when track played",
                "album art id", "playlist position"),
        /**
         * When {@link #RENDER_MENU_REQ} is used to retrieve a set of results, this message will be sent as the final
         * response, following any {@link #MENU_ITEM} messages that were requested.
         */
        MENU_FOOTER      (0x4201, "rendered menu footer"),
        /**
         * Returns the bytes of the small waveform preview to be displayed at the bottom of the player display,
         * or in rekordbox track lists.
         */
        WAVE_PREVIEW     (0x4402, "track waveform preview", "request type", "unknown (0)", "waveform length", "waveform bytes"),
        /**
         * Returns information about the beat number (within a bar) and millisecond position within the track of each
         * beat in a track.
         */
        BEAT_GRID        (0x4602, "beat grid", "request type", "unknown (0)", "beat grid length", "beat grid bytes", "unknown (0)"),
        /**
         * Returns information about any memory points, loops, and hot cues set in the track.
         */
        CUE_LIST         (0x4702, "memory points, loops, and hot cues", "request type", "unknown", "blob 1 length", "blob 1",
                "unknown (0x24)", "unknown", "unknown", "blob 2 length", "blob 2"),
        /**
         * Returns the bytes of the detailed waveform which is scrolled through while the track is playing.
         */
        WAVE_DETAIL      (0x4a02, "track waveform detail", "request type", "unknown (0)", "waveform length", "waveform bytes"),
        /**
         * Returns extended information about any memory points, loops, and hot cues set in the track, including
         * DJ comments, colors, and hot cues beyond C.
         */
        CUE_LIST_EXT     (0x4e02, "extended memory points, loops, and hot cues", "request type", "unknown (0)",
                "blob length", "blob", "entry count"),
        /**
         * Returns the bytes of the requested tag from an ANLZnnnn.DAT or ANLZnnnn.EXT file.
         */
        ANLZ_TAG         (0x4f02, "anlz file tag content", "request type", "unknown (0)", "tag length", "tag bytes", "unknown (1)");

        /**
         * The numeric value that identifies this message type, by its presence in a 4-byte number field immediately
         * following the message start indicator.
         */
        public final long protocolValue;

        /**
         * The descriptive name of the message type.
         */
        @SuppressWarnings("WeakerAccess")
        public final String description;

        /**
         * Descriptions of any arguments with known purposes.
         */
        private final String[] arguments;

        KnownType(long value, String description, String... arguments) {
            protocolValue = value;
            this.description = description;
            this.arguments = arguments.clone();
        }

        /**
         * Get the descriptive name of the specified message argument, if one is known.
         *
         * @param index the zero-based index identifying the argument whose description is desired.
         *
         * @return either the description found, or "unknown" if none was found.
         */
        @SuppressWarnings("WeakerAccess")
        public String describeArgument(int index) {
            if (index < 0 || index >= arguments.length) {
                return "unknown";
            }
            return arguments[index];
        }

        /**
         * Returns the descriptions of all known arguments, in order.
         *
         * @return a list of the descriptions of the arguments that are expected for this message type.
         */
        @SuppressWarnings("unused")
        public List<String> arguments() {
            return Collections.unmodifiableList(Arrays.asList(arguments));
        }
    }

    /**
     * Allows a known message type to be looked up by the message type number.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Long, KnownType> KNOWN_TYPE_MAP;

    static {
        Map<Long, KnownType> scratch = new HashMap<Long, KnownType>();
        for (KnownType type : KnownType.values()) {
            scratch.put(type.protocolValue, type);
        }
        KNOWN_TYPE_MAP = Collections.unmodifiableMap(scratch);
    }

    /**
     * The value to pass for the file type argument of a {@link KnownType#ANLZ_TAG_REQ} request in order to obtain
     * an element of an ANLZnnnn.DAT file. (The characters "DAT" and NUL as a byte-swapped integer.)
     */
    public static final int ALNZ_FILE_TYPE_DAT = 0x00544144;

    /**
     * The value to pass for the file type argument of a {@link KnownType#ANLZ_TAG_REQ} request in order to obtain
     * an element of an ANLZnnnn.EXT file. (The characters "EXT" and NUL as a byte-swapped integer.)
     */
    public static final int ALNZ_FILE_TYPE_EXT = 0x00545845;

    /**
     * The value to pass for the tag type argument of a {@link KnownType#ANLZ_TAG_REQ} request in order to obtain
     * the color waveform preview data. (The characters "PWV4" as a byte-swapped integer.)
     */
    public static final int ANLZ_FILE_TAG_COLOR_WAVEFORM_PREVIEW = 0x34565750;

    /**
     * The value to pass for the tag type argument of a {@link KnownType#ANLZ_TAG_REQ} request in order to obtain
     * the scrollable color waveform data. (The characters "PWV5" as a byte-swapped integer.)
     */
    public static final int ANLZ_FILE_TAG_COLOR_WAVEFORM_DETAIL = 0x35565750;

    /**
     * The value to pass for the tag type argument of a {@link KnownType#ANLZ_TAG_REQ} request in order to obtain
     * the enhanced cue and loop data, but does not seem to work. (The characters "PCO2" as a byte-swapped integer.)
     */
    public static final int ANLZ_FILE_TAG_CUE_COMMENT = 0x324f4350;

    /**
     * Defines all the known types of entries that an be returned for a menu request.
     */
    public enum MenuItemType {
        /**
         * A potentially-nested grouping of other objects, such as a group of playlists in the playlists menu.
         */
        FOLDER (0x0001),
        /**
         * The string identifying the name of an album, part of the track metadata response. Also contains the
         * album ID for listing by album.
         */
        ALBUM_TITLE (0x0002),
        /**
         * I don’t yet know where this appears or what it means.
         */
        DISC (0x0003),
        /**
         * The string identifying the name of a track, part of the track metadata response, as well as track lists
         * and play lists.
         */
        TRACK_TITLE (0x0004),
        /**
         * The string identifying the musical genre of a track, part of the track metadata response. Also contains
         * the genre ID for listing by genre.
         */
        GENRE (0x0006),
        /**
         * The string identifying the name of the artist for a track, part of the track metadata response, as well
         * as track lists and play lists. Also contains the artist ID for listing by artist.
         */
        ARTIST (0x0007),
        /**
         * When listing playlists, reports the name and ID of an actual playlist, as opposed to a sub-folder.
         */
        PLAYLIST (0x0008),
        /**
         * The rating assigned a track by the DJ, part of the track metadata response.
         */
        RATING (0x000a),
        /**
         * The duration, in seconds, of a track, part of the track metadata response.
         */
        DURATION (0x000b),
        /**
         * The tempo of a track, BPM times 100, part of the track metadata response.
         */
        TEMPO (0x000d),
        /**
         * A string containing the label that issued a track, part of the track metadata response. Also contains the
         * label ID for listing by label.
         */
        LABEL (0x000e),
        /**
         * A string containing the musical key of a track, part of the track metadata response. Also contains the
         * key ID for listing by key.
         */
        KEY (0x000f),
        /**
         * Track bit rate in Kbps; also appears in non-rekordbox metadata.
         */
        BIT_RATE (0x0010),
        /**
         * A number identifying a year in which a track was created.
         */
        YEAR ( 0x0011),
        /**
         * Indicates the DJ has not assigned a color label to a track, part of the track metadata response.
         */
        COLOR_NONE (0x0013),
        /**
         * A label assigned a track by the DJ, marked with a pink dot, part of the track metadata response.
         */
        COLOR_PINK (0x0014),
        /**
         * A label assigned a track by the DJ, marked with a red dot, part of the track metadata response.
         */
        COLOR_RED (0x0015),
        /**
         * A label assigned a track by the DJ, marked with an orange dot, part of the track metadata response.
         */
        COLOR_ORANGE (0x0016),
        /**
         * A label assigned a track by the DJ, marked with a yellow dot, part of the track metadata response.
         */
        COLOR_YELLOW (0x0017),
        /**
         * A label assigned a track by the DJ, marked with a green dot, part of the track metadata response.
         */
        COLOR_GREEN (0x0018),
        /**
         * A label assigned a track by the DJ, marked with an aqua dot, part of the track metadata response.
         */
        COLOR_AQUA (0x0019),
        /**
         * A label assigned a track by the DJ, marked with a blue dot, part of the track metadata response.
         */
        COLOR_BLUE (0x001a),
        /**
         * A label assigned a track by the DJ, marked with a purple dot, part of the track metadata response.
         */
        COLOR_PURPLE (0x001b),
        /**
         * An arbitrary text string assigned to a track by the DJ. Also contains a comment ID, for listing by comment.
         */
        COMMENT (0x0023),
        /**
         * A list of tracks that were performed in a past session.
         */
        HISTORY_PLAYLIST (0x0024),
        /**
         * The artist who originally recorded a track. Also contains an original artist ID, for listing by original
         * artist.
         */
        ORIGINAL_ARTIST (0x0028),
        /**
         * The producer who remixed a track. Also contains a remixer ID, for listing by remixer.
         */
        REMIXER (0x0029),
        /**
         * A string reporting when the track was added to the collection, in the form "YYYY-MM-DD", part of the track
         * metadata response. This seems to propagate from iTunes. Also contains a date added ID, for listing by
         * date added.
         */
        DATE_ADDED (0x002e),
        /**
         * The root menu item that takes you to the genre list.
         */
        GENRE_MENU (0x0080),
        /**
         * The root menu item that takes you to the artist list.
         */
        ARTIST_MENU (0x0081),
        /**
         * The root menu item that takes you to the artist list.
         */
        ALBUM_MENU (0x0082),
        /**
         * The root menu item that takes you to the track list.
         */
        TRACK_MENU (0x0083),
        /**
         * The root menu item that takes you to the playlist hierarchy.
         */
        PLAYLIST_MENU (0x0084),
        /**
         * The root menu item that takes you to the tempo list.
         */
        BPM_MENU (0x0085),
        /**
         * The root menu item that takes you to the folder list.
         */
        RATING_MENU (0x0086),
        /**
         * The root menu item that takes you to the years by decade list.
         */
        YEAR_MENU (0x0087),
        /**
         * The root menu item that takes you to the remixer menu.
         */
        REMIXER_MENU ( 0x0088),
        /**
         * The root menu item that takes you to the label menu.
         */
        LABEL_MENU ( 0x0089),
        /**
         * The root menu item that takes you to the original artist menu.
         */
        ORIGINAL_ARTIST_MENU ( 0x008a),
        /**
         * The root menu item that takes you to the key list.
         */
        KEY_MENU (0x008b),
        /**
         * The root menu item that takes you to the color list.
         */
        COLOR_MENU (0x008e),
        /**
         * The root menu item that takes you to the folder list.
         */
        FOLDER_MENU (0x0090),
        /**
         * The root menu item that takes you to the search interface.
         */
        SEARCH_MENU (0x0091),
        /**
         * The root menu item that takes you to the time (track length in minutes) list.
         */
        TIME_MENU (0x0092),
        /**
         * The root menu item that takes you to the track bit rate (Kbps) list.
         */
        BIT_RATE_MENU (0x0093),
        /**
         * The root menu item that takes you to the file name list.
         */
        FILENAME_MENU (0x0094),
        /**
         * The root menu item that takes you to the history list.
         */
        HISTORY_MENU (0x0095),
        /**
         * The root menu item that takes you to the hot cue bank list.
         */
        HOT_CUE_BANK_MENU (0x0098),
        /**
         * The menu item that lets you choose all values in a subcategory.
         */
        ALL (0x00a0),
        /**
         * Reports the title and album of a track, returned when listing playlists or all tracks sorted by album,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_ALBUM (0x0204),
        /**
         * Reports the title and genre of a track, returned when listing playlists or all tracks sorted by genre,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_GENRE (0x0604),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks sorted by artist,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_ARTIST(0x0704),
        /**
         * Reports the title and duration of a track, returned when listing playlists or all tracks sorted by time,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_TIME(0x0b04),
        /**
         * Reports the title and rating of a track, returned when listing playlists or all tracks sorted by rating,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_RATING (0x0a04),
        /**
         * Reports the title and BPM of a track, returned when listing playlists or all tracks sorted by BPM,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_BPM (0x0d04),
        /**
         * Reports the title and label of a track, returned when listing playlists or all tracks sorted by label,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_LABEL (0x0e04),
        /**
         * Reports the title and bit rate of a track, return when listing playlists or all tracks sorted by bit rate,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_RATE (0x1004),
        /**
         * Reports the title and color of a track, return when listing playlists or all tracks sorted by color,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_LIST_ENTRY_BY_COLOR (0x1a04),
        /**
         * Reports the title and comment of a track, returned when listing playlists or all tracks sorted by comment,
         * or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_COMMENT (0x2304),
        /**
         * Reports the title and original artist of a track, returned when listing playlists sorted by original artist,
         * or in their default sort order when listing all tracks and the DJ has set the original artist as the second
         * column for track lists.
         */
        TRACK_TITLE_AND_ORIGINAL_ARTIST (0x02804),
        /**
         * Reports the title and remixer of a track, returned when listing playlists sorted by remixer, or in their
         * default sort order when listing all tracks and the DJ has set this as the second column for track lists.
         */
        TRACK_TITLE_AND_REMIXER (0x02904),
        /**
         * Reports the title and play count of a track, returned when listing playlists or all tracks sorted by how many
         * times the DJ has played the track, or in their default sort order when the DJ has set this as the default
         * second column for track lists.
         */
        TRACK_TITLE_AND_DJ_PLAY_COUNT (0x2a04),
        /**
         * Reports the title and date added of a track, returned when listing playlists or all tracks sorted by date
         * added, or in their default sort order when the DJ has set this as the default second column for track lists.
         */
        TRACK_TITLE_AND_DATE_ADDED (0x2e04),
        /**
         * We received a value that we don't recognize, so we don't know what it contains.
         */
        UNKNOWN (-1);

        /**
         * The value which identifies this type of menu item by appearing in the seventh argument of a
         * {@link KnownType#MENU_ITEM} response.
         */
        @SuppressWarnings("WeakerAccess")
        public final long protocolValue;

        MenuItemType(long value) {
            protocolValue = value;
        }
    }

    /**
     * Allows a menu item type to be looked up by the value seen in the seventh argument of a
     * {@link KnownType#MENU_ITEM} response.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Long, MenuItemType> MENU_ITEM_TYPE_MAP;

    static {
        Map<Long, MenuItemType> scratch = new HashMap<Long, MenuItemType>();
        for (MenuItemType type : MenuItemType.values()) {
            scratch.put(type.protocolValue, type);
        }
        MENU_ITEM_TYPE_MAP = scratch;
    }

    /**
     * The 4-byte number field that provides the sequence number tying a query to its response messages, immediately
     * following the message start field.
     */
    @SuppressWarnings("WeakerAccess")
    public final NumberField transaction;

    /**
     * The 2-byte number field that identifies what type of message this is, immediately following the transaction
     * sequence number.
     */
    public final NumberField messageType;

    /**
     * The recognized type, if any, of this message.
     */
    public final KnownType knownType;

    /**
     * The 1-byte number field that specifies how many arguments the message has.
     */
    @SuppressWarnings("WeakerAccess")
    public final NumberField argumentCount;

    /**
     * The arguments being sent as part of this message.
     */
    public final List<Field> arguments;

    /**
     * The entire list of fields that make up the message.
     */
    public final List<Field> fields;

    /**
     * Constructor for experimenting with new message types.
     *
     * @param transaction the transaction ID (sequence number) that ties a message to its responses
     * @param messageType identifies the purpose and structure of the message
     * @param arguments the arguments to send with the message
     */
    @SuppressWarnings("WeakerAccess")
    public Message(long transaction, long messageType, Field... arguments) {
        this(new NumberField(transaction, 4), new NumberField(messageType, 2), arguments);
    }

    /**
     * Constructor from code using known message types.
     *
     * @param transaction the transaction ID (sequence number) that ties a message to its responses
     * @param messageType identifies the purpose and structure of the message
     * @param arguments the arguments to send with the message
     */
    @SuppressWarnings("SameParameterValue")
    public Message(long transaction, KnownType messageType, Field... arguments) {
        this(transaction, messageType.protocolValue, arguments);
    }

    /**
     * Constructor when being read from the network, so already have all the fields created.
     *
     * @param transaction the transaction ID (sequence number) that ties a message to its responses
     * @param messageType identifies the purpose and structure of the message
     * @param arguments the arguments to send with the message
     */
    public Message(NumberField transaction, NumberField messageType, Field... arguments) {
        if (transaction.getSize() != 4) {
            throw new IllegalArgumentException("Message transaction sequence number must be 4 bytes long");
        }
        if (messageType.getSize() != 2) {
            throw new IllegalArgumentException("Message type must be 2 bytes long");
        }
        if (arguments.length > 12) {
            throw new IllegalArgumentException("Messages cannot have more than 12 arguments");
        }
        this.transaction = transaction;
        this.messageType = messageType;
        this.knownType = KNOWN_TYPE_MAP.get(messageType.getValue());
        this.argumentCount = new NumberField(arguments.length, 1);
        this.arguments = Collections.unmodifiableList(Arrays.asList(arguments.clone()));

        // Build the list of argument type tags
        byte[] argTags = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        for (int i = 0; i < arguments.length; i++) {
            argTags[i] = arguments[i].getArgumentTag();
        }

        // And build the full list of fields that will be sent when this message is sent.
        Field[] allFields = new Field[arguments.length + 5];
        allFields[0] = MESSAGE_START;
        allFields[1] = transaction;
        allFields[2] = messageType;
        allFields[3] = argumentCount;
        allFields[4] = new BinaryField(argTags);
        System.arraycopy(arguments, 0, allFields, 5, arguments.length);
        fields = Collections.unmodifiableList(Arrays.asList(allFields));
    }

    /**
     * Read the next message from the stream.
     *
     * @param is a stream connected to a dbserver which is expected to be sending a message.
     *
     * @return the next full message found on the stream.
     *
     * @throws IOException if there is a problem reading the message.
     */
    public static Message read(DataInputStream is) throws IOException {
        final Field start = Field.read(is);
        if (!(start instanceof NumberField)) {
            throw new IOException("Did not find number field reading start of message; got: " + start);
        }
        if (start.getSize() != 4) {
            throw new IOException("Number field to start message must be of size 4, got: " + start);
        }
        if (((NumberField) start).getValue() != MESSAGE_START.getValue()) {
            throw new IOException("Number field had wrong value to start message. Expected: " + MESSAGE_START +
            ", got: " + start);
        }

        final Field transaction = Field.read(is);
        if (!(transaction instanceof NumberField)) {
            throw new IOException("Did not find number field reading transaction ID of message; got: " + transaction);
        }
        if (transaction.getSize() != 4) {
            throw new IOException("Transaction number field of message must be of size 4, got: " + transaction);
        }

        final Field type = Field.read(is);
        if (!(type instanceof NumberField)) {
            throw new IOException("Did not find number field reading type of message; got: " + type);
        }
        if (type.getSize() != 2) {
            throw new IOException("Type field of message must be of size 2, got: " + type);
        }

        final Field argCountField = Field.read(is);
        if (!(argCountField instanceof NumberField)) {
            throw new IOException("Did not find number field reading argument count of message; got: " + argCountField);
        }
        if (argCountField.getSize() != 1) {
            throw new IOException("Argument count field of message must be of size 1, got: " + argCountField);
        }
        final int argCount = (int)((NumberField)argCountField).getValue();
        if (argCount < 0 || argCount > 12) {
            throw new IOException("Illegal argument count while reading message; must be between 0 and 12, got: " +
            argCount);
        }

        final Field argTypes = Field.read(is);
        if (!(argTypes instanceof BinaryField)) {
            throw new IOException("Did not find binary field reading argument types of message, got: " + argTypes);
        }
        byte[] argTags = new byte[12];
        ((BinaryField)argTypes).getValue().get(argTags);

        Field[] arguments = new Field[argCount];
        Field lastArg = null;
        for (int i = 0; i < argCount; i++) {
            if (argTags[i] == 3 && (lastArg instanceof NumberField) &&
                    ((NumberField) lastArg).getValue() == 0) {
                arguments [i] = new BinaryField(new byte[0]);  // Do not attempt to read a zero-length binary field
            } else {
                arguments[i] = Field.read(is);
            }
            lastArg = arguments[i];
            if (lastArg.getArgumentTag() != argTags[i]) {
                throw new IOException("Found argument of wrong type reading message. Expected tag: " + argTags[i] +
                " and got: " + arguments[i].getArgumentTag());
            }
        }
        Message result = new Message((NumberField)transaction, (NumberField)type, arguments);
        logger.debug("Received> {}", result);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("Message: [transaction: ").append(transaction.getValue());
        result.append(String.format(", type: 0x%04x (", messageType.getValue()));
        if (knownType != null) {
            result.append(knownType.description);
        } else {
            result.append("unknown");
        }
        result.append("), arg count: ").append(argumentCount.getValue()).append(String.format(", arguments:%n"));
        for (int i = 0; i < arguments.size(); i++) {
            final Field arg = arguments.get(i);
            result.append(String.format("%4d: ", i + 1));
            if (arg instanceof NumberField) {
                final long value = ((NumberField) arg).getValue();
                result.append(String.format("number: %10d (0x%08x)", value, value));
            } else if (arg instanceof BinaryField) {
                ByteBuffer bytes = ((BinaryField)arg).getValue();
                byte[] array = new byte[bytes.remaining()];
                bytes.get(array);
                result.append(String.format("blob length %d:",arg.getSize()));
                for (byte b : array) {
                    result.append(String.format(" %02x", b));
                }
            } else if (arg instanceof StringField) {
                result.append(String.format("string length %d: \"%s\"", arg.getSize(), ((StringField)arg).getValue()));
            } else {
                result.append("unknown: ").append(arg);
            }
            String argDescription = "unknown";
            if (knownType != null) {
                argDescription = knownType.describeArgument(i);
                if (knownType == KnownType.MENU_ITEM && i == 6 && (arg instanceof NumberField)) {
                    String itemType = "unknown";
                    MenuItemType match = MENU_ITEM_TYPE_MAP.get(((NumberField) arg).getValue());
                    if (match != null) {
                        itemType = match.name();
                    }
                    argDescription = argDescription + ": " + itemType;
                }
            }
            result.append(String.format(" [%s]%n", argDescription));
        }
        return result.append("]").toString();
    }

    /**
     * For many types of query messages, the first argument of the message is a 4-byte integer which we currently
     * refer to as <em>r:m:s:t</em>, because the first byte is the player number of the player making the
     * <em>request</em>, the second byte identifies the <em>menu</em> or destination for which information is being
     * loaded, the third byte identifies the media <em>slot</em> (USB or SD) being asked about (as described in
     * {@link org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot}), and the fourth byte identifies the type of
     * track being worked with (for most requests this is 1, meaning rekordbox). This enumeration lists
     * the known values for the second, menu, byte.
     */
    public enum MenuIdentifier {
        /**
         * The primary menu which appears on the left half of the player display.
         */
        MAIN_MENU  (1),
        /**
         * The secondary menu which sometimes appears down the right half of the player display.
         */
        SUB_MENU   (2),
        /**
         * The pseudo-menu of track metadata.
         */
        TRACK_INFO (3),
        /**
         * Types of sorting available? I am not entirely sure when this is used.
         */
        SORT_MENU  (5),
        /**
         * Values which do not display in a menu, such as track waveforms, beat grids, album art, etc. are loaded
         * “into” this menu/destination.
         */
        DATA       (8);

        /**
         * The value which identifies this menu or destination by appearing in the second byte of the first argument
         * of many request messages.
         */
        public final byte protocolValue;

        MenuIdentifier(int value) {
            protocolValue = (byte)value;
        }
    }

    /**
     * Allows a menu/destination to be looked up by the value seen in the second byte of the first argument of many
     * request messages.
     */
    @SuppressWarnings("WeakerAccess")
    public static final Map<Byte, MenuIdentifier> MENU_IDENTIFIER_MAP;

    static {
        Map<Byte, MenuIdentifier> scratch = new HashMap<Byte, MenuIdentifier>();
        for (MenuIdentifier identifier : MenuIdentifier.values()) {
            scratch.put(identifier.protocolValue, identifier);
        }
        MENU_IDENTIFIER_MAP = scratch;
    }

    /**
     * The value returned by {@link #getMenuResultsCount()} when there is no data available for the request
     * that was made.
     */
    public static final long NO_MENU_RESULTS_AVAILABLE = 0xffffffff;

    /**
     * Extracts the result count from a {@link KnownType#MENU_AVAILABLE} response.
     *
     * @return the reported count of available results
     *
     * @throws IllegalArgumentException if this is not a {@link KnownType#MENU_AVAILABLE} response.
     */
    public long getMenuResultsCount() {
        if (knownType != Message.KnownType.MENU_AVAILABLE) {
            throw new IllegalArgumentException("getMenuResultsCount() can only be used with MENU_AVAILABLE responses.");
        }
        final NumberField count = (NumberField)arguments.get(1);
        return count.getValue();
    }

    /**
     * Extracts the menu item type from a {@link KnownType#MENU_ITEM} response.
     *
     * @return the reported type of this menu item
     *
     * @throws IllegalArgumentException if this is not a {@link KnownType#MENU_ITEM} response.
     */
    public MenuItemType getMenuItemType() {
        if (knownType != KnownType.MENU_ITEM) {
            throw new IllegalArgumentException("getMenuItemType() can only be used with MENU_ITEM responses.");
        }
        final NumberField type = (NumberField)arguments.get(6);
        final MenuItemType result = MENU_ITEM_TYPE_MAP.get(type.getValue());
        if (result == null) {
            return MenuItemType.UNKNOWN;
        }
        return result;
    }

    /**
     * Writes the message to the specified channel, for example when creating metadata cache files.
     *
     * @param channel the channel to which it should be written
     *
     * @throws IOException if there is a problem writing to the channel
     */
    public void write(WritableByteChannel channel) throws IOException {
        logger.debug("Writing> {}", this);
        for (Field field : fields) {
            field.write(channel);
        }
    }
}
