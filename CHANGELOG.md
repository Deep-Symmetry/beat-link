# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Added

- Metadata caches now store the details of the media from which they
  were created to more easily auto-attach them and survive small
  changes.

### Fixed

- When a Nexus player is reporting that it is pre-loading hot cues, we
  no longer incorrectly consider it to be playing.
- When scanning metadata caches to consider them for auto-attachment,
  we were not closing the ones which failed to match. This was
  probably eventually being taken care of by the garbage collector and
  finalizers, but there is no guarantee if or when that would actually
  happen.

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


[unreleased]: https://github.com/brunchboy/beat-link/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/brunchboy/beat-link/compare/v0.3.7...v0.4.0
[0.3.7]: https://github.com/brunchboy/beat-link/compare/v0.3.6...v0.3.7
[0.3.6]: https://github.com/brunchboy/beat-link/compare/v0.3.5...v0.3.6
[0.3.5]: https://github.com/brunchboy/beat-link/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/brunchboy/beat-link/compare/v0.3.2...v0.3.4
[0.3.2]: https://github.com/brunchboy/beat-link/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/brunchboy/beat-link/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/brunchboy/beat-link/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/brunchboy/beat-link/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/brunchboy/beat-link/compare/v0.1.9...v0.2.0
[0.1.9]: https://github.com/brunchboy/beat-link/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/brunchboy/beat-link/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/brunchboy/beat-link/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/brunchboy/beat-link/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/brunchboy/beat-link/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/brunchboy/beat-link/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/brunchboy/beat-link/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/beat-link/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link/compare/v0.1.0...v0.1.1
