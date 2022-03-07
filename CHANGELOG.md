# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- Support for channels 5 and 6 in the DJM-V10 channels-on-air messages,
  [#47](https://github.com/Deep-Symmetry/beat-link/issues/47) for
  receiving, and [#46](https://github.com/Deep-Symmetry/beat-link/issues/47)
  for sending.
- Support for obtaining song structure (phrase analysis) information
  from players, now that rekordbox 6 and later export it.
- Ability to display analyzed phrases on track waveforms.
- Support for obtaining any new type of track analysis which we can
  create definitions for in Crate Digger and obtain using the general
  track-analysis section `dbserver` request.
- Information about the timing of upcoming beats and bars present within
  beat packets is now made available.

### Removed

- The ability to create and use metadata caches has not been useful
  for a couple of years now, since we figured out how to use Crate
  Digger to reliably obtain track metadata even when there are a full
  set of real players on the network. So the complicated code
  which supported it has been removed, rather than trying to update it
  to keep up with new features like phrase analysis.

### Changed

- The versioning numbering scheme has been made more reasonable. This
  is now version 7.0.0 instead of 0.7.0, to reflect the fact that
  there have been many releases in active production use.


## [0.6.3] - 2020-12-28

### Added

- The ability to set a player's My Settings configuration by sending
  a newly-discovered packet.
- The ability to set a background color for the `WaveformDetailComponent`
  and `WaveformPreviewComponent`, for use by the OBS overlay server in Beat Link
  Trigger. You can also now change the colors used to draw the playback
  position indicator and tick and beat marks to go with your overall look.

### Fixed

- Some Unicode strings were [not being properly
  read](https://github.com/Deep-Symmetry/crate-digger/issues/14) by Crate Digger.


## [0.6.2] - 2020-05-10

### Fixed

- The interpretation of colors for memory points and loops (as opposed
  to hot cues) has been fixed, thanks to
  [a contribution](https://github.com/Deep-Symmetry/crate-digger/pull/13)
  by [@ehendrikd](https://github.com/ehendrikd)
- It turns out that nxs2 cue list entries are not always complete,
  they are sometimes missing color information or even the comment
  information that precedes it. We no longer crash when we encounter
  such partial cues.
- Fixes to the Beat Link and Crate Digger libraries allow them to work
  properly with new formats for data that rekordbox 6 sends. We may
  find more problems in the future, because testing with this new
  version has been limited, but it is already working much better than
  it did at first.
- An update to Crate Digger avoids crashes when trying to parse track
  analysis files created with mal-formed vestigial waveform preview
  tags.
- The interpretation of byte `0x37` in the CDJ status packet has been
  updated, which forced fixes of the `isDiscSlotEmpty()` and
  `getDiscTrackCount()` methods in `CdjStatus`.
- Links in the JavaDoc now directly take you to the relevant sections
  of the new Antora documentation site that replaced the old PDF
  protocol analysis document.
- Track Load packets now need to have an extra value to tell the
  recipient on which deck the track should be loaded, because the
  XDJ-XZ has two decks but only one network address on which to receive
  these commands. (Sadly for us, it refuses to load tracks from other
  players even when this byte is sent correctly, it only loads tracks
  from a rekordbox collection when rekordbox tells it to.)

### Added

- Another bit has been explained in the main `CdjStatus` flags byte:
  we can now tell when a DJ has forced a player into degraded BPM-only
  sync mode (by nudging the jog wheel of a synced player).
- We now know that beat grid entries also report the tempo of the
  track at that beat, so this is properly exposed in the API.
- We now fully support the device number assignment phase of the
  Pro DJ Link protocol, so when Beat Link is connected to an ethernet
  port on a mixer that is assigned a dedicated channel number, it will
  use that device number, and we defend our device number once we have
  successfully claimed it.

### Deprecated

- The classes and methods relating to creating and using metadata caches
  are no longer needed because we can reliably obtain metadata no matter
  how many CDJs are on the network.
- The `getName` and `getNumber` methods in `DeviceAnnouncement`
  were inconsistent with the `getDeviceName` and `getDeviceNumber`
  naming convention used in the entire device update packet hierarchy.


## [0.6.1] - 2020-02-09

### Fixed

- The `TimeFinder` is no longer tricked by beat packets from pre-nexus
  players into thinking that it might know where in the track that
  player is. Track position detection is only possible with nexus and
  later hardware, because only they report beat numbers.
- When the `VirtualCdj` is forced to shut down because of apparent network
  changes, it now also flushes the `DeviceFinder`’s list of known DJ Link
  devices, because they are probably no longer reachable. This will allow
  for immediate recovery attempts by telling the `VirtualCdj` to restart
  itself (without this change, restarting it within ten seconds or so
  would fail because it would complain about being unable to communicate
  with the ghost devices on a no-longer reachable network, until they
  disappeared due to lack of recent packets).
- The `crate-digger` library was updated to fix a misunderstanding of the
  structure of cue list entries which could crash the parser.
- Our own parsing of nxs2 cue list entries fetched using the `dbserver`
  protocol is now robust against missing color bytes, which also seems
  to happen in the wild.
- Some nxs2 cue list entries are so short that they don't even have
  space for comments/labels, which we now handle properly too.

### Added

- Error messages reported when parsing an ANLZ or EXT file fails now include
  the path to the file in the source media, to help find it for forensic
  analysis. This enabled the `crate-digger` fix mentioned above.
- A flag on the DeviceUpdate class that indicates whether it seems to
  have been sent by a pre-nexus player (CDJ-900 or CDJ-2000).

### Changed

- No longer log a stack trace for the expected situation of a color
  waveform (or waveform preview) being unavailable. Also handle the
  case where it is reported available, but has zero size.
- Because the XDJ-1000 seems to calculate the payload size value for
  its status packets incorrectly (subtracting the header size twice)
  we now only report that one time, to stop flooding the logs with
  warnings for each status packet received.


## [0.6.0] - 2019-11-24

### Added

- Support for the XDJ-XZ, which reports multiple devices on a single
  IP address. This broke assumptions in many places in the code, and
  required a more sophisticated approach to device matching. Thanks to
  patient and detailed reports, experiments, packet captures, and
  videos from [Teo Tormo](https://djtechtools.com/?s=teo+tormo).
- The `TrackPositionListener` now always sends an initial track
  position update to newly registered listeners, even if the update
  is `null` (meaning nothing is known) to help them get initialized.
  This is especially useful if their registration is being moved to
  a different player.

### Fixed

- A [race condition](https://github.com/Deep-Symmetry/beat-link/issues/38)
  which caused `CrateDigger` to retain incorrect track information when
  a player was removed from the network while it had a mounted USB, then
  returned to the network with a different USB, has been resolved. Thanks
  to [@ben-xo](https://github.com/ben-xo) for discovering and reporting
  this!
- Players report themselves as stopped when they reach the end of the
  track playing forwards, but they don't when they reach the start
  playing backwards. This caused weird display in the Player Status
  window, and weird generated time code with expressions. The
  `TimeFinder` now compensates for this buggy behavior by reporting
  playback as stopped when it is stuck at 0 milliseconds.

### Changed

- `tempoChanged` events are no longer sent to the `MasterListener`
  interface when the tempo is meaningless (i.e. the new master has
  no track loaded).

## [0.5.5] - 2019-10-25

### Fixed

- CDJs which were powered on after Beat Link was already running would
  not ever get assigned a valid `dbserver` port by the `ConnectionManager`
  so the `MetadataFinder` would not be able to perform metadata requests
  to them without `CrateDigger`. This seems to have been caused by the
  players not being quite ready to respond to the port number query right
  after booting, so we now try again a few times.
- Some extended cue entries encountered by users were missing the color
  bytes, which prevented Crate Digger from parsing `EXT` files and
  accessing color waveforms.
  These values are now treated as optional.
- Eliminated spurious warnings in the log for cue entries with rekordbox
  color code 0 which also had explicit green color values stored for
  nxs2 players.
- Now builds properly under current JDKs, including Amazon Corretto 11
  (which is a long-term support release). The minimum JDK for building
  is now Java 9, but the resulting build is still compatible back to
  Java 1.6. Building under Java 11 results in much nicer JavaDoc, with
  search support.

## [0.5.2] - 2019-09-02

### Added

- `CueList` entries can now include nxs2-style DJ comments when they
  are present, along with colors and hot cues beyond C.
- Helper functions to search `CueList` entries for the closest cue
  before or after a specific time in a track.
- The `WaveformPreviewComponent` can return its `CueList` for the
  convenience of code that wants to add informative tool tips.
- The `WaveformDetailComponent` draws labels for hot cues and for any
  cue or loop that has been assigned a comment.
- `MountListener` instances registered with the `MetadataFinder` will
  be examined to see if they also implement `MediaDetailsListener`. If
  they do, they will be informed when details are available for
  newly-mounted media.

## [0.5.1] - 2019-03-05

### Fixed

- The `SignatureFinder` would crash trying to calculate signatures
  for tracks without artists.

### Added

- The `MenuLoader` now supports loading tracks from the Label, Bit Rate,
  Original Artist, and Remixer menus.
- Track Metadata now includes bit rate, when applicable.


## [0.5.0] - 2019-02-23

### Changed

> :warning: These are breaking API changes. Code that used some
> cache-related and metadata methods will need to be rewritten.

- The handling of metadata cache files has been moved out of the
  MetadataFinder into a new class focused on this task. It also
  implements a new interface that can be used by client objects to
  offer cached metadata to Beat Link (for example track cue lists
  that group metadata from multiple different media sources).
- Track comments and dates added are just plain strings, not
  searchable items, so the API has been changed to reflect this.
- `WaveformPreviewComponent` and `WaveformDetailComponent` have been
  generalized to support tracking multiple simultaneous playback
  positions, so they can be used in the context of a Beat Link Trigger
  show file, where a track can be loaded and even playing on multiple
  players at once.
- It is now possible to construct a fully-functional
  `WaveformPreviewComponent` or `WaveformDetailComponent` even if you
  don’t have access to actual `TrackMetadata` objects, as long as you
  pass them the few individual pieces of information they need. Again,
  this supports the needs of the new Beat Link Trigger show interface.

### Added

- We now can use
  [Crate Digger](https://github.com/Deep-Symmetry/crate-digger#crate-digger)
  to reliably obtain metadata even when there are four players on the
  network, and we are using player number 5.
- We can now retrieve and display the full-color waveforms used by
  nxs2 players and rekordbox, and prefer them when available. This
  can be turned off through a `WaveformFinder` property.
- When going online, the `VirtualCdj` now reports information about
  the chosen network interface to help with troubleshooting
  problematic network environments.
- There is a new interface, `TrackPositionBeatListener`, that can
  be registered with the `TimeFinder` in order to learn about new
  beats as soon as they happen, along with the actual beat number
  within the track represented by the beat (which is missing from
  the raw beat packet available through the `BeatFinder`).
- The `TimeFinder` takes advantage of track metadata, (when it is
  available) to detect when the DJ has jumped to a cue, or when a
  track has loaded with auto-cue to a memory point, so it can more
  accurately infer where the player has stopped.
- `MenuLoader` supports loading several more menu types from the
  dbserver.
- We can offer track years as part of the metadata when rekordbox is
  indexing that.
- When disconnecting from a player’s dbserver, we politely send a
  teardown message and let it close the connection from its side.
- A few new methods that can help troubleshoot problematic network
  topologies are available. Beat Link Trigger uses them to provide
  better help at startup.

### Fixed

- A mistake in the `TimeFinder` which could trap the `VirtualCdj`
  packet delivery thread in an infinite loop, which would then cause
  client applications to lose touch with what was happening on the
  players, with no recourse except going offline and back online (and
  even this would gradually waste more and more CPU time on each
  occurrence). Thanks to [@Kevinnns](https://github.com/Kevinnns) for
  the thread dumps which helped finally find this!
- The code that was supposed to notice computers running rekordbox and
  report their media collections as being available on the network had
  always been broken, but was easy enough to fix. Now Beat Link
  Trigger can tell players to load tracks from rekordbox and rekordbox
  mobile.
- The various metadata finders were supposed to report the loss of
  associated metadata when a player disappeared from the network, but
  they were not doing so.
- At some point (possibly when adding the ability to show multiple
  player locations in a waveform) the memory point and hot cue markers
  stopped being displayed in the waveform preview. They have been
  restored.
- Cue Lists were not being sorted into the correct order when they
  were loaded from Crate Digger. Now they are sorted regardless of
  how we obtain them.
- The colors used for playback position markers were inconsistent
  between the waveform preview and waveform detail (one used red
  when the other used white for playing/stopped). Now they both
  use the constants defined in the WaveformDetailComponent, which
  also adds a bit of transparency to the preview markers.
- It turns out that rekordbox mobile does not report meaningful values
  for its media name or creation date, so we no longer attempt to
  parse them.
- It seems rekordbox sometimes sends mixer status packets with a
  different subtype structure which was causing us to log warnings
  about a length mismatch. We now properly recognize this packet
  subtype.

## [0.4.1] - 2018-10-28

### Added

- Metadata caches now store the details of the media from which they
  were created to more easily and reliably auto-attach them and
  survive small changes.

### Fixed

- When a Nexus player is reporting that it is pre-loading hot cues, we
  no longer incorrectly consider it to be playing.
- When scanning metadata caches to consider them for auto-attachment,
  we were not closing the ones which failed to match. This was
  probably eventually being taken care of by the garbage collector and
  finalizers, but there is no guarantee if or when that would actually
  happen.
- When handling a Fader Start command, a missing `break` statement
  caused the `BeatFinder` to log warning which incorrectly reported
  that the command had not been recognized. This no longer happens.

## [0.4.0] - 2018-10-07

### Added

- Metadata can now be retrieved for non-rekordbox tracks, including
  unanalyzed files in the media slots, and on audio CDs and data
  discs.
- The mounting and removal of discs is reported to registered media
  mount listeners.
- Details about all mounted media slots can be discovered, including
  the name assigned in rekordbox, creation date, number of rekordbox
  tracks and playlists (and whether there is a rekordbox database at
  all, which is needed to correctly request the root menu for that
  slot), size, and free space.
- The number of tracks on a disc can be discovered.
- The `VirtualCdj` can send status and beat packets, simulate playing,
  and become the tempo master.
- The `VirtualCdj` has new methods allowing you to tell other devices
  to turn sync mode on or off, or to become the tempo master.
- The `VirtualCdj` has a new method allowing you to tell other players
  to start or stop playing.
- The `VirtualCdj` has a new method allowing you to tell other players
  to load a particular track from any rekordbox database on the
  network (in a player's media slot, or on a laptop running
  rekordbox).
- The `VirtualCdj` has a new method allowing you to tell players
  whether they are on or off the air in the absence of a DJM mixer.
- The `Message.KnownType` enum has many new entries describing menu
  requests we now know how to perform.
- The `Message.MenuItemType` enum has new entries for the menus that
  can appear in the root menu response.
- A new class, `MenuLoader`, provides methods for navigating the menu
  hierarchies served by players.
- A new Enum which captures all known packet types, to improve the
  readability and compactness of code that works with them.

### Fixed

- Waveform details are properly loaded for tracks that are found on
  multiple players on the network. This affected time-remaining
  calculations too.
- Now assembles entire dbserver messages into a buffer to write them to
  the network as a single operation, avoiding the chance of them being
  split into multiple packets, because Windows rekordbox can't handle
  when they are.
- Removed a potential source of crashes in the waveform rendering
  code.
- Improved the clarity of an exception thrown when trying to ask for
  metadata from a player for which we did not find a db server port.
- Improved protection against problems that can occur when delivering
  events (status updates, beats, etc.) to registered listeners;
  previously we were only catching exceptions, but some kinds of
  problems in the listener classes would lead to other kinds of
  throwables, which could kill our event delivery loops.
- The _count_ parameter is now passed in menu render requests in a way
  that is more consistent with the way CDJs do it, although this has
  not seemed to cause any problems.

### Changed

- We no longer simply reject packets with unexpected lengths; if they
  are longer than the minimum value we expect, we try to process them
  after logging a warning. If they are too short, we just log the
  warning.
- More use of ByteBuffers to efficiently assemble and compare packets.
- The `TRACK_LIST_REQ` message type has been renamed `TRACK_MENU_REQ`
  to fit in with the large number of other menus that have been added.

## [0.3.7] - 2018-03-26

### Fixed

- Although the `TrackMetadata` object received the Album field, and
  would display it in the `toString()` method, there was no accessor
  by which the value could be obtained and used by other code!

## [0.3.6] - 2017-11-30

### Fixed

- When looping a track that has audio data that extends well past the
  final beat in the beat grid, players sometimes report playing a beat
  that does not exist in the beat grid. This previously caused an
  exception in the log, and the reported playback position would keep
  growing without bound as long as the loop continued. This situation
  is now handled better by interpolating missing beats at the end of
  the beat grid, so there is no exception and the looping of the
  player is properly reflected by the `TimeFinder`.

## [0.3.5] - 2017-10-08

### Fixed

- Creating a metadata cache would fail if a playlist contained more
  than one copy of the same track, as it would try to create the same
  ZIP file entry more than once. Now redundant copies of the same track
  are skipped.

## [0.3.4] - 2017-09-05

### Added

- Exposed the function which opens and returns a metadata cache file,
  so that other projects can explore their contents, for example to
  offer a view of what they contain.

## [0.3.2] - 2017-08-08

### Added

- More logging about the detection of media being mounted and removed
  from players, to help track down problems creating metadata caches.

### Fixed

- There was a potential for the thread that reports media being
  mounted or removed to terminate if any exceptions occurred while
  handling player updates. It now logs these and continues.

## [0.3.1] - 2017-07-22

### Added

- The TimeFinder now keeps a list of listeners that want help tightly
  following track position changes, for example to generate SMPTE
  time code that is tied to the current playback position.

## [0.3.0] - 2017-06-25

### Changed

- The entire metadata retrieval approach has been fundamentally
  rewritten, based on a much deeper understanding of the message and
  field format, thanks to tips and sample code from @awwright.
- This led to a deep refactoring of the library, and a split into
  several packages, now that there are many more classes to support
  all of these new features. API compatibility was not maintained with
  previous releases.

### Added

- Many more kinds of information can be retrieved from players, and
  the classes and API documentation have been enriched to reflect our
  deepening understanding of the protocol.
- Metadata cache files can be created by downloading either entire
  collections or individual playlists from players on the network, and
  then attached for use in environments (such as busy shows with four
  physical players) where metadata queries are not reliable.
- Metadata cache files can be monitored for automatic attachment to
  player ports when media that matches the cache is mounted by the
  player.
- New listener interfaces to provide information about changes to the
  available metadata caches, as well as changes to metadata about
  tracks loaded in players on the network.

### Fixed

- The approach to concurrency was examined carefully, and almost all
  synchronized methods were eliminated in favor of newer concurrent
  classes for improved performance and safety.
- When the last device disappears from the Pro DJ Link network, we
  clear the DeviceFinder's notion of when we first saw one, so that
  the VirtualCDJ's device-number conflict-avoidance code can work
  properly when we later encounter a new network.

## [0.2.1] - 2017-03-14

### Fixed

- Several issues which prevented Beat Link from reliably detecting all
  other players on the network when self-assigning the number to use
  for the VirtualCDJ. First, keep track of when we actually received
  our first device announcement packet, rather than just when we
  started trying to find the Pro DJ Link network, because that provides
  a much more meaningful indication of how long to wait for
  players on the network. Second, remove the mutual dependency between
  the DeviceFinder and the VirtualCDJ, because the latter is in a
  synchronized method during startup, which was preventing the
  DeviceFinder from being able to actually process any device
  announcement packets which came in while the VirtualCDJ was trying
  to wait for them!

### Added

- A configuration option to tell the VirtualCDJ to try to use a
  standard player number when self-assigning.
- When stopping the VirtualCDJ, it sets its device number to 0 so
  that the default at startup is always self-assignment, to avoid
  conflicts. You can still set a specific number before calling
  start if you want to force a device number.
- Support new, slightly larger CDJ status packets from new Pioneer
  firmware.
- Allow you to request all metadata for a given media slot.

## [0.2.0] - 2016-12-11

### Added

- The ability to retrieve track metadata (artist, title, etc.) from
  the CDJs, thanks to discoveries by
  [@EvanPurkhiser](https://github.com/EvanPurkhiser). See
  his [prolink-go](https://github.com/EvanPurkhiser/prolink-go) project
  for a Go language version of a library like beat-link, with no need
  for a Java virtual machine.
- The ability to get track artwork images.

## [0.1.9] - 2016-11-09

### Changed

- The values returned by the `getTimestamp()` method on device update
  objects have been changed to be based on `System.nanoTime()` rather
  than `System.currentTimeMillis()`. This makes them easier to work
  with as relative times, and also makes them compatible with the time
  values used by Ableton Link, for better interoperability.

## [0.1.8] - 2016-07-04

### Added

- Initial support for NXS2 players. Will now recognize status update
  packets, even though they are larger than from other players, and
  can recognize all the state flags we already know about. We still
  don't know how to interpret any new features represented in the new
  section of the packet.
- Now uses slf4j to abstract over whatever logging framework is being
  used by projects that embed beat-link.
- Can now obtain track metadata (title and artist, so far), from the
  CDJs! Not yet reliable or safe, however, so not documented.
- As part of that, the rekordbox track id and source player / slot
  information have been found in the CDJ status packets. This part is
  safe to use, and beat-link-trigger is happily doing so.

## [0.1.7] - 2016-05-31

### Added

- Unless you assign an explicit `deviceNumber` to `VirtualCdj`, it will
  watch the DJ Link network looking for an unused player number between
  5 and 15 to assign itself when it starts up.

## [0.1.6] - 2016-05-27

### Fixed

- The byte being used to determine device number did not work for beat
  packets, so `Beat` objects always returned zero from
  `getDeviceNumber()`.

## [0.1.5] - 2016-05-25

### Added

- A new method, `DeviceFinder.getLatestStatus()` that returns the most
  recent status updates received from all active devices.

### Changed

- Device updates, beat announcements, and master announcements are time
  sensitive, so they are now delivered directly on the thread that is
  receiving them from the network, rather than being added to the Event
  Dispatch Queue. This will reduce latency, but means listener methods
  need to be very fast, and delegate any lengthy, non-time-sensitive
  operations to another thread themselves.
- The threads which receive device updates and beat announcements now
  run at maximum priority, also to reduce latency. Device announcements
  are less time-sensitive, so those are still received at normal priority,
  and sent on the event dispatch thread, to make it easier to update user
  interface elements.

## [0.1.4] - 2016-05-18

### Fixed

- Accept shorter, 208-byte CDJ status packets sent by non-nexus players.
- The value of *F* seems to always be zero for non-nexus players, so we
  need to use a different way to check playing status for them.
- The value of *B<sub>b</sub>* is not meaningful for non-rekordbox tracks
  and non-nexus players, so reflect that in
  `CdjStatus.isBeatWithinBarMeaningful()`.
- The values reported for *P<sub>2</sub>* are different for non-nexus players.

### Changed

- `VirtualCdj.start()` now returns a `boolean` value to indicate
  whether the attempt was successful, with `false` meaning that no DJ
  Link devices could be found.
- `DeviceWatcher` now updates its device lists before posting
  lost/found announcements, so recipients can see the results as a
  whole.

### Added

- The firmware version number has been found in the CDJ status packets,
  so it is now available through `CdjStatus.getFirmwareVersion()`.
- When the beat number within the track is not meaningful (because a
  non-rekordbox track is being played, or a non-nexus player sent the
  update), `CdjStatus.getBeatNumber()` now returns `-1`.
- A new method `DeviceFinder.getLatestAnnouncementFrom()` and
  a new overload of `VirtualCdj.getLatestStatusFor()` which take a
  device (player) number and look for matching announcement or status
  reports.

## [0.1.3] - 2016-05-12

### Fixed

- Device Update objects (including `Beat`s) were missing the first
  character of the device name.
- The `VirtualCdj` socket was being bound to the wildcard address
  rather than the address of the interface on which DJ-Link traffic
  had been detected.
- Resolved a crash when stopping the `VirtualCdj` because it was
  calling one of its own methods after marking itself inactive.
- During startup, the `VirtualCdj` waits longer for DJ-Link traffic
  before giving up.

### Added

- Device updates have a `boolean` property which identifies whether
  you can expect `getBeatWithinBar()` to give you meaningful results.
- The virtual device we create to communicate with other DJ-Link
  devices is now filtered out from the list of devices we see on the
  network, since you don&rsquo;t ever want to interact with it.

## [0.1.2] - 2016-05-09

### Fixed

- The `CdjStatus` object was looking at the wrong byte for
  *B<sub>b</sub>* so `getBeatWithinBar()` was always returning `0`.

### Changed

- Trying to call methods that require the `DeviceFinder` or `VirtualCdj`
  to be running in order to give you valid results will now throw an
  `IllegalStateException` if those objects are not running, rather than
  giving you meaningless results.

## [0.1.1] - 2016-05-08

### Added

- Initial release of the full implementation, including
  BeatFinder, VirtualCdj and the various listener interfaces and
  status objects they provide.

## 0.1.0 - 2016-05-05

### Added

- Initial early release of DeviceFinder.


[unreleased]: https://github.com/Deep-Symmetry/beat-link/compare/v0.6.3...HEAD
[0.6.3]: https://github.com/Deep-Symmetry/beat-link/compare/v0.6.2...v0.6.3
[0.6.2]: https://github.com/Deep-Symmetry/beat-link/compare/v0.6.1...v0.6.2
[0.6.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/Deep-Symmetry/beat-link/compare/v0.5.5...v0.6.0
[0.5.5]: https://github.com/Deep-Symmetry/beat-link/compare/v0.5.2...v0.5.5
[0.5.2]: https://github.com/Deep-Symmetry/beat-link/compare/v0.5.1...v0.5.2
[0.5.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/Deep-Symmetry/beat-link/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.7...v0.4.0
[0.3.7]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.6...v0.3.7
[0.3.6]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.2...v0.3.4
[0.3.2]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/Deep-Symmetry/beat-link/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.9...v0.2.0
[0.1.9]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/Deep-Symmetry/beat-link/compare/v0.1.0...v0.1.1
