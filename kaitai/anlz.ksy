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
      - id: len_header
        type: u4
      - id: len_tag
        type: u4
      - id: len_path
        type: u4
        if: 'fourcc == section_tags::path'
      - size: len_header - 12
        if: 'len_header > 12 and fourcc != section_tags::path'
      - id: body
        size: len_tag - len_header
        type:
          switch-on: fourcc
          cases:
            'section_tags::path': path_tag

  path_tag:
    seq:
      - id: path
        type: str
        size: _parent.len_path - 2
        encoding: utf-16be
        if: _parent.len_path > 1

enums:
    section_tags:
      0x50434f42: cues          # PCOB
      0x50505448: path          # PPTH
      0x50564252: vbr           # PVBR
      0x5051545a: beat_grid     # PQTZ
      0x50574156: wave_preview  # PWAV
      0x50575632: wave_scroll   # PWV2
