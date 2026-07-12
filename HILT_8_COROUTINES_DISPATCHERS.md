# 8. Coroutines — injecting dispatchers and an application scope

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → **8 · Coroutines** → [9 · WorkManager](HILT_9_WORKMANAGER.md) → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

This part has almost no new Hilt in it — it's the qualifiers of part 3 applied to the single most
common real-world case: `CoroutineDispatcher`s. The reason it gets its own page is that "inject your
dispatchers" is a load-bearing testability decision, and the multi-`CoroutineDispatcher` graph is the
textbook "several bindings of one type" problem.

---

## The one mental model to keep

**Hardcoding `Dispatchers.IO` in a class is a hidden dependency; injecting it makes it swappable.**

A class that calls `withContext(Dispatchers.IO) { … }` directly cannot be told to run on a *test*
dispatcher, so its coroutines run on real threads and your tests get racy and slow. Inject the
dispatcher instead and a test can substitute a `TestDispatcher` that it controls. Because every
dispatcher is the same type — `CoroutineDispatcher` — you disambiguate them with qualifiers, exactly
as part 3 did for two `WordsRepository`s.

---

## 1. Qualifiers for each dispatcher

```kotlin
package com.example.myapp.core.di

import javax.inject.Qualifier

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope
```

## 2. Provide them from a module

Dispatchers come from the `kotlinx.coroutines` library — they have no `@Inject` constructor — so
this is a `@Provides` module (an `object`), just like the Retrofit example in part 2:

```kotlin
package com.example.myapp.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher
    fun providesMainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
```

Three bindings, all of type `CoroutineDispatcher`, kept distinct only by their qualifiers. Without
them this would be `CoroutineDispatcher is bound multiple times` — the part 3 duplicate-key error.

## 3. Use a dispatcher in a class

Give the repository a real suspend function that offloads work:

```kotlin
package com.example.myapp.core

import com.example.myapp.core.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface WordsRepository {
    suspend fun getWords(): List<String>
}

class WordsRepositoryImpl @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WordsRepository {
    override suspend fun getWords(): List<String> = withContext(ioDispatcher) {
        // pretend this reads from disk or the network
        listOf("apple", "banana", "cherry")
    }
}
```

`@IoDispatcher` on the constructor parameter requests the qualified binding — the injection-site half
of part 3. The class never mentions `Dispatchers.IO`, so a test can hand it a different dispatcher.

## 4. An application-scoped `CoroutineScope`

Work that must outlive any one screen — a write that has to finish even if the user navigates away —
needs a scope tied to the whole app, not to a `viewModelScope`. Provide one, built on the injected
default dispatcher and a `SupervisorJob` so one failed child doesn't cancel the rest:

```kotlin
package com.example.myapp.core.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun providesApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
```

`@Singleton` because you want **one** app scope (part 4); `@ApplicationScope` so it doesn't collide
with any other `CoroutineScope` binding. Inject it where fire-and-forget app-lifetime work lives:

```kotlin
class WordSyncManager @Inject constructor(
    @ApplicationScope private val appScope: CoroutineScope,
    private val repository: WordsRepository,
) {
    fun syncInBackground() = appScope.launch { repository.getWords() }
}
```

## 5. The payoff — testing (ties back to part 6)

Because dispatchers are injected, a test replaces the whole `CoroutinesModule` with one that hands
out a single `TestDispatcher`, making coroutines run deterministically on the test's scheduler:

```kotlin
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [CoroutinesModule::class])
object TestCoroutinesModule {
    private val testDispatcher = UnconfinedTestDispatcher()

    @Provides @IoDispatcher      fun ioDispatcher(): CoroutineDispatcher = testDispatcher
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = testDispatcher
    @Provides @MainDispatcher    fun mainDispatcher(): CoroutineDispatcher = testDispatcher
}
```

(Don't name a `@Provides` method `default` — Dagger generates Java, where `default` is a reserved
keyword, and KSP fails with *"The name 'default' cannot be used because it is a Java keyword."*
Method names like `io`/`main` are fine.)

Now `withContext(ioDispatcher)` runs on the test dispatcher, and `runTest { … }` advances it
synchronously. This is impossible if the class hardcoded `Dispatchers.IO`.

---

## Coroutines — error → cause

| Error | Cause |
|---|---|
| `CoroutineDispatcher is bound multiple times` | Two `@Provides` return `CoroutineDispatcher` without distinct qualifiers — give each its own `@Qualifier` (§1) |
| `CoroutineDispatcher cannot be provided …` at an injection site | The site omits the qualifier, or asks for `@IoDispatcher` while only `@DefaultDispatcher` is provided |
| `CoroutineScope is bound multiple times` | More than one `CoroutineScope` binding without a qualifier — qualify with `@ApplicationScope` |
| Tests hang or are flaky despite injection | A test still uses the **real** dispatcher — replace `CoroutinesModule` with a `TestDispatcher` module (§5), and drive with `runTest` |
| `[Dagger/IncompatiblyScopedBindings] …` on the app scope | `@Singleton` sits on a `@Provides` in a module not installed in `SingletonComponent` — check `@InstallIn` (part 4) |
| Runtime: `Module with the Main dispatcher had failed to initialize` in a plain unit test | `Dispatchers.Main` needs the Android main looper — use Robolectric, or `Dispatchers.setMain(testDispatcher)` in the test |
| KSP: `The name 'default' cannot be used because it is a Java keyword` | A `@Provides`/`@Binds` method is named `default` (a Java keyword — Dagger emits Java) — rename it, e.g. `defaultDispatcher` |

---

## Where to go next

**[9 · WorkManager](HILT_9_WORKMANAGER.md)** — injecting into a `Worker`, the other place assisted
injection (part 5) shows up, and background work that survives process death.

## Quick reference

| I want to… | Do this |
|---|---|
| Inject a dispatcher instead of hardcoding it | `@IoDispatcher` (a `@Qualifier`) + a `@Provides` returning `Dispatchers.IO` |
| Distinguish IO / Default / Main | One `@Qualifier` per dispatcher, all typed `CoroutineDispatcher` |
| App-lifetime background scope | `@Singleton @ApplicationScope` `@Provides` returning `CoroutineScope(SupervisorJob() + dispatcher)` |
| Deterministic coroutine tests | `@TestInstallIn` a module that returns a `TestDispatcher` for every qualifier |
