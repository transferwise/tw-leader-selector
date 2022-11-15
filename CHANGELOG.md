# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.8.0] - 2022-11-16
- Support `tw-curator.disabled: true` case, so users of this library does not have to introduce Zookeeper as a 
dependency. E.g. `Wise Kafka Processor` can support database cleanup that depends on leader selection provided by this
library. But `Wise Kafka Processor` can also be used without a DB dependency, in which case we want to avoid introducing 
the dependency on Zookeeper.

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