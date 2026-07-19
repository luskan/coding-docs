# 2. Hilt basics -- from field injection to modules

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> **2 - Basics** -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> [6 - Testing](HILT_6_TESTING.md) -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

A hands-on tutorial that takes you from *nothing injected* to *injecting an interface backed by a
module*. It assumes Hilt is already wired into the project (see [`HILT_1_SETUP.md`](HILT_1_SETUP.md)
-- `@HiltAndroidApp` on your `Application`, the plugins, KSP, and the manifest registration).

Everything here uses one running example: a `WordManager` that hands out a random word, later backed
by a `WordsRepository`. By the end you'll have this graph:

```
MainActivity              @AndroidEntryPoint  (field injection)
   `-- WordManager          @Inject constructor(WordsRepository)
       `-- WordsRepository  interface, bound to WordsRepositoryImpl by a @Module
```

---

## The one mental model to keep

For Hilt to satisfy any injection, **two** questions must both have an answer:

1. **"How do I *build* this type?"** -- answered by `@Inject constructor()` on a class, or by a
   `@Provides` / `@Binds` method in a module.
2. **"Is this a place Hilt is allowed to inject *into*?"** -- answered by `@AndroidEntryPoint`
   for Android-created classes (Activities/Fragments/Services), or automatically when Hilt is
   already building a class and sees one of its constructor parameters.

`@AndroidEntryPoint` does **not** mean Hilt creates your Activity. Android still creates the
Activity and owns its lifecycle; the annotation only marks it as a place where Hilt may inject
fields. In contrast, when Hilt builds a normal dependency like `WordManager`, Hilt controls that
constructor call, so its constructor parameters are already injection points:

```kotlin
class WordManager @Inject constructor(
    private val wordsRepository: WordsRepository
)
```

`WordManager` does not need `@AndroidEntryPoint` here. Hilt is not injecting into an Android-created
object; it is creating `WordManager` itself, then recursively providing its constructor parameters.

Every error in this tutorial is ultimately one of those two questions going unanswered.

---

## 1. Field injection into an Activity

The simplest possible injection: put an instance into a `var` on your Activity.

### Step 1 -- make the type constructible

```kotlin
package com.example.myapp.core

import javax.inject.Inject

class WordManager @Inject constructor() {
    fun nextWord(): String = listOf("apple", "banana", "cherry").random()
}
```

`@Inject constructor()` answers question 1: *"to build a `WordManager`, call this constructor."*
No arguments -> nothing else to wire.

### Step 2 -- mark the Activity as an injection site and declare the field

```kotlin
package com.example.myapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.myapp.core.WordManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var wordManager: WordManager   // Hilt fills this in

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)   // <-- injection happens INSIDE this call
        val word = wordManager.nextWord()    // safe to use from here on
        // ... setContent { ... } etc.
    }
}
```

`@AndroidEntryPoint` answers question 2. That's it -- Hilt now populates `wordManager` for you.

### The three rules that trip everyone up

1. **The field must not be `private`.** Hilt injects it from generated code in another class, so it
   needs at least package/`internal` visibility. `@Inject lateinit var` (public) is the idiom.
   `@Inject private lateinit var` -> compile error.
2. **Don't touch the field before `super.onCreate()`.** For an `@AndroidEntryPoint` Activity the
   injection runs *during* `super.onCreate()`. Reading it earlier (e.g. in an `init {}` block) throws
   `UninitializedPropertyAccessException`.
3. **`@AndroidEntryPoint` is per-class.** It is not inherited or global -- every Activity/Fragment/
   Service you inject into needs its own annotation.

### Field injection -- error -> cause

| Error | Cause |
|---|---|
| `WordManager cannot be provided without an @Inject constructor or an @Provides-annotated method.` | Question 1 unanswered -- add `@Inject constructor()` to `WordManager` |
| `Dagger does not support injection into private fields` (compile-time) | The `@Inject` field is `private` -- remove `private` |
| Compiles cleanly, then `UninitializedPropertyAccessException` at runtime | The field was never injected. Either the class is **missing `@AndroidEntryPoint`** (a *silent* failure -- no compile error), or you read the field **before `super.onCreate()`** |

---

## 2. Constructor injection & transitive dependencies

Field injection is only needed at framework boundaries: objects Android creates, but Hilt is allowed
to inject into, such as Activities and Fragments. Everywhere else, prefer **constructor injection**
-- it's simpler and makes dependencies explicit.

Give `WordManager` a real collaborator:

```kotlin
package com.example.myapp.core

import javax.inject.Inject

class WordsRepository @Inject constructor() {
    fun getWords(): List<String> = listOf("apple", "banana", "cherry")
}

class WordManager @Inject constructor(
    private val wordsRepository: WordsRepository
) {
    fun nextWord(): String = wordsRepository.getWords().random()
}
```

You changed **nothing** in `MainActivity`. Hilt walks the graph automatically:

```
MainActivity  --asks for-->  WordManager  --asks for-->  WordsRepository
```

Because every class in that chain has `@Inject constructor()`, Hilt knows how to build each one and
supplies them recursively. This is the payoff of DI: you added a dependency without touching a single
line where `WordManager` is *used*.

**Note the `private val`.** A constructor parameter written as `val` becomes a public property of the
class. A dependency is an implementation detail, so mark it `private val` -- the collaborator is not
part of `WordManager`'s API.

That `private val` is still a stored property/field on `WordManager`; it is just initialized through
the constructor. This is different from field injection with `@Inject lateinit var`:

```kotlin
class WordManager @Inject constructor() {
    @Inject lateinit var wordsRepository: WordsRepository

    fun nextWord(): String = wordsRepository.getWords().random()
}
```

That can work when Hilt creates `WordManager`, but it is worse for normal classes. Constructor
injection keeps the dependency:

- required at creation time,
- immutable with `val`,
- private,
- impossible to forget when testing manually,
- safe to use immediately inside the class.

Also notice the visibility difference: an `@Inject lateinit var` field must not be `private`, because
generated code has to assign it after construction. A constructor-injected dependency can stay
`private val`, because Hilt passes it into the constructor instead of assigning the field directly.

---

## 3. Depending on an *interface* -- modules with `@Binds` / `@Provides`

Real apps depend on **interfaces**, not concrete classes, so you can swap implementations (fakes in
tests, a different backend, etc.) without touching the consumer. This is the first time you need a
`@Module`.

### Why a module is now required

So far Hilt could build everything because each class had `@Inject constructor()`. An **interface has
no constructor** -- Hilt cannot `new` it. When `WordManager` asks for the `WordsRepository` interface,
Hilt asks: *"which concrete implementation do I use?"* A module answers that in one line.

### Step 1 -- turn the type into an interface

```kotlin
package com.example.myapp.core

interface WordsRepository {
    fun getWords(): List<String>
}
```

No `@Inject` on the interface -- there's no constructor to annotate.

### Step 2 -- the implementation, constructible by Hilt

```kotlin
package com.example.myapp.core

import javax.inject.Inject

class WordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords(): List<String> = listOf("apple", "banana", "cherry")
}
```

`@Inject constructor()` tells Hilt how to build the concrete class.

### Step 3 -- bind the interface to the implementation

```kotlin
package com.example.myapp.core.di

import com.example.myapp.core.WordsRepository
import com.example.myapp.core.WordsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindWordsRepository(impl: WordsRepositoryImpl): WordsRepository
}
```

| Annotation | Meaning |
|---|---|
| `@Module` | "This class carries binding instructions for Hilt." |
| `@InstallIn(SingletonComponent::class)` | "These bindings are available app-wide." A safe default. |
| `@Binds` | "When someone asks for the **return type**, give them the **parameter type**." |

Read the `@Binds` signature as **parameter (concrete) -> return type (interface)**.

`WordManager` **stays exactly the same** -- it still asks for `WordsRepository`. It neither knows nor
cares that `WordsRepositoryImpl` is behind it. That indirection is the entire point: swap the binding
in the module and every consumer follows, untouched.

### The module doesn't contain code you write

A common confusion: `abstract class` sounds like "something to implement." It's the opposite. The
module is abstract **precisely so you write nothing inside it**:

- the `@Binds` method has **no body** -- just a signature,
- you **never** instantiate the module (`RepositoryModule()`),
- Hilt generates the real implementation for you.

The module is an instruction sheet, not executable code.

### `@Binds` vs `@Provides` -- which and when

| | `@Binds` | `@Provides` |
|---|---|---|
| Module must be | `abstract class` **or** `interface` (methods are abstract) | any type Hilt can invoke the method on -- `object` (idiomatic), a `companion object`, `@JvmStatic`, or a concrete `class` |
| Method | abstract, no body | concrete, returns the object |
| Use when | You have an interface + **one impl Hilt can already build** (`@Inject constructor()`) | You must **construct manually** -- third-party types with no `@Inject` (Retrofit, Room, OkHttp), or objects assembled from parameters |
| Generated code | Minimal | A bit more |

Same result via `@Provides` (here an `object` -- the idiomatic choice -- with a method body):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    fun provideWordsRepository(): WordsRepository = WordsRepositoryImpl()
}
```

For an interface backed by a single injectable impl -- our case -- **prefer `@Binds`**. Reach for
`@Provides` when Hilt can't build the thing itself.

A realistic `@Provides` example (building a type you don't own):

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    fun provideRetrofit(): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://example.com/")
            .build()
}
```

### `abstract class` vs `interface` vs `object`

- **`@Binds`** -> the module must be an `abstract class` or an `interface` (methods are abstract). With
  an `interface` you simply drop the `abstract` keyword. Pure style choice; tutorials favor
  `abstract class`.
- **`@Provides`** -> the method has a body, so it must live somewhere Hilt can invoke it: an `object`
  (idiomatic, allocation-free), a `companion object`, a `@JvmStatic` method, or a concrete
  (instantiable) `class`. It is **not** required to be an `object`. You cannot, however, mix a
  concrete `@Provides` and an abstract `@Binds` in the *same* non-`object` module -- combine them by
  moving the `@Provides` into that module's `companion object`.

### Interfaces & modules -- error -> cause

| Error | Cause |
|---|---|
| `WordsRepository cannot be provided without an @Provides-annotated method.` | No module binds the interface -- add a `@Binds` (abstract, in an `abstract class`/`interface` module) or a `@Provides` |
| `@Binds methods must be abstract` | The `@Binds` method or its module class isn't `abstract` (or you used `object`) |
| `RepositoryModule is missing an @InstallIn annotation` | Forgot `@InstallIn(...)` on the module |
| `WordsRepository is bound multiple times` | Two bindings for the **same key** -- e.g. a `@Binds` *and* a `@Provides` both returning `WordsRepository`, or the interface bound in two installed modules. (`@Inject constructor()` on the impl **plus** `@Binds` of the interface is *not* a duplicate -- different keys, and it's the intended setup.) |
| `A @Module may not contain both non-static and abstract binding methods` | Mixed a concrete `@Provides` with an abstract `@Binds` in one module -- move the `@Provides` into that module's `companion object` (or a separate `object` module) |

---

## Where to go next

Continue in reading order:

- **[3 - Qualifiers](HILT_3_QUALIFIERS.md)** -- what happens when you write a *second*
  `WordsRepository` implementation, why Hilt never picks one for you, and how `@Qualifier`
  annotations let two bindings of the same type coexist.
- **[4 - Scopes](HILT_4_SCOPES.md)** -- everything above is *unscoped*: Hilt creates a fresh instance
  at each injection point. Scopes (`@Singleton`, `@ActivityScoped`, ...) give you one shared instance
  per app / activity / ViewModel.
- **[5 - ViewModels](HILT_5_VIEWMODELS.md)** -- `@HiltViewModel`, `by viewModels()`,
  `hiltViewModel()` in Compose, and why a ViewModel needs its own injection mechanism.

## Quick reference

| I want to... | Do this |
|---|---|
| Inject into an Activity/Fragment | `@AndroidEntryPoint` on it + `@Inject lateinit var` (non-private) |
| Let Hilt build my own class | `@Inject constructor(...)` |
| Add a dependency to a class | Add a constructor parameter (`private val`) -- Hilt supplies it |
| Inject an interface | Interface + impl (`@Inject constructor()`) + `@Module` with `@Binds` |
| Provide a type I don't own | `object` `@Module` with `@Provides` |
| One shared instance | `@Singleton` -- on the `@Inject constructor` class directly, or on the `@Binds`/`@Provides` method in a module installed in `SingletonComponent` |
