// This is a generated file! Please edit source .ksy file and use kaitai-struct-compiler to rebuild

package org.deepsymmetry.beatlink.pdb.generated;

import io.kaitai.struct.ByteBufferKaitaiStream;
import io.kaitai.struct.KaitaiStruct;
import io.kaitai.struct.KaitaiStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.nio.charset.Charset;


/**
 * This is a relational database format designed to be efficiently used
 * by very low power devices (there were deployments on 16 bit devices
 * with 32K of RAM). Today you are most likely to encounter it within
 * the Pioneer Professional DJ ecosystem, because it is the format that
 * their rekordbox software uses to write USB and SD media which can be
 * mounted in DJ controllers and used to play and mix music.
 * 
 * It has been reverse-engineered to facilitate sophisticated
 * integrations with light and laser shows, videos, and other musical
 * instruments, by supporting deep knowledge of what is playing and
 * what is coming next through monitoring the network communications of
 * the players.
 * 
 * The file is divided into fixed-size blocks. The first block has a
 * header that establishes the block size, and lists the tables
 * available in the database, identifying their types and the index of
 * the first of the series of linked pages that make up that table.
 * 
 * Each table is made up of a series of rows which may be spread across
 * any number of pages. The pages start with a header describing the
 * page and linking to the next page. The rest of the page is used as a
 * heap: rows are scattered around it, and located using an index
 * structure that builds backwards from the end of the page. Each row
 * of a given type has a fixed size structure which links to any
 * variable-sized strings by their offsets within the page.
 * 
 * As changes are made to the table, some records may become unused,
 * and there may be gaps within the heap that are too small to be used
 * by other data. There is a bit map in the row index that identifies
 * which rows are actually present. Rows that are not present must be
 * ignored: they do not contain valid (or even necessarily well-formed)
 * data.
 * 
 * The majority of the work in reverse-engineering this format was
 * performed by @henrybetts and @flesniak, for which I am hugely
 * grateful. @GreyCat helped me learn the intricacies (and best
 * practices) of Kaitai far faster than I would have managed on my own.
 * @see <a href="https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf">Source</a>
 */
public class PdbFile extends KaitaiStruct {
    public static PdbFile fromFile(String fileName) throws IOException {
        return new PdbFile(new ByteBufferKaitaiStream(fileName));
    }

    public enum PageType {
        TRACKS(0),
        GENRES(1),
        ARTISTS(2),
        ALBUMS(3),
        LABELS(4),
        KEYS(5),
        COLORS(6),
        PLAYLISTS(7),
        PLAYLIST_MAP(8),
        UNKNOWN_9(9),
        UNKNOWN_10(10),
        UNKNOWN_11(11),
        UNKNOWN_12(12),
        ARTWORK(13),
        UNKNOWN_14(14),
        UNKNOWN_15(15),
        COLUMNS(16),
        UNKNOWN_17(17),
        UNKNOWN_18(18),
        HISTORY(19);

        private final long id;
        PageType(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, PageType> byId = new HashMap<Long, PageType>(20);
        static {
            for (PageType e : PageType.values())
                byId.put(e.id(), e);
        }
        public static PageType byId(long id) { return byId.get(id); }
    }

    public PdbFile(KaitaiStream _io) {
        this(_io, null, null);
    }

    public PdbFile(KaitaiStream _io, KaitaiStruct _parent) {
        this(_io, _parent, null);
    }

    public PdbFile(KaitaiStream _io, KaitaiStruct _parent, PdbFile _root) {
        super(_io);
        this._parent = _parent;
        this._root = _root == null ? this : _root;
        _read();
    }
    private void _read() {
        this._unnamed0 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
        this.lenPage = this._io.readU4le();
        this.numTables = this._io.readU4le();
        this.nextUnusedPage = this._io.readU4le();
        this._unnamed4 = this._io.readU4le();
        this.sequence = this._io.readU4le();
        this._unnamed6 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
        tables = new ArrayList<Table>((int) (numTables()));
        for (int i = 0; i < numTables(); i++) {
            this.tables.add(new Table(this._io, this, _root));
        }
    }

    /**
     * A variable length string which can be stored in a variety of
     * different encodings. TODO: May need to skip leading zeros before
     * the length byte.
     */
    public static class DeviceSqlString extends KaitaiStruct {
        public static DeviceSqlString fromFile(String fileName) throws IOException {
            return new DeviceSqlString(new ByteBufferKaitaiStream(fileName));
        }

        public DeviceSqlString(KaitaiStream _io) {
            this(_io, null, null);
        }

        public DeviceSqlString(KaitaiStream _io, KaitaiStruct _parent) {
            this(_io, _parent, null);
        }

        public DeviceSqlString(KaitaiStream _io, KaitaiStruct _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.lengthAndKind = this._io.readU1();
            switch (lengthAndKind()) {
            case 64: {
                this.body = new DeviceSqlLongAscii(this._io, this, _root);
                break;
            }
            case 144: {
                this.body = new DeviceSqlLongUtf16be(this._io, this, _root);
                break;
            }
            default: {
                this.body = new DeviceSqlShortAscii(this._io, this, _root, lengthAndKind());
                break;
            }
            }
        }
        private int lengthAndKind;
        private KaitaiStruct body;
        private PdbFile _root;
        private KaitaiStruct _parent;

        /**
         * Mangled length of an ordinary ASCII string if odd, or a flag
         * indicating another encoding with a longer length value to
         * follow.
         */
        public int lengthAndKind() { return lengthAndKind; }
        public KaitaiStruct body() { return body; }
        public PdbFile _root() { return _root; }
        public KaitaiStruct _parent() { return _parent; }
    }

    /**
     * An ASCII-encoded string up to 127 bytes long.
     */
    public static class DeviceSqlShortAscii extends KaitaiStruct {

        public DeviceSqlShortAscii(KaitaiStream _io, int mangledLength) {
            this(_io, null, null, mangledLength);
        }

        public DeviceSqlShortAscii(KaitaiStream _io, PdbFile.DeviceSqlString _parent, int mangledLength) {
            this(_io, _parent, null, mangledLength);
        }

        public DeviceSqlShortAscii(KaitaiStream _io, PdbFile.DeviceSqlString _parent, PdbFile _root, int mangledLength) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            this.mangledLength = mangledLength;
            _read();
        }
        private void _read() {
            this.text = new String(this._io.readBytes(length()), Charset.forName("ascii"));
        }
        private Integer length;
        public Integer length() {
            if (this.length != null)
                return this.length;
            int _tmp = (int) ((((mangledLength() - 1) / 2) - 1));
            this.length = _tmp;
            return this.length;
        }
        private String text;
        private int mangledLength;
        private PdbFile _root;
        private PdbFile.DeviceSqlString _parent;
        public String text() { return text; }

        /**
         * Contains the actual length, incremented, doubled, and
         * incremented again. Go figure.
         */
        public int mangledLength() { return mangledLength; }
        public PdbFile _root() { return _root; }
        public PdbFile.DeviceSqlString _parent() { return _parent; }
    }

    /**
     * A row that holds an artist name and ID.
     */
    public static class AlbumRow extends KaitaiStruct {
        public static AlbumRow fromFile(String fileName) throws IOException {
            return new AlbumRow(new ByteBufferKaitaiStream(fileName));
        }

        public AlbumRow(KaitaiStream _io) {
            this(_io, null, null);
        }

        public AlbumRow(KaitaiStream _io, PdbFile.RowRef _parent) {
            this(_io, _parent, null);
        }

        public AlbumRow(KaitaiStream _io, PdbFile.RowRef _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.magic = this._io.ensureFixedContents(new byte[] { -128, 0 });
            this.indexShift = this._io.readU2le();
            this._unnamed2 = this._io.readU4le();
            this.artistId = this._io.readU4le();
            this.id = this._io.readU4le();
            this._unnamed5 = this._io.readU4le();
            this._unnamed6 = this._io.readU1();
            this.ofsName = this._io.readU1();
        }
        private DeviceSqlString name;
        public DeviceSqlString name() {
            if (this.name != null)
                return this.name;
            long _pos = this._io.pos();
            this._io.seek(((_parent().ofsRow() + 40) + ofsName()));
            this.name = new DeviceSqlString(this._io, this, _root);
            this._io.seek(_pos);
            return this.name;
        }
        private byte[] magic;
        private int indexShift;
        private long _unnamed2;
        private long artistId;
        private long id;
        private long _unnamed5;
        private int _unnamed6;
        private int ofsName;
        private PdbFile _root;
        private PdbFile.RowRef _parent;
        public byte[] magic() { return magic; }

        /**
         * TODO name from @flesniak, but what does it mean?
         */
        public int indexShift() { return indexShift; }
        public long _unnamed2() { return _unnamed2; }

        /**
         * Identifies the artist associated with the album.
         */
        public long artistId() { return artistId; }

        /**
         * The unique identifier by which this album can be requested
         * and linked from other rows (such as tracks).
         */
        public long id() { return id; }
        public long _unnamed5() { return _unnamed5; }

        /**
         * @flesniak says: "alwayx 0x03, maybe an unindexed empty string"
         */
        public int _unnamed6() { return _unnamed6; }

        /**
         * The location of the variable-length name string, relative to
         * the start of this row.
         */
        public int ofsName() { return ofsName; }
        public PdbFile _root() { return _root; }
        public PdbFile.RowRef _parent() { return _parent; }
    }

    /**
     * A table page, consisting of a short header describing the
     * content of the page and linking to the next page, followed by a
     * heap in which row data is found. At the end of the page there is
     * an index which locates all rows present in the heap via their
     * offsets past the end of the page header.
     */
    public static class Page extends KaitaiStruct {
        public static Page fromFile(String fileName) throws IOException {
            return new Page(new ByteBufferKaitaiStream(fileName));
        }

        public Page(KaitaiStream _io) {
            this(_io, null, null);
        }

        public Page(KaitaiStream _io, PdbFile.PageRef _parent) {
            this(_io, _parent, null);
        }

        public Page(KaitaiStream _io, PdbFile.PageRef _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this._unnamed0 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
            this.pageIndex = this._io.readU4le();
            this.type = PdbFile.PageType.byId(this._io.readU4le());
            this.nextPage = new PageRef(this._io, this, _root);
            this._unnamed4 = this._io.readU4le();
            this._unnamed5 = this._io.readBytes(4);
            this.numRows = this._io.readU1();
            this._unnamed7 = this._io.readU1();
            this._unnamed8 = this._io.readU2le();
            this.freeSize = this._io.readU2le();
            this.usedSize = this._io.readU2le();
            this._unnamed11 = this._io.readU2le();
            this.numRowsLarge = this._io.readU2le();
            this._unnamed13 = this._io.readU2le();
            this._unnamed14 = this._io.readU2le();
        }
        private Integer numGroups;

        /**
         * The number of row groups that are present in the index. Each
         * group can hold up to sixteen rows. All but the final one
         * will hold sixteen rows.
         */
        public Integer numGroups() {
            if (this.numGroups != null)
                return this.numGroups;
            int _tmp = (int) ((((numRows() - 1) / 16) + 1));
            this.numGroups = _tmp;
            return this.numGroups;
        }
        private ArrayList<RowGroup> rowGroups;

        /**
         * The actual row groups making up the row index. Each group
         * can hold up to sixteen rows.
         */
        public ArrayList<RowGroup> rowGroups() {
            if (this.rowGroups != null)
                return this.rowGroups;
            rowGroups = new ArrayList<RowGroup>((int) (numGroups()));
            for (int i = 0; i < numGroups(); i++) {
                this.rowGroups.add(new RowGroup(this._io, this, _root, i));
            }
            return this.rowGroups;
        }
        private byte[] _unnamed0;
        private long pageIndex;
        private PageType type;
        private PageRef nextPage;
        private long _unnamed4;
        private byte[] _unnamed5;
        private int numRows;
        private int _unnamed7;
        private int _unnamed8;
        private int freeSize;
        private int usedSize;
        private int _unnamed11;
        private int numRowsLarge;
        private int _unnamed13;
        private int _unnamed14;
        private PdbFile _root;
        private PdbFile.PageRef _parent;
        public byte[] _unnamed0() { return _unnamed0; }

        /**
         * Matches the index we used to look up the page, sanity check?
         */
        public long pageIndex() { return pageIndex; }

        /**
         * Identifies the type of information stored in the rows of this page.
         */
        public PageType type() { return type; }

        /**
         * Index of the next page containing this type of rows. Points past
         * the end of the file if there are no more.
         */
        public PageRef nextPage() { return nextPage; }

        /**
         * @flesniak said: "sequence number (0->1: 8->13, 1->2: 22, 2->3: 27)"
         */
        public long _unnamed4() { return _unnamed4; }
        public byte[] _unnamed5() { return _unnamed5; }

        /**
         * The number of rows on this page (controls the number of row
         * index entries there are, but some of those may not be marked
         * as present in the table due to deletion).
         */
        public int numRows() { return numRows; }

        /**
         * @flesniak said: "a bitmask (1st track: 32)"
         */
        public int _unnamed7() { return _unnamed7; }

        /**
         * @flesniak said: "25600 for strange blocks"
         */
        public int _unnamed8() { return _unnamed8; }

        /**
         * Unused space (in bytes) in the page heap, excluding the row
         * index at end of page.
         */
        public int freeSize() { return freeSize; }

        /**
         * The number of bytes that are in use in the page heap.
         */
        public int usedSize() { return usedSize; }

        /**
         * @flesniak said: "(0->1: 2)"
         */
        public int _unnamed11() { return _unnamed11; }

        /**
         * @flesniak said: "usually <= num_rows except for playlist_map?"
         */
        public int numRowsLarge() { return numRowsLarge; }

        /**
         * @flesniak said: "1004 for strange blocks, 0 otherwise"
         */
        public int _unnamed13() { return _unnamed13; }

        /**
         * @flesniak said: "always 0 except 1 for history pages, num
         * entries for strange pages?"
         */
        public int _unnamed14() { return _unnamed14; }
        public PdbFile _root() { return _root; }
        public PdbFile.PageRef _parent() { return _parent; }
    }

    /**
     * A group of row indices, which are built backwards from the end
     * of the page. Holds up to sixteen row offsets, along with a bit
     * mask that indicates whether each row is actually present in the
     * table.
     */
    public static class RowGroup extends KaitaiStruct {

        public RowGroup(KaitaiStream _io, int groupIndex) {
            this(_io, null, null, groupIndex);
        }

        public RowGroup(KaitaiStream _io, PdbFile.Page _parent, int groupIndex) {
            this(_io, _parent, null, groupIndex);
        }

        public RowGroup(KaitaiStream _io, PdbFile.Page _parent, PdbFile _root, int groupIndex) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            this.groupIndex = groupIndex;
            _read();
        }
        private void _read() {
        }
        private Integer base;

        /**
         * The starting point of this group of row indices.
         */
        public Integer base() {
            if (this.base != null)
                return this.base;
            int _tmp = (int) ((_root.lenPage() - (groupIndex() * 36)));
            this.base = _tmp;
            return this.base;
        }
        private Integer rowPresentFlags;

        /**
         * Each bit specifies whether a particular row is present. The
         * low order bit corresponds to the first row in this index,
         * whose offset immediately precedes these flag bits. The
         * second bit corresponds to the row whose offset precedes
         * that, and so on.
         */
        public Integer rowPresentFlags() {
            if (this.rowPresentFlags != null)
                return this.rowPresentFlags;
            long _pos = this._io.pos();
            this._io.seek((base() - 4));
            this.rowPresentFlags = this._io.readU2le();
            this._io.seek(_pos);
            return this.rowPresentFlags;
        }
        private ArrayList<RowRef> rows;

        /**
         * The row offsets in this group.
         */
        public ArrayList<RowRef> rows() {
            if (this.rows != null)
                return this.rows;
            rows = new ArrayList<RowRef>((int) ((groupIndex() < (_parent().numGroups() - 1) ? 16 : (KaitaiStream.mod((_parent().numRows() - 1), 16) + 1))));
            for (int i = 0; i < (groupIndex() < (_parent().numGroups() - 1) ? 16 : (KaitaiStream.mod((_parent().numRows() - 1), 16) + 1)); i++) {
                this.rows.add(new RowRef(this._io, this, _root, i));
            }
            return this.rows;
        }
        private int groupIndex;
        private PdbFile _root;
        private PdbFile.Page _parent;

        /**
         * Identifies which group is being generated. They build backwards
         * from the end of the page.
         */
        public int groupIndex() { return groupIndex; }
        public PdbFile _root() { return _root; }
        public PdbFile.Page _parent() { return _parent; }
    }

    /**
     * A row that holds the path to an album art image file and the
     * associated artwork ID.
     */
    public static class ArtworkRow extends KaitaiStruct {
        public static ArtworkRow fromFile(String fileName) throws IOException {
            return new ArtworkRow(new ByteBufferKaitaiStream(fileName));
        }

        public ArtworkRow(KaitaiStream _io) {
            this(_io, null, null);
        }

        public ArtworkRow(KaitaiStream _io, PdbFile.RowRef _parent) {
            this(_io, _parent, null);
        }

        public ArtworkRow(KaitaiStream _io, PdbFile.RowRef _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.id = this._io.readU4le();
            this.path = new DeviceSqlString(this._io, this, _root);
        }
        private long id;
        private DeviceSqlString path;
        private PdbFile _root;
        private PdbFile.RowRef _parent;

        /**
         * The unique identifier by which this art can be requested
         * and linked from other rows (such as tracks).
         */
        public long id() { return id; }

        /**
         * The variable-length file path string at which the art file
         * can be found.
         */
        public DeviceSqlString path() { return path; }
        public PdbFile _root() { return _root; }
        public PdbFile.RowRef _parent() { return _parent; }
    }

    /**
     * An ASCII-encoded string preceded by a two-byte length field.
     * TODO May need to skip a byte after the length!
     *      Have not found any test data.
     */
    public static class DeviceSqlLongAscii extends KaitaiStruct {
        public static DeviceSqlLongAscii fromFile(String fileName) throws IOException {
            return new DeviceSqlLongAscii(new ByteBufferKaitaiStream(fileName));
        }

        public DeviceSqlLongAscii(KaitaiStream _io) {
            this(_io, null, null);
        }

        public DeviceSqlLongAscii(KaitaiStream _io, PdbFile.DeviceSqlString _parent) {
            this(_io, _parent, null);
        }

        public DeviceSqlLongAscii(KaitaiStream _io, PdbFile.DeviceSqlString _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.length = this._io.readU2le();
            this.text = new String(this._io.readBytes(length()), Charset.forName("ascii"));
        }
        private int length;
        private String text;
        private PdbFile _root;
        private PdbFile.DeviceSqlString _parent;

        /**
         * Contains the length of the string.
         */
        public int length() { return length; }
        public String text() { return text; }
        public PdbFile _root() { return _root; }
        public PdbFile.DeviceSqlString _parent() { return _parent; }
    }

    /**
     * A row that holds an artist name and ID.
     */
    public static class ArtistRow extends KaitaiStruct {
        public static ArtistRow fromFile(String fileName) throws IOException {
            return new ArtistRow(new ByteBufferKaitaiStream(fileName));
        }

        public ArtistRow(KaitaiStream _io) {
            this(_io, null, null);
        }

        public ArtistRow(KaitaiStream _io, PdbFile.RowRef _parent) {
            this(_io, _parent, null);
        }

        public ArtistRow(KaitaiStream _io, PdbFile.RowRef _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.magic = this._io.ensureFixedContents(new byte[] { 96, 0 });
            this.indexShift = this._io.readU2le();
            this.id = this._io.readU4le();
            this._unnamed3 = this._io.readU1();
            this.ofsName = this._io.readU1();
        }
        private DeviceSqlString name;
        public DeviceSqlString name() {
            if (this.name != null)
                return this.name;
            long _pos = this._io.pos();
            this._io.seek(((_parent().ofsRow() + 40) + ofsName()));
            this.name = new DeviceSqlString(this._io, this, _root);
            this._io.seek(_pos);
            return this.name;
        }
        private byte[] magic;
        private int indexShift;
        private long id;
        private int _unnamed3;
        private int ofsName;
        private PdbFile _root;
        private PdbFile.RowRef _parent;
        public byte[] magic() { return magic; }

        /**
         * TODO name from @flesniak, but what does it mean?
         */
        public int indexShift() { return indexShift; }

        /**
         * The unique identifier by which this artist can be requested
         * and linked from other rows (such as tracks).
         */
        public long id() { return id; }

        /**
         * @flesniak says: "alwayx 0x03, maybe an unindexed empty string"
         */
        public int _unnamed3() { return _unnamed3; }

        /**
         * The location of the variable-length name string, relative to
         * the start of this row.
         */
        public int ofsName() { return ofsName; }
        public PdbFile _root() { return _root; }
        public PdbFile.RowRef _parent() { return _parent; }
    }

    /**
     * An index which points to a table page (its offset can be found
     * by multiplying the index by the `page_len` value in the file
     * header). This type allows the linked page to be lazy loaded.
     */
    public static class PageRef extends KaitaiStruct {
        public static PageRef fromFile(String fileName) throws IOException {
            return new PageRef(new ByteBufferKaitaiStream(fileName));
        }

        public PageRef(KaitaiStream _io) {
            this(_io, null, null);
        }

        public PageRef(KaitaiStream _io, KaitaiStruct _parent) {
            this(_io, _parent, null);
        }

        public PageRef(KaitaiStream _io, KaitaiStruct _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.index = this._io.readU4le();
        }
        private Page body;

        /**
         * When referenced, loads the specified page and parses its
         * contents appropriately for the type of data it contains.
         */
        public Page body() {
            if (this.body != null)
                return this.body;
            KaitaiStream io = _root._io();
            long _pos = io.pos();
            io.seek((_root.lenPage() * index()));
            this._raw_body = io.readBytes(_root.lenPage());
            KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
            this.body = new Page(_io__raw_body, this, _root);
            io.seek(_pos);
            return this.body;
        }
        private long index;
        private PdbFile _root;
        private KaitaiStruct _parent;
        private byte[] _raw_body;

        /**
         * Identifies the desired page number.
         */
        public long index() { return index; }
        public PdbFile _root() { return _root; }
        public KaitaiStruct _parent() { return _parent; }
        public byte[] _raw_body() { return _raw_body; }
    }

    /**
     * A UTF-16BE-encoded string preceded by a two-byte length field.
     */
    public static class DeviceSqlLongUtf16be extends KaitaiStruct {
        public static DeviceSqlLongUtf16be fromFile(String fileName) throws IOException {
            return new DeviceSqlLongUtf16be(new ByteBufferKaitaiStream(fileName));
        }

        public DeviceSqlLongUtf16be(KaitaiStream _io) {
            this(_io, null, null);
        }

        public DeviceSqlLongUtf16be(KaitaiStream _io, PdbFile.DeviceSqlString _parent) {
            this(_io, _parent, null);
        }

        public DeviceSqlLongUtf16be(KaitaiStream _io, PdbFile.DeviceSqlString _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.length = this._io.readU2le();
            this.text = new String(this._io.readBytes((length() - 4)), Charset.forName("utf-16be"));
        }
        private int length;
        private String text;
        private PdbFile _root;
        private PdbFile.DeviceSqlString _parent;

        /**
         * Contains the length of the string in bytes, including two trailing nulls.
         */
        public int length() { return length; }
        public String text() { return text; }
        public PdbFile _root() { return _root; }
        public PdbFile.DeviceSqlString _parent() { return _parent; }
    }

    /**
     * Each table is a linked list of pages containing rows of a single
     * type. This header describes the nature of the table and links to
     * its pages by index.
     */
    public static class Table extends KaitaiStruct {
        public static Table fromFile(String fileName) throws IOException {
            return new Table(new ByteBufferKaitaiStream(fileName));
        }

        public Table(KaitaiStream _io) {
            this(_io, null, null);
        }

        public Table(KaitaiStream _io, PdbFile _parent) {
            this(_io, _parent, null);
        }

        public Table(KaitaiStream _io, PdbFile _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.type = PdbFile.PageType.byId(this._io.readU4le());
            this.emptyCandidate = this._io.readU4le();
            this.firstPage = new PageRef(this._io, this, _root);
            this.lastPage = new PageRef(this._io, this, _root);
        }
        private PageType type;
        private long emptyCandidate;
        private PageRef firstPage;
        private PageRef lastPage;
        private PdbFile _root;
        private PdbFile _parent;

        /**
         * Identifies the kind of rows that are found in this table.
         */
        public PageType type() { return type; }
        public long emptyCandidate() { return emptyCandidate; }

        /**
         * Links to the chain of pages making up that table. The first
         * page seems to always contain similar garbage patterns and
         * zero rows, but the next page it links to contains the start
         * of the meaningful data rows.
         */
        public PageRef firstPage() { return firstPage; }
        public PageRef lastPage() { return lastPage; }
        public PdbFile _root() { return _root; }
        public PdbFile _parent() { return _parent; }
    }

    /**
     * An offset which points to a row in the table, whose actual
     * presence is controlled by one of the bits in
     * `row_present_flags`. This instance allows the row itself to be
     * lazily loaded, unless it is not present, in which case there is
     * no content to be loaded.
     */
    public static class RowRef extends KaitaiStruct {

        public RowRef(KaitaiStream _io, int rowIndex) {
            this(_io, null, null, rowIndex);
        }

        public RowRef(KaitaiStream _io, PdbFile.RowGroup _parent, int rowIndex) {
            this(_io, _parent, null, rowIndex);
        }

        public RowRef(KaitaiStream _io, PdbFile.RowGroup _parent, PdbFile _root, int rowIndex) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            this.rowIndex = rowIndex;
            _read();
        }
        private void _read() {
        }
        private Integer ofsRow;

        /**
         * The offset of the start of the row (in bytes past the end of
         * the page header).
         */
        public Integer ofsRow() {
            if (this.ofsRow != null)
                return this.ofsRow;
            long _pos = this._io.pos();
            this._io.seek((_parent().base() - (6 + (2 * rowIndex()))));
            this.ofsRow = this._io.readU2le();
            this._io.seek(_pos);
            return this.ofsRow;
        }
        private Boolean present;

        /**
         * Indicates whether the row index considers this row to be
         * present in the table. Will be `false` if the row has been
         * deleted.
         */
        public Boolean present() {
            if (this.present != null)
                return this.present;
            boolean _tmp = (boolean) ((((_parent().rowPresentFlags() >> rowIndex()) & 1) != 0 ? true : false));
            this.present = _tmp;
            return this.present;
        }
        private KaitaiStruct body;

        /**
         * The actual content of the row, as long as it is present.
         */
        public KaitaiStruct body() {
            if (this.body != null)
                return this.body;
            if (present()) {
                long _pos = this._io.pos();
                this._io.seek((ofsRow() + 40));
                switch (_parent()._parent().type()) {
                case ALBUMS: {
                    this.body = new AlbumRow(this._io, this, _root);
                    break;
                }
                case ARTISTS: {
                    this.body = new ArtistRow(this._io, this, _root);
                    break;
                }
                case ARTWORK: {
                    this.body = new ArtworkRow(this._io, this, _root);
                    break;
                }
                }
                this._io.seek(_pos);
            }
            return this.body;
        }
        private int rowIndex;
        private PdbFile _root;
        private PdbFile.RowGroup _parent;

        /**
         * Identifies which row within the row index this reference
         * came from, so the correct flag can be checked for the row
         * presence and the correct row offset can be found.
         */
        public int rowIndex() { return rowIndex; }
        public PdbFile _root() { return _root; }
        public PdbFile.RowGroup _parent() { return _parent; }
    }
    private byte[] _unnamed0;
    private long lenPage;
    private long numTables;
    private long nextUnusedPage;
    private long _unnamed4;
    private long sequence;
    private byte[] _unnamed6;
    private ArrayList<Table> tables;
    private PdbFile _root;
    private KaitaiStruct _parent;
    public byte[] _unnamed0() { return _unnamed0; }

    /**
     * The database page size, in bytes. Pages are referred to by
     * index, so this size is needed to calculate their offset, and
     * table pages have a row index structure which is built from the
     * end of the page backwards, so finding that also requires this
     * value.
     */
    public long lenPage() { return lenPage; }

    /**
     * Determines the number of table entries that are present. Each
     * table is a linked list of pages containing rows of a particular
     * type.
     */
    public long numTables() { return numTables; }

    /**
     * @flesinak said: "Not used as any `empty_candidate`, points
     * past the end of the file."
     */
    public long nextUnusedPage() { return nextUnusedPage; }
    public long _unnamed4() { return _unnamed4; }

    /**
     * @flesniak said: "Always incremented by at least one,
     * sometimes by two or three."
     */
    public long sequence() { return sequence; }
    public byte[] _unnamed6() { return _unnamed6; }

    /**
     * Describes and links to the tables present in the database.
     */
    public ArrayList<Table> tables() { return tables; }
    public PdbFile _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
}
