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
        this._raw_pages = new ArrayList<byte[]>();
        this.pages = new ArrayList<Page>();
        {
            int i = 0;
            while (!this._io.isEof()) {
                this._raw_pages.add(this._io.readBytes(header().pageSize()));
                KaitaiStream _io__raw_pages = new ByteBufferKaitaiStream(_raw_pages.get(_raw_pages.size() - 1));
                this.pages.add(new Page(_io__raw_pages, this, _root));
                i++;
            }
        }
    }
    public static class Page extends KaitaiStruct {
        public static Page fromFile(String fileName) throws IOException {
            return new Page(new ByteBufferKaitaiStream(fileName));
        }

        public Page(KaitaiStream _io) {
            this(_io, null, null);
        }

        public Page(KaitaiStream _io, PdbFile _parent) {
            this(_io, _parent, null);
        }

        public Page(KaitaiStream _io, PdbFile _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.header = new PageHeader(this._io, this, _root);
        }
        private Integer footerArrayCount;
        public Integer footerArrayCount() {
            if (this.footerArrayCount != null)
                return this.footerArrayCount;
            int _tmp = (int) (((header().entryCount() / 16) + 1));
            this.footerArrayCount = _tmp;
            return this.footerArrayCount;
        }
        private Integer footerSize;
        public Integer footerSize() {
            if (this.footerSize != null)
                return this.footerSize;
            int _tmp = (int) ((footerArrayCount() * 36));
            this.footerSize = _tmp;
            return this.footerSize;
        }
        private Integer footerPosition;
        public Integer footerPosition() {
            if (this.footerPosition != null)
                return this.footerPosition;
            int _tmp = (int) ((4096 - footerSize()));
            this.footerPosition = _tmp;
            return this.footerPosition;
        }
        private ArrayList<PageEntryIndex> footer;
        public ArrayList<PageEntryIndex> footer() {
            if (this.footer != null)
                return this.footer;
            long _pos = this._io.pos();
            this._io.seek(footerPosition());
            footer = new ArrayList<PageEntryIndex>((int) (footerArrayCount()));
            for (int i = 0; i < footerArrayCount(); i++) {
                this.footer.add(new PageEntryIndex(this._io, this, _root));
            }
            this._io.seek(_pos);
            return this.footer;
        }
        private PageHeader header;
        private PdbFile _root;
        private PdbFile _parent;
        public PageHeader header() { return header; }
        public PdbFile _root() { return _root; }
        public PdbFile _parent() { return _parent; }
    }
    public static class PageEntryIndex extends KaitaiStruct {
        public static PageEntryIndex fromFile(String fileName) throws IOException {
            return new PageEntryIndex(new ByteBufferKaitaiStream(fileName));
        }

        public PageEntryIndex(KaitaiStream _io) {
            this(_io, null, null);
        }

        public PageEntryIndex(KaitaiStream _io, PdbFile.Page _parent) {
            this(_io, _parent, null);
        }

        public PageEntryIndex(KaitaiStream _io, PdbFile.Page _parent, PdbFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            entryOffsets = new ArrayList<Integer>((int) (16));
            for (int i = 0; i < 16; i++) {
                this.entryOffsets.add(this._io.readU2le());
            }
        }
        private ArrayList<Boolean> entryEnabledFlags;
        public ArrayList<Boolean> entryEnabledFlags() {
            if (this.entryEnabledFlags != null)
                return this.entryEnabledFlags;
            entryEnabledFlags = new ArrayList<Boolean>((int) (16));
            for (int i = 0; i < 16; i++) {
                this.entryEnabledFlags.add(this._io.readBitsInt(1) != 0);
            }
            return this.entryEnabledFlags;
        }
        private ArrayList<Integer> entryOffsets;
        private PdbFile _root;
        private PdbFile.Page _parent;
        public ArrayList<Integer> entryOffsets() { return entryOffsets; }
        public PdbFile _root() { return _root; }
        public PdbFile.Page _parent() { return _parent; }
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
            this.nextPage = this._io.readU4le();
            this.unknown1 = this._io.readU4le();
            this.unknown2 = this._io.readBytes(4);
            this.entryCount = this._io.readU1();
            this.unknown3 = this._io.readU1();
            this.unknown4 = this._io.readU2le();
            this.freeSize = this._io.readU2le();
            this.usedSize = this._io.readU2le();
            this.unknown5 = this._io.readU2le();
            this.largeEntryCount = this._io.readU2le();
            this.unknown6 = this._io.readU2le();
            this.unknown7 = this._io.readU2le();
        }
        private byte[] empty1;
        private long pageIndex;
        private PageType type;
        private long nextPage;
        private long unknown1;
        private byte[] unknown2;
        private int entryCount;
        private int unknown3;
        private int unknown4;
        private int freeSize;
        private int usedSize;
        private int unknown5;
        private int largeEntryCount;
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
        public long nextPage() { return nextPage; }

        /**
         * @flesniak said: "sequence number (0->1: 8->13, 1->2: 22, 2->3: 27)"
         */
        public long unknown1() { return unknown1; }
        public byte[] unknown2() { return unknown2; }
        public int entryCount() { return entryCount; }

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
         * @flesniak said: "usually <= entry_count except for playlist_map?"
         */
        public int largeEntryCount() { return largeEntryCount; }

        /**
         * @flesniak said: "1004 for strange blocks, 0 otherwise"
         */
        public int unknown6() { return unknown6; }

        /**
         * @flesniak said: "always 0 except 1 for history pages, entry count for strange pages?"
         */
        public int unknown7() { return unknown7; }
        public PdbFile _root() { return _root; }
        public PdbFile.Page _parent() { return _parent; }
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
            this.firstPage = this._io.readU4le();
            this.lastPage = this._io.readU4le();
        }
        private PageType type;
        private long emptyCandidate;
        private long firstPage;
        private long lastPage;
        private PdbFile _root;
        private PdbFile.FileHeader _parent;
        public PageType type() { return type; }
        public long emptyCandidate() { return emptyCandidate; }

        /**
         * Always points to a strange page, which then links to a real data page.
         */
        public long firstPage() { return firstPage; }
        public long lastPage() { return lastPage; }
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
            this.pageSize = this._io.readU4le();
            this.entryCount = this._io.readU4le();
            this.nextUnusedPage = this._io.readU4le();
            this.unknown1 = this._io.readU4le();
            this.sequence = this._io.readU4le();
            this.empty2 = this._io.ensureFixedContents(new byte[] { 0, 0, 0, 0 });
            entries = new ArrayList<FileHeaderEntry>((int) (entryCount()));
            for (int i = 0; i < entryCount(); i++) {
                this.entries.add(new FileHeaderEntry(this._io, this, _root));
            }
            this.padding = this._io.readBytes((pageSize() - _io().pos()));
        }
        private byte[] empty1;
        private long pageSize;
        private long entryCount;
        private long nextUnusedPage;
        private long unknown1;
        private long sequence;
        private byte[] empty2;
        private ArrayList<FileHeaderEntry> entries;
        private byte[] padding;
        private PdbFile _root;
        private PdbFile _parent;
        public byte[] empty1() { return empty1; }
        public long pageSize() { return pageSize; }

        /**
         * Determines the number of file header entries that are present.
         */
        public long entryCount() { return entryCount; }

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
    private FileHeader header;
    private ArrayList<Page> pages;
    private PdbFile _root;
    private KaitaiStruct _parent;
    private ArrayList<byte[]> _raw_pages;
    public FileHeader header() { return header; }
    public ArrayList<Page> pages() { return pages; }
    public PdbFile _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
    public ArrayList<byte[]> _raw_pages() { return _raw_pages; }
}
