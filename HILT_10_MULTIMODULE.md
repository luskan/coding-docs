# 10. Hilt in a multi-module project

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → [9 · WorkManager](HILT_9_WORKMANAGER.md) → **10 · Multi-module***

Everything so far lived in one Gradle module. Real apps split into many — `:app`, `:core`,
`:feature:words` — for build speed and separation. The good news: Hilt was designed for this and the
DI concepts don't change. The things that *do* change are where the `@HiltAndroidApp` lives, what
each module's build file needs, and how Kotlin visibility (`internal`) interacts with the graph.

---

## The one mental model to keep

**There is still exactly one graph. Gradle module boundaries do not partition it — `@InstallIn` and
Kotlin `internal` do.**

Hilt aggregates every `@Module`, `@Inject` class, and entry point from **all** Gradle modules into a
single component tree, assembled in the `:app` module where `@HiltAndroidApp` sits. So:

- a `@Binds` in `:core` installed in `SingletonComponent` is visible to a ViewModel in
  `:feature:words` — because they end up in the same merged component (part 4's tree),
- what actually hides an implementation from another module is **Kotlin `internal`**, plus what a
  module exposes with `api` vs `implementation` — not Hilt.

That combination is the whole game: publish the **interface**, hide the **implementation and its
binding**.

---

## 1. The single-`@HiltAndroidApp` rule

Exactly one class in the whole project carries `@HiltAndroidApp`, and it must be in the application
module (`:app`). That module performs the final aggregation across every module it depends on. A
library module never has its own `@HiltAndroidApp`; it just contributes bindings that `:app` pulls
together.

```
:app            @HiltAndroidApp MyApplication, MainActivity (@AndroidEntryPoint)
  ├─ :feature:words   WordViewModel (@HiltViewModel), depends on :core
  └─ :core            WordsRepository (interface), WordsRepositoryImpl + module (internal)
```

## 2. What each module's build file needs

Every module that uses **any** Hilt annotation (`@Inject`, `@Module`, `@AndroidEntryPoint`,
`@HiltViewModel`, …) applies the Hilt plugin and the KSP compiler — not just `:app`:

Add the library plugin alias to the catalog first — part 1 defined `android-application` but not
`android-library`: `android-library = { id = "com.android.library", version.ref = "agp" }`.

```kotlin
// :core/build.gradle.kts and :feature:words/build.gradle.kts (and :app)
plugins {
    alias(libs.plugins.android.library)   // com.android.application only in :app
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
```

Using the shared version catalog from part 1 keeps versions identical across modules (a must — mixed
Hilt versions break the merge). A library module that only holds `@Inject` classes still needs the
KSP compiler to generate their factories; a module with `@AndroidEntryPoint` additionally needs the
Hilt **Gradle plugin** for its bytecode transform. Applying both everywhere is the simple, safe rule.

## 3. The api / implementation split

This is the payoff of interfaces (part 2 §3) at the module level. Put the interface where consumers can
see it, and keep the implementation and its `@Binds` module `internal` to the module that owns them.

```kotlin
// :core — WordsRepository.kt  (public: other modules inject this)
package com.example.myapp.core

interface WordsRepository {
    fun getWords(): List<String>
}
```

```kotlin
// :core — WordsRepositoryImpl.kt  (internal: nobody outside :core names this)
package com.example.myapp.core

import javax.inject.Inject

internal class WordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords() = listOf("apple", "banana", "cherry")
}
```

```kotlin
// :core — RepositoryModule.kt  (internal binding, installed app-wide)
package com.example.myapp.core.di

import com.example.myapp.core.WordsRepository
import com.example.myapp.core.WordsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RepositoryModule {
    @Binds
    abstract fun bindWordsRepository(impl: WordsRepositoryImpl): WordsRepository
}
```

The `@Binds` is installed in `SingletonComponent`, so its binding is visible to the entire app graph
— but `WordsRepositoryImpl` and `RepositoryModule` are `internal`, so no other Gradle module can
*reference* them. A consumer can only ask for `WordsRepository`, which is exactly what you want.

## 4. Injecting across the module boundary — nothing special

`:feature:words` depends on `:core` and injects the interface as if everything were one module:

```kotlin
// :feature:words — WordViewModel.kt
@HiltViewModel
class WordViewModel @Inject constructor(
    private val wordsRepository: WordsRepository,   // from :core, resolved by :app's merged graph
) : ViewModel() {
    // part 10 keeps the simple non-suspend repository to stay focused on module structure
    val currentWord: String = wordsRepository.getWords().random()
}
```

`:feature:words` needs a Gradle dependency on `:core` only so it can **name** `WordsRepository` at
compile time. The actual binding is contributed to the graph by `:core` and stitched in when `:app`
aggregates. Neither feature nor app mentions `WordsRepositoryImpl`.

## 5. `@AndroidEntryPoint` and entry points in library modules

Both work in any module, with one caveat each:

- an `@AndroidEntryPoint` Activity/Fragment in a feature module needs that module to apply the Hilt
  **Gradle plugin** (§2) — the transform that makes injection work is per-module,
- an `@EntryPoint` (part 7) defined in `:core` is visible app-wide like any binding; put it wherever
  the type it exposes lives.

---

## Multi-module — error → cause

| Error | Cause |
|---|---|
| `WordsRepository cannot be provided …` from a feature module, though `:core` defines the binding | `:app` doesn't (transitively) depend on `:core`, so the binding never reaches the merged graph — or the module is missing the KSP compiler (§2) |
| `Unresolved reference: WordsRepositoryImpl` in another module | Correct and intended — the impl is `internal`; depend on the **interface** instead |
| `WordsRepositoryImpl … must be … accessible` / `@Binds` can't see the impl | The `@Binds` module is in a *different* module from the `internal` impl — keep the impl and its binding together |
| Two `@HiltAndroidApp` classes / `Duplicate` component | More than one `@HiltAndroidApp` — only `:app` may have one |
| `@AndroidEntryPoint` field never injected (runtime `Uninitialized…`) in a feature module | That module didn't apply the Hilt Gradle plugin (§2) |
| Version/`Dagger` merge errors after adding a module | Modules use **different** Hilt/KSP versions — unify via the shared version catalog (part 1) |

---

## Where to go next

You've reached the end of the series: from a single `@Inject` (part 2) to a scoped, tested,
multi-module graph. Beyond here lies advanced Dagger/Hilt territory worth a look when you need it:

- **Custom components & scopes** — `@DefineComponent` for a lifetime Hilt doesn't ship (e.g. a
  logged-in-user scope).
- **Multibindings** — `@IntoSet` / `@IntoMap` to collect plugin-style contributions from many
  modules (a natural fit for feature modules each registering themselves).
- **Convention plugins** — a Gradle `build-logic` plugin that applies the §2 boilerplate so every
  module doesn't repeat it.

## Quick reference

| I want to… | Do this |
|---|---|
| One app graph across modules | Single `@HiltAndroidApp` in `:app`; every Hilt-using module applies the plugin + KSP |
| Hide an implementation from other modules | `internal` impl + `internal` `@Binds` module, expose only the `interface` |
| Inject a `:core` type from a feature | Gradle-depend on `:core` to name the interface; the binding is merged automatically |
| Keep versions in sync | One version catalog (part 1), same Hilt/KSP everywhere |
| `@AndroidEntryPoint` in a feature module | Apply the Hilt Gradle plugin in that module |
