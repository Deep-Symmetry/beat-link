meta:
  id: pdb_file
  endian: le
  file-extension:
    - pdb

seq:
  - id: header
    type: file_header
# Uncomment this if you want to load and parse all the pages in the file so you can
# randomly scroll through them in the IDE. Don't do that when building the library
# classes, though, or it will waste a ton of memory in most use patterns:
#  - id: pages
#    type: page
#    size: header.len_page
#    repeat: eos

types:
  file_header:
    seq:
      - id: empty_1
        contents: [0, 0, 0, 0]
      - id: len_page
        type: u4
      - id: num_entries
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
        repeat-expr: num_entries
      - id: padding
        size: len_page - _io.pos

  file_header_entry:
    seq:
      - id: type
        type: u4
        enum: page_type
      - id: empty_candidate
        type: u4
      - id: first_page
        type: page_ref
        doc: |
          Always points to a strange page, which then links to a real data page.
      - id: last_page
        type: page_ref

  page_ref:
    seq:
      - id: index
        type: u4
    instances:
      body:
        io: _root._io
        pos: _root.header.len_page * index
        size: _root.header.len_page
        type: page

  page:
    seq:
      - id: header
        type: page_header
    instances:
      num_row_indices:
        value: header.num_entries / 16 + 1
      footer:
        pos: '0x1000 - (num_row_indices * 36)'
        type: page_row_index
        repeat: expr
        repeat-expr: num_row_indices

  page_header:
    seq:
      - id: empty_1
        contents: [0, 0, 0, 0]
      - id: page_index
        doc: Matches the index we used to look up the page, sanity check?
        type: u4
      - id: type
        type: u4
        enum: page_type
        doc: Identifies the type of information stored in the rows of this page.
      - id: next_page
        doc: |
          Index of the next page containing this type of rows. Points past
          the end of the file if there are no more.
        type: page_ref
      - id: unknown_1
        type: u4
        doc: '@flesniak said: "sequence number (0->1: 8->13, 1->2: 22, 2->3: 27)"'
      - id: unknown_2
        size: 4
      - id: num_entries
        type: u1
      - id: unknown_3
        type: u1
        doc: '@flesniak said: "a bitmask (1st track: 32)"'
      - id: unknown_4
        type: u2
        doc: '@flesniak said: "25600 for strange blocks"'
      - id: free_size
        type: u2
        doc: Unused space, excluding index at end of page.
      - id: used_size
        type: u2
      - id: unknown_5
        type: u2
        doc: '@flesniak said: "(0->1: 2)"'
      - id: num_entries_large
        type: u2
        doc: '@flesniak said: "usually <= num_entries except for playlist_map?"'
      - id: unknown_6
        type: u2
        doc: '@flesniak said: "1004 for strange blocks, 0 otherwise"'
      - id: unknown_7
        type: u2
        doc: '@flesniak said: "always 0 except 1 for history pages, num entries for strange pages?"'

  page_row_index:
    seq:
      - id: entry_offsets
        type: u2
        repeat: expr
        repeat-expr: 16
      - id: entry_enabled_flags
        type: b1
        repeat: expr
        repeat-expr: 16
      - id: unknown_flags
        type: b1
        repeat: expr
        repeat-expr: 16

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
