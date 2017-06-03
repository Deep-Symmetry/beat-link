package org.deepsymmetry.beatlink.dbserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.*;

/**
 * Encapsulates a full dbserver message, made up of a list of {@link Field} objects,
 * and having a particular structure, as described in the
 * <a href="https://github.com/brunchboy/dysentery/blob/master/doc/Analysis.pdf">Packet Analysis</a> paper.
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
         * Asks for the top-level menu of the player.
         */
        ROOT_MENU_REQ    (0x1000, "root menu request", "r:m:s:1", "sort order", "magic constant?"),
        /**
         * Asks for a list of artists in the specified media slot.
         */
        ARTIST_LIST_REQ  (0x1002, "artist list request", "r:m:s:1", "sort order?"),
        /**
         * Asks for a list of all the tracks in the specified media slot.
         */
        TRACK_LIST_REQ   (0x1004, "track list request", "r:m:s:1", "sort order"),
        /**
         * Asks for a playlist or folder by ID.
         */
        PLAYLIST_REQ     (0x1105, "playlist/folder request", "r:m:s:1", "sort order", "playlist/folder ID", "0=playlist, 1=folder"),
        /**
         * Asks for the metadata associated with a particular track, by rekordbox ID.
         */
        METADATA_REQ     (0x2002, "track metadata request", "r:m:s:1", "rekordbox id"),
        /**
         * Asks for an album artwork image, by artwork ID.
         */
        ALBUM_ART_REQ    (0x2003, "album art request", "r:m:s:1", "artwork id"),
        /**
         * Asks for the preview (summary) waveform data for a track, by rekordbox ID.
         */
        WAVE_PREVIEW_REQ (0x2004, "track waveform preview request", "r:m:s:1", "unknown (4)", "rekordbox id", "unknown (0)"),
        /**
         * Asks for the cue points of a track, by rekordbox ID.
         */
        CUE_LIST_REQ     (0x2104, "track cue list request", "r:m:s:1", "rekordbox id"),
        /**
         * Asks for metadata about a CD track, by track number.
         */
        CD_METADATA_REQ  (0x2202, "CD track metadata request", "r:m:s:1", "track number"),
        /**
         * Asks for the beat grid of a track, by rekordbox id.
         */
        BEAT_GRID_REQ    (0x2204, "beat grid request", "r:m:s:1", "rekordbox id"),
        /**
         * Asks for the detailed waveform data for a track, by rekordbox ID.
         */
        WAVE_DETAIL_REQ  (0x2904, "track waveform detail request", "r:m:s:1", "rekordbox id"),
        /**
         * Once a specific type of request has been made and acknowledged, this allows the results to be retrieved,
         * possibly in paginated chunks starting at <em>offset</em>, returning up to <em>limit</em> results.
         */
        RENDER_MENU_REQ  (0x3000, "render items from last requested menu", "r:m:s:1", "offset", "limit", "unknown (0)", "len_a (=limit)?", "unknown (0)"),
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
         * Returns information about any cue points set in the track.
         */
        CUE_LIST         (0x4702, "cue points", "request type", "unknown", "blob 1 length", "blob 1", "unknown (0x24)",
                "unknown", "unknown", "blob 2 length", "blob 2"),
        /**
         * Returns the bytes of the detailed waveform which is scrolled through while the track is playing.
         */
        WAVE_DETAIL      (0x4a02, "track waveform detail", "request type", "unknown (0)", "waveform length", "waveform bytes");

        /**
         * The numeric value that identifies this message type, by its presence in a 4-byte number field immediately
         * following the message start indicator.
         */
        public final long protocolValue;

        /**
         * The descriptive name of the message type.
         */
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
         * A string containing the musical key of a track, part of the track metadata response. Also contains the
         * key ID for listing by key.
         */
        KEY (0x000f),
        /**
         * The color assigned a track by the DJ, part of the track metadata response.
         */
        COLOR (0x0013),
        /**
         * A label assigned a track by the DJ, with an associated color, part of the track metadata response.
         */
        MY_TAG_1 (0x0014),  // TODO: Verify tags 2, 6-8, and see if they set a separate color than the color entry.
        /**
         * A label assigned a track by the DJ, with an associated color, part of the track metadata response.
         */
        MY_TAG_2 (0x0015),
        /**
         * A label assigned a track by the DJ, marked with a red dot, part of the track metadata response.
         */
        MY_TAG_3 (0x0016),
        /**
         * The comment assigned a track by the DJ, marked with an orange dot, part of the track metadata response.
         */
        MY_TAG_4 (0x0017),
        /**
         * A label assigned a track by the DJ, marked with a yellow dot, part of the track metadata response.
         */
        MY_TAG_5 (0x0018),
        /**
         * A label assigned a track by the DJ, marked with a green dot, part of the track metadata response.
         */
        MY_TAG_6 (0x0019),
        /**
         * A label assigned a track by the DJ, marked with an aqua dot, part of the track metadata response.
         */
        MY_TAG_7 (0x001a),
        /**
         * A label assigned a track by the DJ, marked with a blue dot, part of the track metadata response.
         */
        MY_TAG_8 (0x001b),
        /**
         * A label assigned a track by the DJ, marked with a purple dot, part of the track metadata response.
         */
        COMMENT (0x0023),
        /**
         * A string reporting when the track was added to the collection, in the form "YYYY-MM-DD", part of the track
         * metadata response. This seems to propagate from iTunes.
         */
        DATE_ADDED (0x002e),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks sorted by album.
         */
        TRACK_LIST_ENTRY_BY_ALBUM (0x0204),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks sorted by genre.
         */
        TRACK_LIST_ENTRY_BY_GENRE (0x0604),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks in their default
         * sort order.
         */
        TRACK_LIST_ENTRY (0x0704),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks sorted by BPM.
         */
        TRACK_LIST_ENTRY_BY_BPM (0x0d04),
        /**
         * Reports the title and artist of a track, returned when listing playlists or all tracks sorted by comment.
         */
        TRACK_LIST_ENTRY_BY_COMMENT (0x2304),
        /**
         * Reports the title and artist of a track, returned when listing playlists sorted by track title.
         */
        PLAYLIST_ENTRY_BY_TITLE (0x02904),
        /**
         * We received a value that we don't recognize, so we don't know what it contains.
         */
        UNKNOWN (-1);

        /**
         * The value which identifies this type of menu item by appearing in the seventh argument of a
         * {@link KnownType#MENU_ITEM} response.
         */
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
    @SuppressWarnings("WeakerAccess")
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
            if (argTags[i] == 3 && lastArg != null && (lastArg instanceof  NumberField) &&
                    ((NumberField)lastArg).getValue() == 0) {
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
     * refer to as <em>r:m:s:1</em>, because the first byte is the player number of the player making the
     * <em>request</em>, the second byte identifies the <em>menu</em> or destination for which information is being
     * loaded, the third byte identifies the media <em>slot</em> (USB or SD) being asked about (as described in
     * {@link org.deepsymmetry.beatlink.CdjStatus.TrackSourceSlot}), and the fourth byte
     * always seems to be <em>1</em> (Austin's libpdjl called it <em>sourceAnalyzed</em>). This enumeration lists
     * the known values for the second, menu, byte.
     */
    public enum MenuIdentifier {
        /**
         * The primary menu which appears on the left half of the player display.
         */
        MAIN_MENU  (1),
        /**
         * The secondary menu which sometimes appears down th right half of the player display.
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
