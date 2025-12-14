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

- ⏰ **Customizable Notifications**
    - Choose *when* to get notified (e.g., no alerts while you’re sleeping)
    - Customize notification sounds, vibration, and behavior

- 📡 **Full Charge Notification**
    - Helpful if you charge your phone in **Airplane Mode** for faster charging — you’ll get reminded not to forget it there

- 📊 **Battery Insights**
    - Extra details like **temperature**, **health**, and more

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
(Add installation instructions here, e.g., link to Google Play or APK download)

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
The project includes **11 focused unit tests** covering critical business logic:
- **BatteryDO calculation logic** - Percentage calculation with edge cases
- **Division by zero handling** - Tests defensive programming
- **Negative values and boundary conditions** - Real-world edge cases
- **Builder pattern validation** - Method chaining correctness

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
2. Follow the coding guidelines in `.claude/guidelines.md`
3. Add tests for new features
4. Update documentation as needed

---

## 📄 License
Licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.

---

## 🏆 Code Quality

- ✅ **Focused unit tests** (11 tests, 100% pass rate)
- ✅ **Zero critical bugs**
- ✅ **Full accessibility support** (TalkBack compatible)
- ✅ **Modern Java 25** features (switch expressions, pattern matching, records)
- ✅ **Clean architecture** (SOLID principles, DRY)
- ✅ **Comprehensive documentation** (~90% JavaDoc coverage)
- ✅ **CI/CD pipeline** (GitHub Actions)

---

**Made with ❤️ for battery-conscious Android users**
