# Practice 10. Splitting one Hilt graph across Gradle modules

*Tutorial: [10 · Multi-module](HILT_10_MULTIMODULE.md) · **Practice 10 of 10***

Start from the Part 10 state of [`hilt-practice-app/`](hilt-practice-app/). The production app is
now split into `:app`, `:core`, and `:feature:words`, while one application root assembles the
cumulative Hilt component tree. Work on a throwaway branch: several exercises deliberately remove
project edges, processors, manifest wiring, or graph visibility. Restore the green checkpoint after
every experiment.

## Self-check questions

1. Which three independent mechanisms decide whether a contribution reaches the app, which Hilt
   component owns it, and which Kotlin source modules may name it? Why does `internal` not create a
   private Hilt graph?
2. After an injectable type moves to another Gradle module, which module must run Dagger's Hilt
   processor, where must AndroidX's Worker processor run, and where does this Dagger 2.60 build need
   `error_prone_annotations`?
3. What do `implementation(project(":core"))`, `api(project(":core"))`, and a direct
   `:app` → `:core` dependency each change? Which of them can expose an `internal` core class?
4. Why is `RepositoryModule` public while its two `@Binds` methods and concrete repositories are
   internal, and what Part 6 test behavior would an internal production module break?
5. What different boundaries are proved by generated-code/manifest inspection, the JVM graph test,
   the connected device test, and a normal APK cold-start followed by a successful Worker request?

Answer these before looking at the final section.

## Practical tasks

### 1. Map the three axes and establish the baseline

Draw the real dependency graph without looking at the tutorial, then compare it with:

```text
:app ─────────────────→ :core
  └──→ :feature:words ─→ :core
```

For every item below, write down its source-owning Gradle module, generated-code owner, Hilt
component, scope, and source visibility:

- `MyApplication`;
- `WordManager`;
- `FancyWordsRepositoryImpl`;
- `RepositoryModule` and each of its binding methods;
- `WordViewModel`;
- `WordsProvider` and its manifest registration; and
- `SyncWordsWorker` plus `WorkManagerModule`.

Run the baseline commands separately from `hilt-practice-app/`:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am force-stop com.example.myapp.practice
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** all 12 JVM tests and the one cumulative device test pass. The normal APK displays
`Part 10 · One graph across three modules` and `:app → :feature:words → :core`. After pressing
**Enqueue injected worker**, it reaches `Worker state: succeeded` and returns the production fancy
words.

For that one running request, distinguish the three axes explicitly:

```text
Gradle path:       :app → :feature:words → :core
Hilt ownership:    SyncWordsWorker factory map + dependencies in SingletonComponent
Kotlin visibility: feature source names public AsyncWordsLoader, not internal repositories
```

### 2. Follow generated evidence across compilation units

Generate the three production layers:

```bash
./gradlew \
  :core:kspDebugKotlin \
  :feature:words:kspDebugKotlin \
  :app:hiltJavaCompileDebug \
  :app:processDebugManifest
```

Inspect representative core output:

```bash
rg --files core/build/generated/ksp/debug/java | \
  rg 'FancyWordsRepositoryImpl_Factory|RepositoryModule|WordsEntryPoint' | sort
```

**Check:** `:core` owns the concrete repository factory and aggregation records for its installed
module and entry-point interfaces. A constructor factory and an aggregated Hilt declaration are
different generated artifacts.

Inspect feature output:

```bash
rg --files feature/words/build/generated/ksp/debug/java | \
  rg 'WordViewModel|SyncWordsWorker' | sort
rg -n 'StringKey|SyncWordsWorker' \
  feature/words/build/generated/ksp/debug/java/com/example/myapp/work/SyncWordsWorker_HiltModule.java
```

**Check:** `:feature:words` owns the ViewModel factory and all four Worker bridge files. The Worker
module contributes the exact class-name key `com.example.myapp.work.SyncWordsWorker`.

Now inspect the app-root output:

```bash
rg -n 'FancyWordsRepositoryImpl|WordViewModel|SyncWordsWorker|HiltWorkerFactory' \
  app/build/generated/hilt/component_sources/debug/com/example/myapp/\
DaggerMyApplication_HiltComponents_SingletonC.java
```

**Check:** the final application component refers to generated code from both libraries. `:app`
does not regenerate their source-owned factories.

Finally inspect the merged manifest and its blame file:

```bash
rg -n 'WordsProvider|InitializationProvider|WorkManagerInitializer' \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
rg -n 'WordsProvider' \
  app/build/intermediates/manifest_merge_blame_file/debug/processDebugMainManifest/\
manifest-merger-blame-debug-report.txt
```

**Check:** the merged manifest contains `com.example.myapp.WordsProvider` with authority
`com.example.myapp.practice.words`; the blame points to `:feature:words`; the shared App Startup
provider remains; and `androidx.work.WorkManagerInitializer` is absent.

### 3. Separate source API exposure from graph aggregation

In `app/build.gradle.kts`, temporarily remove:

```kotlin
implementation(project(":core"))
```

Keep `implementation(project(":core"))` in `feature/words/build.gradle.kts`, then run:

```bash
./gradlew :app:compileDebugKotlin
```

**Check:** app source cannot resolve the core types used by `MainActivity`. The feature can compile
against core, but an `implementation` dependency does not re-export core APIs to downstream app
source.

Now change only the feature edge to:

```kotlin
api(project(":core"))
```

Build again.

**Check:** public core declarations are now exposed through the feature's compile API, so app source
can name `WordSession`. This does not make `api` the preferred design here: `MainActivity` and app
tests directly use core, so the direct app dependency states their real dependency more clearly.

While using `api`, make feature or app source try to name `FancyWordsRepositoryImpl`.

**Check:** Kotlin still reports that the class is internal. `api` can re-export only declarations
that are public to begin with; it is not a Hilt visibility switch.

Restore both production edges:

```kotlin
// :feature:words
implementation(project(":core"))

// :app
implementation(project(":core"))
implementation(project(":feature:words"))
```

Finish this task with `./gradlew :app:assembleDebug` green.

### 4. Move processors with the source, not with the consumer

Run each experiment from a clean generated-output state and restore the edited line before starting
the next one.

First remove this line from `core/build.gradle.kts`:

```kotlin
ksp(libs.hilt.compiler)
```

Run:

```bash
./gradlew clean :app:assembleDebug
```

**Check:** `:core` no longer generates its constructor factories and Hilt aggregation records, so
the downstream graph cannot resolve the reachable qualified repository path. The processor in
`:app` does not process another module's source retroactively.

Restore core KSP. Next remove core's explicit compile-only dependency and clean-build again:

```kotlin
compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
```

**Check:** this Dagger 2.60 toolchain fails with
`package com.google.errorprone.annotations does not exist`. Add the dependency back to the module
that owns those real `@Inject` declarations.

Finally move, rather than copy, this line from `:feature:words` to `:app`:

```kotlin
ksp(libs.androidx.hilt.compiler)
```

Run a clean build and inspect both modules' generated output.

**Check:** `:app` has no `@HiltWorker` source for the AndroidX processor to process, and the feature
no longer produces `SyncWordsWorker_HiltModule` or its class-name map entry. A build may still
package the Worker, but the injected `HiltWorkerFactory` cannot find it. WorkManager delegates to
reflection, which cannot call the Worker's three-argument assisted constructor.

Install that intentionally broken APK only if you want to observe the runtime boundary:

```bash
./gradlew :app:installDebug
adb shell am force-stop com.example.myapp.practice
adb logcat -c
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

Press **Enqueue injected worker**, then inspect:

```bash
adb logcat -d | \
  rg 'SyncWordsWorker|Could not instantiate|Could not create Worker|NoSuchMethodException'
```

Restore the AndroidX processor to `:feature:words` and finish with a clean build and all 12 JVM
tests passing.

### 5. Design the visibility seam for production and tests

Temporarily make the production declaration in `RepositoryModule.kt` internal:

```kotlin
internal abstract class RepositoryModule
```

Run:

```bash
./gradlew clean :app:kspDebugUnitTestKotlin
```

**Check:** app-owned Part 6 tests cannot use this as a cross-module replacement seam. Hilt 2.60
reports:

```text
@TestInstallIn#replaces() cannot contain internal Hilt modules, but found:
```

Kotlin source may also report that the app test cannot access the internal class. Hilt's generated
public visibility wrapper is an aggregation implementation detail, not the production module that
`@TestInstallIn` or `@UninstallModules` should name.

Restore the public class. Next remove `internal` from one binding method while leaving its concrete
repository parameter internal:

```kotlin
@FancyWords
@Binds
abstract fun bindFancyWordsRepository(
    impl: FancyWordsRepositoryImpl,
): WordsRepository
```

**Check:** Kotlin rejects a public function that exposes an internal parameter type. Restore the
method to `internal`.

Explain why the final shape satisfies all three consumers:

```text
public WordsRepository + qualifiers   feature/app source may request graph contracts
internal repository implementations  only :core source/generated code may name them
internal @Binds methods               internal parameter types do not leak into public API
public RepositoryModule               app-owned tests may replace/uninstall it by class
```

Run both focused replacement paths after restoring the file:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.WordManagerBindValueTest
./gradlew :app:assembleDebugAndroidTest
```

### 6. Exercise root and manifest ownership failures

In the feature manifest, change the provider name temporarily:

```xml
android:name=".WordsProvider"
```

Merge the manifest and inspect the result:

```bash
./gradlew :app:processDebugManifest
rg -n 'WordsProvider' \
  app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

**Check:** AGP 9.2.1 resolves the relative name against the feature namespace, producing
`com.example.myapp.feature.words.WordsProvider`. The actual retained source package is
`com.example.myapp`, so installing and cold-starting this APK fails while Android is creating the
provider. Restore the full class name:

```xml
android:name="com.example.myapp.WordsProvider"
```

Next add a temporary second root in `:app`:

```kotlin
package com.example.myapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SecondApplication : Application()
```

Run `./gradlew clean :app:kspDebugKotlin`.

**Check:** Hilt reports:

```text
Cannot process multiple app roots in the same compilation unit:
```

Delete `SecondApplication`. If the same annotation is instead placed in an Android library, Hilt
rejects it because an `@HiltAndroidApp` application must be defined in a Gradle Android application
module. A repository may have multiple independently built app modules, but each assembled app has
its own single root; a shared library is not one of those roots.

Finish with the full provider name, one root, and a green `:app:assembleDebug`.

### 7. Add one small feature module correctly

As a positive exercise, add a temporary `:feature:compare` Android library. Register it in
`settings.gradle.kts`, add `implementation(project(":feature:compare"))` to `:app`, and give it this
minimal build configuration:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.example.myapp.feature.compare"
    compileSdk { version = release(37) }

    defaultConfig { minSdk = 24 }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
```

It needs neither Compose nor AndroidX's Worker processor because it owns neither kind of source.
Add this consumer:

```kotlin
package com.example.myapp.feature.compare

import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.BasicWords
import com.example.myapp.core.di.FancyWords
import javax.inject.Inject

class WordPairReader @Inject constructor(
    @param:BasicWords private val basicRepository: WordsRepository,
    @param:FancyWords private val fancyRepository: WordsRepository,
) {
    fun nextPair(): Pair<String, String> =
        basicRepository.getWords().random() to fancyRepository.getWords().random()
}
```

Extend `MultiModuleGraphTest` to inject `WordPairReader`. Assert that the first item is in the basic
production list and the second is in the fancy production list.

**Check:** the new library generates `WordPairReader_Factory`; the app test requests it from the
same final component; and no new `@HiltAndroidApp`, manual component, or public repository
implementation is needed.

Keep the extension on the throwaway branch or remove the temporary module and test edits. Return to
the committed baseline with 12 JVM tests, one device test, and the normal production Worker green.

---

## Answers to the self-check questions

1. The Gradle dependency closure decides whether a library's code and Hilt contributions reach the
   application root. `@InstallIn` plus a matching scope selects a component and lifetime. Kotlin
   visibility plus compile dependencies decides which source modules may name a declaration.
   `internal` changes only that source-access question: Hilt can still install an internal-backed
   binding in the app's component tree.
2. Dagger's `hilt-android-compiler` must run in each source-owning module so that module generates
   its factories, members injectors, and relevant Hilt metadata. AndroidX's `hilt-compiler` must run
   specifically where `@HiltWorker` source lives and works alongside Dagger's processor. With the
   targeted Dagger 2.60 artifact, every module containing a real `@Inject` use also needs
   `compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")`.
3. `implementation(project(":core"))` lets the direct dependent compile against public core APIs
   without re-exporting them. `api(project(":core"))` also exposes those public APIs to downstream
   compile consumers. A direct app edge declares that app source itself depends on core. None can
   expose an internal class; Kotlin visibility remains a separate boundary from Gradle API exposure
   and Hilt aggregation.
4. The public module is an intentional cross-module test seam: Part 6's app tests must name it in
   `@TestInstallIn(replaces = …)` and `@UninstallModules`. Hilt 2.60 rejects an internal module in
   `replaces`, and Kotlin would also prevent app test source from naming it. Keeping the concrete
   repositories and binding methods internal prevents implementation types from entering core's
   public API while still letting generated core code install the bindings.
5. Generated output proves that each source module produced its own factories/metadata and that the
   app component consumed them; the merged manifest proves the feature's Android declaration and
   app-owned initializer removal. The JVM graph test proves an app test, a feature consumer, and a
   core qualified singleton meet in one test component. The device test adds Android UI, provider,
   test WorkManager, and fake-graph behavior. A normal cold start proves the production manifest and
   `MyApplication`; a successful request additionally proves the production `HiltWorkerFactory`,
   feature-generated worker map, and real graph.
