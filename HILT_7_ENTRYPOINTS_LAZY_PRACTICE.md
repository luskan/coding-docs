# Practice 7. Manual graph access and deferred requests

*Tutorial: [7 - Entry points, `Lazy`, and `Provider`](HILT_7_ENTRYPOINTS_LAZY.md) - **Practice 7 of 10***

Start from the Part 7 state of [`hilt-practice-app/`](hilt-practice-app/). It contains a real
`ContentProvider` doorway, an observable `dagger.Lazy`, an unscoped value requested through
`Provider`, and both ordinary and early entry points. Work on a throwaway branch and restore the
green graph after every failure experiment.

## Self-check questions

1. When is `@EntryPoint` appropriate, and why should constructor injection or
   `@AndroidEntryPoint` remain the default?
2. Why must an entry point's `@InstallIn` component match the object and accessor passed to
   `EntryPointAccessors.fromXxx(...)`?
3. How do direct `T`, `dagger.Lazy<T>`, and `Provider<T>` differ in request timing, caching, and
   their ability to break a constructor cycle?
4. Why do this app's two `Provider<GameRound>.get()` calls create different rounds while two
   requests for a `@Singleton` binding within one component instance return the same object?
5. Why can entry-point access during provider startup work in production but fail in a Hilt test,
   and what isolation tradeoffs come with `@EarlyEntryPoint`?

Answer these before looking at the final section.

## Practical tasks

### 1. Trace the `ContentProvider` doorway

Trace one press of **Query content provider** through this entire path:

```text
Part7ViewModel
  -> WordsProviderClient(@ApplicationContext)
  -> ContentResolver.query()
  -> WordsProvider.query()
  -> EntryPointAccessors.fromApplication()
  -> WordsEntryPoint
  -> WordManager
  -> @FancyWords WordsRepository
```

For each step, identify who constructs the object and whether it receives normal injection or
manually asks for an existing component. Then verify the provider contract:

- the manifest authority is `${applicationId}.words`,
- the runtime URI uses `context.packageName`, which is the application ID rather than the Kotlin
  namespace,
- the provider is not exported,
- `/random` is the only accepted path, and
- a successful query returns one `word` column and one row.

Run these commands separately from `hilt-practice-app/`:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** seven JVM tests and one device test pass. The device test queries the real provider but
gets `device-fancy-word` from `androidTest`'s replacement graph. It also proves that the top-level
`@TestInstallIn` module participates in the early component while that component's scoped
repository remains a different object from the normal test component's repository. The final two
commands reinstall and launch the production app because connected-test cleanup may remove its
packages.

Do not substitute `adb shell content query` for the in-app or instrumentation query. The provider
is deliberately non-exported, and the shell process is an external caller with a different UID.

### 2. Match exact keys, components, and accessors

Add this method to `WordsEntryPoint` and rebuild:

```kotlin
import com.example.myapp.core.WordsRepository

// Inside WordsEntryPoint:
fun wordsRepository(): WordsRepository
```

**Check:** the reachable entry point fails with `[Dagger/MissingBinding]`. The graph has
`@BasicWords WordsRepository` and `@FancyWords WordsRepository`; it has no plain
`WordsRepository` key. Add `@FancyWords` to the method and confirm that the build succeeds, then
remove the experiment.

Next, install `WordsEntryPoint` in `ActivityComponent` while leaving
`EntryPointAccessors.fromApplication(...)` in `WordsProvider`. Build, launch, and press the query
button.

**Check:** compilation can succeed because an activity component can see `WordManager`'s
singleton-component dependencies. The query fails at runtime because the application component
does not implement an activity-component entry point; the accessor boundary performs a cast that
the Kotlin call site cannot check. Restore `SingletonComponent` and verify the query again.

As a visibility failure, expose the existing `@ActivityScoped WordSession` from the
singleton-component entry point.

**Check:** graph compilation fails because a parent component cannot request a binding owned by
its child activity component. A `ContentProvider` has no Hilt Activity instance with which to use
`fromActivity`, so moving this binding through the provider would also be the wrong lifetime
design. Restore the original entry point.

### 3. Prove first-use construction and per-wrapper caching

Run only the Part 7 JVM tests:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.EntryPointLazyProviderTest
```

Trace `DictionaryConstructionTracker`, `WordDefiner`, and `ExpensiveDictionary`. Explain why the
tracker must be `@Singleton` while the dictionary deliberately remains unscoped. Confirm that the
test encodes this sequence:

```text
resolve WordDefiner -> count 0
first define()      -> count 1, dictionary #1
second define()     -> count 1, dictionary #1 again
```

Temporarily inject `ExpensiveDictionary` directly into `WordDefiner` and replace
`dictionary.get()` with direct use.

**Check:** constructing `WordDefiner` now constructs the dictionary. The assertion expecting zero
before `define()` fails. This is a request-timing difference; direct injection is not inherently
wrong when deferral has no value.

Restore `Lazy<ExpensiveDictionary>`, then import `kotlin.Lazy` instead of `dagger.Lazy` and replace
`dictionary.get()` with `dictionary.value` so the Kotlin API usage itself is valid.

**Check:** the reachable graph fails with a missing binding for Kotlin's lazy type. Restore
`dagger.Lazy`, restore `.get()`, and get the test green.

Finally, add a second unscoped helper that injects its own `Lazy<ExpensiveDictionary>`. Resolve
both helpers and call each wrapper once.

**Check:** each wrapper caches its own result, so two wrappers can resolve two different unscoped
dictionaries. `Lazy` is a per-wrapper cache, not a graph-wide scope. Remove the helper afterward.

### 4. Let the binding decide `Provider` identity

Start with the existing `RoundManager` test. Confirm that `RoundIdSource` is singleton-scoped only
to issue observable IDs shared within one `SingletonComponent`, while `GameRound` itself is
unscoped. Two calls to
`startRound()` therefore produce two references with IDs `#1` and `#2`.

Replace `Provider<GameRound>` in `RoundManager` with one directly injected `GameRound`, and return
that held object from `startRound()`.

**Check:** repeated calls return the same reference and ID because the manager holds one resolved
value. Restore the provider.

Now annotate `GameRound` with `@Singleton` without changing `RoundManager`.

**Check:** `Provider.get()` still re-asks the graph on every call, but both requests receive the
singleton component's cached round. The existing freshness test fails. Remove the scope and get
IDs `#1` and `#2` again.

Explain why `Provider<T>` alone cannot promise distinct identities for an unscoped `@Provides`
method: Dagger invokes the method again, but that method is allowed to return a previously stored
object. Do not use `@Reusable` for a test that requires either stable sharing or guaranteed
freshness; reuse is permitted, not guaranteed.

### 5. Reproduce the early-access test boundary

Temporarily make `WordsProvider.onCreate()` call the ordinary application entry point and request
`WordManager` immediately. Run these two cases:

1. install and cold-launch the production debug APK;
2. run `connectedDebugAndroidTest` with `HiltTestApplication`.

**Check:** production can create its singleton component lazily before `Application.onCreate()`.
The Hilt test's ordinary per-test component does not exist until `HiltAndroidRule` prepares it, so
the provider-startup request fails before the test method can run. Restore the deferred ordinary
access in `query()`.

For a controlled early-entry-point experiment, use the existing `StartupWordsEntryPoint` from
`onCreate()` through `EarlyEntryPoints.get(...)`. Do not pass that early interface to
`EntryPointAccessors`.

**Check:** the early interface is installed only in `SingletonComponent`. In production it uses
the normal application component. In the device test it uses a separate application-lifetime
early component: the top-level `androidTest` `@TestInstallIn` repository is available, but its
`@Singleton` object is not the same object held by the normal per-test component. A test-class
`@BindValue` or nested per-test module would not be available during startup, and mutable scoped
state in the early component could leak across test cases.

Restore the provider's no-op `onCreate()`. Deferring normal access until `query()` is preferable
when startup does not actually need the graph.

### 6. Make, expose, and break a dependency cycle

Add this direct cycle in a temporary file:

```kotlin
package com.example.myapp.core

import javax.inject.Inject

class CycleA @Inject constructor(val b: CycleB)
class CycleB @Inject constructor(val a: CycleA)
```

Inject `CycleA` into a Hilt test so the binding becomes reachable, then compile.

**Check:** Dagger reports `[Dagger/DependencyCycle] Found a dependency cycle:`. An unreachable
pair may not be validated, so merely declaring the two classes is not a sufficient experiment.

Replace the `CycleB` edge with a provider:

```kotlin
import javax.inject.Provider

class CycleB @Inject constructor(val a: Provider<CycleA>)
```

**Check:** Dagger can construct `CycleB` with a provider handle without recursively constructing
another `CycleA`, so the reachable graph compiles. Keep `.get()` out of constructors and property
initializers; the edge breaks construction only while it remains deferred.

If the later code calls this unscoped `Provider<CycleA>`, it creates another `CycleA`, not the
original owner. Repeating the lab with `dagger.Lazy<CycleA>` creates one other unscoped `CycleA`
on first use and caches it in that wrapper. Scope `CycleA` only if shared component identity is a
real requirement, and prefer redesigning two-way ownership when possible. Remove the temporary
cycle and finish with the complete JVM and device suites green.

---

## Answers to the self-check questions

1. Use an entry point when code that Hilt neither creates nor supports for automatic injection
   must reach an existing graph, such as this `ContentProvider`. Prefer constructor injection or a
   supported `@AndroidEntryPoint` owner because dependencies then stay explicit, lifecycle-aware,
   and compile-time connected without a manual accessor and cast boundary.
2. The generated component named by `@InstallIn` implements the entry-point interface. The object
   passed to `fromApplication`, `fromActivity`, and the other accessors must hold that same kind of
   component; otherwise the returned component cannot be cast to the generated entry-point type.
3. Direct `T` is requested while its consumer is built. `Lazy<T>` waits for the first `.get()` and
   caches once per wrapper. `Provider<T>` requests the binding on every `.get()`. A provider or
   lazy edge can break a constructor cycle only when `.get()` remains deferred beyond
   construction.
4. `Provider` controls when Dagger re-requests a key, not the key's lifetime. This app's unscoped
   constructor binding creates a new `GameRound` per request. A singleton binding answers every
   request in that component with its cached object.
5. The production Hilt application can create its singleton component lazily during provider
   startup, but a Hilt test's normal singleton component belongs to one test and is unavailable
   before its rule runs. An early entry point uses a separate application-lifetime component in
   tests: it does not share scoped instances with the per-test component, cannot see
   test-class-owned bindings, and can leak mutable state across cases. A top-level source-set-wide
   `@TestInstallIn` module can still be compiled into that early component.
