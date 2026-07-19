# 5. Hilt ViewModels -- injection that survives rotation

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> **5 - ViewModels** -> [6 - Testing](HILT_6_TESTING.md) -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

Parts 2-4 built a graph and controlled instance lifetimes. This part connects it to the place UI
state actually lives in a modern Android app: the `ViewModel`.

---

## The one mental model to keep

Part 2 had two creators -- Android creates activities (-> field injection), Hilt creates your classes
(-> constructor injection). ViewModels introduce a **third creator**: the framework's
`ViewModelProvider`, which not only creates a ViewModel but **retains** it across configuration
changes.

That retention is exactly why neither existing mechanism fits:

- Hilt can't just `new WordViewModel(...)` for you -- an instance Hilt creates per request would not be
  retained; you'd get a fresh ViewModel on every rotation, defeating its purpose.
- Field-injecting a ViewModel into the activity is the same mistake with extra steps.

`@HiltViewModel` is the bridge: it registers a factory so that **`ViewModelProvider` stays the owner
and decides *when* to create, while Hilt decides *how* to build** -- resolving the constructor from
the same graph as everything else. You never inject a ViewModel; you *request* it through the normal
Android APIs (`by viewModels()`, `hiltViewModel()`), and Hilt is behind them.

---

## 1. The ViewModel

```kotlin
package com.example.myapp.ui

import androidx.lifecycle.ViewModel
import com.example.myapp.core.WordsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class WordViewModel @Inject constructor(
    private val wordsRepository: WordsRepository,
) : ViewModel() {

    val currentWord: String = wordsRepository.getWords().random()
}
```

Constructor injection works exactly as in part 2: `WordsRepository` arrives through the same
`@Binds` module, qualifiers from part 3 apply unchanged. The rules specific to ViewModels:

- exactly **one** constructor annotated `@Inject` (or `@AssistedInject`, for the assisted-injection
  variant in section 6),
- the class extends `ViewModel`,
- **no `@AndroidEntryPoint`** -- that annotation is for Android-created UI classes, and this class is
  created by `ViewModelProvider`.

---

## 2. Requesting it from an Activity

```kotlin
package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.example.myapp.ui.WordViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: WordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val word = viewModel.currentWord
        // ... setContent { ... } etc.
    }
}
```

Three things worth noticing:

1. **`by viewModels()` is not a Hilt API.** It's the standard AndroidX delegate. The Hilt part is
   invisible: `@AndroidEntryPoint` swaps the activity's *default ViewModel factory* for one backed
   by the graph. That's why the **host must be `@AndroidEntryPoint`** -- without it, the stock
   factory tries the no-arg constructor and crashes at runtime.
2. **`private` is fine here.** This is a plain property delegate, not an `@Inject` field -- part 2's
   "no private `@Inject` fields" rule doesn't apply because nothing is injected into the activity.
3. In a Fragment, the same works with `by viewModels()` (own ViewModel) or `by activityViewModels()`
   (shared with the host activity) -- the fragment needs its own `@AndroidEntryPoint`.

### The payoff -- rotation

`currentWord` is picked randomly at construction. Rotate the screen: the activity is destroyed and
recreated, `by viewModels()` hands back the **same** `WordViewModel`, and the word doesn't change.
Compare with part 2's `@Inject lateinit var wordManager` -- a plain injected field is re-injected
fresh on every recreation. That difference is the entire reason ViewModels get special treatment.

---

## 3. In Compose -- `hiltViewModel()`

Add the Compose artifact from part 1 section 6 -- `androidx.hilt:hilt-lifecycle-viewmodel-compose`, the
current home of `hiltViewModel()` since androidx.hilt 1.3. (The older
`androidx.hilt:hilt-navigation-compose` / `androidx.hilt.navigation.compose.hiltViewModel` still
compiles but is deprecated for this call.)

```kotlin
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@Composable
fun WordScreen(viewModel: WordViewModel = hiltViewModel()) {
    Text(viewModel.currentWord)
}
```

`hiltViewModel()` resolves against the current `LocalViewModelStoreOwner` -- usually the activity in
`setContent`, a fragment in fragment-hosted Compose, or, inside a Navigation Compose destination,
the **navigation back-stack entry** (which is what gives each screen its own ViewModel, cleared when
the destination leaves the back stack).

---

## 4. `SavedStateHandle` -- surviving process death, not just rotation

A retained ViewModel survives rotation but **not** process death (the system killing your
backgrounded app). For the small state that must survive that too, inject `SavedStateHandle` -- a
built-in binding of `ViewModelComponent`, available with no module:

```kotlin
@HiltViewModel
class WordViewModel @Inject constructor(
    private val wordsRepository: WordsRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val currentWord: String =
        savedStateHandle["word"] ?: wordsRepository.getWords().random()
            .also { savedStateHandle["word"] = it }
}
```

With Navigation, the same handle also carries the destination's **navigation arguments** -- the
idiomatic way to get "which item was clicked" into a ViewModel.

---

## 5. Where a ViewModel sits in part 4's component tree

ViewModel dependencies resolve in `ViewModelComponent`, whose parent is
`ActivityRetainedComponent` -- the rotation-surviving branch:

```
SingletonComponent
 `-- ActivityRetainedComponent       <- survives rotation
     |-- ViewModelComponent          <- @HiltViewModel constructor args resolve HERE
     `-- ActivityComponent           <- destroyed on rotation
```

Consequences, straight from part 4's rule 2 (you may only depend on longer-lived things):

- A ViewModel **can** inject unscoped, `@Singleton`, `@ActivityRetainedScoped`, and
  `@ViewModelScoped` bindings.
- A ViewModel **cannot** inject `@ActivityScoped`/`@FragmentScoped` bindings -- `ActivityComponent`
  is a *sibling* branch, not an ancestor. In particular there is **no activity `Context`**: the
  ViewModel outlives the activity, so holding one would be a leak by construction. Need a `Context`?
  `@ApplicationContext` (part 3 section 6).
- `@ViewModelScoped` gives one instance per ViewModel, shared by everything that ViewModel's graph
  builds:

```kotlin
@ViewModelScoped
class WordSession @Inject constructor() { ... }
```

Two different ViewModels get two `WordSession`s; within one ViewModel, every dependency that asks
for a `WordSession` shares the same one.

---

## 6. Runtime parameters -- a pointer

A constructor argument only known at runtime (`wordId` of the clicked row) can't come from the
graph. In order of preference:

1. **Navigation arguments via `SavedStateHandle`** -- zero extra machinery, works with process death.
2. **Assisted injection** -- since Dagger 2.49 / androidx.hilt 1.2: annotate the constructor
   `@AssistedInject` (**not** `@Inject`), mark the runtime parameters `@Assisted`, add an
   `@AssistedFactory` interface (say `WordViewModel.Factory`) with a `create(...)` method, and name
   it in `@HiltViewModel(assistedFactory = WordViewModel.Factory::class)`. Compose then calls it with
   **explicit type arguments** -- the factory type can't be inferred from the lambda:
   `hiltViewModel<WordViewModel, WordViewModel.Factory>(creationCallback = { factory -> factory.create(id) })`.
   Reach for it only when the value genuinely can't travel as a navigation argument.

---

## ViewModels -- error -> cause

| Error | Cause |
|---|---|
| Runtime: `RuntimeException: Cannot create an instance of class com.example.myapp.ui.WordViewModel` | The *default* factory ran instead of Hilt's: the host Activity/Fragment is missing `@AndroidEntryPoint`, **or** the ViewModel is missing `@HiltViewModel` |
| Compile: `@HiltViewModel annotated class should contain exactly one @Inject or @AssistedInject annotated constructor` | Zero, several, or a wrongly-annotated constructor -- a `@HiltViewModel` needs exactly one constructor annotated `@Inject` (normal) or `@AssistedInject` (assisted, section 6); other *unannotated* constructors are fine |
| Compile: `Injection of an @HiltViewModel class is prohibited since it does not create a ViewModel instance correctly. Access the ViewModel via the Android APIs (e.g. ViewModelProvider) instead` | You wrote `@Inject lateinit var viewModel: WordViewModel` or put it in a constructor -- request it with `by viewModels()` / `hiltViewModel()` |
| Compile: `Unresolved reference: hiltViewModel` | Missing the `androidx.hilt:hilt-lifecycle-viewmodel-compose` dependency, or you imported `hiltViewModel` from `androidx.hilt.navigation.compose` without that (now-deprecated) artifact on the classpath -- import from the new package (section 3) |
| A ViewModel dependency `cannot be provided...` although it *is* bound in an `ActivityComponent` module | `ViewModelComponent` can't see the sibling `ActivityComponent` -- install the module higher (`SingletonComponent` or `ActivityRetainedComponent`) |
| State resets on rotation despite the ViewModel | The state lives outside the ViewModel (e.g. in an unscoped or `@ActivityScoped` class the *activity* injects) -- move it into the ViewModel or behind `@ActivityRetainedScoped` |

---

## Where to go next

**[6 - Testing](HILT_6_TESTING.md)** -- collect the payoff of all those interfaces: swap
`WordsRepositoryImpl` for a fake with `@TestInstallIn` / `@BindValue`, and run the graph on the JVM.

The series continues from there: [7 - Entry points & `Lazy`/`Provider`](HILT_7_ENTRYPOINTS_LAZY.md),
[8 - Coroutines & dispatchers](HILT_8_COROUTINES_DISPATCHERS.md),
[9 - WorkManager](HILT_9_WORKMANAGER.md), and
[10 - Hilt in a multi-module project](HILT_10_MULTIMODULE.md).

## Quick reference

| I want to... | Do this |
|---|---|
| A ViewModel with injected dependencies | `@HiltViewModel class X @Inject constructor(...) : ViewModel()` |
| Use it in an Activity/Fragment | Host has `@AndroidEntryPoint` + `by viewModels()` |
| Share one ViewModel across fragments | `by activityViewModels()` |
| Use it in Compose | `hiltViewModel()` + `androidx.hilt:hilt-lifecycle-viewmodel-compose` |
| Survive process death (small state) | Inject `SavedStateHandle` |
| One shared helper per ViewModel | `@ViewModelScoped` |
| A `Context` inside a ViewModel | `@ApplicationContext` only -- never the activity |
| Pass a runtime argument | Navigation args via `SavedStateHandle`; assisted injection as the fallback |
