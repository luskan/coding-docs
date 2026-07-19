# Practice 2. Field injection, constructor injection, and modules

*Tutorial: [2 - Basics](HILT_2_BASICS.md) - **Practice 2 of 10***

Start from the Part 2 state of [`hilt-practice-app/`](hilt-practice-app/). The current graph is:

```text
MainActivity -> WordManager -> WordsRepository -> WordsRepositoryImpl
```

Work on a throwaway branch. Keep the activity dependent only on `WordManager`; the exercises should
make the constructor graph deeper without turning the activity into a service locator.

## Self-check questions

1. Which two questions must Hilt answer before it can satisfy an injection?
2. Who constructs `MainActivity`, and who constructs `WordManager`?
3. Why must an injected activity field be non-private, and why is it unsafe before
   `super.onCreate()` returns?
4. How should you read the parameter and return types of a `@Binds` method?
5. Does installing a module in `SingletonComponent` make its bindings singletons?

Answer these before looking at the final section.

## Practical tasks

### 1. Add another interface binding

Create this abstraction in the `core` package:

```kotlin
interface WordFormatter {
    fun format(word: String): String
}
```

Implement it as `UppercaseWordFormatter` with an `@Inject constructor()`, then bind the interface to
that implementation with `@Binds`. You may add the binding to `RepositoryModule` or create a second
abstract module installed in `SingletonComponent`.

Constructor-inject `WordFormatter` into `WordManager` and use it before returning a word.

**Check:** `MainActivity` is unchanged and every displayed word is uppercase.

### 2. Provide a type you do not own

Replace `List.random()` with an injected `java.util.Random`:

1. Create an `object` module installed in `SingletonComponent`.
2. Add a concrete `@Provides` method that returns `Random()`.
3. Constructor-inject `Random` into `WordManager` and use `nextInt(words.size)`.

Do not try to put this concrete provider next to abstract `@Binds` methods in the body of the same
abstract module. Use a separate `object` module, or a companion object if you intentionally want one
module declaration.

**Check:** the graph compiles without adding `@Inject` to a JDK class you cannot modify.

### 3. Explain the completed graph from the injection site

Draw the graph after Tasks 1-2. For every edge, label:

- who requests the dependency,
- whether Hilt calls an `@Inject` constructor, follows a `@Binds` alias, or invokes `@Provides`, and
- which object Android still constructs itself.

Your graph should end with two branches below `WordManager`: one reaches the repository
implementation, and the other reaches the formatter implementation. `Random` is a third,
provider-created constructor argument.

### 4. Run three controlled failure experiments

Make one change at a time, run `./gradlew :app:assembleDebug`, record the relevant error, and restore
the green graph before continuing:

1. Mark `MainActivity.wordManager` as `private`.
2. Remove `@Inject` from `WordsRepositoryImpl`'s constructor.
3. Remove the `@Binds` method for `WordsRepository` while keeping the interface request.

Classify each result using the tutorial's two questions: either Hilt cannot build a requested key,
or generated Android field injection cannot access the destination field.

### 5. Verify the result on a device

From `hilt-practice-app/`, run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

Press **Next word** several times.

**Check:** the activity launches without a `lateinit` failure, every value is one of the basic
repository's words in uppercase, and no activity code constructs a dependency manually.

---

## Answers to the self-check questions

1. Hilt must know how to build the requested type or key, and it must be allowed to inject at the
   destination. An `@Inject` constructor/module answers the first question; `@AndroidEntryPoint`
   answers the second for an Android-created activity.
2. Android constructs `MainActivity`. Hilt is allowed to inject it, then Hilt constructs
   `WordManager` and recursively supplies its constructor parameters.
3. Generated code in another class assigns the field, so it needs accessible visibility. For an
   `@AndroidEntryPoint` activity, that assignment occurs during `super.onCreate()`; an earlier read
   sees an uninitialized `lateinit` property.
4. Read it as concrete parameter type -> requested return/interface type. When a consumer asks for
   the return type, Hilt obtains the parameter type and returns it through that binding.
5. No. `@InstallIn(SingletonComponent::class)` controls where a binding is visible. Reuse requires
   a scope annotation, introduced in Part 4; the Part 2 bindings remain unscoped.
