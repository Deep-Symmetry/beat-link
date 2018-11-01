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
        INKNOWN_18(18),
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
    private FileHeader header;
    private PdbFile _root;
    private KaitaiStruct _parent;
    public FileHeader header() { return header; }
    public PdbFile _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
}
