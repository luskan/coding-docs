# 7. Entry points, `Lazy`, and `Provider` -- the escape hatches

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> [6 - Testing](HILT_6_TESTING.md) -> **7 - Entry points & Lazy/Provider** -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

Two loose ends from the core model. First: what if code needs the graph but Hilt neither creates it
nor supports it as an `@AndroidEntryPoint`--a `ContentProvider` or a library-owned callback? That is
`@EntryPoint`. Second: what if a dependency should be requested only on first use, or its binding
logic should run again for each use? Those are `Lazy<T>` and `Provider<T>`.

---

## The one mental model to keep

These features change **where** and **when** a dependency is requested; they do not create a second
kind of graph:

- **`@EntryPoint`** is a manual doorway into the named Hilt component or components. Code asks
  explicitly instead of receiving member or constructor injection.
- **`Lazy<T>`** asks for `T` on the first `.get()`, then caches that result in that `Lazy` wrapper.
- **`Provider<T>`** asks for `T` on every `.get()`. Whether the result is a new object still depends
  on `T`'s scope and binding logic.

Directly injecting `T` resolves it while the consumer is constructed and leaves the consumer
holding that reference.

---

## 1. `@EntryPoint` -- reaching the graph from outside

`@AndroidEntryPoint` supports Activities, Fragments, Views, Services, and BroadcastReceivers. It
does not support `ContentProvider`, and third-party libraries can create objects that never pass
through any Hilt injection hook. Define a public entry-point interface for those boundaries:

```kotlin
package com.example.myapp.core.di

import com.example.myapp.core.WordManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WordsEntryPoint {
    fun wordManager(): WordManager
}
```

Each method requests an ordinary Dagger key from the installed component. This series has only
`@BasicWords WordsRepository` and `@FancyWords WordsRepository`, so an unqualified
`fun wordsRepository(): WordsRepository` would be a missing binding. Expose `WordManager`, as
above, or put `@FancyWords` on the accessor method to request that exact qualified key. Entry-point
return types must be public because generated component implementations may live in another
package.

The provider reads the application component manually when an actual query arrives:

```kotlin
package com.example.myapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.example.myapp.core.di.WordsEntryPoint
import dagger.hilt.android.EntryPointAccessors

class WordsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true   // startup itself does not need the graph

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val wordManager = EntryPointAccessors
            .fromApplication(
                checkNotNull(context).applicationContext,
                WordsEntryPoint::class.java,
            )
            .wordManager()

        return MatrixCursor(arrayOf("word"), 1).apply {
            addRow(arrayOf(wordManager.nextWord()))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        s: String?,
        a: Array<out String>?,
    ): Int = 0
}
```

Register it with an authority based on the application ID:

```xml
<provider
    android:name=".WordsProvider"
    android:authorities="${applicationId}.words"
    android:exported="false" />
```

The app's in-process client builds
`content://${context.packageName}.words/random`. Use `context.packageName`, not the Kotlin
namespace: this app's namespace is `com.example.myapp`, while its application ID is
`com.example.myapp.practice`. Because the provider is not exported, exercise it through the app or
an instrumentation test--not `adb shell content query`, which runs as another process/UID.

### Match the accessor to the installed component

The object passed to an accessor must be managed by Hilt and must hold the component named by the
entry point:

- `fromApplication(anApplicationDerivedContext, ...)` <-> `SingletonComponent`,
- `fromActivity(aHiltActivity, ...)` <-> `ActivityComponent`,
- `fromFragment(aHiltFragment, ...)` <-> `FragmentComponent`,
- `fromView(aHiltView, ...)` <-> its `ViewComponent`, or `ViewWithFragmentComponent` for a view with
  `@WithFragmentBindings`.

This pairing is not type-safe at the call site. If an entry point is installed in
`ActivityComponent` but requested with `fromApplication`, the application component does not
implement that generated interface and the call fails with a runtime cast. A `ContentProvider` has
no Activity instance, so its application-level doorway must expose only keys visible from
`SingletonComponent`.

### Provider startup and Hilt tests

Android attaches providers and calls `ContentProvider.onCreate()` before `Application.onCreate()`.
That does **not** mean the production Hilt graph is unavailable: the generated application owns an
`ApplicationComponentManager`, and a `fromApplication()` access can create `SingletonComponent`
lazily even at that point.

Hilt tests are the important special case. Their normal singleton component belongs to one test
case and is not created until `HiltAndroidRule` runs. An ordinary entry point read during provider
startup can therefore fail before the rule exists. This app does not need the graph during
`onCreate()`, so it waits until `query()`. That keeps the ordinary entry point and lets a query made
after the rule see the normal per-test graph--including Part 6's `androidTest` `@TestInstallIn` fake.

If startup truly must read the graph, Hilt has a deliberately different tool:

```kotlin
import com.example.myapp.core.WordManager
import dagger.hilt.InstallIn
import dagger.hilt.android.EarlyEntryPoint
import dagger.hilt.android.EarlyEntryPoints
import dagger.hilt.components.SingletonComponent

@EarlyEntryPoint
@InstallIn(SingletonComponent::class)
interface StartupWordsEntryPoint {
    fun wordManager(): WordManager
}

val wordManager = EarlyEntryPoints
    .get(checkNotNull(context), StartupWordsEntryPoint::class.java)
    .wordManager()
```

Use `@EarlyEntryPoint` **instead of** `@EntryPoint`, install it only in `SingletonComponent`, and
route every use through `EarlyEntryPoints.get()`. In production it delegates to the normal
application component. In Hilt tests it creates a separate, application-lifetime early component:

- it does not share even `@Singleton` instances with the normal per-test component,
- it cannot see test-class-owned bindings such as `@BindValue` or nested per-test modules,
- top-level source-set-wide `@TestInstallIn` modules can be compiled into it, but that does not make
  it the test case's normal component, and
- mutable scoped state can leak between test cases because the early component outlives one case.

Prefer deferring access when the boundary allows it. Early entry points exist for genuinely early
work, not as a general replacement for normal test injection.

---

## 2. `Lazy<T>` -- request it only on first use

`dagger.Lazy<T>` postpones the request for `T` until the first `.get()`. The running app makes
construction visible rather than inferring it:

```kotlin
package com.example.myapp.core

import dagger.Lazy
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DictionaryConstructionTracker @Inject constructor() {
    private val constructions = AtomicInteger()

    val constructionCount: Int
        get() = constructions.get()

    fun recordConstruction(): Int = constructions.incrementAndGet()
}

class ExpensiveDictionary @Inject constructor(
    tracker: DictionaryConstructionTracker,
) {
    val instanceId: Int = tracker.recordConstruction()

    fun lookup(word: String): String = "definition of $word"
}

class WordDefiner @Inject constructor(
    private val dictionary: Lazy<ExpensiveDictionary>,
    private val tracker: DictionaryConstructionTracker,
) {
    val constructionCount: Int
        get() = tracker.constructionCount   // does not call dictionary.get()

    fun define(word: String): DefinitionResult {
        val resolvedDictionary = dictionary.get()
        return DefinitionResult(
            text = resolvedDictionary.lookup(word),
            dictionaryInstanceId = resolvedDictionary.instanceId,
        )
    }
}

data class DefinitionResult(
    val text: String,
    val dictionaryInstanceId: Int,
)
```

Resolving `WordDefiner` leaves the count at `0`. Its first `define()` constructs dictionary `#1`;
later calls through that same wrapper keep the count at `1` and reuse `#1`.

The cache belongs to the injected `Lazy`, not to the graph as a new scope. Two injection sites get
two wrappers and can resolve two unscoped values. If `T` is scoped, the first `.get()` resolves the
component's scoped value, creating it if needed, then the wrapper caches that same reference too.

Use **`dagger.Lazy`**, not Kotlin's `kotlin.Lazy`/`by lazy`. A qualifier still targets the underlying
key: `@param:FancyWords Lazy<WordsRepository>` asks for the existing
`@FancyWords WordsRepository` binding.

---

## 3. `Provider<T>` -- re-request the binding on every use

`javax.inject.Provider<T>.get()` asks Dagger for `T` every time. It controls request timing, not
object identity:

- a scoped binding returns that component's cached instance,
- an unscoped `@Inject` constructor runs again and creates a new instance,
- an unscoped `@Provides` method runs again but is free to return a reused object, and
- `@Reusable` may reuse an instance.

The app uses an unscoped constructor-injected `GameRound`, so this particular provider reliably
creates a new round:

```kotlin
package com.example.myapp.core

import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class RoundIdSource @Inject constructor() {
    private val nextId = AtomicInteger()

    fun nextId(): Int = nextId.incrementAndGet()
}

class GameRound @Inject constructor(
    idSource: RoundIdSource,
) {
    val instanceId: Int = idSource.nextId()
}

class RoundManager @Inject constructor(
    private val roundProvider: Provider<GameRound>,
) {
    fun startRound(): GameRound = roundProvider.get()
}
```

Calling `startRound()` twice produces round IDs `#1` and `#2` and two different references.
Scoping `RoundIdSource` only centralizes ID generation; it does not scope `GameRound`. If
`GameRound` itself were `@Singleton`, both calls would return the same round.

Qualifiers work as for direct injection and `Lazy`: `@param:FancyWords Provider<WordsRepository>`
re-requests the fancy key. In this series that key resolves a singleton implementation, so it would
return the same repository each time.

### `T` vs `Lazy<T>` vs `Provider<T>`

| You inject | When `T` is requested | Identity behavior |
|---|---|---|
| `T` | While the consumer is constructed | The consumer holds that resolved reference |
| `Lazy<T>` | On the first `.get()` | That wrapper returns the same cached result afterward |
| `Provider<T>` | On every `.get()` | Follows the binding's scope and return behavior |

---

## 4. Breaking a dependency cycle

If `A` directly needs `B` and `B` directly needs `A`, a reachable graph fails with
`[Dagger/DependencyCycle]`. One deferred edge can make construction possible:

```kotlin
package com.example.myapp.core

import javax.inject.Inject
import javax.inject.Provider

class A @Inject constructor(private val b: B)
class B @Inject constructor(private val a: Provider<A>)
```

To construct the requested `A`, Dagger constructs `B` with a provider handle without resolving
another `A`, then completes the original `A`. Do not call `a.get()` from `B`'s constructor, an
initializer, or another path that runs during construction--the indirection must remain deferred.

Identity still follows section 3. A later call to an unscoped `Provider<A>` builds another `A` on every
call; an unscoped `Lazy<A>` builds one other `A` on first use and caches it. Scope `A` only if `B`
must recover the original component-cached instance. Cycles are usually a design smell, so prefer
removing the two-way ownership when possible.

---

## Entry points, Lazy & Provider -- error -> cause

| Error | Cause |
|---|---|
| `[Dagger/MissingBinding] WordsRepository cannot be provided...` | The entry-point method requested an unavailable exact key--for this graph, plain `WordsRepository` instead of `@FancyWords WordsRepository`--or the key is not visible from its installed component |
| `ClassCastException: Cannot cast ...SingletonC... to ...ActivityEntryPoint` | `fromXxx` retrieved a different component from the entry point's `@InstallIn`; match the accessor, installed component, and Hilt-managed host |
| `[Hilt] @InstallIn-annotated classes must also be annotated with @Module or @EntryPoint: ...` | The ordinary entry-point interface is missing `@EntryPoint` |
| `[Hilt] @EntryPoint ... must also be annotated with @InstallIn` | The entry point does not name its component |
| `IllegalStateException: The component was not created. Check that you have added the HiltAndroidRule.` | A Hilt test requested its ordinary component during provider/application startup; defer access until after the rule, or deliberately use an early entry point with its isolation tradeoffs |
| `[Hilt] @EarlyEntryPoint can only be installed into the SingletonComponent. Found: [...]` | An early entry point was installed in another component |
| `[Dagger/DependencyCycle] Found a dependency cycle:` | A reachable direct constructor cycle exists; redesign it or defer one edge with `Provider`/`Lazy` |
| `[Dagger/MissingBinding] kotlin.Lazy<...> cannot be provided...` | Imported Kotlin `Lazy` instead of `dagger.Lazy` |
| `Provider<T>.get()` returns the same object you wanted fresh | `T` is scoped/`@Reusable`, aliases a scoped binding, or its unscoped binding logic returns a reused object; Provider guarantees a new request, not a distinct identity |

---

## Where to go next

**[8 - Coroutines & dispatchers](HILT_8_COROUTINES_DISPATCHERS.md)** -- inject
`CoroutineDispatcher`s and an application `CoroutineScope`, a direct application of the qualifiers
from part 3.

## Quick reference

| I want to... | Do this |
|---|---|
| Read the graph from a provider/library after normal initialization | Public `@EntryPoint` + matching `EntryPointAccessors.fromXxx(...)` |
| Read `SingletonComponent` during provider/application startup in Hilt tests | `@EarlyEntryPoint` + `EarlyEntryPoints.get(...)`, accepting its separate test component |
| Match an accessor to a component | Application <-> singleton; Activity <-> activity; Fragment <-> fragment; View <-> view or view-with-fragment |
| Delay a dependency until first use | Inject `dagger.Lazy<T>`; the wrapper caches its first result |
| Re-run a binding request each time | Inject `Provider<T>`; identity still follows scope/binding behavior |
| Reliably create a fresh value | Use a provider for an unscoped binding whose constructor/provider actually creates a new object |
| Break an A<->B constructor cycle | Keep one edge deferred as `Provider<>` or `Lazy<>` |
