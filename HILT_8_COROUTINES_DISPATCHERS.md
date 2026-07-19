# 8. Coroutines -- injected dispatchers and an application scope

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> [6 - Testing](HILT_6_TESTING.md) -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> **8 - Coroutines** -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

Part 3's exact-key rule applies directly to coroutines: IO, Default, and Main are all
`CoroutineDispatcher`, so qualifiers distinguish them. Injection also makes their execution
policy replaceable in tests. This part adds an asynchronous adapter around the existing
synchronous repository; it does not break the `WordManager` or `ContentProvider` paths built in
earlier parts.

---

## The one mental model to keep

**A dispatcher decides where a coroutine executes; its `Job` and scope decide who owns and
cancels it. Inject execution policy, and choose ownership deliberately.**

- `withContext(ioDispatcher)` runs its block in the injected dispatcher context without detaching
  the work: the caller suspends, structured ownership remains intact, and cancellation propagates.
- `viewModelScope` owns work for one ViewModel.
- an injected application scope can outlive screens, but it still dies with the app process.
- `@IoDispatcher`, `@DefaultDispatcher`, and `@MainDispatcher` are different Dagger keys even
  though their Kotlin type is identical.

Tests become deterministic only when the injected `TestDispatcher`s and `runTest` use the same
`TestCoroutineScheduler`.

---

## 1. Add the coroutine libraries

The companion app keeps the target version in its catalog:

```toml
# gradle/libs.versions.toml
[versions]
coroutines = "1.9.0"

[libraries]
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

The Android artifact supplies `Dispatchers.Main`; the test artifact supplies virtual-time
dispatchers and schedulers.

---

## 2. Qualify every dispatcher key

The four qualifiers support provider methods, constructor/function parameters, injected fields,
and Kotlin property getters:

```kotlin
package com.example.myapp.core.di

import javax.inject.Qualifier

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
```

`ApplicationScope` is a qualifier: it distinguishes one `CoroutineScope` key from another. It is
not a Dagger scope annotation and does not cache anything by itself.

Provide the three dispatcher keys from the application component:

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

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
}
```

The return type alone is not enough: type plus qualifier is the key. These providers do not need
a scope because each call returns a standard dispatcher object. Do not name the Default provider
`default`; Dagger generates Java, where that name is a keyword.

---

## 3. Add an asynchronous boundary without rewriting old APIs

The repository built in Parts 2-7 stays synchronous. `WordManager`, ViewModel initialization, and
`ContentProvider.query()` all use that contract. A small adapter adds the suspend boundary and
requests the existing fancy repository key:

```kotlin
package com.example.myapp.core

import com.example.myapp.core.di.FancyWords
import com.example.myapp.core.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AsyncWordsLoader @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @param:FancyWords private val repository: WordsRepository,
) {
    suspend fun load(): List<String> = withContext(ioDispatcher) {
        repository.getWords()
    }
}
```

`@param:` is appropriate here because these are primary-constructor properties. The loader never
mentions `Dispatchers.IO`, so a test can replace its execution policy. `withContext` does not make
detached fire-and-forget work: the caller suspends until it finishes, and cancellation still
propagates.

The in-memory repository does not require IO threads in real life; it stands in for a blocking
disk/network implementation while keeping the running example small.

---

## 4. Provide one application-owned scope

The scope binding uses the qualified Default dispatcher and a `SupervisorJob`:

```kotlin
package com.example.myapp.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
```

Here `@ApplicationScope` selects the key; `@Singleton` caches one scope in each
`SingletonComponent`. A qualifier on an ordinary `@Provides` function parameter is written
without `@param:`--that use-site prefix is only valid for primary-constructor parameters.

The running app keeps observable sync state in a singleton manager and returns the launched job:

```kotlin
package com.example.myapp.core

import com.example.myapp.core.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class WordSyncManager @Inject constructor(
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val loader: AsyncWordsLoader,
) {
    private val mutableState = MutableStateFlow(WordSyncState())
    val state: StateFlow<WordSyncState> = mutableState.asStateFlow()

    private val syncLock = Any()
    private var activeJob: Job? = null

    fun syncInBackground(): Job = synchronized(syncLock) {
        activeJob?.takeIf { it.isActive } ?: appScope.launch {
            mutableState.update { it.copy(status = WordSyncStatus.RUNNING) }
            val words = loader.load()
            mutableState.update {
                WordSyncState(
                    status = WordSyncStatus.COMPLETE,
                    completedSyncs = it.completedSyncs + 1,
                    words = words,
                )
            }
        }.also {
            activeJob = it
        }
    }
}

data class WordSyncState(
    val status: WordSyncStatus = WordSyncStatus.IDLE,
    val completedSyncs: Int = 0,
    val words: List<String> = emptyList(),
)

enum class WordSyncStatus { IDLE, RUNNING, COMPLETE }
```

Repeated calls while a sync is active return the same job, so the singular status cannot report
`COMPLETE` while another accepted sync is still running.

A `SupervisorJob` prevents one failed child from cancelling its siblings. It does not catch,
consume, or report that failure for you; production launch code still needs an error policy.
Parent-scope cancellation cancels its children.

This scope is process-lifetime best effort. Hilt does not automatically call `cancel()` on a
`CoroutineScope` binding, and process death ends all of its work. Use WorkManager (Part 9) for work
that must be rescheduled after process loss.

---

## 5. Keep screen work owned by the ViewModel

The screen's one-shot load belongs to its ViewModel, while the sync deliberately belongs to the
application scope:

```kotlin
package com.example.myapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapp.core.AsyncWordsLoader
import com.example.myapp.core.WordSyncManager
import com.example.myapp.core.di.MainDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch

@HiltViewModel
class Part8ViewModel @Inject constructor(
    private val loader: AsyncWordsLoader,
    private val syncManager: WordSyncManager,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    fun loadWords() {
        viewModelScope.launch(mainDispatcher) {
            val words = loader.load()
            // Publish words to UI state; loader runs in @IoDispatcher and returns here.
        }
    }

    fun syncInApplicationScope() {
        syncManager.syncInBackground()
    }
}
```

Passing `mainDispatcher` to `launch` changes the dispatcher for this child while
`viewModelScope`'s job still owns and cancels it. The app summarizes the routes as
`Main -> IO - application scope -> Default -> IO` and labels their sections
`@MainDispatcher -> @IoDispatcher` and `@ApplicationScope on @DefaultDispatcher`. Expanded, the
dependency paths are:

```text
ViewModel job:     @MainDispatcher -> AsyncWordsLoader -> @IoDispatcher
Application job:  @ApplicationScope on @DefaultDispatcher -> AsyncWordsLoader -> @IoDispatcher
```

An injected `@MainDispatcher CoroutineDispatcher` is a Dagger key; it does **not** replace the
global `Dispatchers.Main` used as the default by APIs such as `viewModelScope`. A plain JVM test
whose code relies on that default (or directly uses `Dispatchers.Main`) must call
`Dispatchers.setMain(testDispatcher)` before use and `Dispatchers.resetMain()` afterward. This
app passes the injected dispatcher to `launch`, so that child does not rely on the scope's default
dispatcher. Robolectric and Android provide a main environment, but that alone does not put Main
work on `runTest`'s virtual scheduler.

---

## 6. Give every test dispatcher one scheduler

The JVM source set replaces the production dispatcher module. It creates one scheduler and one
standard test dispatcher **through providers**, so each Hilt test component gets fresh virtual
time. All three qualified keys alias that same dispatcher:

```kotlin
package com.example.myapp.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CoroutinesModule::class],
)
object TestCoroutinesModule {

    @Provides
    @Singleton
    fun provideTestCoroutineScheduler(): TestCoroutineScheduler = TestCoroutineScheduler()

    @Provides
    @Singleton
    fun provideTestDispatcher(
        scheduler: TestCoroutineScheduler,
    ): TestDispatcher = StandardTestDispatcher(scheduler)

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher
}
```

Do not initialize `StandardTestDispatcher()` or `UnconfinedTestDispatcher()` in an object property:
that silently creates a scheduler unrelated to a later plain `runTest {}` and shares it across
test cases.

The actual graph test injects the dispatcher and enters `runTest` with it:

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordSyncManager
import com.example.myapp.core.WordSyncStatus
import com.example.myapp.core.di.ApplicationScope
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class CoroutinesGraphTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var scheduler: TestCoroutineScheduler
    @Inject lateinit var testDispatcher: TestDispatcher
    @Inject lateinit var syncManager: WordSyncManager

    @field:ApplicationScope
    @Inject
    lateinit var appScope: CoroutineScope

    @Before fun setUp() = hiltRule.inject()
    @After
    fun tearDown() {
        if (::appScope.isInitialized) appScope.cancel()
    }

    @Test
    fun applicationScopeUsesTheControlledScheduler() = runTest(testDispatcher) {
        assertSame(scheduler, testScheduler)

        val job = syncManager.syncInBackground()
        assertFalse(job.isCompleted)

        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(WordSyncStatus.COMPLETE, syncManager.state.value.status)
    }
}
```

`StandardTestDispatcher` queues work. `runCurrent()`, `advanceTimeBy(...)`, or
`advanceUntilIdle()` drives work on the shared scheduler; `runTest` is deterministic, not simply
"synchronous." The application scope keeps its production-style `SupervisorJob`, so it is not a
child of `runTest`'s `TestScope`. The test drives their shared scheduler explicitly and cancels the
application scope in `@After`.

---

## Coroutines -- error -> cause

| Error | Cause |
|---|---|
| `[Dagger/DuplicateBindings] kotlinx.coroutines.CoroutineDispatcher is bound multiple times:` | Two reachable providers supply the same unqualified dispatcher key; restore distinct qualifiers on providers and request those exact keys at consumers |
| `[Dagger/MissingBinding] @IoDispatcher kotlinx.coroutines.CoroutineDispatcher cannot be provided...` | The injection site requests a qualified key that no installed module supplies, often because the test replacement omitted one key |
| `Detected use of different schedulers. If you need to use several test coroutine dispatchers, create one TestCoroutineScheduler and pass it to each of them.` | Test dispatchers were constructed with different schedulers; inject one scheduler and pass it to every dispatcher and to `runTest` |
| Virtual-time test stays queued, hangs, or observes `IDLE` | The work uses a different scheduler/real dispatcher, or a `StandardTestDispatcher` was never driven; share the scheduler and use `runCurrent`/`advanceUntilIdle`/`join` |
| `@param: annotations can only be applied to primary constructor parameters.` | Used `@param:DefaultDispatcher` on an ordinary `@Provides` function parameter; use bare `@DefaultDispatcher` there |
| `[Dagger/IncompatiblyScopedBindings] ...` on the application scope | A reachable `@Singleton` scope binding is installed in a component that does not own `@Singleton`; install it in `SingletonComponent` |
| `IllegalStateException: Module with the Main dispatcher is missing...` | Code used global `Dispatchers.Main` without a platform Main implementation; on Android add the matching `kotlinx-coroutines-android` artifact |
| `IllegalStateException: Module with the Main dispatcher had failed to initialize` | A plain JVM test found Android Main but cannot initialize its real looper; use Robolectric or set/reset Main around the test. Replacing `@MainDispatcher` alone does not set the global dispatcher |
| KSP: `The name 'default' cannot be used because it is a Java keyword` | A `@Provides`/`@Binds` method is named `default`; Dagger emits Java, so rename it, for example `provideDefaultDispatcher` |

---

## Where to go next

**[9 - WorkManager](HILT_9_WORKMANAGER.md)** -- inject dependencies into work that Android can
reschedule after the app process disappears.

## Quick reference

| I want to... | Do this |
|---|---|
| Make blocking execution replaceable | Inject `@IoDispatcher CoroutineDispatcher`; call `withContext(ioDispatcher)` |
| Distinguish IO, Default, and Main | Define one qualifier per `CoroutineDispatcher` key |
| Own work for one ViewModel | Launch from `viewModelScope`; an injected dispatcher changes execution, not ownership |
| Own best-effort process-lifetime work | Inject a `@Singleton @ApplicationScope CoroutineScope` and return/track launched jobs |
| Survive process loss | Use WorkManager, not an in-memory application scope |
| Control virtual time | Provide one `TestCoroutineScheduler`, build every `TestDispatcher` from it, and run/advance that scheduler |
| Replace injected Main vs global Main | `@MainDispatcher` replaces a Dagger key; `Dispatchers.setMain/resetMain` changes the global test dispatcher |
