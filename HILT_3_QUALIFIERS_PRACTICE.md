# Practice 3. Multiple bindings with qualifiers

*Tutorial: [3 - Qualifiers](HILT_3_QUALIFIERS.md) - **Practice 3 of 10***

Start from the Part 3 state of [`hilt-practice-app/`](hilt-practice-app/). Its UI already proves that
these two keys coexist:

```text
@BasicWords WordsRepository -> WordsRepositoryImpl
@FancyWords WordsRepository -> FancyWordsRepositoryImpl
```

Use a throwaway branch and keep every word set disjoint. Distinct values make a device check prove
which key Hilt resolved.

## Self-check questions

1. What constitutes a Hilt/Dagger binding key?
2. Does adding `FancyWordsRepositoryImpl @Inject constructor()` change an existing request for
   `WordsRepository`?
3. Where does a qualifier create or request a key, and where is the same annotation ineffective?
4. Can qualified bindings coexist with an unqualified binding of the same type?
5. Why is a custom qualifier usually safer than `@Named("...")`?

Answer these before looking at the final section.

## Practical tasks

### 1. Trace both existing keys end to end

For `@BasicWords WordsRepository` and `@FancyWords WordsRepository`, identify:

- the qualifier declaration,
- the module method that creates the key,
- every constructor parameter that requests the key, and
- the concrete implementation Hilt constructs.

Then inspect the Part 3 screen and explain why its disjoint basic and fancy values are stronger
evidence than two successful `@Binds` declarations that no reachable consumer requests.

### 2. Add a third custom-qualified repository

Create `SeasonalWordsRepositoryImpl` with words that appear in neither existing list. Declare a
`@SeasonalWords` qualifier with `BINARY` retention and the same explicit Kotlin targets as the two
existing qualifiers.

Add a third qualified `@Binds` method and request the new key from a constructor-injected
`SeasonalWordPicker`. When annotating a constructor property, use the explicit parameter use-site:

```kotlin
@param:SeasonalWords private val repository: WordsRepository
```

This keeps the qualifier on the injection parameter and avoids Kotlin 2.2's annotation-target
migration warning while still allowing the qualifier type to support field injection elsewhere.

**Check:** add the seasonal result to the screen and verify that it can only come from the seasonal
list.

### 3. Keep an unqualified default beside the special variants

Create `DefaultWordsRepositoryImpl` with a fourth disjoint word list. Add one unqualified `@Binds`
method returning `WordsRepository`; keep all three qualified methods. Create `DefaultWordPicker`
whose constructor requests plain `WordsRepository` with no qualifier. Inject the picker into
`MainActivity` and add its value as a labeled default row so the unqualified key is reachable.

Before building, list the four resulting keys. Explain why the new binding does not collide with
`@BasicWords`, `@FancyWords`, or `@SeasonalWords` even though every method has the same return type.

**Check:** the original qualified consumers still resolve exactly as before, while the default
consumer resolves the unqualified implementation.

### 4. Use a qualifier supplied by Hilt

Create a constructor-injected `AppIdentity` that requests Android's application context:

```kotlin
class AppIdentity @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun packageName(): String = context.packageName
}
```

Use the import `dagger.hilt.android.qualifiers.ApplicationContext`, inject `AppIdentity` into the
activity, and display `com.example.myapp.practice`. Do not pass the activity itself where an
application context is sufficient.

### 5. Run exact-key failure experiments

Run one experiment at a time, execute `./gradlew :app:assembleDebug`, record the error, and restore
the green graph:

1. Remove the entire `@FancyWords` binding method while `WordManager` still requests that key.
2. Add a second unqualified `@Binds` returning `WordsRepository`.
3. Temporarily add `AnnotationTarget.CLASS` to `FancyWords`, put `@FancyWords` on
   `FancyWordsRepositoryImpl`, and remove the entire fancy binding method. The class annotation is
   now legal Kotlin but still does not bind the interface.
4. Temporarily replace `@SeasonalWords` at its binding with `@Named("seasonal")`, then request
   `@param:Named("sesonal")` from the picker.

Classify each failure as a missing exact key or two bindings for the same exact key. Notice that the
qualifier on an implementation class does not create an interface binding.

After restoring the graph, install and launch the app:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** basic, fancy, seasonal, default, and application-identity rows all display their expected
sources; no consumer depends on a concrete repository class.

---

## Answers to the self-check questions

1. A key is the requested type plus its qualifier. The absence of a qualifier is also part of the
   key, so plain `WordsRepository` differs from `@FancyWords WordsRepository`.
2. No. The injectable constructor creates the concrete `FancyWordsRepositoryImpl` key only. It does
   not tell Hilt that this implementation should answer an interface request.
3. A qualifier creates a key on a `@Binds`/`@Provides` method and requests that key on a constructor
   parameter, injected field, or module parameter. Putting it on the implementation class does not
   bind the interface.
4. Yes. Unqualified and qualified requests are distinct keys, so one default can coexist with any
   number of separately qualified variants.
5. A custom annotation has compiler-checked spelling, find-usages, and safe rename support. A typo
   inside `@Named` is still a valid string and survives name checking, then fails later as a missing
   binding.
