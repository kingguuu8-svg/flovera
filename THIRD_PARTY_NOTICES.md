# Third-Party Notices

This project uses third-party open source dependencies. This file records the
major dependency families that should be reviewed before a public release.

The Android dependency versions are defined in
`android/spike/gradle/libs.versions.toml`.

## Android App Dependencies

| Dependency family | Purpose | License source |
| --- | --- | --- |
| Android Gradle Plugin | Android build system | Google Maven metadata and Android Gradle Plugin license |
| Kotlin | Kotlin language, compiler plugins, serialization plugin | JetBrains Kotlin license metadata |
| AndroidX Core | Android compatibility utilities | AndroidX license metadata |
| AndroidX Activity | Compose activity integration | AndroidX license metadata |
| AndroidX Lifecycle | Lifecycle runtime and Compose lifecycle helpers | AndroidX license metadata |
| Jetpack Compose UI | Android UI toolkit | AndroidX Compose license metadata |
| Jetpack Compose Material 3 | Material UI components | AndroidX Compose Material license metadata |
| Jetpack Compose Material Icons Extended | Icon set used by the app UI | AndroidX Compose Material license metadata |
| Kotlinx Serialization JSON | JSON serialization | Kotlinx Serialization license metadata |
| Kotlinx Coroutines Test | Coroutine test utilities | Kotlinx Coroutines license metadata |
| AndroidX Test | Instrumented test runner and test APIs | AndroidX Test license metadata |
| Espresso | Android UI test support | AndroidX Test/Espresso license metadata |
| JUnit 4 | Unit test framework | JUnit license metadata |
| Koog Agents | Agent runtime integration | Koog project license metadata |

## Release Requirement

Before publishing a binary release, generate or audit a dependency license
report from the final Gradle dependency graph and update this file if new
runtime dependencies are added.

Do not ship copied third-party source, model weights, icons, fonts, or binaries
unless their license allows redistribution and the required notices are present.
