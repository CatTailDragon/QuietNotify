# QuietNotify AI Agent Guide

This document is operational context for AI coding agents working on QuietNotify. Read it before changing the project. User-facing documentation belongs in `README.md`; keep this file focused on implementation constraints, code organization, and verification.

## Project Summary

QuietNotify is a single-module Android application and LSPosed module built with Kotlin and Jetpack Compose. It uses libxposed Modern API 102 and runs module code in two system processes:

- `system` / `system_server`: limits repeated notification sound and vibration.
- `com.android.systemui`: limits repeated heads-up notifications while the device is interactive and unlocked.

Users select applications and assign each one a fixed window from 1 second to 24 hours. Heads-up suppression and sound/vibration suppression are independent global features, but use the same per-package duration rules.

The module must never cancel notifications or remove them from the notification center. Hook failures and inspection failures deliberately fail open and preserve normal system behavior.

## Toolchain

- Android Gradle Plugin: `9.2.1`
- Kotlin Compose plugin: `2.2.21`
- Gradle Wrapper: `9.5.1`
- Java source and target: `17`
- `compileSdk`: `37`
- `minSdk`: `34`
- `targetSdk`: `35`
- libxposed API/service: `102.0.0`
- UI: Jetpack Compose with Material 3
- Unit tests: JUnit 4

Use JDK 17 and Android SDK 37 for local builds.

## Repository Layout

```text
.
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/io/github/lsp1/quietnotify/
│       │   │   ├── Config.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── QuietNotifyApp.kt
│       │   │   ├── data/
│       │   │   │   ├── ConfigRepository.kt
│       │   │   │   └── InstalledAppsRepository.kt
│       │   │   ├── ui/MainScreen.kt
│       │   │   └── xposed/
│       │   │       ├── QuietNotifyModule.kt
│       │   │       └── WindowTracker.kt
│       │   ├── res/
│       │   └── resources/META-INF/xposed/
│       │       ├── java_init.list
│       │       ├── module.prop
│       │       └── scope.list
│       └── test/java/io/github/lsp1/quietnotify/xposed/
│           └── WindowTrackerTest.kt
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/wrapper/
├── README.md
└── AIREADME.md
```

`signing/`, `local.properties`, Gradle caches, and build outputs are local-only and ignored. Never expose or commit signing material.

## Runtime Architecture

### Configuration application process

`QuietNotifyApp` registers an `XposedServiceHelper.OnServiceListener` and exposes the current `XposedService?` as a `StateFlow`.

`MainActivity` only enables edge-to-edge rendering and hosts `QuietNotifyRoot`.

`MainScreen.kt` owns the Compose screen. It creates:

- `ConfigRepository` for Remote Preferences and reactive rule state.
- `InstalledAppsRepository` for loading installed package labels, icons, and system-app status.

There is no ViewModel, dependency-injection framework, Room database, Android service, or local preference fallback. Do not introduce one without a concrete requirement.

### Shared configuration

All UI and injected processes use libxposed Remote Preferences group `rules`.

Keys are defined centrally in `Config.kt`:

```text
enabled                  Boolean, default true
heads_up_enabled         Boolean, default true
sound_enabled            Boolean, default true
rule.<packageName>       Long duration in milliseconds
```

Valid durations are `1_000L..86_400_000L`; the default shown for a new rule is 5 minutes. Invalid persisted rule durations are ignored by `Config.readRules`.

When changing configuration:

- Add key constants and validation in `Config.kt`.
- Mirror defaults and fields in `RulesState` and `ConfigRepository.updateFrom`.
- Persist only through `XposedService.getRemotePreferences(Config.GROUP)`.
- Update module-side `reloadRules` so both injected processes react immediately.
- Preserve existing keys unless an explicit migration or behavior change requires otherwise.

### Injected module processes

`QuietNotifyModule` is the sole Xposed entry point. It is listed in `META-INF/xposed/java_init.list`.

Static scopes in `scope.list` are required and currently are exactly:

```text
system
com.android.systemui
```

Do not replace `system` with `android`. In Modern API, `system` represents `system_server`; `android` is not equivalent.

Process responsibilities are strictly separated:

- `onSystemServerStarting`: initialize preferences and install notification sound/vibration hooks with `param.classLoader`.
- `onPackageLoaded`: only handle the first package load for `com.android.systemui`, then install heads-up hooks with `param.defaultClassLoader`.

Never reuse classes or class loaders between these processes. `com.android.server.notification.*` belongs to `system_server`; `com.android.systemui.*` belongs to SystemUI.

Each process creates its own `QuietNotifyModule` instance and maintains independent memory. Remote Preferences share configuration, not runtime timestamps.

## Fixed-Window Semantics

`WindowTracker` is a small synchronized in-memory state machine keyed as `<userId>:<packageName>`.

`shouldMute(key, durationMs, nowMs)` behaves as follows:

- No existing window: record `nowMs`, return `false`.
- Inside an existing window: return `true` without changing the start time.
- At or after expiration: replace the start time, return `false`.
- Clock rollback: start a new window and return `false`.

This is a fixed window, not a sliding window. Do not refresh the timestamp for suppressed notifications.

There are two independent trackers:

- `soundTracker` in the `system_server` module instance.
- `headsUpTracker` in the SystemUI module instance.

They intentionally do not synchronize across processes. Do not write notification timestamps to Remote Preferences or add Binder/disk I/O to notification hot paths.

When rules are removed or features disabled, `reloadRules` prunes or clears tracker state.

## Sound and Vibration Hook

The system-server path reflects `NotificationRecord` and tries these execution points:

1. `NotificationAttentionHelper` or `NotificationManagerService` methods named `buzzBeepBlinkLocked`, returning primitive `int`, with `NotificationRecord` as the first parameter.
2. Fallback methods named `shouldMuteNotificationLocked`, returning primitive `boolean`, with `NotificationRecord` as the first parameter.

`RecordAccess` supports non-public methods and field fallbacks for ROM compatibility:

- SBN: `getSbn`, then `sbn` or `mSbn`.
- Sound: `getSound`, then `mSound`.
- Vibration: `getVibration`, then `mVibration`.
- Existing suppression: `isIntercepted`, `shouldPostSilently`.
- Mutable silent state: `mPostSilently`.

A notification only enters the sound window if the selected application has a rule and the record currently has sound or vibration and is not already intercepted/silent.

For a suppressed notification, `muteDuring` temporarily clears mutable sound/vibration fields and sets `mPostSilently`, invokes the original method, and restores all original values in `finally`. Preserve this temporary-mutation pattern; permanent mutation can affect notification display and later processing.

## Heads-Up Hook

The SystemUI path first resolves `NotificationEntry` and its `StatusBarNotification` through `getSbn` or `mSbn`.

Compatibility is capability-based and intentionally ordered from decision-level hooks to pre-binding fallbacks:

1. `NotificationInterruptStateProviderWrapper.makeAndLogHeadsUpDecision(NotificationEntry)`
2. `VisualInterruptionDecisionProviderImpl.makeAndLogHeadsUpDecision(NotificationEntry)`
3. Legacy `NotificationInterruptStateProviderImpl.shouldHeadsUpWhenAwake(NotificationEntry, boolean)`
4. `HeadsUpCoordinator.bindForAsyncHeadsUp(PostedEntry)`
5. `HeadsUpViewBinder.bindHeadsUpView(NotificationEntry, ...)`

Only one tier is installed: fallback tiers run only when earlier tiers are unavailable. This prevents one notification from being counted multiple times.

The tested HyperOS compatibility path uses the pre-binding fallback. It suppresses a repeated heads-up before its view is bound, while leaving the notification in the normal notification pipeline.

Heads-up window logic runs only when all of the following are true:

- Module is globally enabled.
- Heads-up limiting is enabled.
- The package has a valid rule.
- `PowerManager.isInteractive` is true.
- `KeyguardManager.isDeviceLocked` is false.
- The hook point represents a notification that the system would otherwise display, where the decision API exposes that distinction.

Do not suppress AOD/locked-device behavior, remove `NotificationEntry`, alter ranking/importance, cancel notifications, or intercept full-screen intents and bubbles.

Avoid using `HeadsUpManager.showNotification` as a simple skip fallback. At that point the heads-up view may already be bound, and bypassing the manager can leave incomplete SystemUI lifecycle state.

## Failure Policy and Diagnostics

Both process initializers are isolated with `runCatching`. Installation failure is logged and the installed guard is reset. Notification inspection failures log a warning and return `false`, allowing normal system behavior.

This fail-open policy is mandatory because both target processes are system-critical.

Module logs use tag `QuietNotify`. Diagnostic decision logs are capped with `AtomicInteger(20)` per module instance to avoid unbounded hot-path logging.

Useful success messages include:

```text
Loaded in system, API 102
Loaded in com.android.systemui, API 102
Configuration updated: ...
Hooked NotificationAttentionHelper.buzzBeepBlinkLocked/...
Hooked HeadsUpCoordinator.bindForAsyncHeadsUp/1
Heads-up decision pkg=..., durationMs=..., suppress=...
```

When debugging device behavior, distinguish framework causes such as Do Not Disturb, notification-channel settings, and foreground-app behavior from module decisions. Inspect only `QuietNotify` log lines first.

## UI Conventions

The UI is a single Compose screen in `MainScreen.kt` and follows Material 3 with dynamic light/dark colors.

Current patterns:

- State comes from `StateFlow` via `collectAsState`.
- Repository instances are created with `remember`.
- Initial asynchronous work uses `LaunchedEffect`.
- Transient search/filter/dialog state uses `rememberSaveable` or `remember`.
- Selected applications sort before unselected applications.
- System applications are hidden unless explicitly requested.
- Rule changes are disabled until the Xposed service is connected.

Keep UI changes within the existing single-screen structure unless navigation becomes necessary. Prefer small composables such as `StatusCard`, `FeatureCard`, and `AppRow`. User-facing strings currently live mostly inline in Chinese; Android app name and module description are in `res/values/strings.xml`. Follow the existing language unless localization is explicitly requested.

The status card independently verifies:

- Xposed service connection.
- `system` scope.
- `com.android.systemui` scope.
- `system_server` running target.
- SystemUI running target.

Do not imply that selected applications should be added to LSPosed scope; they are configuration targets only.

## Code Style

- Kotlin official style is enabled.
- Keep changes minimal and local to the existing architecture.
- Prefer immutable snapshots for shared rule maps and `@Volatile` for values read from hook threads.
- Preserve synchronization in `WindowTracker` because notification callbacks may occur on different threads.
- Use explicit method signatures and parameter-type checks when selecting ROM methods; method-name-only reflection is unsafe.
- Traverse class hierarchies and support non-public members when ROM compatibility requires it.
- Keep comments rare and focused on non-obvious compatibility or lifecycle constraints.
- Use `runCatching` around reflection boundaries and default to allowing notifications on failure.
- Avoid disk, network, Binder, coroutine launches, or preference reads inside notification hook callbacks.
- Do not introduce dependencies solely for simple reflection or state management.
- Keep source edits ASCII unless an existing user-facing Chinese string requires Unicode.

## R8 and Xposed Metadata

Release builds enable R8 minification and resource shrinking.

`proguard-rules.pro` deliberately:

- Suppresses unavailable libxposed annotation warnings.
- Adapts `META-INF/xposed/java_init.list` when the module class is obfuscated.
- Keeps public constructors for `XposedModule` subclasses while allowing optimization and obfuscation.

If adding an entry point instantiated by name, update metadata and R8 rules together. Do not assume release stack traces use source class names; use `app/build/outputs/mapping/release/mapping.txt` when analyzing obfuscated logs.

`module.prop` currently requires and targets API 102, uses static scope, and allows hot reload. Scope changes require editing `scope.list` and updating both UI status text and user documentation.

## Testing and Verification

The existing unit tests cover fixed-window behavior, independent application/user keys, clock rollback, and pruning removed packages.

For changes to window behavior, add or update tests in `WindowTrackerTest.kt`. Keep the state machine independently testable without Android dependencies.

Before considering a code change complete, run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease
```

For a clean release verification when practical:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease
```

Expected release APK:

```text
app/build/outputs/apk/release/app-release.apk
```

For scope or release changes, inspect the final APK rather than only the source file:

```powershell
apkanalyzer files cat --file "META-INF/xposed/scope.list" app/build/outputs/apk/release/app-release.apk
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

Also verify `versionCode` and `versionName` from the final APK when changing versions.

Device-level validation is required for hook changes. At minimum test:

- First eligible notification is allowed.
- A second eligible notification inside the window is suppressed for the selected feature.
- A notification at or after expiration is allowed and starts a new window.
- Suppressed heads-up notifications remain in the notification center.
- Locked, screen-off, and AOD cases do not consume the heads-up window.
- Different applications and Android users remain independent.
- Disabling a feature immediately stops its suppression behavior.
- Do Not Disturb is disabled or accounted for during heads-up testing.

## Change Checklist

When adding or changing behavior, review all applicable items:

1. Does the change belong to the UI process, `system_server`, SystemUI, or shared configuration?
2. Is process-local state intentionally independent, or is cross-process behavior truly required?
3. Does every reflection target have strict signature checks and a fail-open path?
4. Can the change accidentally cancel, hide, rerank, or permanently mutate a notification?
5. Does the hot path remain free of disk, Binder, network, and unbounded logging?
6. Are configuration defaults consistent in `Config`, `ConfigRepository`, and `QuietNotifyModule`?
7. Are `README.md`, UI text, Xposed scopes, and metadata still accurate?
8. Were unit tests, lint, release build, final APK metadata, scope, and signature verified?
