# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

### Changed

- `VirtualCdj.start()` now returns a `boolean` value to indicate
  whether the attempt was successful, with `false` meaning that no DJ
  Link devices could be found.

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


[unreleased]: https://github.com/brunchboy/beat-link/compare/v0.1.3...HEAD
[0.1.3]: https://github.com/brunchboy/beat-link/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/brunchboy/beat-link/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link/compare/v0.1.0...v0.1.1

