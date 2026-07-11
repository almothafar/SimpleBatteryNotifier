# 📱 SimpleBatteryNotifier

[![Android CI](https://github.com/almothafar/SimpleBatteryNotifier/workflows/Android%20CI/badge.svg)](https://github.com/almothafar/SimpleBatteryNotifier/actions)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-green?logo=android)](https://developer.android.com/about/versions/oreo)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A lightweight Android app to keep you informed about your battery status — without heavy resource usage or unnecessary "power saver" bloat.

## 💡 Why SimpleBatteryNotifier?
Ever been busy at work or home and suddenly realized your phone is almost out of battery?  
Or worse — your phone dies while you’re traveling and you can’t recharge it.

Unlike heavy battery saver apps (which Android doesn’t actually need), SimpleBatteryNotifier is designed to:
- Monitor your battery efficiently
- Send you timely notifications
- Avoid slowing down your phone or draining your resources

No surprises. No clutter. Just simple battery notifications.

---

## ✨ Features

- 🔋 **Battery Alerts**
    - Get notified at *Critical* and *Warning* levels
    - Receive an alert when charging is complete
    - **High-temperature safety alert** when the battery gets too hot

- ⏰ **Customizable Notifications**
    - Choose *when* to get notified (e.g., no alerts while you’re sleeping)
    - Customize notification sounds, vibration, and behavior

- 📡 **Full Charge Notification**
    - Helpful if you charge your phone in **Airplane Mode** for faster charging — you’ll get reminded not to forget it there

- ⚡ **Charging Speed & Type**
    - When you plug in, see the estimated **charging speed** (tier + wattage, e.g. *Fast charging · ~18 W*) and whether it’s **wired or wireless**
    - Pick how it’s announced: a low-clutter **Toast** (default), a full **Notification**, or **None**

- 📊 **Battery Insights**
    - Estimated **battery health %**, **charge cycles**, temperature, voltage, and more
    - Enter your battery’s **design capacity** for a measured health estimate

- 📌 **Persistent & Repeated Alerts**
    - Keep a permanent battery status notification if you tend to forget things
    - Optionally receive alerts for **every 1% drop** when at critical levels

---

## 🚀 Lightweight & Simple
- No unnecessary background services
- No bloated “power saver” features
- Just the essentials to keep you in control of your battery

---

## 📥 Installation
A packaged release (Google Play / F-Droid / APK) is not published yet. For now, build it yourself — see [Building from Source](#building-from-source) below.

---

## 🛠️ Development

### Building from Source

```bash
# Clone the repository
git clone https://github.com/almothafar/SimpleBatteryNotifier.git
cd SimpleBatteryNotifier

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test
```

### Requirements
- **JDK 25+** (Java 25)
- **Android SDK 36** (compileSdk)
- **Gradle 9.2+**

### Testing
The project has a focused unit-test suite (JUnit; Robolectric and Mockito are available for framework-dependent tests) covering the logic most likely to regress:
- **Battery math** — percentage calculation, division-by-zero and boundary handling (`BatteryDO`)
- **Quiet-hours window** — inclusive/exclusive and overnight ranges (`NotificationService`)
- **Temperature** — °C/°F conversion and threshold/hysteresis (`TemperatureUtils`)
- **Capacity estimate** — full-capacity math from public `BatteryManager` readings (`SystemService`)

Run tests with:
```bash
./gradlew test
```

View test reports at:
```
app/build/reports/tests/testDebugUnitTest/index.html
```

### CI/CD
Every pull request and push to master automatically:
- ✅ Runs unit tests
- ✅ Builds debug and release APKs
- ✅ Generates test reports
- ✅ Uploads build artifacts

Check the [Actions tab](https://github.com/almothafar/SimpleBatteryNotifier/actions) for build status.

---

## 🤝 Contributing
Pull requests and suggestions are welcome!

Before submitting:
1. Ensure all tests pass: `./gradlew test`
2. Follow the coding standards in [`CODE_REVIEW_GUIDELINES.md`](CODE_REVIEW_GUIDELINES.md)
3. Add tests for new features
4. Update documentation as needed

> The repo keeps two guideline docs: [`CODE_REVIEW_GUIDELINES.md`](CODE_REVIEW_GUIDELINES.md) is the human-facing standard for contributors; [`.claude/guidelines.md`](.claude/guidelines.md) is the machine-facing rulebook for the AI code-review agent. Keep them in sync when standards change.

---

## 📄 License
Licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.

---

## 🏆 Code Quality

- ✅ **Focused unit-test suite** (JUnit + Robolectric, 100% pass rate)
- ✅ **Zero critical bugs**
- ✅ **Full accessibility support** (TalkBack compatible)
- ✅ **Modern Java 25** features (switch expressions, pattern matching, records)
- ✅ **Clean architecture** (SOLID principles, DRY)
- ✅ **Comprehensive documentation** (~90% JavaDoc coverage)
- ✅ **CI/CD pipeline** (GitHub Actions)

---

**Made with ❤️ for battery-conscious Android users**
