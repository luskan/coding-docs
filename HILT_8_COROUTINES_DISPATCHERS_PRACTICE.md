# Practice 8. Controlling coroutine execution and ownership

*Tutorial: [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) - **Practice 8 of 10***

Start from the Part 8 state of [`hilt-practice-app/`](hilt-practice-app/). It preserves the
synchronous repository used by earlier parts, adds an injected-dispatcher `AsyncWordsLoader`, and
shows both ViewModel-owned and application-owned coroutine work. Work on a throwaway branch and
restore the green graph after every failure experiment.

## Self-check questions

1. Why is a hardcoded `Dispatchers.IO` a hidden dependency, and why do IO, Default, and Main need
   separate qualifiers even though all three have type `CoroutineDispatcher`?
2. What does a dispatcher control, what do a scope and `Job` control, and what ownership does
   `withContext(ioDispatcher)` retain?
3. Why must every injected `TestDispatcher` use the same `TestCoroutineScheduler` that the test
   drives?
4. On the application-scope binding, what does `@ApplicationScope` do and what does `@Singleton`
   do?
5. Which work survives Activity recreation, what can continue after a ViewModel is cleared, which
   work survives process death, and why does replacing `@MainDispatcher` not replace global
   `Dispatchers.Main`?

Answer these before looking at the final section.

## Practical tasks

### 1. Trace both coroutine paths

Trace the first Part 8 button from ownership to execution context:

```text
Part8ViewModel.viewModelScope
  -> @MainDispatcher
  -> AsyncWordsLoader
  -> @IoDispatcher
  -> @FancyWords WordsRepository
```

Then trace the application sync:

```text
WordSyncManager
  -> @ApplicationScope CoroutineScope
  -> @DefaultDispatcher
  -> AsyncWordsLoader
  -> @IoDispatcher
  -> @FancyWords WordsRepository
```

For every arrow, identify the exact Dagger key, the component that owns it, and the job that owns
cancellation. Explain why `AsyncWordsLoader` wraps the existing synchronous repository instead of
changing `WordsRepository.getWords()` to `suspend`: the prior `WordManager`, initialization, and
`ContentProvider.query()` paths must stay valid at this checkpoint.

Run these commands separately from `hilt-practice-app/`:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** ten JVM tests and one device test pass. The device test drives both coroutine paths
through the `androidTest` repository replacement and observes `device-fancy-word`. After
connected-test cleanup, the last two commands restore and launch the production app; both paths
display `dragonfruit, kumquat, persimmon`.

### 2. Break and repair exact dispatcher keys

Run one experiment at a time, in the named file:

1. In production `CoroutinesModule.kt`, remove `@IoDispatcher` from
   `provideIoDispatcher()`. Run `./gradlew :app:assembleDebug`.
2. Restore that provider. In `AsyncWordsLoader.kt`, remove `@param:IoDispatcher` from the
   constructor. Run `./gradlew :app:assembleDebug` again.
3. Restore the loader. In the JVM-only `TestCoroutinesModule.kt`, omit only
   `@MainDispatcher`. Run `./gradlew :app:testDebugUnitTest --tests
   com.example.myapp.CoroutinesGraphTest`.

**Check:** the first and third experiments produce a qualified missing binding for a reachable
key. The second requests plain `CoroutineDispatcher`, which this graph does not supply. A
qualifier on only the provider or only the consumer does not match a different key.
Restore `@MainDispatcher` in `TestCoroutinesModule` before the next experiment.

To reproduce a duplicate binding deliberately without breaking the qualified keys, add two
temporary unqualified dispatcher providers to `TestCoroutinesModule` and add a reachable plain
`CoroutineDispatcher` injection field to `CoroutinesGraphTest`:

```kotlin
@Provides
fun firstUnqualified(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

@Provides
fun secondUnqualified(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher
```

Run the graph-test target (compilation fails before a test method executes):

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.CoroutinesGraphTest
```

**Check:** Dagger reports:

```text
[Dagger/DuplicateBindings] kotlinx.coroutines.CoroutineDispatcher is bound multiple times:
```

Reachability matters: unqualified providers that no graph entry point requests may not trigger
this validation. Remove the temporary field and providers.

Finally, try two production-module Kotlin/Dagger failures independently and build with
`:app:assembleDebug` after each edit:

- in `CoroutinesModule.kt`, rename `provideDefaultDispatcher()` to `default`;
- restore its name, then in `CoroutinesScopeModule.kt` write `@param:DefaultDispatcher` on the
  ordinary `provideApplicationScope()` function parameter.

**Check:** the first fails KSP because `default` is a Java keyword. The second fails Kotlin with
`'@param:' annotations can only be applied to primary constructor parameters.` Use bare
`@DefaultDispatcher` on the provider parameter; reserve `@param:` for primary-constructor
parameters.

### 3. Prove queued execution and virtual time

The existing `loaderIsQueuedAndUsesTheFancyRepository` test uses `StandardTestDispatcher`.
Before `runCurrent()`, its `async` result is incomplete; after `runCurrent()`, the injected loader
finishes without a real thread race.

Temporarily add a long virtual delay inside `AsyncWordsLoader.load()`:

```kotlin
suspend fun load(): List<String> = withContext(ioDispatcher) {
    delay(60_000)
    repository.getWords()
}
```

Import `kotlinx.coroutines.delay`, then change the test body to make time explicit:

```kotlin
val words = async { loader.load() }
runCurrent()
assertFalse(words.isCompleted)

advanceTimeBy(60_000)
runCurrent() // execute work scheduled exactly at the new current time

assertTrue(words.isCompleted)
assertEquals(listOf("dragonfruit", "kumquat", "persimmon"), words.await())
```

**Check:** the test completes immediately in wall-clock time because the injected IO dispatcher
and `runTest(testDispatcher)` share one scheduler. Import `advanceTimeBy` from
`kotlinx.coroutines.test` and run only this test while the temporary delay is present:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.CoroutinesGraphTest.loaderIsQueuedAndUsesTheFancyRepository
```

Restore the no-delay loader and original assertion afterward.

Repeat the experiment with this hardcoded loader body and retain the temporary delay:

```kotlin
suspend fun load(): List<String> = withContext(Dispatchers.IO) {
    delay(60_000)
    repository.getWords()
}
```

Import `kotlinx.coroutines.Dispatchers`. In the same targeted test, start the deferred result,
call `runCurrent()`, advance virtual time by 60 seconds, and call `runCurrent()` again. Assert that
the result is still incomplete, then call `words.cancelAndJoin()` so the test does not wait for
the real delay. Import `kotlinx.coroutines.cancelAndJoin` so cleanup finishes before the test
returns.

**Check:** advancing the test scheduler cannot drive real IO threads. Restore the injected
dispatcher before continuing.

### 4. Detect and fix scheduler splits

Inspect `TestCoroutinesModule`: one `@Singleton TestCoroutineScheduler` constructs one
`@Singleton TestDispatcher`, and all three qualified dispatcher bindings return that exact object.
`CoroutinesGraphTest` enters `runTest` with the injected dispatcher and asserts that
`testScheduler` is the injected scheduler.

Temporarily change only the IO test provider to construct a new dispatcher:

```kotlin
@Provides
@Singleton
@IoDispatcher
fun provideIoDispatcher(): CoroutineDispatcher = StandardTestDispatcher()
```

Do not run the whole class in this broken state: the two existing scheduler-identity/loader tests
are expected to fail for their original reasons. Temporarily replace only
`qualifiedDispatchersShareTheInjectedScheduler()` with this test body, keeping the normal
no-delay loader:

```kotlin
@Test
fun splitDispatcherHasItsOwnQueue() = runTest(testDispatcher) {
    val splitIo = ioDispatcher as TestDispatcher
    assertNotSame(scheduler, splitIo.scheduler)

    val splitScope = CoroutineScope(splitIo)
    try {
        var splitWorkRan = false
        val splitJob = splitScope.launch { splitWorkRan = true }

        runCurrent() // drives only the injected runTest scheduler
        assertFalse(splitWorkRan)

        splitIo.scheduler.runCurrent()
        assertTrue(splitWorkRan)
        assertTrue(splitJob.isCompleted)
    } finally {
        splitScope.cancel()
    }
}
```

Import `kotlinx.coroutines.launch` and `org.junit.Assert.assertNotSame`; the test file already
imports `kotlinx.coroutines.cancel`. Run only the temporary method:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.CoroutinesGraphTest.splitDispatcherHasItsOwnQueue
```

**Check:** `runCurrent()` and `advanceTimeBy()` on the injected scheduler cannot advance work
queued on `splitIo.scheduler`; that scheduler must be driven separately. In APIs that combine both
test dispatchers in one test scope,
coroutines-test can fail explicitly with `Detected use of different schedulers...`. Either way, the
repair is one scheduler passed to every `TestDispatcher`; creating several test dispatchers is
safe only when all receive that same scheduler.

Restore the original IO provider and original identity test before running the class again. Never
store a test dispatcher in the `object` module as a property: that would share scheduler state
across Hilt test components.

### 5. Separate qualification, lifetime, and cancellation

First isolate this lifetime experiment so it does not instantiate an uncancellable third scope:

1. temporarily remove the `syncManager` injection field and
   `applicationScopeRunsOnTheSameControlledScheduler()` from `CoroutinesGraphTest`;
2. change `tearDown()` to cancel both `appScope` and `sameAppScope` when initialized;
3. add this identity-only test;
4. remove only `@Singleton` from `provideApplicationScope()`; and
5. run only the identity test.

```kotlin
@Test
fun applicationScopeIsUnscopedForTheExperiment() {
    assertNotSame(appScope, sameAppScope)
}
```

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.CoroutinesGraphTest.applicationScopeIsUnscopedForTheExperiment
```

**Check:** `@ApplicationScope` still distinguishes the key, but each unscoped provider request can
create a different `CoroutineScope`; the temporary non-identity assertion passes. Because this
isolated graph no longer requests `WordSyncManager`, the two injected scopes are the only scope
instances it constructs, and cleanup cancels both. Restore `@Singleton`, the manager field, its
test, the same-instance assertion, and the original guarded cleanup.

Next, run two exact-key failures separately:

1. in production `CoroutinesScopeModule.kt`, remove `@ApplicationScope` from
   `provideApplicationScope()` and run `./gradlew :app:assembleDebug`;
2. restore it, then in `WordSyncManager.kt` remove `@param:ApplicationScope` from `appScope` and
   run the same build command.

**Check:** the type is still `CoroutineScope`, but the exact keys no longer match. Restore the
qualifier on both sides.

Strengthen the sync test with this sequence:

1. call `syncInBackground()` twice before driving the scheduler;
2. assert both calls return the same active job;
3. run the scheduler and assert one completed sync;
4. call again after completion, run the scheduler, and assert a new job and two completed syncs.

This proves that `WordSyncManager` coalesces concurrent requests but accepts later work. The
StateFlow owned by the singleton manager and the singleton application scope can outlive a
ViewModel; neither survives process death. A `SupervisorJob` keeps one child failure from
cancelling siblings but does not handle or hide the exception.

### 6. Distinguish injected Main from global Main

The current ViewModel calls `viewModelScope.launch(mainDispatcher)`. The supplied dispatcher
overrides the child's inherited dispatcher while `viewModelScope` still supplies its owner job.

Temporarily change it to `viewModelScope.launch { ... }` and remove the injected constructor
parameter.

**Check:** the class now relies on global `Dispatchers.Main`; replacing the Dagger
`@MainDispatcher` key no longer controls this coroutine. A plain JVM test of that path must use
`Dispatchers.setMain(testDispatcher)` before the ViewModel starts work and
`Dispatchers.resetMain()` in `@After`. That global mutation is separate from Hilt module
replacement.

Restore the injected Main path. On the device, start an application sync and wait for its completed
count. Rotation alone proves Activity recreation but normally retains the same ViewModel, so use a
stronger same-process check:

1. record `adb shell pidof com.example.myapp.practice`;
2. press Back to finish the Activity and clear its ViewModel;
3. immediately launch it again with `adb shell am start -W -n
   com.example.myapp.practice/com.example.myapp.MainActivity`; and
4. confirm the PID is unchanged and the singleton manager's completed state remains visible.

If the PID changed, Android killed the process and the run is inconclusive; repeat it. With an
unchanged PID, the Activity and ViewModel are new while `SingletonComponent` is the same. Then
explain why actual process death discards the scope, jobs, and in-memory state. Do not introduce
WorkManager yet; Part 9 is the checkpoint for process-resilient scheduling.

Finish with all ten JVM tests, the one device test, a normal reinstall, and a cold launch green.

---

## Answers to the self-check questions

1. A hardcoded dispatcher hides execution policy inside the class, so tests cannot replace it.
   Dagger keys are type plus qualifier: IO, Default, and Main all share the same Kotlin type and
   therefore need distinct qualifiers on both providers and consumers.
2. A dispatcher controls the execution context. A scope and its job control parentage, lifetime,
   and cancellation. `withContext(ioDispatcher)` runs structured work in that context: its caller
   suspends until completion, and cancellation remains connected rather than creating a detached
   job.
3. Virtual-time operations drive one scheduler. A dispatcher created with another scheduler has a
   different queue and clock, so advancing the test's scheduler cannot run its work. Construct
   every test dispatcher from one injected scheduler and enter `runTest` with that controlled
   dispatcher.
4. `@ApplicationScope` is a qualifier that selects one `CoroutineScope` key; it does not cache an
   object. `@Singleton` scopes the provider binding to one instance per `SingletonComponent`.
5. ViewModel-owned work survives Activity recreation while its ViewModel remains, and
   application-scope work can continue after that ViewModel is cleared. Process death ends both;
   durable rescheduling needs WorkManager. `@MainDispatcher` replaces one Dagger key, while global
   `Dispatchers.Main` is changed separately with `Dispatchers.setMain/resetMain` in tests that use
   it.
