meta:
  id: pdb_file
  title: DeviceSQL database export (probably generated by rekordbox)
  application: rekordbox
  file-extension:
    - pdb
  license: EPL-1.0
  endian: le

doc: |
  This is a relational database format designed to be efficiently used
  by very low power devices (there were deployments on 16 bit devices
  with 32K of RAM). Today you are most likely to encounter it within
  the Pioneer Professional DJ ecosystem, because it is the format that
  their rekordbox software uses to write USB and SD media which can be
  mounted in DJ controllers and used to play and mix music.

  It has been reverse-engineered to facilitate sophisticated
  integrations with light and laser shows, videos, and other musical
  instruments, by supporting deep knowledge of what is playing and
  what is coming next through monitoring the network communications of
  the players.

  The file is divided into fixed-size blocks. The first block has a
  header that establishes the block size, and lists the tables
  available in the database, identifying their types and the index of
  the first of the series of linked pages that make up that table.

  Each table is made up of a series of rows which may be spread across
  any number of pages. The pages start with a header describing the
  page and linking to the next page. The rest of the page is used as a
  heap: rows are scattered around it, and located using an index
  structure that builds backwards from the end of the page. Each row
  of a given type has a fixed size structure which links to any
  variable-sized strings by their offsets within the page.

  As changes are made to the table, some records may become unused,
  and there may be gaps within the heap that are too small to be used
  by other data. There is a bit map in the row index that identifies
  which rows are actually present. Rows that are not present must be
  ignored: they do not contain valid (or even necessarily well-formed)
  data.

  The majority of the work in reverse-engineering this format was
  performed by @henrybetts and @flesniak, for which I am hugely
  grateful. @GreyCat helped me learn the intricacies (and best
  practices) of Kaitai far faster than I would have managed on my own.

doc-ref: https://github.com/Deep-Symmetry/dysentery/blob/master/doc/Analysis.pdf

seq:
  - contents: [0, 0, 0, 0]
  - id: len_page
    type: u4
    doc: |
      The database page size, in bytes. Pages are referred to by
      index, so this size is needed to calculate their offset, and
      table pages have a row index structure which is built from the
      end of the page backwards, so finding that also requires this
      value.
  - id: num_tables
    type: u4
    doc: |
      Determines the number of table entries that are present. Each
      table is a linked list of pages containing rows of a particular
      type.
  - id: next_unused_page
    type: u4
    doc: |
      @flesinak said: "Not used as any `empty_candidate`, points
      past the end of the file."
  - type: u4
  - id: sequence
    type: u4
    doc: |
      @flesniak said: "Always incremented by at least one,
      sometimes by two or three."
  - contents: [0, 0, 0, 0]
  - id: tables
    type: table
    repeat: expr
    repeat-expr: num_tables
    doc: |
      Describes and links to the tables present in the database.

types:
  table:
    doc: |
      Each table is a linked list of pages containing rows of a single
      type. This header describes the nature of the table and links to
      its pages by index.
    seq:
      - id: type
        type: u4
        enum: page_type
        doc: |
          Identifies the kind of rows that are found in this table.
      - id: empty_candidate
        type: u4
      - id: first_page
        type: page_ref
        doc: |
          Links to the chain of pages making up that table. The first
          page seems to always contain similar garbage patterns and
          zero rows, but the next page it links to contains the start
          of the meaningful data rows.
      - id: last_page
        type: page_ref
        doc: |
          Holds the index of the last page that makes up this table.
          When following the linked list of pages of the table, you
          either need to stop when you reach this page, or when you
          notice that the `next_page` link you followed took you to a
          page of a different `type`.
    -webide-representation: '{type}'

  page_ref:
    doc: |
      An index which points to a table page (its offset can be found
      by multiplying the index by the `page_len` value in the file
      header). This type allows the linked page to be lazy loaded.
    seq:
      - id: index
        type: u4
        doc: |
          Identifies the desired page number.
    instances:
      body:
        doc: |
          When referenced, loads the specified page and parses its
          contents appropriately for the type of data it contains.
        io: _root._io
        pos: _root.len_page * index
        size: _root.len_page
        type: page

  page:
    doc: |
      A table page, consisting of a short header describing the
      content of the page and linking to the next page, followed by a
      heap in which row data is found. At the end of the page there is
      an index which locates all rows present in the heap via their
      offsets past the end of the page header.
    seq:
      - contents: [0, 0, 0, 0]
      - id: page_index
        doc: Matches the index we used to look up the page, sanity check?
        type: u4
      - id: type
        type: u4
        enum: page_type
        doc: |
          Identifies the type of information stored in the rows of this page.
      - id: next_page
        doc: |
          Index of the next page containing this type of rows. Points past
          the end of the file if there are no more.
        type: page_ref
      - type: u4
        doc: |
          @flesniak said: "sequence number (0->1: 8->13, 1->2: 22, 2->3: 27)"
      - size: 4
      - id: num_rows_small
        type: u1
        doc: |
          Holds the value used for `num_rows` (see below) unless
          `num_rows_large` is larger (but not equal to `0x1fff`). This
          seems like some strange mechanism to deal with the fact that
          lots of tiny entries, such as are found in the
          `playlist_entries` table, are too big to count with a single
          byte. But why not just always use `num_rows_large`, then?
      - type: u1
        doc: |
          @flesniak said: "a bitmask (1st track: 32)"
      - type: u2
        doc: |
          @flesniak said: "25600 for strange blocks"
      - id: free_size
        type: u2
        doc: |
          Unused space (in bytes) in the page heap, excluding the row
          index at end of page.
      - id: used_size
        type: u2
        doc: |
          The number of bytes that are in use in the page heap.
      - type: u2
        doc: |
          @flesniak said: "(0->1: 2)"
      - id: num_rows_large
        type: u2
        doc: |
          Holds the value used for `num_rows` (see below) when that is
          too large to fit into `num_rows_small`, and that situation
          seems to be indicated when this value is larger than
          `num_rows_small`, but not equal to `0x1fff`. This seems like
          some strange mechanism to deal with the fact that lots of
          tiny entries, such as are found in the `playlist_entries`
          table, are too big to count with a single byte. But why not
          just always use this value, then?
      - type: u2
        doc: |
          @flesniak said: "1004 for strange blocks, 0 otherwise"
      - type: u2
        doc: |
          @flesniak said: "always 0 except 1 for history pages, num
          entries for strange pages?"
      - id: heap
        size-eos: true
        if: heap_pos < 0  # never true, but stores pos
    instances:
      heap_pos:
        value: _io.pos
      num_rows:
        value: |
          (num_rows_large > num_rows_small) and (num_rows_large != 0x1fff) ? num_rows_large : num_rows_small
        doc: |
          The number of rows on this page (controls the number of row
          index entries there are, but some of those may not be marked
          as present in the table due to deletion).
        -webide-parse-mode: eager
      num_groups:
        value: '(num_rows - 1) / 16 + 1'
        doc: |
          The number of row groups that are present in the index. Each
          group can hold up to sixteen rows. All but the final one
          will hold sixteen rows.
      row_groups:
        type: 'row_group(_index)'
        repeat: expr
        repeat-expr: num_groups
        doc: |
          The actual row groups making up the row index. Each group
          can hold up to sixteen rows.

  row_group:
    doc: |
      A group of row indices, which are built backwards from the end
      of the page. Holds up to sixteen row offsets, along with a bit
      mask that indicates whether each row is actually present in the
      table.
    params:
      - id: group_index
        type: u2
        doc: |
          Identifies which group is being generated. They build backwards
          from the end of the page.
    instances:
      base:
        value: '_root.len_page - (group_index * 0x24)'
        doc: |
          The starting point of this group of row indices.
      row_present_flags:
        pos: base - 4
        type: u2
        doc: |
          Each bit specifies whether a particular row is present. The
          low order bit corresponds to the first row in this index,
          whose offset immediately precedes these flag bits. The
          second bit corresponds to the row whose offset precedes
          that, and so on.
        -webide-parse-mode: eager
      rows:
        type: row_ref(_index)
        repeat: expr
        repeat-expr: '(group_index < (_parent.num_groups - 1)) ? 16 : ((_parent.num_rows - 1) % 16 + 1)'
        doc: |
          The row offsets in this group.

  row_ref:
    doc: |
      An offset which points to a row in the table, whose actual
      presence is controlled by one of the bits in
      `row_present_flags`. This instance allows the row itself to be
      lazily loaded, unless it is not present, in which case there is
      no content to be loaded.
    params:
      - id: row_index
        type: u2
        doc: |
          Identifies which row within the row index this reference
          came from, so the correct flag can be checked for the row
          presence and the correct row offset can be found.
    instances:
      ofs_row:
        pos: '_parent.base - (6 + (2 * row_index))'
        type: u2
        doc: |
          The offset of the start of the row (in bytes past the end of
          the page header).
      row_base:
        value: ofs_row + _parent._parent.heap_pos
        doc: |
          The location of this row relative to the start of the page.
          A variety of pointers (such as all device_sql_string values)
          are calculated with respect to this position.
      present:
        value: '(((_parent.row_present_flags >> row_index) & 1) != 0 ? true : false)'
        doc: |
          Indicates whether the row index considers this row to be
          present in the table. Will be `false` if the row has been
          deleted.
        -webide-parse-mode: eager
      body:
        pos: row_base
        type:
          switch-on: _parent._parent.type
          cases:
            'page_type::albums': album_row
            'page_type::artists': artist_row
            'page_type::artwork': artwork_row
            'page_type::colors': color_row
            'page_type::genres': genre_row
            'page_type::keys': key_row
            'page_type::labels': label_row
            'page_type::playlist_tree': playlist_tree_row
            'page_type::playlist_entries': playlist_entry_row
            'page_type::tracks': track_row
        if: present
        doc: |
          The actual content of the row, as long as it is present.
        -webide-parse-mode: eager
    -webide-representation: '{body.name.body.text}{body.title.body.text} ({body.id})'

  album_row:
    doc: |
      A row that holds an album name and ID.
    seq:
      - id: magic
        contents: [0x80, 0x00]
      - id: index_shift
        type: u2
        doc: TODO name from @flesniak, but what does it mean?
      - type: u4
      - id: artist_id
        type: u4
        doc: |
          Identifies the artist associated with the album.
      - id: id
        type: u4
        doc: |
          The unique identifier by which this album can be requested
          and linked from other rows (such as tracks).
      - type: u4
      - type: u1
        doc: |
          @flesniak says: "alwayx 0x03, maybe an unindexed empty string"
      - id: ofs_name
        type: u1
        doc: |
          The location of the variable-length name string, relative to
          the start of this row.
    instances:
      name:
        type: device_sql_string
        pos: _parent.row_base + ofs_name
        doc: |
          The name of this album.
        -webide-parse-mode: eager

  artist_row:
    doc: |
      A row that holds an artist name and ID.
    seq:
      - id: magic
        contents: [0x60, 0x00]
      - id: index_shift
        type: u2
        doc: TODO name from @flesniak, but what does it mean?
      - id: id
        type: u4
        doc: |
          The unique identifier by which this artist can be requested
          and linked from other rows (such as tracks).
      - type: u1
        doc: |
          @flesniak says: "alwayx 0x03, maybe an unindexed empty string"
      - id: ofs_name
        type: u1
        doc: |
          The location of the variable-length name string, relative to
          the start of this row.
    instances:
      name:
        type: device_sql_string
        pos: _parent.row_base + ofs_name
        doc: |
          The name of this artist.
        -webide-parse-mode: eager

  artwork_row:
    doc: |
      A row that holds the path to an album art image file and the
      associated artwork ID.
    seq:
      - id: id
        type: u4
        doc: |
          The unique identifier by which this art can be requested
          and linked from other rows (such as tracks).
      - id: path
        type: device_sql_string
        doc: |
          The variable-length file path string at which the art file
          can be found.
    -webide-representation: '{path.body.text}'

  color_row:
    doc: |
      A row that holds a color name and the associated ID.
    seq:
      - size: 5
      - id: id
        type: u2
        doc: |
          The unique identifier by which this color can be requested
          and linked from other rows (such as tracks).
      - type: u1
      - id: name
        type: device_sql_string
        doc: |
          The variable-length string naming the color.

  genre_row:
    doc: |
      A row that holds a genre name and the associated ID.
    seq:
      - id: id
        type: u4
        doc: |
          The unique identifier by which this genre can be requested
          and linked from other rows (such as tracks).
      - id: name
        type: device_sql_string
        doc: |
          The variable-length string naming the genre.

  key_row:
    doc: |
      A row that holds a musical key and the associated ID.
    seq:
      - id: id
        type: u4
        doc: |
          The unique identifier by which this key can be requested
          and linked from other rows (such as tracks).
      - id: id2
        type: u4
        doc: |
          Seems to be a second copy of the ID?
      - id: name
        type: device_sql_string
        doc: |
          The variable-length string naming the key.

  label_row:
    doc: |
      A row that holds a label name and the associated ID.
    seq:
      - id: id
        type: u4
        doc: |
          The unique identifier by which this label can be requested
          and linked from other rows (such as tracks).
      - id: name
        type: device_sql_string
        doc: |
          The variable-length string naming the label.

  playlist_tree_row:
    doc: |
      A row that holds a playlist name, ID, indication of whether it
      is an ordinary playlist or a folder of other playlists, a link
      to its parent folder, and its sort order.
    seq:
      - id: parent_id
        type: u4
        doc: |
          The ID of the `playlist_tree_row` in which this one can be
          found, or `0` if this playlist exists at the root level.
      - size: 4
      - id: sort_order
        type: u4
        doc: |
          The order in which the entries of this playlist are sorted.
      - id: id
        type: u4
        doc: |
          The unique identifier by which this playlist can be requested
          and linked from other rows (such as tracks).
      - id: raw_is_folder
        type: u4
        doc: |
          Has a non-zero value if this is actually a folder rather
          than a playlist.
      - id: name
        type: device_sql_string
        doc: |
          The variable-length string naming the playlist.
    instances:
      is_folder:
        value: raw_is_folder != 0
        -webide-parse-mode: eager

  playlist_entry_row:
    doc: |
      A row that associates a track with a position in a playlist.
    seq:
      - id: entry_index
        type: u4
        doc: |
          The position within the playlist represented by this entry.
      - id: track_id
        type: u4
        doc: |
          The track found at this position in the playlist.
      - id: playlist_id
        type: u4
        doc: |
          The playlist to which this entry belongs.

  track_row:
    doc: |
      A row that describes a track that can be played, with many
      details about the music, and links to other tables like artists,
      albums, keys, etc.
    seq:
      - id: magic
        contents: [0x24, 0x00]
      - id: index_shift
        type: u2
        doc: TODO name from @flesniak, but what does it mean?
      - id: bitmask
        type: u4
        doc: TODO what do the bits mean?
      - id: sample_rate
        type: u4
        doc: |
          Playback sample rate of the audio file.
      - id: composer_id
        type: u4
        doc: |
          References a row in the artist table if the composer is
          known.
      - id: file_size
        type: u4
        doc: |
          The length of the audio file, in bytes.
      - type: u4
        doc: |
          Some ID? Purpose as yet unknown.
      - type: u2
        doc: |
          From @flesniak: "always 19048?"
      - type: u2
        doc: |
          From @flesniak: "always 30967?"
      - id: artwork_id
        type: u4
        doc: |
          References a row in the artwork table if there is album art.
      - id: key_id
        type: u4
        doc: |
          References a row in the keys table if the track has a known
          main musical key.
      - id: original_artist_id
        type: u4
        doc: |
          References a row in the artwork table if this is a cover
          performance and the original artist is known.
      - id: label_id
        type: u4
        doc: |
          References a row in the labels table if the track has a
          known record label.
      - id: remixer_id
        type: u4
        doc: |
          References a row in the artists table if the track has a
          known remixer.
      - id: bitrate
        type: u4
        doc: |
          Playback bit rate of the audio file.
      - id: track_number
        type: u4
        doc: |
          The position of the track within an album.
      - id: tempo
        type: u4
        doc: |
          The tempo at the start of the track in beats per minute,
          multiplied by 100.
      - id: genre_id
        type: u4
        doc: |
          References a row in the genres table if the track has a
          known musical genre.
      - id: album_id
        type: u4
        doc: |
          References a row in the albums table if the track has a
          known album.
      - id: artist_id
        type: u4
        doc: |
          References a row in the artists table if the track has a
          known performer.
      - id: id
        type: u4
        doc: |
          The id by which this track can be looked up; players will
          report this value in their status packets when they are
          playing the track.
      - id: disc_number
        type: u2
        doc: |
          The number of the disc on which this track is found, if it
          is known to be part of a multi-disc album.
      - id: play_count
        type: u2
        doc: |
          The number of times this track has been played.
      - id: year
        type: u2
        doc: |
          The year in which this track was released.
      - id: sample_depth
        type: u2
        doc: |
          The number of bits per sample of the audio file.
      - id: duration
        type: u2
        doc: |
          The length, in seconds, of the track when played at normal
          speed.
      - type: u2
        doc: |
          From @flesniak: "always 41?"
      - id: color_id
        type: u1
        doc: |
          References a row in the colors table if the track has been
          assigned a color.
      - id: rating
        type: u1
        doc: |
          The number of stars to display for the track, 0 to 5.
      - type: u2
        doc: |
          From @flesniak: "always 1?"
      - type: u2
        doc: |
          From @flesniak: "alternating 2 or 3"
      - id: ofs_strings
        type: u2
        repeat: expr
        repeat-expr: 21
        doc: |
          The location, relative to the start of this row, of a
          variety of variable-length strings.
    instances:
      unknown_string_1:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[0]
        doc: |
          A string of unknown purpose, which has so far only been
          empty.
        -webide-parse-mode: eager
      texter:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[1]
        doc: |
          A string of unknown purpose, which @flesnik named.
        -webide-parse-mode: eager
      unknown_string_2:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[2]
        doc: |
          A string of unknown purpose; @flesniak said "thought
          tracknumber -> wrong!"
      unknown_string_3:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[3]
        doc: |
          A string of unknown purpose; @flesniak said "strange
          strings, often zero length, sometimes low binary values
          0x01/0x02 as content"
      unknown_string_4:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[4]
        doc: |
          A string of unknown purpose; @flesniak said "strange
          strings, often zero length, sometimes low binary values
          0x01/0x02 as content"
        -webide-parse-mode: eager
      message:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[5]
        doc: |
          A string of unknown purpose, which @flesnik named.
        -webide-parse-mode: eager
      unknown_switch:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[6]
        doc: |
          A string of unknown purpose, whose value is always either
          empty or "ON".
        -webide-parse-mode: eager
      autoload_hotcues:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[7]
        doc: |
          A string whose value is always either empty or "ON", and
          which apparently for some insane reason is used, rather than
          a single bit somewhere, to control whether hot-cues are
          auto-loaded for the track.
        -webide-parse-mode: eager
      unknown_string_5:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[8]
        doc: |
          A string of unknown purpose.
        -webide-parse-mode: eager
      unknown_string_6:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[9]
        doc: |
          A string of unknown purpose, usually empty.
        -webide-parse-mode: eager
      date_added:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[10]
        doc: |
          A string containing the date this track was added to the collection.
        -webide-parse-mode: eager
      release_date:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[11]
        doc: |
          A string containing the date this track was released, if known.
        -webide-parse-mode: eager
      mix_name:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[12]
        doc: |
          A string naming the remix of the track, if known.
        -webide-parse-mode: eager
      unknown_string_7:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[13]
        doc: |
          A string of unknown purpose, usually empty.
        -webide-parse-mode: eager
      analyze_path:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[14]
        doc: |
          The file path of the track analysis, which allows rapid
          seeking to particular times in variable bit-rate files,
          jumping to particular beats, visual waveform previews, and
          stores cue points and loops.
        -webide-parse-mode: eager
      analyze_date:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[15]
        doc: |
          A string containing the date this track was analyzed by rekordbox.
        -webide-parse-mode: eager
      comment:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[16]
        doc: |
          The comment assigned to the track by the DJ, if any.
        -webide-parse-mode: eager
      title:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[17]
        doc: |
          The title of the track.
        -webide-parse-mode: eager
      unknown_string_8:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[18]
        doc: |
          A string of unknown purpose, usually empty.
        -webide-parse-mode: eager
      filename:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[19]
        doc: |
          The file name of the track audio file.
        -webide-parse-mode: eager
      file_path:
        type: device_sql_string
        pos: _parent.row_base + ofs_strings[20]
        doc: |
          The file path of the track audio file.
        -webide-parse-mode: eager

  device_sql_string:
    doc: |
      A variable length string which can be stored in a variety of
      different encodings.
    seq:
      - id: length_and_kind
        type: u1
        doc: |
          Mangled length of an ordinary ASCII string if odd, or a flag
          indicating another encoding with a longer length value to
          follow.
      - id: body
        type:
          switch-on: length_and_kind
          cases:
            0x40: device_sql_long_ascii
            0x90: device_sql_long_utf16be
            _: device_sql_short_ascii(length_and_kind)
        -webide-parse-mode: eager
    -webide-representation: '{body.text}'

  device_sql_short_ascii:
    doc: |
      An ASCII-encoded string up to 127 bytes long.
    params:
      - id: mangled_length
        type: u1
        doc: |
          Contains the actual length, incremented, doubled, and
          incremented again. Go figure.
    seq:
      - id: text
        type: str
        size: length
        encoding: ascii
        doc: |
          The content of the string.
    instances:
      length:
        value: '((mangled_length - 1) / 2) - 1'
        doc: |
          The un-mangled length of the string, in bytes.
        -webide-parse-mode: eager

  device_sql_long_ascii:
    doc: |
      An ASCII-encoded string preceded by a two-byte length field.
      TODO May need to skip a byte after the length!
           Have not found any test data.
    seq:
      - id: length
        type: u2
        doc: |
          Contains the length of the string in bytes.
      - id: text
        type: str
        size: length
        encoding: ascii
        doc: |
          The content of the string.

  device_sql_long_utf16be:
    doc: |
      A UTF-16BE-encoded string preceded by a two-byte length field.
    seq:
      - id: length
        type: u2
        doc: |
          Contains the length of the string in bytes, including two trailing nulls.
      - id: text
        type: str
        size: length - 4
        encoding: utf-16be
        doc: |
          The content of the string.

enums:
  page_type:
    0:
      id: tracks
      doc: |
        Holds rows describing tracks, such as their title, artist,
        genre, artwork ID, playing time, etc.
    1:
      id: genres
      doc: |
        Holds rows naming musical genres, for reference by tracks and searching.
    2:
      id: artists
      doc: |
        Holds rows naming artists, for reference by tracks and searching.
    3:
      id: albums
      doc: |
        Holds rows naming albums, for reference by tracks and searching.
    4:
      id: labels
      doc: |
        Holds rows naming music labels, for reference by tracks and searching.
    5:
      id: keys
      doc: |
        Holds rows naming musical keys, for reference by tracks and searching.
    6:
      id: colors
      doc: |
        Holds rows naming color labels, for reference  by tracks and searching.
    7:
      id: playlist_tree
      doc: |
        Holds rows that describe the hierarchical tree structure of
        available playlists and folders grouping them.
    8:
      id: playlist_entries
      doc: |
        Holds rows that enumerate the tracks found in playlists and
        the playlists they belong to.
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
      doc: |
        Holds rows pointing to album artwork images.
    14:
      id: unknown_14
    15:
      id: unknown_15
    16:
      id: columns
      doc: |
        TODO figure out and explain
    17:
      id: unknown_17
    18:
      id: unknown_18
    19:
      id: history
      doc: |
        Holds rows listing tracks played in performance sessions.
