# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

Nothing so far.

## [0.1.8] - 2016-07-04

### Added

- Initial support for NXS2 players. Will now recognize status update
  packets, even though they are larger than from other players, and
  can recognize all the state flags we already know about. We still
  don't know how to interpret any new features represented in the new
  section of the packet.
- Now uses slf4j to abstract over whatever logging framework is being
  used by projects that embed beat-link.
- Can now obtain track metadata (title and arist, so far), from the
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
  a new overload of `VirtualCdj.getLatustStatusFor()` which take a
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

- Intial early release of DeviceFinder.


[unreleased]: https://github.com/brunchboy/beat-link/compare/v0.1.8...HEAD
[0.1.8]: https://github.com/brunchboy/beat-link/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/brunchboy/beat-link/compare/v0.1.6...v0.1.7
[0.1.6]: https://github.com/brunchboy/beat-link/compare/v0.1.5...v0.1.6
[0.1.5]: https://github.com/brunchboy/beat-link/compare/v0.1.4...v0.1.5
[0.1.4]: https://github.com/brunchboy/beat-link/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/brunchboy/beat-link/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/beat-link/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link/compare/v0.1.0...v0.1.1

