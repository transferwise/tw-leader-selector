# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.10.4] - 2024-12-06

### Added
* Support for spring boot 3.4

## Removed
* Support for spring boot 3.2

## [1.10.3] - 2024-07-16

### Added
* Support for spring boot 3.3

## [1.10.2] - 2024-02-21

### Changed
* - Added support for Spring Boot 3.2.
    - Updated dependencies.

## [1.10.1] - 2023-08-01

### Added

* Support for Spring Boot 3.1

### Bumped

* Build against Spring Boot 3.0.6 --> 3.0.7
* Build against Spring Boot 2.7.11 --> 2.7.13
* Build against Spring Boot 2.6.14 --> 2.6.15

## [1.10.0] - 2023-05-06

### Added

* Support for Spring Boot 3.0

### Removed

* Support for Spring Boot 2.5

## [1.9.0] - 2023-01-24

### Fixed

* `LeaderSelectorV2` was telling us it has stopped too early, especially when it was waiting in the lock acquire method of
  `lockAcquired = config.lock.acquire(config.tickDuration);`.
  Now, when CuratorFramework was stopped shortly afterwards, the lock acquiring and Zookeeper client created noise, i.e. logged out errors and
  warnings.

## [1.8.0] - 2022-11-16

- Only autoconfigure beans if CuratorFramework is available. With this change, users of this library does not always
  have to introduce Zookeeper as a dependency.

## [1.7.0] - 2022-05-31

- Dependencies upgrades.

## [1.6.0] - 2021-05-31

## Changed

- JDK 11+ is required
- Starting to publish into maven central again.

## [1.5.0] - 2021-02-09

## Changed

- Removed deprecated `LeaderSelector`. `LeaderSelectorV2` can be used instead.
- Fixed and improved documentation.
- Upgraded dependencies.

## [1.4.0] - 2020-10-01

## Changed

- Upgraded external libraries.
- Better error when CuratorFramework is not properly set up.
