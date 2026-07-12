# 7. Entry points, `Lazy`, and `Provider` — the escape hatches

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → **7 · Entry points & Lazy/Provider** → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → [9 · WorkManager](HILT_9_WORKMANAGER.md) → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

Two loose ends from the core model. First: what if you need something from the graph but you're in a
class Hilt **doesn't create** and can't annotate — a `ContentProvider`, a library callback, a
framework object built before injection runs? That's `@EntryPoint`. Second: what if you don't want a
dependency built *eagerly*, or you want a *fresh* one each time? That's `Lazy<T>` and `Provider<T>`.

---

## The one mental model to keep

Everything so far answered *"how is this built?"* and *"where can Hilt inject?"*. These two features
change **when** you receive a dependency and **from where** you ask:

- **`@EntryPoint`** — a manual doorway into the graph for code that is neither `@AndroidEntryPoint`
  nor built by Hilt. You ask the graph explicitly instead of being injected.
- **`Lazy<T>` / `Provider<T>`** — wrappers you inject *instead of* `T`. `T` is built now, once;
  `Lazy<T>` defers building to first use; `Provider<T>` re-asks the graph on every `.get()`.

---

## 1. `@EntryPoint` — reaching the graph from outside

`@AndroidEntryPoint` works only for the classes Android hands to Hilt (Activities, Fragments,
Services, BroadcastReceivers). A `ContentProvider` is created **too early** for that (its `onCreate`
runs before `Application.onCreate`), and third-party libraries instantiate their own objects that
never pass through Hilt. For those, define an entry point: an interface listing what you want to pull
out of a component.

```kotlin
package com.example.myapp.core.di

import com.example.myapp.core.WordsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)   // which component to read from
interface WordsEntryPoint {
    fun wordsRepository(): WordsRepository
}
```

Then reach it through `EntryPointAccessors`, passing a context tied to the right component:

```kotlin
package com.example.myapp

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.example.myapp.core.di.WordsEntryPoint
import dagger.hilt.android.EntryPointAccessors

class WordsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true   // do NOT read the graph here — it isn't ready yet

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? {
        val repository = EntryPointAccessors
            .fromApplication(context!!.applicationContext, WordsEntryPoint::class.java)
            .wordsRepository()
        // ... build a Cursor from repository.getWords() ...
        return null
    }

    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
```

Two things that trip people up:

1. **Match the accessor to the component.** `fromApplication(...)` reads `SingletonComponent`;
   `fromActivity(activity, …)` reads `ActivityComponent`; there are also `fromFragment` and
   `fromView`. The entry point's `@InstallIn` must name the same component the accessor reads.
2. **A `ContentProvider` must not touch the graph in `onCreate`.** Providers initialize before the
   `SingletonComponent` exists. Ask for the entry point lazily, inside `query`/`insert`/etc. — by
   then the app is up.

`@EntryPoint` is a deliberate escape hatch, not the normal path. If you *can* use constructor
injection or `@AndroidEntryPoint`, do that instead.

---

## 2. `Lazy<T>` — build it only if it's actually used

`dagger.Lazy<T>` postpones construction until the first `.get()`, then caches that instance inside
the `Lazy`. Use it for a dependency that is expensive to create and only needed on some code paths:

```kotlin
import dagger.Lazy
import javax.inject.Inject

class WordManager @Inject constructor(
    private val dictionary: Lazy<ExpensiveDictionary>,   // NOT built at construction
) {
    fun nextWord(): String = "apple"                     // dictionary never built here

    fun define(word: String): String =
        dictionary.get().lookup(word)                    // built on the first call, reused after
}
```

Without `Lazy`, injecting `ExpensiveDictionary` directly would build it (and its whole subgraph)
every time a `WordManager` is created, even for callers that only ever call `nextWord()`.

Note the import: **`dagger.Lazy`**, not Kotlin's `kotlin.Lazy`/`by lazy`. Same idea, but this one is
wired by the graph.

---

## 3. `Provider<T>` — a fresh instance on demand

`javax.inject.Provider<T>` hands you `.get()` that **re-asks the graph every call**. What you get
back depends on the binding's scope (part 4):

- binding is **unscoped** → a brand-new instance on each `.get()`,
- binding is **scoped** → the same scoped instance each time.

```kotlin
import javax.inject.Inject
import javax.inject.Provider

class RoundManager @Inject constructor(
    private val roundProvider: Provider<GameRound>,   // GameRound is unscoped
) {
    fun startRound(): GameRound = roundProvider.get()  // a new GameRound every round
}
```

Injecting `GameRound` directly would freeze one instance for the lifetime of `RoundManager`;
`Provider<GameRound>` lets you mint a fresh one whenever you need it.

### `T` vs `Lazy<T>` vs `Provider<T>`

| You inject | Built when | `.get()` returns |
|---|---|---|
| `T` | at `RoundManager`'s construction | — (you hold the instance) |
| `Lazy<T>` | on the **first** `.get()` | always the **same** cached instance |
| `Provider<T>` | on **every** `.get()` | new if unscoped; the scoped instance if scoped |

---

## 4. Breaking a dependency cycle

If `A` needs `B` and `B` needs `A`, Dagger reports a `[Dagger/DependencyCycle]` at compile time —
it can't construct either first. Break the loop by making **one** side lazy, so it's resolved after
the other exists:

```kotlin
class A @Inject constructor(private val b: B)
class B @Inject constructor(private val a: Provider<A>)   // or Lazy<A>
```

Now Dagger builds `A`, injects it into `B` as a provider (nothing constructed yet), and `B` pulls
`A` out via `a.get()` only when it actually needs it. `Lazy<A>` works identically. (A cycle is
usually a design smell worth removing — but when you can't, this is the tool.)

One caveat: because `A` here is unscoped, `a.get()` inside `B` builds a **different** `A` than the
one that owns `B`. If the two must reference the *same* instance, scope it (e.g. `@Singleton`) — an
unscoped `Provider`/`Lazy` cycle-break hands out fresh instances (§3).

---

## Entry points, Lazy & Provider — error → cause

| Error | Cause |
|---|---|
| `[Dagger/MissingBinding]` from an `@EntryPoint` accessor call | The entry point's `@InstallIn` component doesn't provide that type, or you used the wrong `fromXxx` accessor for the component |
| `NullPointerException` / crash reading the graph in `ContentProvider.onCreate` | Read the entry point before the app was built — move it into `query`/`insert` (§1) |
| `IllegalStateException: … not an EntryPoint` | The interface is missing `@EntryPoint` (or `@InstallIn`) |
| `[Dagger/DependencyCycle] … depends on itself` | A `↔` B constructor cycle — wrap one side in `Provider<>`/`Lazy<>` (§4) |
| Injected `kotlin.Lazy` doesn't compile / isn't provided | Wrong `Lazy` — import `dagger.Lazy`, not the Kotlin one |
| `Provider<T>.get()` returns the same instance you wanted fresh | `T`'s binding is **scoped** — a scoped `Provider` returns the cached instance by design (part 4) |

---

## Where to go next

**[8 · Coroutines & dispatchers](HILT_8_COROUTINES_DISPATCHERS.md)** — injecting
`CoroutineDispatcher`s and an application `CoroutineScope`, a direct application of the qualifiers
from part 3.

## Quick reference

| I want to… | Do this |
|---|---|
| Read the graph from a `ContentProvider` / library class | `@EntryPoint` interface + `EntryPointAccessors.fromApplication(context, …)` |
| Match the accessor to a component | `fromApplication` / `fromActivity` / `fromFragment` / `fromView` = its `@InstallIn` |
| Avoid building an expensive dep unless used | Inject `dagger.Lazy<T>`, call `.get()` on demand |
| Get a fresh instance each time | Inject `javax.inject.Provider<T>` (with an unscoped `T`) |
| Break an A↔B constructor cycle | Wrap one side in `Provider<>` or `Lazy<>` |
