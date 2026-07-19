# Practice 5. Hilt ViewModels and saved state

*Tutorial: [5 - ViewModels](HILT_5_VIEWMODELS.md) - **Practice 5 of 10***

Start from the Part 5 state of [`hilt-practice-app/`](hilt-practice-app/). Its screen deliberately
requests one `WordViewModel` through both an Activity delegate and Compose, exposes identity and
lifetime evidence, and creates a second ViewModel with an assisted runtime argument. Work on a
throwaway branch and restore the green graph after each failure experiment.

## Self-check questions

1. Who decides when a Hilt ViewModel is created, retained, and cleared, and who decides how its
   constructor dependencies are built?
2. Which annotations and request APIs are required for a normal Hilt ViewModel and its host?
3. Which state survives rotation automatically, and when is `SavedStateHandle` needed?
4. Why can a ViewModel inject a singleton or `@ViewModelScoped` object but not an
   `@ActivityScoped` object or activity `Context`?
5. What is the preferred way to pass a runtime item ID to a ViewModel, and what extra pieces does
   the assisted-injection fallback require?

Answer these before looking at the final section.

## Practical tasks

### 1. Prove that both request APIs use the same owner

Trace the existing requests from `MainActivity` into `WordScreen`:

- `WordViewModel by viewModels()` in the Activity,
- `WordViewModel = hiltViewModel()` in the composable's default argument.

Build and launch the app. Record the ViewModel instance ID and the value of
`Activity delegate === Compose`. Rotate twice and record them again.

**Check:** the identity comparison remains `true` and the ViewModel ID stays unchanged. Both calls
use the Activity as their `ViewModelStoreOwner` at this point in the UI tree. The
`@ActivityScoped` session ID changes, which proves that retaining a ViewModel is not the same as
retaining the Activity or its component.

Next, pass a non-default key to the Compose call:

```kotlin
hiltViewModel<WordViewModel>(key = "practice")
```

**Check:** the Activity and Compose requests now return different ViewModels even though they have
the same owner and class. Restore the default call before continuing.

### 2. Put mutable UI state in the ViewModel

Extend `WordViewModel` with a second counter called `wordsAccepted`. Increment it from a new button
and display it next to the existing word. First keep it only in a Compose state property owned by
the ViewModel; do not put it in the Activity or composable.

Press the button twice and rotate.

**Check:** the counter and current word survive because the same ViewModel is retained. If either
value resets, find the state that accidentally lives outside the ViewModel.

### 3. Distinguish retention from saved-state restoration

Store `wordsAccepted` in the injected `SavedStateHandle`, just as the existing `word` and
`changes` values are stored. Change all three visible values, then background and kill only the
app's process:

```bash
adb shell input keyevent KEYCODE_HOME
adb shell am kill com.example.myapp.practice
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** Android constructs a new ViewModel after process death, but the word and both counters
are restored from saved state. The ViewModel instance ID may restart at `1` because its teaching
counter is process-local; that is not evidence that the old object survived.

Repeat once after removing only the `SavedStateHandle` read/write for `wordsAccepted`.

**Check:** rotation still retains that counter, while process death resets it. Restore the saved
state code afterward. Do not use `am force-stop` for this exercise: force-stop represents an
explicitly stopped package and is not an ordinary background process-death simulation.

### 4. Measure `@ViewModelScoped`

Create `ViewModelWordSession @Inject constructor()` with an instance ID and annotate the class
with `@ViewModelScoped`. Inject it through each of two unscoped helper classes, then inject both
helpers into `WordViewModel`. Expose whether the helpers received the same session.

Also inject `ViewModelWordSession` into `WordDetailViewModel` and display its ID beside the normal
ViewModel's session ID.

**Check:** both helper paths inside `WordViewModel` share one session, while
`WordDetailViewModel` gets a different session because it owns a different `ViewModelComponent`.
Both IDs remain stable on rotation.

### 5. Supply a real assisted runtime value

Replace the fixed detail ID with an Activity intent extra:

```kotlin
val wordId = intent.getIntExtra("word_id", 7)
```

Pass it into `WordScreen`, then forward it through the existing assisted creation callback. Keep
the explicit ViewModel and factory type arguments:

```kotlin
hiltViewModel<WordDetailViewModel, WordDetailViewModel.Factory>(
    creationCallback = { factory -> factory.create(wordId) },
)
```

Launch with a different ID:

```bash
adb shell am force-stop com.example.myapp.practice
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity \
  --ei word_id 11
```

**Check:** the screen reports assisted word `#11`; Hilt still supplies the qualified repository,
while the callback supplies only the runtime ID. Explain why a navigation argument in
`SavedStateHandle` would be preferable in a Navigation Compose destination.

### 6. Read three failures as ownership and lifetime errors

Run one experiment at a time, capture the result, and restore the green app:

1. Add `@Inject lateinit var invalidViewModel: WordViewModel` to `MainActivity`. Build and identify
   Dagger's diagnostic prohibiting direct injection of a `@HiltViewModel`; request it through
   `ViewModelProvider` instead.
2. Add `WordSession`--the existing `@ActivityScoped` class--as a constructor parameter of
   `WordViewModel`. Build and trace why `ViewModelComponent` cannot see a binding from its sibling
   `ActivityComponent`.
3. In an Activity reduced to only `private val viewModel: WordViewModel by viewModels()`, remove
   `@AndroidEntryPoint`. The graph can compile, but launching uses the stock factory and cannot
   construct the injected ViewModel. Restore the annotation and the original Activity afterward.

Finish by running `./gradlew :app:assembleDebug`, installing the APK, and repeating the rotation
and process-death checks on the attached device.

---

## Answers to the self-check questions

1. AndroidX `ViewModelProvider`, acting through a `ViewModelStoreOwner`, controls creation timing,
   retention, and clearing. Hilt supplies the factory logic that resolves the constructor and its
   graph dependencies.
2. A normal ViewModel extends `ViewModel`, has `@HiltViewModel`, and has exactly one `@Inject`
   constructor. Its Activity or Fragment host has `@AndroidEntryPoint`, and callers request the
   ViewModel through `by viewModels()`, `by activityViewModels()`, or `hiltViewModel()` rather than
   injecting the ViewModel itself.
3. Properties held by a correctly requested ViewModel survive configuration changes because the
   same ViewModel is retained. Process death destroys that object; small restorable values must be
   written to `SavedStateHandle` and reconstructed from it when Android creates a new ViewModel.
4. `ViewModelComponent` descends from `ActivityRetainedComponent`, so it can see bindings in that
   ancestor chain and its own component, including singleton and ViewModel-scoped bindings.
   `ActivityComponent` is a sibling that is destroyed on rotation, so its activity-scoped objects
   and activity `Context` cannot safely be dependencies of a longer-lived ViewModel.
5. Prefer a navigation argument read from `SavedStateHandle`, because it participates in saved
   state with no custom factory callback. Assisted injection is the fallback: use an
   `@AssistedInject` constructor, mark runtime values `@Assisted`, declare an `@AssistedFactory`,
   name it in `@HiltViewModel(assistedFactory = ...)`, and call the assisted `hiltViewModel()`
   overload with explicit ViewModel and factory type arguments.
