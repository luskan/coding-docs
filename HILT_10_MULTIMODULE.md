# 10. Hilt in a multi-module project

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> [6 - Testing](HILT_6_TESTING.md) -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> **10 - Multi-module***

The first nine parts kept every production class in `:app`. This part makes a structural change,
not a new kind of binding: move reusable graph code to `:core`, move the words UI and its Android
integration to `:feature:words`, and leave the application root in `:app`. The finished screen still
exercises the qualifiers, scopes, ViewModels, entry points, coroutine contexts, and Worker from the
earlier parts.

---

## The one mental model to keep

**A Gradle module boundary controls which declarations reach and can be named by another module; it
does not create another Hilt component tree.**

Keep three independent questions separate:

| Question | Controlled by |
|---|---|
| Does this contribution reach the application that generates the graph? | The Gradle dependency graph |
| Which Hilt component owns the binding and how long may it live? | `@InstallIn` plus a matching scope |
| Which source modules may name this Kotlin declaration? | `public`/`internal` visibility and compile dependencies |

For example, an `internal` implementation in `:core` can still back a public interface in the
application's `SingletonComponent`. `internal` prevents feature source code from naming the class;
it does not create a private Hilt graph. Conversely, putting a class in a library does not make its
binding reachable if that library is absent from the application's dependency closure.

---

## 1. Split the cumulative app without splitting the graph

The companion app now has this module dependency graph:

```text
:app ------------------------> :core
  |
  `----> :feature:words -----> :core

:app
  MyApplication (@HiltAndroidApp), MainActivity, app tests

:feature:words
  WordScreen, @HiltViewModel classes, WordsProvider, SyncWordsWorker

:core
  WordsRepository, internal implementations, WordManager, @InstallIn modules,
  qualifiers, entry-point interfaces, coroutine services
```

`:app` depends directly on both libraries. `:feature:words` also depends on `:core` because its
source imports `WordManager`, `WordsRepository`, and the coroutine qualifiers. The direct
`:app` -> `:core` edge lets `MainActivity` and the app-owned tests name core APIs without relying on
the feature to re-export them.

Register both libraries:

```kotlin
// settings.gradle.kts
rootProject.name = "HiltPracticeApp"
include(":app", ":core", ":feature:words")
```

Part 1's catalog needs the Android library plugin, and the root build declares it once:

```toml
# gradle/libs.versions.toml
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

```kotlin
// build.gradle.kts
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

For this application component tree, there is one application root:

```kotlin
// :app -- MyApplication.kt
package com.example.myapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

Neither library declares `@HiltAndroidApp`. A repository may contain another independently built
application module with its own root, but a library contributing to this app must not introduce a
second root into this app's dependency closure.

---

## 2. Generate code in the module that owns the source

Each module compiles its own declarations before `:app` generates the final components. The
companion app applies the Hilt plugin and KSP in every participating Android module, then places
each processor beside the annotation it handles.

The core library owns ordinary `@Inject` constructors and Hilt modules:

```kotlin
// core/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp.core"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 24 }
}

dependencies {
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.hilt.compiler)

    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
```

The feature library owns Compose ViewModels and the Part 9 Worker:

```kotlin
// feature/words/build.gradle.kts
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp.feature.words"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 24 }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
```

`:app` remains the application root and owns an injected `HiltWorkerFactory`:

```kotlin
// app/build.gradle.kts -- relevant production dependencies
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":feature:words"))

    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")

    // Part 6's test dependencies and kspTest/kspAndroidTest remain here.
}
```

There are three load-bearing details:

- Dagger's `hilt-android-compiler` runs in the module that owns the `@Inject` constructor, injected
  field, Hilt module, ViewModel, or root. Moving the source but leaving its processor in `:app` is
  not equivalent.
- AndroidX's `hilt-compiler` runs in `:feature:words` because that module owns `@HiltWorker`.
  Dagger's processor is also required there; the two processors generate different pieces of the
  Worker factory path.
- With Dagger/Hilt 2.60, every module containing a real `@Inject` use needs the explicit
  `error_prone_annotations` `compileOnly` dependency. All three modules have one.

The ViewModel Compose library and AndroidX Worker processor no longer belong in `:app` merely
because `:app` consumes the feature. Generated factories belong with their source; the application
root consumes the resulting classes and Hilt metadata.

---

## 3. Expose graph contracts, not implementations

The interface remains public because feature code and app tests use it:

```kotlin
// :core -- WordsRepository.kt
package com.example.myapp.core

interface WordsRepository {
    val instanceId: Int
    val requestCount: Int

    fun getWords(): List<String>
}
```

The two concrete repositories are implementation details of `:core`:

```kotlin
// :core -- FancyWordsRepositoryImpl.kt
package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class FancyWordsRepositoryImpl @Inject constructor() : WordsRepository {
    override val instanceId: Int = nextInstanceId.incrementAndGet()

    private val requests = AtomicInteger()

    override val requestCount: Int
        get() = requests.get()

    override fun getWords(): List<String> {
        requests.incrementAndGet()
        return listOf("dragonfruit", "kumquat", "persimmon")
    }

    private companion object {
        val nextInstanceId = AtomicInteger()
    }
}
```

`WordsRepositoryImpl` has the same shape and is also `internal`. The qualifiers stay public because
consumers and tests may need to request those public graph keys. The binding module is deliberately
public, while its methods and implementation-typed parameters remain internal:

```kotlin
// :core -- RepositoryModule.kt
package com.example.myapp.core.di

import com.example.myapp.core.FancyWordsRepositoryImpl
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.WordsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @BasicWords
    @Binds
    internal abstract fun bindBasicWordsRepository(
        impl: WordsRepositoryImpl,
    ): WordsRepository

    @FancyWords
    @Binds
    internal abstract fun bindFancyWordsRepository(
        impl: FancyWordsRepositoryImpl,
    ): WordsRepository
}
```

Hilt can install a non-public module by generating a public wrapper, so `internal` is often a good
default for a library's Hilt-only module. This series needs a different tradeoff: Part 6's app-owned
tests name `RepositoryModule` in `@TestInstallIn(replaces = ...)` and `@UninstallModules`. Kotlin
cannot name an internal declaration across the module boundary, and Hilt 2.60 also rejects an
internal module in `@TestInstallIn#replaces`. Keeping the module public makes it an explicit test
seam without exposing either concrete repository.

`@InstallIn(SingletonComponent::class)` is still what makes the bindings application-component
bindings. The fact that the source file now lives in `:core` does not alter its lifetime or create a
core-only component.

---

## 4. Consume the same graph from the feature

The feature injects public core types normally. No adapter, component dependency, or manual factory
is required:

```kotlin
// :feature:words -- FeatureWordsReader.kt
package com.example.myapp.feature.words

import com.example.myapp.core.WordManager
import com.example.myapp.core.WordsRepository
import javax.inject.Inject

class FeatureWordsReader @Inject constructor(
    private val wordManager: WordManager,
) {
    fun nextWord(): String = wordManager.nextWord()

    fun usesRepository(repository: WordsRepository): Boolean =
        wordManager.usesRepository(repository)
}
```

The running screen is also feature-owned. Its ViewModel imports `WordManager` from `:core`, while
Compose obtains the ViewModel through the AndroidX Hilt 1.3 package introduced in Part 5:

```kotlin
package com.example.myapp.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.example.myapp.core.WordManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WordViewModel @Inject constructor(
    private val wordManager: WordManager,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // The Part 5 state logic is unchanged.
}
```

```kotlin
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun WordScreen(
    composeViewModel: WordViewModel = hiltViewModel(),
) {
    // ...
}
```

Do not use `api` as a DI visibility switch. In this build:

- `implementation(project(":core"))` lets `:feature:words` source name core's public declarations;
- it does not re-export those declarations to downstream source code;
- `:app` can name core declarations because it has its own direct `implementation(project(":core"))`;
- changing the feature edge to `api` would expose public core types to feature consumers, but it
  would never make an `internal` implementation accessible.

Hilt assembles components from contributions on the application's dependency/classpath closure.
That aggregation concern is separate from whether Kotlin source in a downstream module may compile
against a public type. Prefer `implementation`; add a direct dependency or use `api` only when the
source-level API requires it.

---

## 5. Move Android integration with its owner

Library manifests merge into the final application manifest. The feature owns the Part 7 content
provider, so its manifest owns the registration too:

```xml
<!-- :feature:words/src/main/AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
        <provider
            android:name="com.example.myapp.WordsProvider"
            android:authorities="${applicationId}.words"
            android:exported="false" />
    </application>

</manifest>
```

Use the provider's full class name. A relative `.WordsProvider` would resolve against the feature
namespace `com.example.myapp.feature.words`, but the retained source package is
`com.example.myapp`. `${applicationId}` is resolved from the consuming application, so the installed
authority is `com.example.myapp.practice.words`.

The provider still calls the public `WordsEntryPoint` from `:core`. It is not a second graph root;
it asks the application component for `WordManager` exactly as in Part 7. The app manifest still
owns `MyApplication`, `MainActivity`, and the Part 9 removal of `WorkManagerInitializer`.

Move the Worker and its binding together with the feature behavior:

```text
:feature:words
  com.example.myapp.work.SyncWordsWorker
  com.example.myapp.work.di.WorkManagerModule
  ksp(libs.hilt.compiler)
  ksp(libs.androidx.hilt.compiler)
```

The verified feature KSP output contains the Worker assisted factory, generated Dagger
implementation, Hilt module, and class-name map contribution. If the AndroidX processor remained
only in `:app`, it would not process the Worker source in `:feature:words`; `HiltWorkerFactory`
would have no map entry for it and WorkManager's reflection fallback could not call its assisted
constructor.

---

## 6. Prove that the modules share one component

A graph test owned by `:app` requests one feature class and one qualified core interface:

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.feature.words.FeatureWordsReader
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class MultiModuleGraphTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var reader: FeatureWordsReader

    @field:FancyWords
    @Inject
    lateinit var fancyRepository: WordsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun appComponentInjectsFeatureConsumerFromCoreBinding() {
        val requestsBefore = fancyRepository.requestCount

        assertTrue(reader.usesRepository(fancyRepository))
        assertTrue(reader.nextWord() in listOf("dragonfruit", "kumquat", "persimmon"))
        assertEquals(requestsBefore + 1, fancyRepository.requestCount)
    }
}
```

The identity assertion proves that the `WordManager` inside a feature object received the same
qualified singleton that the app test requested. The count proves `nextWord()` traversed that same
instance. There is no feature component parallel to the app component.

Hilt does not eagerly add and instantiate every class carrying `@Inject`. The source-owning module
generates each constructor-injected class's factory. Hilt declarations such as `@InstallIn` modules
and entry points separately produce aggregation metadata. The application root then generates
components from the reachable declarations and requested binding paths. An unused
constructor-injected class may have a factory without becoming a reachable object in the component.

Run the cumulative checks from the fresh root project:

```bash
cd hilt-practice-app
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The verified Part 10 checkpoint has 12 passing Robolectric/JUnit tests and one cumulative device
test. The normal APK cold-starts with the production application and displays:

```text
Part 10 - One graph across three modules
:app -> :feature:words -> :core
```

After pressing **Enqueue injected worker**, it displays:

```text
Worker state: succeeded
Worker words: dragonfruit, kumquat, persimmon
```

That normal-APK check matters: the Hilt test application proves the test component, while the cold
start proves the merged library manifest and production `MyApplication`. Successful work then
proves the production `HiltWorkerFactory` and feature-generated map path.

---

## Multi-module -- error -> cause

| Error | Cause |
|---|---|
| `Unresolved reference 'core'` / `Unresolved reference 'WordManager'` in `:feature:words` | The feature source imports a core API but lacks `implementation(project(":core"))` |
| `Cannot access 'class FancyWordsRepositoryImpl ...': it is internal in file.` | Another module tried to name a core implementation; inject the public qualified `WordsRepository` contract instead |
| `[Dagger/MissingBinding] @FancyWords WordsRepository cannot be provided...` | No reachable module contributes that exact qualified key; restore the `@FancyWords @Binds` method or request the key that actually exists |
| The same missing-binding diagnostic appears after removing core's KSP processor | `:core` did not generate its factories and Hilt aggregation metadata; restore `ksp(libs.hilt.compiler)` in the source-owning module |
| `package com.google.errorprone.annotations does not exist` | The module now owns a real `@Inject` use but lacks `compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")` required by this Dagger 2.60 toolchain |
| `Expected @AndroidEntryPoint to have a value. Did you forget to apply the Gradle Plugin? (com.google.dagger.hilt.android)` | The Android module with the entry point did not apply the Hilt plugin, or another processor configuration overwrote Hilt's arguments |
| `@TestInstallIn#replaces() cannot contain internal Hilt modules, but found: ...RepositoryModule` | A production module made `internal` is being replaced from another module; keep the replaceable module public and hide its binding methods/implementations instead |
| `Cannot process multiple app roots in the same compilation unit: ...` | Two `@HiltAndroidApp` roots were declared in one compilation unit |
| `Cannot process new app roots when there are app roots from a previous compilation unit: ...` | A dependency library contributed an app root to the application that already has one; remove the library root |
| `Application class, ..., annotated with @HiltAndroidApp must be defined in a Gradle android application module...` | `@HiltAndroidApp` was placed in an Android library instead of the consuming application module |
| Runtime `Unable to get provider ... ClassNotFoundException` for `com.example.myapp.feature.words.WordsProvider` | The feature manifest used a relative provider name even though the retained Kotlin package differs from the feature namespace; use the full class name |
| Runtime `Could not instantiate ...SyncWordsWorker`, followed by `NoSuchMethodException` / `Could not create Worker` | The module containing `@HiltWorker` lacks AndroidX's Hilt processor, or WorkManager was not given the generated `HiltWorkerFactory` path |
| `[Dagger/DuplicateBindings] ...WordsRepository is bound multiple times` | The old app-owned binding and the moved core binding both remain on the final graph; keep one owner |

---

## Where to go next

This completes the series. The same three-axis model scales beyond it:

- [Hilt custom components](https://dagger.dev/hilt/custom-components) when the standard Android
  lifetimes genuinely cannot model a domain lifetime;
- [Dagger multibindings](https://dagger.dev/dev-guide/multibindings.html) when many feature modules
  contribute handlers or plugins to one set or map;
- Gradle convention plugins when many Android libraries need the same Hilt, KSP, and toolchain
  configuration.

Prefer the standard component tree and plain module dependencies until one of those needs is real.

## Quick reference

| I want to... | Do this |
|---|---|
| Keep one graph across `:app`, features, and core | Put every contribution on the app dependency closure; keep one `@HiltAndroidApp` root for that app |
| Choose a binding lifetime | Use `@InstallIn` and a matching Hilt scope; Gradle module placement does not choose it |
| Hide a concrete implementation | Keep it `internal` beside its binding; expose a public interface and any qualifiers consumers need |
| Replace a library module from app tests | Keep the replaceable module public; its methods and implementation types can remain internal |
| Let feature source use core APIs | Add `implementation(project(":core"))`; use `api` only if the feature's public API must re-export core types |
| Generate an `@Inject` factory | Run Dagger's Hilt compiler in the source-owning module and add the Dagger 2.60 `error_prone_annotations` compile-only dependency |
| Generate a `@HiltWorker` factory | Run both Dagger and AndroidX Hilt processors in the module that contains the Worker |
| Register an Android class from a library | Put it in the library manifest; use a full class name when its package differs from the library namespace |
| Verify the final graph | Build, run graph tests, inspect the merged manifest/generated output, then cold-start the normal APK |
