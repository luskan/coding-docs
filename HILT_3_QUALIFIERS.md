# 3. Hilt qualifiers — several bindings of the same type

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → **3 · Qualifiers** → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → [9 · WorkManager](HILT_9_WORKMANAGER.md) → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

Part 2 ended with `WordsRepository` bound to `WordsRepositoryImpl` by a `@Binds` module. This part
answers the natural next question: **what happens when a second implementation of the same interface
appears?** — and introduces the mechanism that lets both live in the graph at once.

---

## The one mental model to keep

A binding key is not a type. It is a **(qualifier + type) pair**.

Part 2's question 1 — *"how do I build this type?"* — was slightly simplified. Hilt really asks:
*"how do I build this **key**?"*, and a request with no qualifier is simply the key
`(no qualifier, WordsRepository)`. Two rules follow directly:

1. **Exactly one binding per key.** Two answers for the same key → compile error.
2. **Hilt never guesses.** It resolves keys by exact lookup — it does not scan your code for classes
   that happen to implement an interface, and it will never choose between two candidates for you.

Qualifiers are how you mint *additional keys* for the same type, so each key can have its own single
answer.

---

## 1. What a second implementation does *not* do

Add a second implementation next to the one from part 2:

```kotlin
package com.example.myapp.core

import javax.inject.Inject

class FancyWordsRepositoryImpl @Inject constructor() : WordsRepository {
    override fun getWords(): List<String> = listOf("dragonfruit", "kumquat", "persimmon")
}
```

Everything still compiles — and **nothing changes**. `@Inject constructor()` only registers the
concrete key `FancyWordsRepositoryImpl`; it says nothing about the `WordsRepository` key. The module
still binds the interface to `WordsRepositoryImpl`, so every consumer keeps getting the old words.
From the graph's point of view the new class is dead code.

The naive fix — a second `@Binds` for the same interface — breaks rule 1:

```kotlin
@Binds abstract fun bindWordsRepository(impl: WordsRepositoryImpl): WordsRepository
@Binds abstract fun bindFancyWordsRepository(impl: FancyWordsRepositoryImpl): WordsRepository
// error: [Dagger/DuplicateBindings] WordsRepository is bound multiple times
```

One key, two answers. Hilt refuses at compile time rather than pick one.

---

## 2. Qualifiers — minting a second key for the same type

A qualifier is a tiny annotation class you define once:

```kotlin
package com.example.myapp.core.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BasicWords

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FancyWords
```

- The class body is empty — the annotation's identity *is* the information.
- `@Retention(AnnotationRetention.BINARY)` is the documented convention: the qualifier must survive
  into compiled classes (Kotlin's default `RUNTIME` also works; `SOURCE` does not).

Stamp each binding with one:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @BasicWords
    @Binds
    abstract fun bindBasicWordsRepository(impl: WordsRepositoryImpl): WordsRepository

    @FancyWords
    @Binds
    abstract fun bindFancyWordsRepository(impl: FancyWordsRepositoryImpl): WordsRepository
}
```

The graph now holds two distinct keys — `@BasicWords WordsRepository` and
`@FancyWords WordsRepository` — each with exactly one answer. The consumer picks by repeating the
qualifier at the injection site:

```kotlin
class WordManager @Inject constructor(
    @FancyWords private val wordsRepository: WordsRepository
) {
    fun nextWord(): String = wordsRepository.getWords().random()
}
```

`MainActivity` is untouched, `WordManager`'s body is untouched — only the requested *key* changed.

---

## 3. Where the qualifier goes — and where it does nothing

A qualifier appears in exactly two kinds of places, and they must match:

| Place | Role | Example |
|---|---|---|
| **Binding site** — the `@Binds`/`@Provides` method | *creates* the qualified key | `@FancyWords @Binds abstract fun bind…` |
| **Injection site** — constructor parameter | *requests* the key | `@FancyWords private val repo: WordsRepository` |
| **Injection site** — `@Inject` field | *requests* the key | `@FancyWords @Inject lateinit var repo: WordsRepository` |
| **Injection site** — parameter of another `@Provides`/`@Binds` method | *requests* the key | `@Provides fun provideX(@FancyWords repo: WordsRepository): X` |

Where it does **nothing**: on the implementation class itself.

```kotlin
@FancyWords  // ← no effect on the graph
class FancyWordsRepositoryImpl @Inject constructor() : WordsRepository { … }
```

An `@Inject constructor()` class always registers under its own *unqualified concrete* key; you
cannot qualify a class declaration into an interface binding. The qualifier belongs on the module
method (or, for classes with no module, you simply don't need one — the concrete type is already a
unique key).

If the two sides don't match, the error names the qualifier — that's your first debugging hint
(Dagger prints the qualifier's **fully-qualified** name):

```
[Dagger/MissingBinding] @com.example.myapp.core.di.FancyWords com.example.myapp.core.WordsRepository
cannot be provided without an @Provides-annotated method.
```

---

## 4. Keeping an unqualified default

You don't have to qualify everything. Unqualified and qualified keys coexist:

```kotlin
@Binds
abstract fun bindWordsRepository(impl: WordsRepositoryImpl): WordsRepository          // the default

@FancyWords
@Binds
abstract fun bindFancyWordsRepository(impl: FancyWordsRepositoryImpl): WordsRepository // opt-in
```

Every existing consumer keeps working untouched (they ask for the unqualified key); only the places
that *want* the fancy variant add `@FancyWords`. This "default + qualified specials" shape is the
most common real-world layout.

---

## 5. `@Named` — the string shortcut

`javax.inject` ships a ready-made qualifier parameterized by a string:

```kotlin
@Named("fancy")
@Binds
abstract fun bindFancyWordsRepository(impl: FancyWordsRepositoryImpl): WordsRepository

class WordManager @Inject constructor(
    @Named("fancy") private val wordsRepository: WordsRepository
)
```

No annotation class to declare — but the string is invisible to the compiler as a *name*:
`@Named("fancey")` compiles fine and only fails graph validation with a missing-binding error that
never says "typo". A custom qualifier annotation gives you compiler-checked spelling, find-usages,
and rename refactoring. Use `@Named` for quick experiments; prefer custom annotations for anything
that lives longer than a branch.

---

## 6. Qualifiers you already use without knowing

Hilt itself ships two `Context` bindings — the application and the current activity — which is
exactly the "two bindings of one type" problem, solved with predefined qualifiers:

```kotlin
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

class WordsFileReader @Inject constructor(
    @ApplicationContext private val context: Context
)
```

`@ApplicationContext` is available everywhere; `@ActivityContext` only in components that live
inside an activity (part 4 explains which those are). Same machinery, just predefined.

---

## 7. A realistic example — two `OkHttpClient`s

The textbook case for qualifiers. Note the qualifier used at a **binding site** and at a
**`@Provides` parameter** in the same module:

```kotlin
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlainClient
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AuthClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @PlainClient
    @Provides
    fun providePlainClient(): OkHttpClient =
        OkHttpClient.Builder().build()

    @AuthClient
    @Provides
    fun provideAuthClient(
        @PlainClient plain: OkHttpClient,     // requesting the other key
    ): OkHttpClient =
        plain.newBuilder()
            .addInterceptor(AuthInterceptor())
            .build()
}
```

Consumers then pick: the login call takes `@PlainClient`, everything after it takes `@AuthClient`.

---

## Qualifiers — error → cause

| Error | Cause |
|---|---|
| `WordsRepository is bound multiple times` | Two bindings share the same (unqualified) key — qualify at least one of them |
| `@FancyWords WordsRepository cannot be provided without an @Provides-annotated method` | The injection site asks for a qualified key nobody creates — the qualifier is missing on the binding method, or sits uselessly on the impl *class* |
| `WordsRepository cannot be provided …` although you *do* have bindings | All bindings are qualified but the injection site asks for the unqualified key — add the qualifier at the injection site (or add an unqualified default binding) |
| Missing-binding error mentioning `@Named("fancey")` | String typo in `@Named` — the compiler can't catch it; this is why custom qualifier annotations are preferred |
| `Unresolved reference: FancyWords` | The opposite failure with a custom annotation — a typo is caught immediately, at the name level |

---

## Where to go next

**[4 · Scopes](HILT_4_SCOPES.md)** — both repositories above are still *unscoped*: every injection
point gets a fresh instance. Scopes control how long an instance lives and who shares it.

## Quick reference

| I want to… | Do this |
|---|---|
| Two implementations of one interface | Two `@Binds` methods, each stamped with its own `@Qualifier` annotation |
| Declare a qualifier | `@Qualifier @Retention(AnnotationRetention.BINARY) annotation class Foo` |
| Request a qualified binding | Repeat the qualifier on the constructor param / `@Inject` field |
| A default plus a special variant | One unqualified `@Binds` + one qualified `@Binds` |
| A quick one-off | `@Named("…")` — accepting the typo risk |
| App vs activity `Context` | Built-in `@ApplicationContext` / `@ActivityContext` |
