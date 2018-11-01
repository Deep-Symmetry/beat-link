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
      doc: Holds records naming musical genres.
