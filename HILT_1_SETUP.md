# 1. Hilt on a new Android project (AGP 9 / Android Studio 2026 templates)

*Reading order: **1 · Setup** → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → [9 · WorkManager](HILT_9_WORKMANAGER.md) → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

A corrected version of the official Hilt setup guide
(https://developer.android.com/training/dependency-injection/hilt-android), which as of mid-2026
still pins Hilt `2.57.1` — a version that does **not** work with AGP 9. Per the Dagger 2.59 release
notes, Hilt 2.59 is the first release with AGP 9 support, and AGP 9 is a hard requirement of its
Gradle plugin from then on. Everything below was verified end-to-end on this project.

**Verified working version matrix:**

| Component  | Version        | Constraint                                          |
|------------|----------------|-----------------------------------------------------|
| AGP        | 9.2.1          | whatever your template generated                    |
| Kotlin     | 2.2.10         | from template                                       |
| KSP        | `2.2.10-2.0.2` | **must match your Kotlin version** (prefix)         |
| Hilt       | `2.60`         | **must be ≥ 2.59 for AGP 9**; note: no `.0` suffix  |
| compileSdk | 37             | template default; not required by the pinned deps (36 also builds) — but a newer androidx.core can force a higher floor |

## 1. Version catalog — `gradle/libs.versions.toml`

Add to the existing sections:

```toml
[versions]
hilt = "2.60"
ksp = "2.2.10-2.0.2"   # first part must equal your kotlin = "..." version

[libraries]
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }

[plugins]
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

When you update Kotlin later, pick the matching KSP version from
https://github.com/google/ksp/releases.

## 2. Root `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

## 3. `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    // ...template defaults...
    compileSdk {
        version = release(37)   // template default; a newer androidx.core can refuse to build against a lower compileSdk
    }
}

dependencies {
    // ...template defaults...
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Dagger 2.60 no longer pulls error_prone_annotations transitively, but the generated
    // component still imports @CanIgnoreReturnValue — without this, your FIRST real @Inject
    // fails to compile. See the note below. Remove once a future Hilt bundles it again.
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
```

Note there is **no `org.jetbrains.kotlin.android` plugin** — AGP 9 compiles Kotlin itself
("built-in Kotlin"). Don't add it back.

**The `error_prone_annotations` workaround.** Dagger 2.60's runtime artifact no longer pulls in
`com.google.errorprone:error_prone_annotations` transitively — its `dagger` POM declares only
`jakarta.inject`, `javax.inject`, and `jspecify`. But Dagger's code generator still emits
`@CanIgnoreReturnValue` on the generated component's builder methods, so the generated
`Dagger…_HiltComponents_SingletonC.java` fails to compile with *"package
com.google.errorprone.annotations does not exist."* Crucially, this does **not** appear during the
setup above: with only `@HiltAndroidApp` the DI graph is empty and no component is generated. It
surfaces the moment you add your **first real injection** (§6) — the first `@Inject` that forces a
component to be built. `compileOnly` is the correct scope: `@CanIgnoreReturnValue` has `CLASS`
retention, so it is needed only to compile the generated code, never at runtime. Any recent
`error_prone_annotations` version works (`2.36.0` verified here). This is a packaging gap in the
2.59/2.60 + AGP 9 line (cf. https://github.com/google/dagger/issues/5099 for a sibling case); drop
the line once a future Hilt/Dagger release ships the annotation again.

## 4. `gradle.properties` — the AGP 9 + KSP workaround

```properties
android.disallowKotlinSourceSets=false
```

Without this, configuration fails with *"Using kotlin.sourceSets DSL to add Kotlin sources is not
allowed with built-in Kotlin"* — KSP registers its generated sources the old way and hasn't been
updated for built-in Kotlin yet. This flag is the officially suggested workaround; remove it once
a future KSP release supports built-in Kotlin natively.

## 5. Application class — annotation **and** manifest

```kotlin
package com.example.myapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application()
```

The step the official guide omits — register it in `AndroidManifest.xml`, or Hilt silently never
initializes:

```xml
<application
    android:name=".MyApplication"
    ... >
```

## 6. Using Hilt

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() { ... }

class MyRepository @Inject constructor() { ... }

@HiltViewModel
class MyViewModel @Inject constructor(
    private val repository: MyRepository
) : ViewModel()
```

For `hiltViewModel()` in Compose, additionally add `androidx.hilt:hilt-lifecycle-viewmodel-compose`
(the current home of `hiltViewModel()` since androidx.hilt 1.3; it was formerly in
`androidx.hilt:hilt-navigation-compose`). See part 5 for usage.

This is where the `error_prone_annotations` gap bites: your first `@Inject` here is the first thing
that makes Dagger generate a component. If it fails with *package com.google.errorprone.annotations
does not exist*, add the `compileOnly` line from §3.

## Error → cause cheat sheet

| Error | Cause |
|---|---|
| `Plugin ... hilt.android:2.60.0 was not found` | Version doesn't exist — Hilt uses `2.60`, not `2.60.0` |
| `Plugin ... devtools.ksp:X was not found` | KSP version must be `<kotlin>-<ksp>`, e.g. `2.2.10-2.0.2` |
| `kotlin.sourceSets DSL ... not allowed with built-in Kotlin` | Missing `android.disallowKotlinSourceSets=false` |
| `The Hilt Android Gradle plugin requires AGP 9` (or transform errors on AGP 9 with Hilt ≤ 2.58) | Hilt and AGP major versions must pair: AGP 9 ↔ Hilt ≥ 2.59 |
| `package com.google.errorprone.annotations does not exist` (in generated `Dagger…SingletonC.java`) | Dagger 2.60 dropped the transitive `error_prone_annotations` dep — add `compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")`. Only appears after your first real `@Inject` |
| `androidx.core:core requires ... version 37` | Bump `compileSdk` to `release(37)` |
| `Unresolved reference: HiltAndroidApp` | Missing `import dagger.hilt.android.HiltAndroidApp` |
| App crashes: `Hilt Activity must be attached to @HiltAndroidApp Application` | `android:name=".MyApplication"` missing from manifest |
