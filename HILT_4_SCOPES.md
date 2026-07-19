# 4. Hilt scopes -- how long an instance lives

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> **4 - Scopes** -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> [6 - Testing](HILT_6_TESTING.md) -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

Everything in parts 2-3 was *unscoped*: Hilt built a fresh object for every single request. This
part is about sharing -- one repository for the whole app, one session object per activity -- and
about the component tree that makes "per what?" a precise question.

---

## The one mental model to keep

**Unscoped: a new instance for every request. Scoped: one instance per *component instance*, cached
for as long as that component lives.**

Two consequences that do most of the work in this part:

1. A scope annotation ties a **binding** to a **component** (`@Singleton` ->
   `SingletonComponent`, `@ActivityScoped` -> `ActivityComponent`, ...). "Component" here is the same
   word you've been writing in `@InstallIn(SingletonComponent::class)` since part 2.
2. The scope belongs to the **binding (key)**, not to the class as such -- the same subtlety as
   qualifiers in part 3. You'll see below why that distinction matters for `@Binds`.

---

## 1. The default: unscoped -- and how it surprises you

With the part 2/3 setup, inject the repository into two places:

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var wordManager: WordManager          // holds its own WordsRepository
    @Inject lateinit var wordsRepository: WordsRepository  // a second, different instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // wordsRepository !== the one inside wordManager
    }
}
```

Every request builds a new object -- even two requests for the same type in the same class. For a
stateless repository returning a fixed list this is invisible and perfectly fine. It stops being
fine the moment the repository holds **state**: give it a cache, and `WordManager` fills a cache
that `MainActivity`'s copy never sees. That's the classic unscoped symptom: *"I set the value, and
somewhere else it's gone."* Not a bug in Hilt -- two objects.

---

## 2. `@Singleton` -- one instance for the whole app

Annotate the class:

```kotlin
package com.example.myapp.core

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords(): List<String> = listOf("apple", "banana", "cherry")
}
```

Now every consumer -- through the interface binding or the concrete type -- shares one instance,
created lazily at the first request (not at app start) and kept until the process dies.

`@Provides` methods are scoped the same way, on the method:

```kotlin
@Singleton
@Provides
fun provideRetrofit(): Retrofit =
    Retrofit.Builder().baseUrl("https://example.com/").build()
```

### Scoping the class vs scoping the `@Binds` -- the key subtlety

Because scope attaches to a **binding**, an interface + impl pair actually has two places a scope
could go, and they are not equivalent:

- **`@Singleton` on the class** (recommended): the concrete key `WordsRepositoryImpl` is cached. The
  unscoped `@Binds` is just an alias from the interface key to the concrete key, so interface
  consumers share the same cached instance too. One instance, no matter which key you ask through.
- **`@Singleton` on the `@Binds` method only**: the *interface* key is cached, but the concrete key
  stays unscoped -- anyone injecting `WordsRepositoryImpl` directly gets a fresh copy that bypasses
  the shared one.

Put the scope on the class: "there is exactly one of these" is a fact about the class, and it holds
regardless of how consumers reach it.

`@Singleton` is **not** the design-pattern singleton: nothing stops you from calling
`WordsRepositoryImpl()` by hand, and nothing survives a process restart. It's purely "one instance
per `SingletonComponent`" -- which exists once per app process.

---

## 3. The component tree -- every scope and its lifetime

Hilt has a fixed hierarchy of components. Each has exactly one scope annotation:

```
SingletonComponent                     @Singleton               app process
 |-- ActivityRetainedComponent         @ActivityRetainedScoped  survives rotation
 |   |-- ViewModelComponent            @ViewModelScoped         one ViewModel
 |   `-- ActivityComponent             @ActivityScoped          destroyed on rotation
 |       |-- FragmentComponent         @FragmentScoped
 |       |   `-- ViewWithFragmentComponent  @ViewScoped
 |       `-- ViewComponent             @ViewScoped
 `-- ServiceComponent                  @ServiceScoped
```

| Component | Scope | Created | Destroyed |
|---|---|---|---|
| `SingletonComponent` | `@Singleton` | `Application#onCreate()` | process death |
| `ActivityRetainedComponent` | `@ActivityRetainedScoped` | first `Activity#onCreate()` | final `Activity#onDestroy()` -- **survives configuration changes** |
| `ViewModelComponent` | `@ViewModelScoped` | ViewModel created | ViewModel cleared |
| `ActivityComponent` | `@ActivityScoped` | `Activity#onCreate()` | `Activity#onDestroy()` -- **every time, including rotation** |
| `FragmentComponent` | `@FragmentScoped` | `Fragment#onAttach()` | `Fragment#onDestroy()` |
| `ViewComponent` / `ViewWithFragmentComponent` | `@ViewScoped` | `View#super()` | view destroyed |
| `ServiceComponent` | `@ServiceScoped` | `Service#onCreate()` | `Service#onDestroy()` |

Read "one instance per component *instance*" literally:

- `@Singleton` -> one per app, because there is one `SingletonComponent`.
- `@ActivityScoped` -> one per **activity instance**. Two activities on the back stack -> two
  instances. Rotate the screen -> the activity is destroyed and recreated -> a **new** instance.
- `@ActivityRetainedScoped` -> one per activity, but the component survives rotation -- this is the
  machinery ViewModels ride on (part 5).

---

## 4. Two rules that keep the tree sane

**Rule 1 -- a scope must match its component.** A scoped `@Provides`/`@Binds` must live in a module
installed in the matching component; a scoped `@Inject` class is automatically owned by the
component its scope names.

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BadModule {
    @ActivityScoped   // <- compile error: wrong component for this scope
    @Provides
    fun provideSession(): WordSession = WordSession()
}
```

**Rule 2 -- you may only depend on things that live at least as long as you.** A child component can
see all of its ancestors' bindings; a parent can never see a child's. So an `@ActivityScoped` class
can inject a `@Singleton` one, but not the reverse:

```
OK:      @ActivityScoped WordSession --depends on--> @Singleton WordsRepositoryImpl
error:   @Singleton WordsRepositoryImpl --depends on--> @ActivityScoped WordSession
```

Both kinds of violation -- rule 1 and rule 2 -- are compile-time errors
(`IncompatiblyScopedBindings`), not runtime surprises. (One related failure looks different: a
binding whose module is installed in a *descendant* component, requested from an ancestor, surfaces
as a plain missing binding rather than a scope error -- that's visibility, not scope; see section 5.)
Both checks only apply to bindings actually **reached** from an injection point -- Dagger doesn't validate
the scope of a binding that nothing requests, so a dead binding can hide a scope violation until
something finally uses it.

A quiet corollary for **unscoped** classes: they aren't pinned to any component -- Hilt builds them
wherever they're requested -- but they can only be requested from components that can also satisfy
*their* dependencies. An unscoped class with an `@ActivityScoped` dependency is effectively
activity-level, annotation or not.

---

## 5. `@InstallIn` chooses *visibility*; scope chooses *caching*

These two are easy to conflate because both mention components:

- `@InstallIn(ActivityComponent::class)` on a module makes its bindings **visible** in
  `ActivityComponent` and everything below it (fragments, views) -- and invisible above it. An
  unscoped binding there is still a fresh instance per request.
- A scope annotation adds **caching** on top: `@ActivityScoped` on that binding means one instance
  per activity.

So "my `@Singleton` class can't see the binding" is usually not a scope problem at all -- the module
is installed in a component the consumer sits above. The error is a plain missing binding, because
from `SingletonComponent`'s point of view the binding doesn't exist.

`SingletonComponent` (from `dagger.hilt.components`) is the safe default install target precisely
because *everything* can see it; note the other components live in `dagger.hilt.android.components`.

---

## 6. Don't scope by default

Scoping looks free but isn't:

- the instance is held in memory for the component's entire lifetime,
- first access initializes it under a lock (double-checked locking); later accesses are plain
  volatile reads -- cheap, but not free,
- shared mutable state is invisible coupling between consumers.

Default to unscoped, and reach for a scope when one of these is true:

1. **State must be shared** -- an in-memory cache, a session, an event bus.
2. **Creation is expensive** -- `Retrofit`, `OkHttpClient`, a Room database.
3. **The thing must be unique** -- a database handle, a websocket connection.

Our word list needs none of these today -- and becomes a `@Singleton` the day it starts caching
network results. Scope for a reason you can name.

---

## Scopes -- error -> cause

| Error | Cause |
|---|---|
| `[Dagger/IncompatiblyScopedBindings] ...SingletonC scoped with @Singleton may not reference bindings with different scopes: ...` | A `@Singleton` binding depends on a shorter-lived one (rule 2), **or** a scoped method sits in a module installed in a non-matching component (rule 1) |
| `X cannot be provided without an @Inject constructor or an @Provides-annotated method` -- but X *is* bound... in a module installed lower in the tree | Visibility, not scope: the consumer's component is an *ancestor* of the one the module is installed in -- move the module up (e.g. to `SingletonComponent`) |
| Two consumers see different state; a value you set "disappears" | The class is unscoped, so each consumer got its own instance -- add a scope (and see part 5 if the state is per-screen) |
| State unexpectedly survives rotation -- or unexpectedly resets | `@ActivityRetainedScoped` vs `@ActivityScoped` mixed up: *Retained* survives configuration changes, plain `@ActivityScoped` does not |
| Memory leak reported on an Activity | An app-lifetime (`@Singleton`) object holds an `Activity` or an activity `Context` handed to it at runtime -- you can't *inject* `@ActivityContext` into a `@Singleton` (that's a compile error), so the reference always arrives via a setter/method call. Hold `@ApplicationContext` instead, or shorten the holder's scope |

---

## Where to go next

**[5 - ViewModels](HILT_5_VIEWMODELS.md)** -- `ActivityRetainedComponent` surviving rotation is
exactly the trick ViewModels are built on; part 5 wires `WordsRepository` into a `@HiltViewModel`.

## Quick reference

| I want... | Do this |
|---|---|
| A fresh instance per injection | Nothing -- that's the default |
| One instance app-wide | `@Singleton` on the class (preferred) or on the `@Binds`/`@Provides` method, module in `SingletonComponent` |
| One per activity, reset on rotation | `@ActivityScoped` |
| One per activity, surviving rotation | `@ActivityRetainedScoped` -- or usually just a ViewModel (part 5) |
| One per ViewModel | `@ViewModelScoped` |
| Share one object between an activity's fragments | `@ActivityScoped` |
| To know who can see a binding | The `@InstallIn` component and everything **below** it in the tree |
