# Practice 9. Joining WorkManager construction to the Hilt graph

*Tutorial: [9 · WorkManager](HILT_9_WORKMANAGER.md) · **Practice 9 of 10***

Start from the Part 9 state of [`hilt-practice-app/`](hilt-practice-app/). It has one
`@HiltWorker` that awaits Part 8's `AsyncWordsLoader`, returns the loaded words as output
`Data`, and exposes the request's terminal `WorkInfo` in the UI. Work on a throwaway branch:
several exercises deliberately break generated code or runtime initialization. Restore the green
checkpoint after every experiment.

## Self-check questions

1. Which `SyncWordsWorker` constructor arguments come from WorkManager, which come from Hilt, and
   from which Hilt component must the graph dependencies be reachable?
2. Why does this integration need both KSP processors, what does each generate, and why is
   `HiltWorkerFactory` not the worker-specific assisted factory?
3. Why must the manifest remove `WorkManagerInitializer` when `MyApplication` supplies
   `HiltWorkerFactory` through `Configuration.Provider`?
4. Why must `doWork()` await `AsyncWordsLoader.load()` instead of launching the load in
   `@ApplicationScope` and immediately returning `Result.success()`?
5. What does the direct Robolectric worker test prove, what additional boundary does the device
   enqueue test prove, and which production boundary still needs a normal APK smoke test?

Answer these before looking at the final section.

## Practical tasks

### 1. Trace and verify the complete request path

Trace one press of **Enqueue injected worker**:

```text
Part9ViewModel
  → Provider<WorkManager>.get()
  → OneTimeWorkRequest<SyncWordsWorker>
  → WorkManager database and scheduler
  → HiltWorkerFactory class-name map
  → SyncWordsWorker_AssistedFactory
      WorkManager: Context + WorkerParameters
      Hilt SingletonComponent: AsyncWordsLoader
  → @IoDispatcher
  → @FancyWords WordsRepository
  → Result.success(output Data)
  → WorkInfo flow
  → Worker words on screen
```

For each arrow, identify:

- the object that owns the next step;
- whether the edge is ordinary code, persisted WorkManager state, or a Dagger key;
- whether it is checked at compile time or can fail only at runtime; and
- what is lost, retained, or reconstructed if the process ends.

Run these commands separately from `hilt-practice-app/`:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am force-stop com.example.myapp.practice
adb shell am start -W -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** all eleven JVM tests and the one cumulative device test pass. The instrumented test's
`@TestInstallIn` repository replacement produces `Worker state: succeeded` and
`Worker words: device-fancy-word`. Reinstalling and
cold-starting the normal app exercises `MyApplication : Configuration.Provider`; pressing the
button produces `dragonfruit, kumquat, persimmon`.

Do not treat `enqueue()` returning as completion. The UI follows `WorkInfo` until a terminal
state. A fast worker may finish between emissions, so seeing every transient state such as
`RUNNING` is not guaranteed.

### 2. Inspect the two-processor bridge

Generate main KSP output and list only this worker's generated files:

```bash
./gradlew :app:kspDebugKotlin
rg --files app/build/generated/ksp/debug/java/com/example/myapp/work | sort
```

Open these four files:

```text
SyncWordsWorker_AssistedFactory.java
SyncWordsWorker_HiltModule.java
SyncWordsWorker_AssistedFactory_Impl.java
SyncWordsWorker_Factory.java
```

In the first two, find `@Generated("androidx.hilt.AndroidXHiltProcessor")`. Confirm that:

- the assisted-factory interface extends
  `WorkerAssistedFactory<SyncWordsWorker>`;
- the generated module is installed in `SingletonComponent`;
- `@IntoMap` uses the exact string key
  `com.example.myapp.work.SyncWordsWorker`; and
- the map value is a `WorkerAssistedFactory`, not the Worker itself.

Then inspect the Dagger-generated implementation and factory. Locate:

```java
create(Context p0, WorkerParameters p1)
```

and the provider-backed `AsyncWordsLoader` argument supplied when the Worker is constructed.

**Check:** the AndroidX processor defines the WorkManager-shaped contract and map binding; the
Dagger processor implements that contract and supplies graph dependencies. At runtime,
`HiltWorkerFactory` looks up this map by class name and delegates to the generated assisted
factory.

### 3. Break the assisted-constructor contract one rule at a time

Make only one edit at a time in `SyncWordsWorker.kt`, run
`./gradlew :app:assembleDebug`, record the diagnostic, and restore the file.

First replace `@AssistedInject` with `@Inject`. Update the import only as needed.

**Check:**

```text
Worker constructor should be annotated with @AssistedInject instead of @Inject.
```

Restore `@AssistedInject`, then remove `@Assisted` from `context`.

**Check:**

```text
Missing @Assisted annotation in param 'context'.
```

Restore the marker, then reverse the two framework parameters:

```kotlin
@Assisted params: WorkerParameters,
@Assisted context: Context,
```

**Check:**

```text
The 'Context' parameter must be declared before the 'WorkerParameters' in the
@AssistedInject constructor of a @HiltWorker annotated class.
```

Finally restore the order and make the assisted constructor private:

```kotlin
class SyncWordsWorker @AssistedInject private constructor(
```

**Check:**

```text
@AssistedInject annotated constructors must not be private.
```

Restore the public constructor and rebuild. Explain why writing a hand-made
`@AssistedFactory` is unnecessary here even though Part 5 used one for a runtime ViewModel
argument.

### 4. Prove exact keys and component visibility

Temporarily add an unused plain repository parameter to the Worker:

```kotlin
private val repository: WordsRepository,
```

Import `com.example.myapp.core.WordsRepository` and build.

**Check:** Dagger reports a missing unqualified `WordsRepository`. The graph contains only
`@BasicWords WordsRepository` and `@FancyWords WordsRepository`; a type without the matching
qualifier is a different key.

One valid repair would be:

```kotlin
@param:FancyWords private val repository: WordsRepository,
```

but the production Worker should keep `AsyncWordsLoader`: it reuses the qualified repository and
preserves Part 8's injected IO boundary. Remove the temporary repository parameter.

Next add this parameter and import:

```kotlin
import com.example.myapp.core.WordSession

private val session: WordSession,
```

Run `./gradlew :app:assembleDebug`.

**Check:** making the Worker factory reachable from `SingletonComponent` also makes the
`@ActivityScoped WordSession` reachable there, so Dagger reports:

```text
[Dagger/IncompatiblyScopedBindings] ...SingletonC scoped with @Singleton
may not reference bindings with different scopes:
    @ActivityScoped class com.example.myapp.core.WordSession
```

The generated trace should include
`SyncWordsWorker → SyncWordsWorker_AssistedFactory → SyncWordsWorker_HiltModule.bind`. This is
compile-time proof that a Worker may inject bindings available from `SingletonComponent`—the app
uses an unscoped loader and singleton repository—but not an Activity/ViewModel-owned object. A
compatible `@Reusable` binding can also be available from `SingletonComponent`.

Remove `WordSession` and finish this task with a green build.

### 5. Separate processor generation from runtime factory installation

In `app/build.gradle.kts`, temporarily remove only:

```kotlin
ksp(libs.androidx.hilt.compiler)
```

Clean before building so stale generated map files cannot hide the experiment:

```bash
./gradlew clean :app:assembleDebug
```

**Check:** the source can still compile, and Dagger can still process the assisted constructor, but
`SyncWordsWorker_HiltModule.java` and the AndroidX worker-factory contract are absent. The
injected `HiltWorkerFactory` therefore has no entry for this class.

Install and cold-start that intentionally broken build. Clear logs before pressing the Part 9
button:

```bash
./gradlew :app:installDebug
adb shell am force-stop com.example.myapp.practice
adb logcat -c
adb shell am start -W -n com.example.myapp.practice/com.example.myapp.MainActivity
```

Scroll to the button, press it, wait for the failed terminal state, and then inspect the relevant
logs:

```bash
adb logcat -d | rg 'SyncWordsWorker|Could not instantiate|Could not create Worker|NoSuchMethodException'
```

**Check:** `HiltWorkerFactory` returns `null` to delegate an unknown class; that return is not
itself an error. WorkManager's reflection fallback then looks for a
`(Context, WorkerParameters)` constructor, logs that it cannot instantiate/create this
three-argument Worker, and the request fails.

Restore the AndroidX processor and run a clean build:

```bash
./gradlew clean :app:assembleDebug :app:testDebugUnitTest
```

Now remove only `.setWorkerFactory(workerFactory)` from `SyncWordsWorkerTest` and run:

```bash
./gradlew :app:testDebugUnitTest --tests com.example.myapp.SyncWordsWorkerTest
```

**Check:** `TestListenableWorkerBuilder` defaults to reflection, so construction fails before
`doWork()` can produce output. Restore the injected factory. The processor creates the map;
passing `HiltWorkerFactory` selects that map at runtime. Both are required.

### 6. Exercise each initialization boundary

First remove the manifest block that removes
`androidx.work.WorkManagerInitializer`, but keep
`MyApplication : Configuration.Provider`. Build, install, cold-start, and press the button.

**Check:** App Startup initializes WorkManager with its default configuration before the provider
path can install `HiltWorkerFactory`, so the injected Worker falls through to reflection and fails
at runtime. The initializer's own message is DEBUG-level, below the default INFO minimum; the
reflection failure is the observable check unless the configuration enables DEBUG logging.

Restore the manifest removal. Then temporarily change `MyApplication` back to a plain
`@HiltAndroidApp Application`: remove `Configuration.Provider`, the factory field, and the
configuration property. Build, install, cold-start, and press the button.

**Check:** on-demand `WorkManager.getInstance(context)` now reports that WorkManager is not
initialized properly: the default initializer is disabled and the Application supplies no custom
configuration. Manual `WorkManager.initialize()` exactly once is an alternative, but do not mix
it with this tutorial's provider-based path.

Restore `MyApplication` and the manifest. In the device test, identify why
`WorkManagerTestInitHelper` is still needed:

```text
production process:     MyApplication implements Configuration.Provider
instrumentation process: HiltTestApplication does not
```

Temporarily remove `.setWorkerFactory(workerFactory)` from the device test's
`Configuration.Builder` and run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

**Check:** the request reaches test WorkManager, but the default test factory cannot construct the
injected Worker. Restore the factory and confirm the device test again displays
`device-fancy-word`.

Summarize the three test/runtime boundaries:

| Check | Proves | Does not prove |
|---|---|---|
| Direct Robolectric Worker test | generated factory, Hilt graph, Worker logic, output | database scheduling, manifest, production Application |
| Device enqueue test | test WorkManager database/scheduler, Hilt factory, fake device graph, UI observation | production `MyApplication` configuration |
| Normal APK cold-start smoke test | production Application, manifest merge, on-demand initialization, real graph | process-death recovery by itself |

### 7. Design the sync for retries and duplicate requests

The tutorial intentionally enqueues a fresh request for every button press so the assisted
construction is visible. For a real sync, write down an explicit policy for all of these cases:

1. the user presses twice while one request is unfinished;
2. the process dies after the server update but before `Result.success()` is recorded;
3. the Worker receives cancellation;
4. a transient network error occurs; and
5. the user force-stops the app.

Prototype unique work with `enqueueUniqueWork` and compare:

- `ExistingWorkPolicy.KEEP`: retain existing unfinished work;
- `ExistingWorkPolicy.REPLACE`: cancel/replace existing work; and
- a plain fresh request: allow independent attempts.

If `KEEP` ignores a newly built request, do not keep observing only that ignored request's UUID.
Observe the unique-work chain/name or the accepted request instead. This is the same ownership
rule in another form: follow the state WorkManager actually owns.

Restore Part 9's fresh-request implementation after comparing the policies.

For the Worker result policy:

- return `success()` only after the idempotent operation and output are complete;
- return `retry()` only for a condition that should use WorkManager backoff;
- return `failure()` for terminal input/business failure;
- let cancellation propagate; and
- store large payloads outside WorkManager and pass a compact key in `Data`.

To reproduce the Data boundary, temporarily replace the output with:

```kotlin
workDataOf(OUTPUT_WORDS to arrayOf("x".repeat(11_000)))
```

Run the focused Worker test.

**Check:** WorkManager reports
`Data cannot occupy more than 10240 bytes when serialized`. Restore the real word array.

Finally explain the lifecycle difference without saying that a coroutine survives process death:
WorkManager persists the request and can create a new Worker attempt later. The old Worker object
and coroutine are gone. A force-stop is stronger than ordinary process loss and suppresses
scheduled work until the app becomes active again.

Finish with all eleven JVM tests, the one device test, a normal reinstall, and a cold launch green.

---

## Answers to the self-check questions

1. WorkManager supplies the exact, unqualified `@Assisted Context` followed by
   `@Assisted WorkerParameters` for each attempt. Hilt supplies `AsyncWordsLoader` and its
   transitive dependencies. Those normal bindings must be available and reachable from
   `SingletonComponent`; unscoped, `@Singleton`, and compatible `@Reusable` bindings can be,
   while an Activity/ViewModel binding is unavailable there.
2. `androidx.hilt:hilt-compiler` recognizes `@HiltWorker`, generates the worker-specific
   assisted-factory interface, and contributes it to a class-name map.
   `hilt-android-compiler` generates the Dagger implementation and supplies graph dependencies.
   `HiltWorkerFactory` is the delegating lookup factory that uses the map; the generated
   `SyncWordsWorker_AssistedFactory` creates this specific Worker.
3. The App Startup initializer eagerly calls `WorkManager.initialize()` with a default
   configuration, which has no Hilt factory. Removing only that initializer enables on-demand
   initialization; `WorkManager.getInstance(context)` can then obtain the custom configuration
   from `MyApplication`. If the default initializer remains, it wins with its default
   configuration; an additional later manual initialization would be a duplicate.
4. WorkManager marks an attempt complete according to the `Result` returned by `doWork()`.
   Awaiting `loader.load()` keeps its `withContext(@IoDispatcher)` work inside the
   WorkManager-owned coroutine and propagates cancellation. Launching into application scope and
   returning success detaches unfinished work, and that in-memory job still dies with the process.
5. The direct Robolectric test proves generated assisted construction, Hilt graph injection,
   Worker logic, and output. The device test additionally proves enqueue/database/scheduler/UI
   behavior under explicitly initialized test WorkManager and the device fake graph. Because that
   process uses `HiltTestApplication`, a normal APK cold-start/button smoke test is still needed
   to prove the production `MyApplication : Configuration.Provider` and manifest wiring.
