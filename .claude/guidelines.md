# SimpleBatteryNotifier Development Guidelines

> **Audience:** this is the machine-facing rulebook for the AI code-review agent. The human-facing companion is [`../CODE_REVIEW_GUIDELINES.md`](../CODE_REVIEW_GUIDELINES.md); keep the two in sync when a standard changes.

## Project Overview
SimpleBatteryNotifier is an Android application that monitors battery status and provides notifications for power events. The app displays battery information with a circular progress bar and supports customizable alerts.

## Code Style

### Java Language Features
- **Use modern Java (JDK 25) features** where appropriate
  - Switch expressions with `yield` keyword
  - Pattern matching (when available)
  - Records for simple data carriers
- **Resource Management**: Use try-with-resources for automatic resource cleanup
- **Null Safety**: Use `isNull()` and `nonNull()` from `java.util.Objects`
- **Immutability**: Use `final` for all method parameters and local variables where possible
- **Static Imports**: Import commonly used static methods (e.g., `isNull`, `nonNull`)

### Naming Conventions
- Classes: PascalCase (e.g., `BatteryDO`, `SystemService`)
- Methods: camelCase with clear verb prefixes (e.g., `getBatteryInfo`, `determineHealthStatus`)
- Constants: UPPER_SNAKE_CASE (e.g., `TAG`, `ANIMATION_DURATION`)
- Private fields: camelCase (e.g., `batteryDO`, `healthStatus`)

### Code Organization
- Keep utility classes final with private constructors
- Group related methods together
- Order: constructors → public methods → protected methods → private methods → inner classes
- Maximum method length: ~30 lines (extract helper methods if longer)

## Architecture Decisions

### Design Patterns
- **Builder Pattern**: Use method chaining with `return this` for data objects (e.g., BatteryDO)
- **Enum Pattern**: Prefer enums over boolean flags for state representation
  - Example: Use `BatteryHealthStatus.WARNING` instead of `isWarning` boolean
- **Single Responsibility Principle**: One method, one clear purpose
  - Method names should clearly indicate what they do
  - Avoid side effects - methods shouldn't mutate objects unexpectedly
  - Split methods that do multiple things into separate methods

### Data Management
- **Data Objects**: Use simple POJOs with getters/setters
- **State Representation**: Use enums for mutually exclusive states
- **Immutability**: Make internal data classes immutable where possible (e.g., `BatteryExtras`)

### Error Handling
- Check for null before accessing system services or intent extras
- Use graceful degradation for non-critical features (e.g., battery-capacity estimate returning 0 when unsupported)
- Log warnings (not errors) for expected failures
- Add clear comments explaining why certain failures are acceptable
- **Never swallow exceptions silently and never `catch (Exception)` broadly.** A catch must take real recovery action or log. Catch the narrowest type.
- **Don't use exceptions for expected validation.** Unchecked exceptions like `NumberFormatException` can be avoided by validating first (e.g. `s.matches("\\d{1,5}")`) and then parsing without a `try`. A catch that shows the user feedback (e.g. `ActivityNotFoundException` → Toast) is fine.

## Android Specifics

### API Level Support
- **Minimum SDK**: API 26 (Android 8.0 Oreo)
- **Target SDK**: API 36
- **Compile SDK**: API 36
- Use modern APIs with fallbacks for older versions when needed

### API Version Handling
```java
// Modern API (API 31+) with fallback
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    // Use VibratorManager
    final VibratorManager vibratorManager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
    vibrator = vibratorManager.getDefaultVibrator();
} else {
    // Use deprecated Vibrator for API 26-30 (with suppression)
    @SuppressWarnings("deprecation")
    final Vibrator systemService = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    vibrator = systemService;
}
```

### Deprecation Handling
- **Never ignore deprecation warnings** - address them properly:
  1. Replace with modern API if available
  2. Add `@SuppressWarnings("deprecation")` only if deprecated API is required for minSdk support
  3. Add comment explaining why deprecated API is necessary
- **Document workarounds**: If using deprecated API, explain the necessity in code comments

### Reflection Usage
- **Avoid reflection.** Prefer public APIs; reflection into internal/private Android APIs is blocked by non-SDK restrictions on modern Android and should not be used.
  - Historical note: battery capacity was once read by reflecting into the internal `PowerProfile`. That was **removed** — `SystemService.getBatteryCapacity` now estimates full capacity from public `BatteryManager` properties (`BATTERY_PROPERTY_CHARGE_COUNTER ÷ BATTERY_PROPERTY_CAPACITY`), returning 0 when unsupported.
- If reflection is ever truly unavoidable, document it thoroughly (why it's needed, what can fail, how failure degrades gracefully) and keep the pure/computational part unit-testable.

### UI Components
- **Edge-to-Edge Display**: Enabled via `WindowCompat.setDecorFitsSystemWindows(getWindow(), false)`
- **System Bar Colors**: Set in themes (values/themes.xml), not programmatically (deprecated in API 35)
- **Activity Results**: Use `ActivityResultLauncher` instead of deprecated `startActivityForResult()`
- **Fragments**: Use AndroidX fragments, never use deprecated `setTargetFragment()`

### Threading
- Use `Handler(Looper.getMainLooper())` constructor - `Handler()` is deprecated
- Post UI updates to main thread via Handler
- Use Timer/TimerTask for periodic updates (as currently implemented)

## Documentation Standards

### JavaDoc Requirements
- **All public methods** must have JavaDoc
- **All classes** must have a brief description
- **Complex private methods** should have JavaDoc explaining purpose

### JavaDoc Structure
```java
/**
 * Brief description of what the method does
 * <p>
 * Optional: Extended description with implementation details,
 * warnings, or important notes.
 *
 * @param paramName Description of parameter
 * @return Description of return value
 * @throws ExceptionType When and why this exception is thrown (if applicable)
 */
```

### Comment Guidelines
- **Why, not what**: Comments should explain reasoning, not repeat the code
- **TODOs**: Mark workarounds that should be addressed later
  - Example: `// TODO: This is a workaround - should be handled with proper RTL layout support`
- **Critical sections**: Mark important null checks or edge cases
  - Example: `// CRITICAL: Check for null batteryDO`
- **API Level notes**: Document API compatibility decisions
  - Example: `// BATTERY_PLUGGED_WIRELESS added in API 17`

## Testing Strategy

The project uses **JUnit 4** for pure logic, with **Robolectric** and **Mockito** available for framework-dependent tests. `build.gradle` sets `testOptions.unitTests.includeAndroidResources = true` so Robolectric can resolve app resources.

### Unit Tests (JUnit) — prefer these
- Extract pure, Android-free helpers and test them directly (e.g. `SystemService.estimateFullCapacityMah`, `NotificationService.isWithinTimeRange`, `TemperatureUtils`, `BatteryDO.getBatteryPercentage`).
- Cover edge cases: division by zero, boundaries, unsupported/`MIN_VALUE` readings.

### Framework Tests (Robolectric + Mockito)
- Use `@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)` — targetSdk 36 is beyond Robolectric's supported range.
- SharedPreferences-backed state (cycle tracker, design capacity) via a real `ApplicationProvider` context.
- Receivers: drive `onReceive` with a sticky `ACTION_BATTERY_CHANGED` intent and `mockStatic(NotificationService.class)` to assert which alert is chosen; reset static de-dup state between tests.

### Add tests with new logic
- New business logic (thresholds, state machines, parsing) should ship with tests that would fail on regression.

## Code Quality

### Avoid Over-Engineering
- Only implement features that are directly requested
- Don't add "improvements" beyond the requirements
- Keep solutions simple and focused
- Three similar lines of code is better than a premature abstraction

### When to Extract Methods
- Method exceeds ~30 lines
- Logic is duplicated in multiple places
- Complex algorithm that needs clear naming
- Side effect separation (separate mutation from computation)

### Performance Considerations
- Minimize object allocations in frequently called methods (e.g., `onDraw`)
- Reuse Paint objects instead of creating new ones
- Use appropriate data structures (e.g., HashMap for key-value lookups)
- Avoid unnecessary boxing/unboxing

## Security

### Input Validation
- Validate at system boundaries (user input, external APIs)
- Trust internal code and framework guarantees
- Don't add validation for scenarios that can't happen

### Permissions
- Request only necessary permissions
- Document why each permission is needed
- Handle permission denial gracefully

### Data Storage
- Store preferences using SharedPreferences
- Never store sensitive data in plain text
- Use appropriate scoped storage for files

## Git Workflow

### Commit Messages
- Use clear, descriptive commit messages
- Format: `<type>: <description>`
- Types: feat, fix, refactor, docs, style, test, chore
- Example: `refactor: replace boolean flags with BatteryHealthStatus enum`

### Branch Strategy
- Main branch: `master` (stable, release-ready code)
- Feature branches: `feature/<feature-name>`
- Bug fixes: `fix/<bug-description>`

## Build Configuration

### Gradle
- Keep dependencies up to date
- Use version catalogs for dependency management (if migrating to modern Gradle)
- Run `./gradlew clean build` before committing major changes

### ProGuard/R8
- Keep rules for reflection usage
- Test release builds thoroughly
- Document any custom ProGuard rules

## Accessibility

### UI Guidelines
- Provide content descriptions for ImageViews and IconButtons
- Ensure minimum touch target size (48dp)
- Support dynamic text sizing
- Test with TalkBack enabled

### RTL Support
- Use start/end instead of left/right for layouts
- Test with RTL languages (e.g., Arabic)
- Current workaround in BatteryDetailsFragment should be replaced with proper RTL layout

## Internationalization

### String Resources
- **All user-facing text must be in string resources — never a hardcoded Java literal** (no `setText("Excellent")`, no `Toast` string literals). Map values/enums to a `@StringRes` in a UI/service layer.
- Use positional format args for dynamic content: `<string name="notification_status_content">%1$d%% · %2$s · %3$s</string>`.

### Arabic (`values-ar/`) Parity
- The app ships an Arabic translation. Every new user-facing string in `values/strings.xml` needs a matching entry in `values-ar/strings.xml`.
- Quick check: diff the `<string name=…>` lists of the two files.
- Consider the `MissingTranslation` lint check as a build gate.
- Arabic wording is drafted by the AI agent and reviewed by the maintainer (native speaker) before merge.

### Resource Naming
- Prefix internal keys with underscore: `_pref_key_*`. Internal identifiers (`_pref_key_*`, `_pref_value_*`, `pref_category_*`, `extra_category`, URIs) are **not** translated — leave them out of `values-ar/`.
- Use descriptive names: `battery_health_good`, not `bh_g`

## Common Patterns in This Codebase

### Null Safety Pattern
```java
final BatteryDO batteryDO = SystemService.getBatteryInfo(context);
if (isNull(batteryDO)) {
    Log.w(TAG, "Unable to retrieve battery information");
    return; // or provide fallback
}
// Use batteryDO safely
```

### Builder Pattern (BatteryDO)
```java
batteryDO.setLevel(level)
         .setScale(scale)
         .setStatus(status)
         .setPowerSource(chargerType);
```

### Switch Expressions
```java
return switch (health) {
    case BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealthStatus.GOOD;
    case BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealthStatus.CRITICAL;
    default -> BatteryHealthStatus.UNKNOWN;
};
```

### Resource Cleanup
```java
try (final TypedArray styledAttributes = context.obtainStyledAttributes(attrs, R.styleable.CircularProgressBar)) {
    // Use styledAttributes
    // No need to call recycle() - try-with-resources handles it
}
```

## Recent Architectural Decisions

### BatteryHealthStatus Enum (2025)
- **Decision**: Replace boolean flags (`warningHealth`, `criticalHealth`) with `BatteryHealthStatus` enum
- **Rationale**:
  - Type safety
  - Single source of truth
  - No hidden side effects
  - Easier to extend with new health states
- **Implementation**: `BatteryHealthStatus` with values: GOOD, WARNING, CRITICAL, UNKNOWN

### Method Separation (2025)
- **Decision**: Split `determineHealthString` into two separate methods
- **Rationale**: Single Responsibility Principle - avoid side effects
- **Implementation**:
  - `determineHealthStatus(int health)` - returns enum, no side effects
  - `getHealthString(int health, Resources resources)` - returns string, no side effects

## Questions or Clarifications?

When in doubt:
1. Check existing code for similar patterns
2. Refer to this guidelines document
3. Follow Single Responsibility Principle
4. Prioritize code clarity over cleverness
5. Ask for clarification if requirements are ambiguous
