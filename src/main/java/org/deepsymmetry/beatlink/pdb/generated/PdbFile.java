// This is a generated file! Please edit source .ksy file and use kaitai-struct-compiler to rebuild

package org.deepsymmetry.beatlink.pdb.generated;

import io.kaitai.struct.ByteBufferKaitaiStream;
import io.kaitai.struct.KaitaiStruct;
import io.kaitai.struct.KaitaiStream;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

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
        this.header = new FileHeader(this._io, this, _root);
    }
    public static class RowFlags extends KaitaiStruct {

        public RowFlags(KaitaiStream _io, int numRemaining, int base) {
            this(_io, null, null, numRemaining, base);
        }

        public RowFlags(KaitaiStream _io, KaitaiStruct _parent, int numRemaining, int base) {
            this(_io, _parent, null, numRemaining, base);
        }

        public RowFlags(KaitaiStream _io, KaitaiStruct _parent, PdbFile _root, int numRemaining, int base) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            this.numRemaining = numRemaining;
            this.base = base;
            _read();
        }
        private void _read() {
            this.entryEnabledFlags = this._io.readU2le();
            this.unknownFlags = this._io.readU2le();
        }
        private ArrayList<RowRef> rows;
        public ArrayList<RowRef> rows() {
            if (this.rows != null)
                return this.rows;
            long _pos = this._io.pos();
            this._io.seek((base() - 32));
            rows = new ArrayList<RowRef>((int) (16));
            for (int i = 0; i < 16; i++) {
                this.rows.add(new RowRef(this._io, this, _root, i));
            }
            this._io.seek(_pos);
            return this.rows;
        }
        private RowFlags next;
        public RowFlags next() {
            if (this.next != null)
                return this.next;
            if (numRemaining() > 0) {
                long _pos = this._io.pos();
                this._io.seek((base() - 36));
                this.next = new RowFlags(this._io, this, _root, (numRemaining() - 1), (base() - 36));
                this._io.seek(_pos);
            }
            return this.next;
        }
        private int entryEnabledFlags;
        private int unknownFlags;
        private int numRemaining;
        private int base;
        private PdbFile _root;
        private KaitaiStruct _parent;
        public int entryEnabledFlags() { return entryEnabledFlags; }
        public int unknownFlags() { return unknownFlags; }
        public int numRemaining() { return numRemaining; }
        public int base() { return base; }
        public PdbFile _root() { return _root; }
        public KaitaiStruct _parent() { return _parent; }
    }
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
            this.header = new PageHeader(this._io, this, _root);
            this.heap = this._io.readBytes((_root.header().lenPage() - _io().pos()));
        }
        private Integer numRowIndices;
        public Integer numRowIndices() {
            if (this.numRowIndices != null)
                return this.numRowIndices;
            int _tmp = (int) (((header().numEntries() / 16) + 1));
            this.numRowIndices = _tmp;
            return this.numRowIndices;
        }
        private RowFlags rowIndexChain;
        public RowFlags rowIndexChain() {
            if (this.rowIndexChain != null)
                return this.rowIndexChain;
            long _pos = this._io.pos();
            this._io.seek(4092);
            this.rowIndexChain = new RowFlags(this._io, this, _root, (numRowIndices() - 1), 4092);
            this._io.seek(_pos);
            return this.rowIndexChain;
        }
        private PageHeader header;
        private byte[] heap;
        private PdbFile _root;
        private PdbFile.PageRef _parent;
        public PageHeader header() { return header; }
        public byte[] heap() { return heap; }
        public PdbFile _root() { return _root; }
        public PdbFile.PageRef _parent() { return _parent; }
    }
    public static class PageHeader extends KaitaiStruct {
        public static PageHeader fromFile(String fileName) throws IOException {
            return new PageHeader(new ByteBufferKaitaiStream(fileName));
        }

        public PageHeader(KaitaiStream _io) {
            this(_io, null, null);
        }

        public PageHeader(KaitaiStream _io, PdbFile.Page _parent) {
            this(_io, _parent, null);
        }

        public PageHeader(KaitaiStream _io, PdbFile.Page _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.empty1 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
            this.pageIndex = this._io.readU4le();
            this.type = PdbFile.PageType.byId(this._io.readU4le());
            this.nextPage = new PageRef(this._io, this, _root);
            this.unknown1 = this._io.readU4le();
            this.unknown2 = this._io.readBytes(4);
            this.numEntries = this._io.readU1();
            this.unknown3 = this._io.readU1();
            this.unknown4 = this._io.readU2le();
            this.freeSize = this._io.readU2le();
            this.usedSize = this._io.readU2le();
            this.unknown5 = this._io.readU2le();
            this.numEntriesLarge = this._io.readU2le();
            this.unknown6 = this._io.readU2le();
            this.unknown7 = this._io.readU2le();
        }
        private byte[] empty1;
        private long pageIndex;
        private PageType type;
        private PageRef nextPage;
        private long unknown1;
        private byte[] unknown2;
        private int numEntries;
        private int unknown3;
        private int unknown4;
        private int freeSize;
        private int usedSize;
        private int unknown5;
        private int numEntriesLarge;
        private int unknown6;
        private int unknown7;
        private PdbFile _root;
        private PdbFile.Page _parent;
        public byte[] empty1() { return empty1; }

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
        public long unknown1() { return unknown1; }
        public byte[] unknown2() { return unknown2; }
        public int numEntries() { return numEntries; }

        /**
         * @flesniak said: "a bitmask (1st track: 32)"
         */
        public int unknown3() { return unknown3; }

        /**
         * @flesniak said: "25600 for strange blocks"
         */
        public int unknown4() { return unknown4; }

        /**
         * Unused space, excluding index at end of page.
         */
        public int freeSize() { return freeSize; }
        public int usedSize() { return usedSize; }

        /**
         * @flesniak said: "(0->1: 2)"
         */
        public int unknown5() { return unknown5; }

        /**
         * @flesniak said: "usually <= num_entries except for playlist_map?"
         */
        public int numEntriesLarge() { return numEntriesLarge; }

        /**
         * @flesniak said: "1004 for strange blocks, 0 otherwise"
         */
        public int unknown6() { return unknown6; }

        /**
         * @flesniak said: "always 0 except 1 for history pages, num entries for strange pages?"
         */
        public int unknown7() { return unknown7; }
        public PdbFile _root() { return _root; }
        public PdbFile.Page _parent() { return _parent; }
    }
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
        public Page body() {
            if (this.body != null)
                return this.body;
            KaitaiStream io = _root._io();
            long _pos = io.pos();
            io.seek((_root.header().lenPage() * index()));
            this._raw_body = io.readBytes(_root.header().lenPage());
            KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
            this.body = new Page(_io__raw_body, this, _root);
            io.seek(_pos);
            return this.body;
        }
        private long index;
        private PdbFile _root;
        private KaitaiStruct _parent;
        private byte[] _raw_body;
        public long index() { return index; }
        public PdbFile _root() { return _root; }
        public KaitaiStruct _parent() { return _parent; }
        public byte[] _raw_body() { return _raw_body; }
    }
    public static class FileHeaderEntry extends KaitaiStruct {
        public static FileHeaderEntry fromFile(String fileName) throws IOException {
            return new FileHeaderEntry(new ByteBufferKaitaiStream(fileName));
        }

        public FileHeaderEntry(KaitaiStream _io) {
            this(_io, null, null);
        }

        public FileHeaderEntry(KaitaiStream _io, PdbFile.FileHeader _parent) {
            this(_io, _parent, null);
        }

        public FileHeaderEntry(KaitaiStream _io, PdbFile.FileHeader _parent, PdbFile _root) {
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
        private PdbFile.FileHeader _parent;
        public PageType type() { return type; }
        public long emptyCandidate() { return emptyCandidate; }

        /**
         * Always points to a strange page, which then links to a real data page.
         */
        public PageRef firstPage() { return firstPage; }
        public PageRef lastPage() { return lastPage; }
        public PdbFile _root() { return _root; }
        public PdbFile.FileHeader _parent() { return _parent; }
    }
    public static class FileHeader extends KaitaiStruct {
        public static FileHeader fromFile(String fileName) throws IOException {
            return new FileHeader(new ByteBufferKaitaiStream(fileName));
        }

        public FileHeader(KaitaiStream _io) {
            this(_io, null, null);
        }

        public FileHeader(KaitaiStream _io, PdbFile _parent) {
            this(_io, _parent, null);
        }

        public FileHeader(KaitaiStream _io, PdbFile _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.empty1 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
            this.lenPage = this._io.readU4le();
            this.numEntries = this._io.readU4le();
            this.nextUnusedPage = this._io.readU4le();
            this.unknown1 = this._io.readU4le();
            this.sequence = this._io.readU4le();
            this.empty2 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
            entries = new ArrayList<FileHeaderEntry>((int) (numEntries()));
            for (int i = 0; i < numEntries(); i++) {
                this.entries.add(new FileHeaderEntry(this._io, this, _root));
            }
            this.padding = this._io.readBytes((lenPage() - _io().pos()));
        }
        private byte[] empty1;
        private long lenPage;
        private long numEntries;
        private long nextUnusedPage;
        private long unknown1;
        private long sequence;
        private byte[] empty2;
        private ArrayList<FileHeaderEntry> entries;
        private byte[] padding;
        private PdbFile _root;
        private PdbFile _parent;
        public byte[] empty1() { return empty1; }
        public long lenPage() { return lenPage; }

        /**
         * Determines the number of file header entries that are present.
         */
        public long numEntries() { return numEntries; }

        /**
         * Not used as any `empty_candidate`, points past the end of the file.
         */
        public long nextUnusedPage() { return nextUnusedPage; }
        public long unknown1() { return unknown1; }

        /**
         * Always incremented by at least one, sometimes by two or three.
         */
        public long sequence() { return sequence; }
        public byte[] empty2() { return empty2; }
        public ArrayList<FileHeaderEntry> entries() { return entries; }
        public byte[] padding() { return padding; }
        public PdbFile _root() { return _root; }
        public PdbFile _parent() { return _parent; }
    }
    public static class RowRef extends KaitaiStruct {

        public RowRef(KaitaiStream _io, int index) {
            this(_io, null, null, index);
        }

        public RowRef(KaitaiStream _io, PdbFile.RowFlags _parent, int index) {
            this(_io, _parent, null, index);
        }

        public RowRef(KaitaiStream _io, PdbFile.RowFlags _parent, PdbFile _root, int index) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            this.index = index;
            _read();
        }
        private void _read() {
            this.offset = this._io.readU2le();
        }
        private Boolean enabled;
        public Boolean enabled() {
            if (this.enabled != null)
                return this.enabled;
            boolean _tmp = (boolean) ((((_parent().entryEnabledFlags() >> (15 - index())) & 1) != 0 ? true : false));
            this.enabled = _tmp;
            return this.enabled;
        }
        private byte[] body;
        public byte[] body() {
            if (this.body != null)
                return this.body;
            if (enabled()) {
                long _pos = this._io.pos();
                this._io.seek((offset() + 40));
                this.body = this._io.readBytesFull();
                this._io.seek(_pos);
            }
            return this.body;
        }
        private int offset;
        private int index;
        private PdbFile _root;
        private PdbFile.RowFlags _parent;
        public int offset() { return offset; }
        public int index() { return index; }
        public PdbFile _root() { return _root; }
        public PdbFile.RowFlags _parent() { return _parent; }
    }
    private FileHeader header;
    private PdbFile _root;
    private KaitaiStruct _parent;
    public FileHeader header() { return header; }
    public PdbFile _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
}
