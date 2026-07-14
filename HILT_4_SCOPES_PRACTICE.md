# Practice 4. Scopes and component lifetimes

*Tutorial: [4 · Scopes](HILT_4_SCOPES.md) · **Practice 4 of 10***

Start from the Part 4 state of [`hilt-practice-app/`](hilt-practice-app/). The screen exposes
instance IDs, request counts, and identity comparisons so lifecycle claims can be observed instead
of inferred. Work on a throwaway branch and restore the green graph after each experiment.

## Self-check questions

1. What does “one scoped instance” mean precisely?
2. Why is class-level `@Singleton` preferable to scoping only an interface's `@Binds` method when
   consumers may request both the interface and concrete type?
3. What happens to `@ActivityScoped` and `@ActivityRetainedScoped` objects on rotation?
4. How do `@InstallIn` and a scope annotation differ?
5. Why can an invalid scope relationship remain undetected until a consumer requests the binding?

Answer these before looking at the final section.

## Practical tasks

### 1. Measure the existing two lifetimes

Launch the app and record these rows:

- the `@Singleton` fancy repository ID, request count, and alias/concrete identity result,
- the `@ActivityScoped` session ID, identity result for two injections, and `wordsSeen` count.

Press **Next scoped word** twice, then rotate the device without killing the process.

**Check:** repository identity stays the same and its request count keeps increasing. Both session
fields still refer to each other, but the session ID changes and `wordsSeen` restarts for the new
activity instance.

### 2. Scope the alias instead of the implementation

Temporarily make these coordinated changes for the fancy binding only:

1. Remove `@Singleton` from `FancyWordsRepositoryImpl`.
2. Put `@Singleton` on the `@FancyWords @Binds` method.
3. Keep the direct concrete injection and the qualified interface consumers unchanged.
4. Add a field that requests the qualified interface key explicitly:

   ```kotlin
   @field:Inject
   @field:FancyWords
   lateinit var qualifiedFancyRepository: WordsRepository
   ```

   Display both repository IDs. Also compare this field with `WordManager`'s repository and with
   the direct concrete field. Temporarily relabel the existing `@Singleton fancy` UI row—the direct
   concrete value shown there is no longer the scoped one during this experiment.

Build and launch again.

**Check:** the explicit qualified field and `WordManager` share the binding cached under the
interface key. The direct `FancyWordsRepositoryImpl` request bypasses it, has a different ID, and
compares unequal.

Restore class-level `@Singleton` and remove it from `@Binds`. This makes “there is one concrete
repository” true regardless of which key reaches it.

### 3. Compare activity and activity-retained scopes

Add a simple `RetainedWordSession @Inject constructor()` annotated with
`@ActivityRetainedScoped`. Give it an instance ID, inject it into `MainActivity`, and display the ID
next to the existing activity-scoped session.

Rotate twice.

**Check:** each rotation creates a new `@ActivityScoped WordSession`, while
`RetainedWordSession` keeps the same ID across all activity recreations. Finish the activity and
launch it from the launcher again; the retained ID should then change because the retained
component ended with the activity's final destruction.

### 4. Make a hidden scope violation reachable

Add this class but do not request it yet:

```kotlin
@Singleton
class InvalidAppHolder @Inject constructor(
    private val wordSession: WordSession,
)
```

`WordSession` is `@ActivityScoped`, so an application-component singleton cannot depend on it. Run
`./gradlew :app:assembleDebug` while `InvalidAppHolder` is dead code and record whether Dagger
reports the invalid relationship.

Now inject `InvalidAppHolder` into `MainActivity` and rebuild.

**Check:** the reachable graph fails with `IncompatiblyScopedBindings` (or a diagnostic showing the
singleton binding cannot reference the activity-scoped binding). Remove the invalid class/request
and return to a green build.

### 5. Distinguish rotation from process death

First press **Next scoped word** until the repository request count is clearly above its initial
value. Rotate and confirm that count remains. Then background the app and kill only its process:

```bash
adb shell input keyevent KEYCODE_HOME
adb shell am kill com.example.myapp.practice
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** repository state returns to its initial count because `SingletonComponent` lasts for the
process, not forever. Static teaching IDs can start from `1` again after process death, so use the
reset request count—not the numeric ID alone—as the evidence.

---

## Answers to the self-check questions

1. Hilt caches one value for each instance of the component associated with that scope. A
   `@Singleton` is one per `SingletonComponent`; an `@ActivityScoped` value is one per
   `ActivityComponent`, not one for the whole program.
2. Class-level scoping caches the concrete key, so both the interface alias and direct concrete
   requests reach the same instance. Scoping only `@Binds` caches the interface key; direct concrete
   requests remain unscoped and can produce other instances.
3. Rotation destroys and recreates `ActivityComponent`, so an activity-scoped object is replaced.
   `ActivityRetainedComponent` survives configuration changes, so an activity-retained object stays
   until the activity is finally destroyed.
4. `@InstallIn` chooses the component where a module binding is visible and therefore which
   descendants can request it. A scope adds caching and ties that cached value to a matching
   component lifetime.
5. Dagger validates relationships along graph paths reachable from component entry/injection
   points. A dead binding needs a generated factory but need not be assembled into a component, so
   its invalid dependency can remain latent until something requests it.
