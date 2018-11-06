meta:
  id: anlz_file
  title: rekordbox track analysis file
  application: rekordbox
  file-extension:
    - dat
    - ext
  license: EPL-1.0
  endian: be

doc: |
  These files are created by rekordbox when analyzing audio tracks
  to facilitate DJ performance. They include waveforms, beat grids
  (information about the precise time at which each beat occurs),
  time indices to allow efficient seeking to specific positions
  inside variable bit-rate audio streams, and lists of memory cues
  and loop points. They are used by Pioneer professional DJ
  equipment.

  The format has been reverse-engineered to facilitate sophisticated
  integrations with light and laser shows, videos, and other musical
  instruments, by supporting deep knowledge of what is playing and
  what is coming next through monitoring the network communications
  of the players.

doc-ref: https://reverseengineering.stackexchange.com/questions/4311/help-reversing-a-edb-database-file-for-pioneers-rekordbox-software

seq:
  - contents: "PMAI"
  - id: len_header
    type: u4
    doc: |
      The number of bytes of this header section.
  - id: len_file
    type: u4
    doc: |
       The number of bytes in the entire file.
  - size: len_header - _io.pos
  - id: sections
    type: tagged_section
    repeat: eos
    doc: |
      The remainder of the file is a sequence of type-tagged sections,
      identified by a four-byte magic sequence.

types:
  tagged_section:
    doc: |
      A type-tagged file section, identified by a four-byte magic
      sequence, with a header specifying its length, and whose payload
      is determined by the type tag.
    seq:
      - id: fourcc
        type: u4
        enum: section_tags
        doc: |
          A tag value indicating what kind of section this is.
      - id: len_header
        type: u4
        doc: |
          The size, in bytes, of the header portion of the tag.
      - id: len_tag
        type: u4
        doc: |
          The size, in bytes, of this entire tag, counting the header.
      - id: body
        size: len_tag - 12
        type:
          switch-on: fourcc
          cases:
            'section_tags::path': path_tag
            'section_tags::beat_grid': beat_grid_tag
            'section_tags::vbr': vbr_tag
            'section_tags::wave_preview': wave_preview_tag
            'section_tags::wave_tiny': wave_preview_tag
    -webide-representation: '{fourcc}'


  beat_grid_tag:
    doc: |
      Holds a list of all the beats found within the track, recording
      their bar position, the time at which they occur, and the tempo
      at that point.
    seq:
      - type: u4
      - type: u4  # @flesniak says this is always 0x80000
      - id: len_beats
        type: u4
        doc: |
          The number of beat entries which follow.
      - id: beats
        type: beat_grid_beat
        repeat: expr
        repeat-expr: len_beats
        doc: The entries of the beat grid.

  beat_grid_beat:
    doc: |
      Describes an individual beat in a beat grid.
    seq:
      - id: beat_number
        type: u2
        doc: |
          The position of the beat within its musical bar, where beat 1
          is the down beat.
      - id: tempo
        type: u2
        doc: |
          The tempo at the time of this beat, in beats per minute,
          multiplied by 100.
      - id: time
        type: u4
        doc: |
          The time, in milliseconds, at which this beat occurs when
          the track is played at normal (100%) pitch.

  path_tag:
    doc: |
      Stores the file path of the audio file to which this analysis
      applies.
    seq:
      - id: len_path
        type: u4
      - id: path
        type: str
        size: len_path - 2
        encoding: utf-16be
        if: len_path > 1

  vbr_tag:
    doc: |
      Stores an index allowing rapid seeking to particular times
      within a variable-bitrate audio file.
    seq:
      - type: u4
      - id: index
        type: u4
        repeat: expr
        repeat-expr: 400

  wave_preview_tag:
    doc: |
      Stores a waveform preview image suitable for display long the
      bottom of a loaded track.
    seq:
      - id: len_preview
        type: u4
        doc: |
          The length, in bytes, of the preview data itself. This is
          slightly redundant because it can be computed from the
          length of the tag.
      - type: u4  # This seems to always have the value 0x10000
      - id: data
        size: len_preview
        doc: |
          The actual bytes of the waveform preview.

enums:
    section_tags:
      0x50434f42: cues          # PCOB
      0x50505448: path          # PPTH
      0x50564252: vbr           # PVBR
      0x5051545a: beat_grid     # PQTZ
      0x50574156: wave_preview  # PWAV
      0x50575632: wave_tiny     # PWV2
