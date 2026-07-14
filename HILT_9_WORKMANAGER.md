# 9. WorkManager — injecting into a Worker

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → **9 · WorkManager** → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

A Worker is created by WorkManager, so normal constructor injection cannot supply every argument.
AndroidX Hilt bridges that framework-owned construction to the same application graph used by the
rest of the app. This part turns Part 8's asynchronous word loader into observable, persisted work:
the screen enqueues a request, watches its WorkInfo, and displays the words returned as output Data.

---

## The one mental model to keep

**WorkManager owns the request and supplies the runtime arguments; Hilt supplies the graph
dependencies; the Worker must not finish until its work has actually finished.**

- WorkManager supplies the Context and WorkerParameters for each attempt.
- Hilt supplies dependencies reachable from SingletonComponent.
- the generated assisted factory joins those two sets of arguments.
- WorkManager persists scheduling and results, not a live Worker object or coroutine.

For a CoroutineWorker, call suspending work directly from doWork(). Launching it into Part 8's
application scope and immediately returning success would tell WorkManager that unfinished work is
complete.

---

## 1. Add WorkManager and both Hilt processors

AndroidX Hilt's WorkManager integration has a processor separate from Dagger/Hilt's processor.
The companion app keeps both versions explicit:

```toml
# gradle/libs.versions.toml
[versions]
hilt = "2.60"
androidxHilt = "1.3.0"
workManager = "2.10.0"

[libraries]
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHilt" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidxHilt" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "workManager" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)

    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
```

The processors cooperate:

- androidx.hilt:hilt-compiler recognizes @HiltWorker and generates the worker-specific assisted
  factory interface plus a class-name map contribution.
- hilt-android-compiler generates the assisted-factory implementation and integrates its normal
  dependencies into the Hilt graph.

Keep the Dagger processor from Part 1. Adding the AndroidX processor does not replace it. The
AndroidX processor is needed on main ksp because the Worker is a main-source class; it does not
also need kspTest or kspAndroidTest unless a Worker is declared in those source sets.

---

## 2. Split framework arguments from graph dependencies

Part 8 already put blocking repository access behind AsyncWordsLoader and @IoDispatcher. Inject
that loader rather than asking for an unqualified WordsRepository: Part 3's graph contains only
@BasicWords WordsRepository and @FancyWords WordsRepository keys.

```kotlin
package com.example.myapp.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapp.core.AsyncWordsLoader
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWordsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val loader: AsyncWordsLoader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val words = loader.load()
        return Result.success(
            workDataOf(OUTPUT_WORDS to words.toTypedArray()),
        )
    }

    companion object {
        const val OUTPUT_WORDS = "output_words"
    }
}
```

The constructor contract is strict:

- exactly one non-private constructor is annotated with @AssistedInject, not @Inject;
- the first assisted parameter is the exact, unqualified Context type;
- the second is the exact, unqualified WorkerParameters type;
- both have @Assisted and neither is wrapped in Lazy or Provider;
- every other parameter is a normal graph dependency;
- a nested Worker must be a static nested class—a Kotlin inner class is not valid.

Do not write an @AssistedFactory for this Worker. The AndroidX processor generates the
WorkManager-shaped factory contract.

Only dependencies available from `SingletonComponent` can be injected into a `@HiltWorker`.
`AsyncWordsLoader` is unscoped and its dependencies are reachable from that component, so it is
valid; `@Singleton` and compatible `@Reusable` bindings can also be available there. An
`@ActivityScoped WordSession`, `@ViewModelScoped` object, Activity, or ViewModel is not.

doWork() awaits loader.load(), whose withContext(@IoDispatcher) keeps the work structured.
CoroutineWorker otherwise uses its configured worker coroutine context—Dispatchers.Default by
default in this setup. The output is intentionally small: WorkManager Data is for compact,
persistable values, not large payloads.

---

## 3. What the generated factory actually does

For this Worker, the verified KSP output includes:

```text
SyncWordsWorker_AssistedFactory.java       AndroidX Hilt factory contract
SyncWordsWorker_HiltModule.java            class-name map contribution
SyncWordsWorker_AssistedFactory_Impl.java  Dagger implementation
SyncWordsWorker_Factory.java               supplies AsyncWordsLoader
```

The generated Hilt module contributes a WorkerAssistedFactory under this string key:

```text
com.example.myapp.work.SyncWordsWorker
```

HiltWorkerFactory is the delegating lookup factory that receives that map. When WorkManager asks
for the class name, it obtains the worker-specific factory, passes Context and WorkerParameters,
and Dagger supplies AsyncWordsLoader. HiltWorkerFactory is therefore not itself the generated
assisted factory.

If no class-name entry exists, HiltWorkerFactory returns null deliberately so WorkManager can try
its reflection fallback. A null return is delegation, not by itself an error. Reflection then
fails for this Worker because its constructor also needs AsyncWordsLoader.

---

## 4. Give WorkManager the Hilt factory

The application has to expose a custom WorkManager Configuration:

```kotlin
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

Use a getter, as shown, rather than constructing an eager property from workerFactory before Hilt
has injected the Application. Configuration.Provider is a Kotlin property override in the
targeted WorkManager 2.10.0 API.

Next remove only WorkManager's App Startup metadata. Keep the shared App Startup provider so
other initializers can still use it:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application
        android:name=".MyApplication"
        ...>

        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.work.WorkManagerInitializer"
                android:value="androidx.startup"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

Without that removal, App Startup initializes WorkManager with the default configuration before
the application provider can install HiltWorkerFactory. With it removed,
WorkManager.getInstance(context) initializes on demand and obtains the configuration from
MyApplication.

Do not additionally call WorkManager.initialize() in this provider-based setup. Manual
initialization is a valid alternative when done exactly once, but mixing initialization paths
causes duplicate-initialization failures.

---

## 5. Inject WorkManager, enqueue, and observe the result

Expose WorkManager as one application-graph binding:

```kotlin
package com.example.myapp.core.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)
}
```

The running app injects Provider<WorkManager>. It is still the same Hilt binding; Provider merely
defers the getInstance() call until the user enqueues work. That deferral also lets an
instrumentation test install test WorkManager before its first use.

```kotlin
package com.example.myapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.myapp.core.di.MainDispatcher
import com.example.myapp.work.SyncWordsWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@HiltViewModel
class Part9ViewModel @Inject constructor(
    private val workManagerProvider: Provider<WorkManager>,
    @param:MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    var workerState: WorkInfo.State? by mutableStateOf(null)
        private set

    var workerWords: List<String> by mutableStateOf(emptyList())
        private set

    private var observationJob: Job? = null

    fun enqueueSync() {
        observationJob?.cancel()

        val workManager = workManagerProvider.get()
        val request = OneTimeWorkRequestBuilder<SyncWordsWorker>().build()

        workerState = WorkInfo.State.ENQUEUED
        workerWords = emptyList()
        observationJob = viewModelScope.launch(mainDispatcher) {
            val finishedInfo = workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .onEach { info ->
                    workerState = info.state
                }
                .first { info -> info.state.isFinished }
            workerWords = finishedInfo.outputData
                .getStringArray(SyncWordsWorker.OUTPUT_WORDS)
                ?.toList()
                .orEmpty()
        }

        workManager.enqueue(request)
    }
}
```

enqueue() means the request was submitted to WorkManager; it does not mean doWork() has finished
or will run immediately. The screen follows the exact request ID until a finished state, then
reads its output. A fast request can finish between emissions, so observers are not guaranteed to
display every transient state such as RUNNING:

```text
@HiltWorker → HiltWorkerFactory → AsyncWordsLoader
Worker state: succeeded
Worker words: dragonfruit, kumquat, persimmon
```

Production sync usually needs a unique-work policy so repeated UI events or process recreation do
not create unwanted duplicate work. This small example uses a fresh request ID so each button
press visibly demonstrates a new assisted Worker instance.

---

## 6. WorkManager ownership is not application-scope ownership

Compare the two Part 8/9 paths:

| Application scope | WorkManager |
|---|---|
| Holds an in-memory Job and state | Persists the WorkRequest and WorkInfo in its database |
| Work ends when the process ends | Eligible unfinished work can be rescheduled after ordinary process loss or reboot |
| The same coroutine does the work | A later attempt gets a new Worker instance and coroutine |
| Starts when app code launches it | Runs when WorkManager and system constraints allow |
| Hilt directly creates the manager | WorkManager creates the Worker through HiltWorkerFactory |

Persistence does not mean a coroutine literally continues through process death. The original
process, Worker, and coroutine disappear; WorkManager can reconstruct another attempt from its
database.

Return Result.success() only after the operation completes. Return Result.retry() to request
another attempt using backoff, or Result.failure() for terminal failure. An uncaught exception
does not request retry for you, and cancellation should not be swallowed. Because an attempt may
run again, make real sync operations idempotent.

An Android force-stop is stronger than ordinary process loss: the OS suppresses scheduled work
until the app becomes active again. Do not use force-stop behavior as the model for normal
WorkManager rescheduling.

---

## 7. Test the factory and the scheduling boundary separately

A focused Robolectric test builds the Worker through HiltWorkerFactory and calls its implementation
directly:

```kotlin
package com.example.myapp

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.myapp.work.SyncWordsWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
class SyncWordsWorkerTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun hiltFactoryBuildsWorkerAndInjectedLoaderProducesOutput() =
        runTest(testDispatcher) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val worker = TestListenableWorkerBuilder<SyncWordsWorker>(context)
                .setWorkerFactory(workerFactory)
                .build()

            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            val success = result as ListenableWorker.Result.Success
            assertEquals(
                listOf("dragonfruit", "kumquat", "persimmon"),
                success.outputData
                    .getStringArray(SyncWordsWorker.OUTPUT_WORDS)
                    ?.toList(),
            )
        }
}
```

runTest(testDispatcher) uses the scheduler injected by Part 8's test module, so
AsyncWordsLoader's injected dispatcher and the test body share virtual time. The real test also
checks the fancy repository's request count.

This test proves generated assisted construction, graph injection, Worker logic, and output. It
does not use WorkManager's database, constraints, application configuration, or manifest
initialization.

The device test covers real enqueue/observe behavior. Its process uses HiltTestApplication rather
than MyApplication, and HiltTestApplication does not implement Configuration.Provider. Because
the main manifest removed the default initializer, initialize test WorkManager before the UI first
calls Provider<WorkManager>. Include the injected Hilt factory:

```kotlin
val executor = SynchronousExecutor()
val configuration = Configuration.Builder()
    .setExecutor(executor)
    .setTaskExecutor(executor)
    .setWorkerFactory(workerFactory)
    .build()

WorkManagerTestInitHelper.initializeTestWorkManager(
    context,
    configuration,
)
```

The companion test then clicks the enqueue button, waits for Worker state: succeeded, and checks
Worker words: device-fancy-word. A separate normal-APK cold-start check is still needed to exercise
the production MyApplication and manifest path; that path produces the three production fancy
words.

---

## WorkManager — error → cause

| Error | Cause |
|---|---|
| Unresolved `HiltWorker`/`HiltWorkerFactory` imports, or processor: `To use @HiltWorker you must add the 'work' artifact…` | Missing `implementation("androidx.hilt:hilt-work:1.3.0")` |
| `Worker constructor should be annotated with @AssistedInject instead of @Inject.` | A `@HiltWorker` constructor uses ordinary `@Inject` |
| `@HiltWorker annotated class should contain exactly one @AssistedInject annotated constructor.` | The Worker has no assisted constructor or has more than one |
| `@AssistedInject annotated constructors must not be private.` | The Worker's assisted constructor is private |
| `Missing @Assisted annotation in param 'context'.` | `Context` or `WorkerParameters` is not marked `@Assisted` |
| `The 'Context' parameter must be declared before the 'WorkerParameters'…` | The exact assisted `Context` and `WorkerParameters` are reversed; restore that order |
| `[Dagger/MissingBinding] …WordsRepository cannot be provided…` | The Worker requested the nonexistent unqualified repository key; request `@BasicWords`/`@FancyWords` or inject `AsyncWordsLoader` |
| `[Dagger/IncompatiblyScopedBindings] …SingletonC scoped with @Singleton may not reference bindings with different scopes…` | The Worker made an `@ActivityScoped`/`@ViewModelScoped` binding reachable from `SingletonComponent`; inject a binding that is available from `SingletonComponent` instead |
| Runtime `Could not instantiate …SyncWordsWorker`, usually followed by `NoSuchMethodException` / `Could not create Worker` | The AndroidX processor did not generate the map entry, or WorkManager was not given `HiltWorkerFactory`; reflection cannot call this Worker's three-argument constructor |
| `WorkManager is not initialized properly…` | The default initializer was removed, but the Application is neither a `Configuration.Provider` nor manually initializing WorkManager once |
| `WorkManager is already initialized…` | Two initialization paths ran: default plus manual, on-demand plus later manual, or manual initialization twice |
| `Data cannot occupy more than 10240 bytes when serialized` | Input/output `Data` exceeded WorkManager's size limit; persist the payload elsewhere and pass a compact key |

---

## Where to go next

**[10 · Hilt in a multi-module project](HILT_10_MULTIMODULE.md)** — move the implementation and
bindings into library modules while keeping one application-wide Hilt graph.

## Quick reference

| I want to… | Do this |
|---|---|
| Inject graph dependencies into a Worker | `@HiltWorker` plus one `@AssistedInject` constructor |
| Accept WorkManager's runtime arguments | First `@Assisted Context`, then `@Assisted WorkerParameters`; exact and unqualified |
| Generate all factory glue | Keep both `hilt-android-compiler` and `androidx.hilt:hilt-compiler` on main `ksp` |
| Make Hilt construct the Worker | Inject `HiltWorkerFactory` into `Configuration.Provider` and remove only `WorkManagerInitializer` |
| Inject a Worker dependency | Make its binding available and reachable from `SingletonComponent` |
| Run suspending work correctly | Await it inside `CoroutineWorker.doWork()`; return `Result` only after completion |
| Observe completion and output | Follow `WorkInfo` for the request ID; read its output `Data` after a finished state |
| Test factory construction | `TestListenableWorkerBuilder.setWorkerFactory(hiltWorkerFactory)` |
| Test real enqueue on a Hilt device test | Initialize test WorkManager with the injected factory before its first use |
| Survive ordinary process loss | Persist a `WorkRequest`; do not launch required work only in an application scope |
