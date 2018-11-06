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
 * These files are created by rekordbox when analyzing audio tracks
 * to facilitate DJ performance. They include waveforms, beat grids
 * (information about the precise time at which each beat occurs),
 * time indices to allow efficient seeking to specific positions
 * inside variable bit-rate audio streams, and lists of memory cues
 * and loop points. They are used by Pioneer professional DJ
 * equipment.
 * 
 * The format has been reverse-engineered to facilitate sophisticated
 * integrations with light and laser shows, videos, and other musical
 * instruments, by supporting deep knowledge of what is playing and
 * what is coming next through monitoring the network communications
 * of the players.
 * @see <a href="https://reverseengineering.stackexchange.com/questions/4311/help-reversing-a-edb-database-file-for-pioneers-rekordbox-software">Source</a>
 */
public class AnlzFile extends KaitaiStruct {
    public static AnlzFile fromFile(String fileName) throws IOException {
        return new AnlzFile(new ByteBufferKaitaiStream(fileName));
    }

    public enum SectionTags {
        CUES(1346588482),
        PATH(1347441736),
        BEAT_GRID(1347507290),
        VBR(1347830354),
        WAVE_PREVIEW(1347895638),
        WAVE_TINY(1347900978);

        private final long id;
        SectionTags(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, SectionTags> byId = new HashMap<Long, SectionTags>(6);
        static {
            for (SectionTags e : SectionTags.values())
                byId.put(e.id(), e);
        }
        public static SectionTags byId(long id) { return byId.get(id); }
    }

    public enum CueListType {
        MEMORY_CUES(0),
        HOT_CUES(1);

        private final long id;
        CueListType(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, CueListType> byId = new HashMap<Long, CueListType>(2);
        static {
            for (CueListType e : CueListType.values())
                byId.put(e.id(), e);
        }
        public static CueListType byId(long id) { return byId.get(id); }
    }

    public enum CueEntryType {
        MEMORY_CUE(1),
        LOOP(2);

        private final long id;
        CueEntryType(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, CueEntryType> byId = new HashMap<Long, CueEntryType>(2);
        static {
            for (CueEntryType e : CueEntryType.values())
                byId.put(e.id(), e);
        }
        public static CueEntryType byId(long id) { return byId.get(id); }
    }

    public enum CueEntryStatus {
        DISABLED(0),
        ENABLED(1);

        private final long id;
        CueEntryStatus(long id) { this.id = id; }
        public long id() { return id; }
        private static final Map<Long, CueEntryStatus> byId = new HashMap<Long, CueEntryStatus>(2);
        static {
            for (CueEntryStatus e : CueEntryStatus.values())
                byId.put(e.id(), e);
        }
        public static CueEntryStatus byId(long id) { return byId.get(id); }
    }

    public AnlzFile(KaitaiStream _io) {
        this(_io, null, null);
    }

    public AnlzFile(KaitaiStream _io, KaitaiStruct _parent) {
        this(_io, _parent, null);
    }

    public AnlzFile(KaitaiStream _io, KaitaiStruct _parent, AnlzFile _root) {
        super(_io);
        this._parent = _parent;
        this._root = _root == null ? this : _root;
        _read();
    }
    private void _read() {
        this._unnamed0 = this._io.ensureFixedContents(new byte[] { 80, 77, 65, 73 });
        this.lenHeader = this._io.readU4be();
        this.lenFile = this._io.readU4be();
        this._unnamed3 = this._io.readBytes((lenHeader() - _io().pos()));
        this.sections = new ArrayList<TaggedSection>();
        {
            int i = 0;
            while (!this._io.isEof()) {
                this.sections.add(new TaggedSection(this._io, this, _root));
                i++;
            }
        }
    }

    /**
     * Stores the file path of the audio file to which this analysis
     * applies.
     */
    public static class PathTag extends KaitaiStruct {
        public static PathTag fromFile(String fileName) throws IOException {
            return new PathTag(new ByteBufferKaitaiStream(fileName));
        }

        public PathTag(KaitaiStream _io) {
            this(_io, null, null);
        }

        public PathTag(KaitaiStream _io, AnlzFile.TaggedSection _parent) {
            this(_io, _parent, null);
        }

        public PathTag(KaitaiStream _io, AnlzFile.TaggedSection _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.lenPath = this._io.readU4be();
            if (lenPath() > 1) {
                this.path = new String(this._io.readBytes((lenPath() - 2)), Charset.forName("utf-16be"));
            }
        }
        private long lenPath;
        private String path;
        private AnlzFile _root;
        private AnlzFile.TaggedSection _parent;
        public long lenPath() { return lenPath; }
        public String path() { return path; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.TaggedSection _parent() { return _parent; }
    }

    /**
     * Stores a waveform preview image suitable for display long the
     * bottom of a loaded track.
     */
    public static class WavePreviewTag extends KaitaiStruct {
        public static WavePreviewTag fromFile(String fileName) throws IOException {
            return new WavePreviewTag(new ByteBufferKaitaiStream(fileName));
        }

        public WavePreviewTag(KaitaiStream _io) {
            this(_io, null, null);
        }

        public WavePreviewTag(KaitaiStream _io, AnlzFile.TaggedSection _parent) {
            this(_io, _parent, null);
        }

        public WavePreviewTag(KaitaiStream _io, AnlzFile.TaggedSection _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.lenPreview = this._io.readU4be();
            this._unnamed1 = this._io.readU4be();
            this.data = this._io.readBytes(lenPreview());
        }
        private long lenPreview;
        private long _unnamed1;
        private byte[] data;
        private AnlzFile _root;
        private AnlzFile.TaggedSection _parent;

        /**
         * The length, in bytes, of the preview data itself. This is
         * slightly redundant because it can be computed from the
         * length of the tag.
         */
        public long lenPreview() { return lenPreview; }
        public long _unnamed1() { return _unnamed1; }

        /**
         * The actual bytes of the waveform preview.
         */
        public byte[] data() { return data; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.TaggedSection _parent() { return _parent; }
    }

    /**
     * Holds a list of all the beats found within the track, recording
     * their bar position, the time at which they occur, and the tempo
     * at that point.
     */
    public static class BeatGridTag extends KaitaiStruct {
        public static BeatGridTag fromFile(String fileName) throws IOException {
            return new BeatGridTag(new ByteBufferKaitaiStream(fileName));
        }

        public BeatGridTag(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BeatGridTag(KaitaiStream _io, AnlzFile.TaggedSection _parent) {
            this(_io, _parent, null);
        }

        public BeatGridTag(KaitaiStream _io, AnlzFile.TaggedSection _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this._unnamed0 = this._io.readU4be();
            this._unnamed1 = this._io.readU4be();
            this.lenBeats = this._io.readU4be();
            beats = new ArrayList<BeatGridBeat>((int) (lenBeats()));
            for (int i = 0; i < lenBeats(); i++) {
                this.beats.add(new BeatGridBeat(this._io, this, _root));
            }
        }
        private long _unnamed0;
        private long _unnamed1;
        private long lenBeats;
        private ArrayList<BeatGridBeat> beats;
        private AnlzFile _root;
        private AnlzFile.TaggedSection _parent;
        public long _unnamed0() { return _unnamed0; }
        public long _unnamed1() { return _unnamed1; }

        /**
         * The number of beat entries which follow.
         */
        public long lenBeats() { return lenBeats; }

        /**
         * The entries of the beat grid.
         */
        public ArrayList<BeatGridBeat> beats() { return beats; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.TaggedSection _parent() { return _parent; }
    }

    /**
     * Stores an index allowing rapid seeking to particular times
     * within a variable-bitrate audio file.
     */
    public static class VbrTag extends KaitaiStruct {
        public static VbrTag fromFile(String fileName) throws IOException {
            return new VbrTag(new ByteBufferKaitaiStream(fileName));
        }

        public VbrTag(KaitaiStream _io) {
            this(_io, null, null);
        }

        public VbrTag(KaitaiStream _io, AnlzFile.TaggedSection _parent) {
            this(_io, _parent, null);
        }

        public VbrTag(KaitaiStream _io, AnlzFile.TaggedSection _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this._unnamed0 = this._io.readU4be();
            index = new ArrayList<Long>((int) (400));
            for (int i = 0; i < 400; i++) {
                this.index.add(this._io.readU4be());
            }
        }
        private long _unnamed0;
        private ArrayList<Long> index;
        private AnlzFile _root;
        private AnlzFile.TaggedSection _parent;
        public long _unnamed0() { return _unnamed0; }
        public ArrayList<Long> index() { return index; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.TaggedSection _parent() { return _parent; }
    }

    /**
     * A cue list entry. Can either represent a memory cue or a loop.
     */
    public static class CueEntry extends KaitaiStruct {
        public static CueEntry fromFile(String fileName) throws IOException {
            return new CueEntry(new ByteBufferKaitaiStream(fileName));
        }

        public CueEntry(KaitaiStream _io) {
            this(_io, null, null);
        }

        public CueEntry(KaitaiStream _io, AnlzFile.CueTag _parent) {
            this(_io, _parent, null);
        }

        public CueEntry(KaitaiStream _io, AnlzFile.CueTag _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this._unnamed0 = this._io.ensureFixedContents(new byte[] { 80, 67, 80, 84 });
            this.lenHeader = this._io.readU4be();
            this.lenEntry = this._io.readU4be();
            this.hotCue = this._io.readU4be();
            this.status = AnlzFile.CueEntryStatus.byId(this._io.readU4be());
            this._unnamed5 = this._io.readU4be();
            this.orderFirst = this._io.readU2be();
            this.orderLast = this._io.readU2be();
            this.type = AnlzFile.CueEntryType.byId(this._io.readU1());
            this._unnamed9 = this._io.readBytes(3);
            this.time = this._io.readU4be();
            this.loopTime = this._io.readU4be();
            this._unnamed12 = this._io.readBytes(16);
        }
        private byte[] _unnamed0;
        private long lenHeader;
        private long lenEntry;
        private long hotCue;
        private CueEntryStatus status;
        private long _unnamed5;
        private int orderFirst;
        private int orderLast;
        private CueEntryType type;
        private byte[] _unnamed9;
        private long time;
        private long loopTime;
        private byte[] _unnamed12;
        private AnlzFile _root;
        private AnlzFile.CueTag _parent;
        public byte[] _unnamed0() { return _unnamed0; }
        public long lenHeader() { return lenHeader; }
        public long lenEntry() { return lenEntry; }

        /**
         * If zero, this is an ordinary memory cue, otherwise this a
         * hot cue with the specified number.
         */
        public long hotCue() { return hotCue; }

        /**
         * If zero, this entry should be ignored.
         */
        public CueEntryStatus status() { return status; }
        public long _unnamed5() { return _unnamed5; }

        /**
         * @flesniak says: "0xffff for first cue, 0,1,3 for next"
         */
        public int orderFirst() { return orderFirst; }

        /**
         * @flesniak says: "1,2,3 for first, second, third cue, 0xffff for last"
         */
        public int orderLast() { return orderLast; }

        /**
         * Indicates whether this is a memory cue or a loop.
         */
        public CueEntryType type() { return type; }
        public byte[] _unnamed9() { return _unnamed9; }

        /**
         * The position, in milliseconds, at which the cue point lies
         * in the track.
         */
        public long time() { return time; }

        /**
         * The position, in milliseconds, at which the player loops
         * back to the cue time if this is a loop.
         */
        public long loopTime() { return loopTime; }
        public byte[] _unnamed12() { return _unnamed12; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.CueTag _parent() { return _parent; }
    }

    /**
     * Describes an individual beat in a beat grid.
     */
    public static class BeatGridBeat extends KaitaiStruct {
        public static BeatGridBeat fromFile(String fileName) throws IOException {
            return new BeatGridBeat(new ByteBufferKaitaiStream(fileName));
        }

        public BeatGridBeat(KaitaiStream _io) {
            this(_io, null, null);
        }

        public BeatGridBeat(KaitaiStream _io, AnlzFile.BeatGridTag _parent) {
            this(_io, _parent, null);
        }

        public BeatGridBeat(KaitaiStream _io, AnlzFile.BeatGridTag _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.beatNumber = this._io.readU2be();
            this.tempo = this._io.readU2be();
            this.time = this._io.readU4be();
        }
        private int beatNumber;
        private int tempo;
        private long time;
        private AnlzFile _root;
        private AnlzFile.BeatGridTag _parent;

        /**
         * The position of the beat within its musical bar, where beat 1
         * is the down beat.
         */
        public int beatNumber() { return beatNumber; }

        /**
         * The tempo at the time of this beat, in beats per minute,
         * multiplied by 100.
         */
        public int tempo() { return tempo; }

        /**
         * The time, in milliseconds, at which this beat occurs when
         * the track is played at normal (100%) pitch.
         */
        public long time() { return time; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.BeatGridTag _parent() { return _parent; }
    }

    /**
     * A type-tagged file section, identified by a four-byte magic
     * sequence, with a header specifying its length, and whose payload
     * is determined by the type tag.
     */
    public static class TaggedSection extends KaitaiStruct {
        public static TaggedSection fromFile(String fileName) throws IOException {
            return new TaggedSection(new ByteBufferKaitaiStream(fileName));
        }

        public TaggedSection(KaitaiStream _io) {
            this(_io, null, null);
        }

        public TaggedSection(KaitaiStream _io, AnlzFile _parent) {
            this(_io, _parent, null);
        }

        public TaggedSection(KaitaiStream _io, AnlzFile _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.fourcc = AnlzFile.SectionTags.byId(this._io.readU4be());
            this.lenHeader = this._io.readU4be();
            this.lenTag = this._io.readU4be();
            switch (fourcc()) {
            case WAVE_PREVIEW: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new WavePreviewTag(_io__raw_body, this, _root);
                break;
            }
            case CUES: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new CueTag(_io__raw_body, this, _root);
                break;
            }
            case PATH: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new PathTag(_io__raw_body, this, _root);
                break;
            }
            case VBR: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new VbrTag(_io__raw_body, this, _root);
                break;
            }
            case BEAT_GRID: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new BeatGridTag(_io__raw_body, this, _root);
                break;
            }
            case WAVE_TINY: {
                this._raw_body = this._io.readBytes((lenTag() - 12));
                KaitaiStream _io__raw_body = new ByteBufferKaitaiStream(_raw_body);
                this.body = new WavePreviewTag(_io__raw_body, this, _root);
                break;
            }
            default: {
                this.body = this._io.readBytes((lenTag() - 12));
                break;
            }
            }
        }
        private SectionTags fourcc;
        private long lenHeader;
        private long lenTag;
        private Object body;
        private AnlzFile _root;
        private AnlzFile _parent;
        private byte[] _raw_body;

        /**
         * A tag value indicating what kind of section this is.
         */
        public SectionTags fourcc() { return fourcc; }

        /**
         * The size, in bytes, of the header portion of the tag.
         */
        public long lenHeader() { return lenHeader; }

        /**
         * The size, in bytes, of this entire tag, counting the header.
         */
        public long lenTag() { return lenTag; }
        public Object body() { return body; }
        public AnlzFile _root() { return _root; }
        public AnlzFile _parent() { return _parent; }
        public byte[] _raw_body() { return _raw_body; }
    }

    /**
     * Stores either a list of ordinary memory cues and loop points, or
     * a list of hot cues and loop points.
     */
    public static class CueTag extends KaitaiStruct {
        public static CueTag fromFile(String fileName) throws IOException {
            return new CueTag(new ByteBufferKaitaiStream(fileName));
        }

        public CueTag(KaitaiStream _io) {
            this(_io, null, null);
        }

        public CueTag(KaitaiStream _io, AnlzFile.TaggedSection _parent) {
            this(_io, _parent, null);
        }

        public CueTag(KaitaiStream _io, AnlzFile.TaggedSection _parent, AnlzFile _root) {
            super(_io);
            this._parent = _parent;
            this._root = _root;
            _read();
        }
        private void _read() {
            this.type = AnlzFile.CueListType.byId(this._io.readU4be());
            this.lenCues = this._io.readU4be();
            this.memoryCount = this._io.readU4be();
            cues = new ArrayList<CueEntry>((int) (lenCues()));
            for (int i = 0; i < lenCues(); i++) {
                this.cues.add(new CueEntry(this._io, this, _root));
            }
        }
        private CueListType type;
        private long lenCues;
        private long memoryCount;
        private ArrayList<CueEntry> cues;
        private AnlzFile _root;
        private AnlzFile.TaggedSection _parent;

        /**
         * Identifies whether this tag stors ordinary or hot cues.
         */
        public CueListType type() { return type; }

        /**
         * The length of the cue list.
         */
        public long lenCues() { return lenCues; }

        /**
         * Unsure what this means.
         */
        public long memoryCount() { return memoryCount; }
        public ArrayList<CueEntry> cues() { return cues; }
        public AnlzFile _root() { return _root; }
        public AnlzFile.TaggedSection _parent() { return _parent; }
    }
    private byte[] _unnamed0;
    private long lenHeader;
    private long lenFile;
    private byte[] _unnamed3;
    private ArrayList<TaggedSection> sections;
    private AnlzFile _root;
    private KaitaiStruct _parent;
    public byte[] _unnamed0() { return _unnamed0; }

    /**
     * The number of bytes of this header section.
     */
    public long lenHeader() { return lenHeader; }

    /**
     * The number of bytes in the entire file.
     */
    public long lenFile() { return lenFile; }
    public byte[] _unnamed3() { return _unnamed3; }

    /**
     * The remainder of the file is a sequence of type-tagged sections,
     * identified by a four-byte magic sequence.
     */
    public ArrayList<TaggedSection> sections() { return sections; }
    public AnlzFile _root() { return _root; }
    public KaitaiStruct _parent() { return _parent; }
}
