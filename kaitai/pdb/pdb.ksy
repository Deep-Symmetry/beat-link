meta:
  id: pdb_file
  endian: le
  file-extension:
    - pdb

seq:
  - id: header
    type: file_header

types:
  file_header:
    seq:
      - id: empty_1
        contents: [0, 0, 0, 0]
      - id: page_size
        type: u4
      - id: entry_count
        type: u4
        doc: |
          Determines the number of file header entries that are present.
      - id: next_unused_page
        type: u4
        doc: |
          Not used as any `empty_candidate`, points past the end of the file.
      - id: unknown_1
        type: u4
      - id: sequence
        type: u4
        doc: Always incremented by at least one, sometimes by two or three.
      - id: empty_2
        contents: [0, 0, 0, 0]
      - id: entries
        type: file_header_entry
        repeat: expr
        repeat-expr: entry_count
      - id: padding
        size: page_size - _io.pos

  file_header_entry:
    seq:
      - id: type
        type: u4
        enum: page_type
      - id: empty_candidate
        type: u4
      - id: first_page
        type: u4
        doc: |
          Always points to a strange page, which then links to a real data page.
      - id: last_page
        type: u4

enums:
  page_type:
    0:
      id: tracks
      doc: |
        Holds records describing tracks, such as their title, artist,
        genre, artwork ID, playing time, etc.
    1:
      id: genres
      doc: Holds records naming musical genres, for reference by tracks and searching.
    2:
      id: artists
      doc: Holds records naming artists, for reference by tracks and searching.
    3:
      id: albums
      doc: Holds records naming albums, for reference by tracks and searching.
    4:
      id: labels
      doc: Holds records naming music labels, for reference by tracks and searching.
    5:
      id: keys
      doc: Holds records naming musical keys, for reference by tracks and searching.
    6:
      id: colors
      doc: Holds records naming color labels, for reference  by tracks and searching.
    7:
      id: playlists
      doc: Holds records containing playlists.
    8:
      id: playlist_map
      doc: TODO figure out and explain
    9:
      id: unknown_9
    10:
      id: unknown_10
    11:
      id: unknown_11
    12:
      id: unknown_12
    13:
      id: artwork
      doc: Holds records pointing to album artwork images.
    14:
      id: unknown_14
    15:
      id: unknown_15
    16:
      id: columns
      doc: TODO figure out and explain
    17:
      id: unknown_17
    18:
      id: unknown_18
    19:
      id: history
      doc: Holds records listing tracks played in performance sessions.
