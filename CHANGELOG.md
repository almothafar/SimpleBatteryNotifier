# Changelog

## [3.1.0](https://github.com/almothafar/SimpleBatteryNotifier/compare/v3.0.1...v3.1.0) (2026-07-24)


### Features

* group settings screens into rounded cards (Material You style) ([#226](https://github.com/almothafar/SimpleBatteryNotifier/issues/226)) ([f2f8c20](https://github.com/almothafar/SimpleBatteryNotifier/commit/f2f8c2030e6a86990e5071f90ad34f1bdedd88ac))
* show averaged measured capacity with min/max in Insights ([#229](https://github.com/almothafar/SimpleBatteryNotifier/issues/229)) ([06a385f](https://github.com/almothafar/SimpleBatteryNotifier/commit/06a385f6255e99b5ca3bdabc9c73c56346ecc4fb))
* show calculating and tap-to-set hints instead of the dash in the details table ([#224](https://github.com/almothafar/SimpleBatteryNotifier/issues/224)) ([eefcd5d](https://github.com/almothafar/SimpleBatteryNotifier/commit/eefcd5d3ed761bc65d5fffbccdb34996139ada6f))
* smoothly animate the live battery percentage between counter updates ([#218](https://github.com/almothafar/SimpleBatteryNotifier/issues/218)) ([8ae2500](https://github.com/almothafar/SimpleBatteryNotifier/commit/8ae25000cbfbd89be798375cb032b3012630291f))


### Bug Fixes

* keep sampling through the charge handshake so fast chargers aren't labelled "Slow charging" ([#228](https://github.com/almothafar/SimpleBatteryNotifier/issues/228)) ([05e47b7](https://github.com/almothafar/SimpleBatteryNotifier/commit/05e47b7fe806967ae36cdbfea9cddefe645b7e98))
* live sub-percent decimals stuck at .00 on trusted-counter devices ([#215](https://github.com/almothafar/SimpleBatteryNotifier/issues/215)) ([2b1ab4f](https://github.com/almothafar/SimpleBatteryNotifier/commit/2b1ab4f08ca59bea3cff6cf4265b4f7dd01cb7b0))
* lock app to portrait to stop the main-screen landscape crash ([#232](https://github.com/almothafar/SimpleBatteryNotifier/issues/232)) ([4c8fdf1](https://github.com/almothafar/SimpleBatteryNotifier/commit/4c8fdf10fd5aa6c08aae2c3cdbd27e45af76f7b0))

## [3.0.1](https://github.com/almothafar/SimpleBatteryNotifier/compare/v3.0.0...v3.0.1) (2026-07-22)


### Bug Fixes

* auto-clean stale fast-drain alert and retry the charge-speed sample ([#205](https://github.com/almothafar/SimpleBatteryNotifier/issues/205)) ([7de9931](https://github.com/almothafar/SimpleBatteryNotifier/commit/7de9931ae5c3de7b37fd1b7fc57deca95170c8c8))
* label the bare current value in the collapsed notification  ([#208](https://github.com/almothafar/SimpleBatteryNotifier/issues/208)) ([1cd1430](https://github.com/almothafar/SimpleBatteryNotifier/commit/1cd1430e265eb9619524319a09e8f029672497ad))
