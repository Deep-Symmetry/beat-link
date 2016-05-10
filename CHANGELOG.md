# Change Log

All notable changes to this project will be documented in this file.
This change log follows the conventions of
[keepachangelog.com](http://keepachangelog.com/).

## [Unreleased][unreleased]

Nothing so far.

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


[unreleased]: https://github.com/brunchboy/beat-link/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/brunchboy/beat-link/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/brunchboy/beat-link/compare/v0.1.0...v0.1.1

