# Practice 6. Editing Hilt test graphs

*Tutorial: [6 - Testing](HILT_6_TESTING.md) - **Practice 6 of 10***

Start from the Part 6 state of [`hilt-practice-app/`](hilt-practice-app/). It keeps the local and
instrumented replacement strategies in different source sets on purpose: `test/` contains a real
graph, a per-test `@BindValue`, and a plain ViewModel unit test; `androidTest/` contains the
suite-wide `@TestInstallIn` fake and a Compose test that runs on a device. Work on a throwaway
branch and restore the green graph after each failure experiment.

## Self-check questions

1. Why does a Hilt test use `HiltTestApplication` and its own component tree instead of mutating
   the graph owned by `MyApplication`?
2. When should you use `@TestInstallIn`, and when should you use `@UninstallModules` with
   `@BindValue`?
3. Why must a fake binding repeat the production qualifier, and what access does Hilt 2.60 need to
   a Kotlin `@BindValue` property?
4. What does `HiltAndroidRule` do, when must `inject()` run, and why must that rule wrap an Activity
   or Compose rule?
5. How do Robolectric and instrumentation select `HiltTestApplication`, and when is direct
   ViewModel construction a better test?

Answer these before looking at the final section.

## Practical tasks

### 1. Map the two test source sets

Inspect `app/build.gradle.kts`, `app/src/test/`, and `app/src/androidTest/`. For every dependency,
KSP configuration, application-selection mechanism, fake, and test module, state which source set
can see it.

Run these commands separately:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** the first runs four tests on the JVM, the second compiles the target code and packages the
instrumentation test APK without running it, and the third builds/installs both APKs and runs one
test on the attached device. AGP's test infrastructure may remove those packages afterward, so the
fourth command reinstalls the normal debug app before the explicit launch. Confirm that the device
test launches the real `MainActivity` but reports `device-fancy-word`, while the subsequent normal
launch shows a production fancy word.

Explain why `assembleDebug` alone proves neither source set's tests ran.

### 2. Strengthen the real-graph Robolectric test

Extend `WordManagerRealGraphTest` to inject the two concrete singleton repositories as well as
`WordManager` and `WordComparison`. Assert all of the following:

- `WordManager` returns a value from the production fancy list,
- `WordComparison` reaches both disjoint production lists,
- the `@FancyWords WordsRepository` alias and concrete fancy repository identify the same scoped
  object (use `wordManager.usesRepository(concreteFancyRepository)`), and
- repeated requests increase the repository's observable request count.

Keep `@Config(application = HiltTestApplication::class, sdk = [34])` and run only this class:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.WordManagerRealGraphTest
```

Temporarily read `wordManager` before `hiltRule.inject()` or remove the `@Before` call.

**Check:** the test-class property is still uninitialized. Restore injection before continuing.

### 3. Exercise an exact per-test key

Pass a different one-item list to `FakeWordsRepository` in `WordManagerBindValueTest` and prove that
the test can inspect the same fake object after `WordManager` uses it. Then run these failure
experiments one at a time:

1. Remove `@field:FancyWords` from the property. The fake now creates plain `WordsRepository`,
   which cannot satisfy the requested `@FancyWords WordsRepository` key.
2. Remove `@UninstallModules(RepositoryModule::class)` but retain the qualified fake. The
   production and test bindings now collide on the same key.
3. Remove `@JvmField` and make the property `private`. Hilt 2.60 can use a non-private Kotlin
   getter, but this private field fails with `[Hilt] @BindValue fields cannot be private`.

Restore the green test. Finally, remove only `@JvmField` while leaving the property non-private.

**Check:** it still compiles and passes because Hilt uses the accessible getter. Decide whether the
explicit Java field or ordinary Kotlin property is clearer for this test and keep one consistently.

### 4. Move a suite-wide replacement deliberately

In `DeviceFakeRepositoryModule`, change the basic and fancy fake values and verify that every Hilt
test in `androidTest/` observes them. Keep both providers qualified and `@Singleton` so the test
graph retains the production keys and effective sharing behavior.

Copy the module into `test/` temporarily and rebuild the JVM suite.

**Check:** compilation deterministically fails with `[Dagger/DuplicateBindings]` for
`@FancyWords WordsRepository` in the per-test `WordManagerBindValueTest`. Its
`@UninstallModules(RepositoryModule::class)` removes the production module, not the separate
`@TestInstallIn` replacement, so that suite-wide fake remains beside the `@BindValue` for the same
key. No JVM test runs while that duplicate graph is present. This also shows why a global module in
`test/` would invalidate the intended real-graph test. Remove the copied module.

As a processor failure lab, put a class without `@InstallIn` in `replaces`. Confirm the Hilt 2.60
diagnostic starts with:

```text
@TestInstallIn#replaces() can only contain @InstallIn modules, but found:
```

Restore `RepositoryModule::class` afterward.

### 5. Prove the instrumented runner and rule order

Trace the device test startup in this order:

1. `HiltTestRunner` substitutes `HiltTestApplication`.
2. `HiltAndroidRule` at order `0` prepares the test component.
3. The Compose rule at order `1` launches the production `@AndroidEntryPoint MainActivity`.
4. `hiltRule.inject()` fills the test class's own fields.
5. The assertion sees the suite-wide fake both through the injected `WordManager` and on screen.

Temporarily configure plain `AndroidJUnitRunner` and run the device test.

**Check:** `HiltAndroidRule` rejects the real `@HiltAndroidApp` application with an
`IllegalStateException` saying the Hilt test cannot use that application. Restore
`HiltTestRunner`.

Then swap the two rule orders.

**Check:** the Activity-launching rule can run before Hilt has prepared its test component. Record
the resulting initialization failure, restore Hilt to order `0`, and get the device test green
again.

### 6. Separate ViewModel logic from graph wiring

Add a third method to `WordViewModelUnitTest`. Construct the current ViewModel directly with a fake
`WordManager` and this handle:

```kotlin
val handle = SavedStateHandle(
    mapOf(
        "word" to "saved-word",
        "changes" to 4,
    ),
)
```

Assert the restored values, call `chooseAndSaveAnotherWord()`, and verify both the ViewModel and
the handle contain the new word and a changes count of `5`.

Run only this class:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.example.myapp.WordViewModelUnitTest
```

**Check:** this test needs neither `@HiltAndroidTest` nor Robolectric configuration. Explain what
it proves about ViewModel logic, and what the separate Hilt graph tests still prove that it cannot.

---

## Answers to the self-check questions

1. Hilt tests need isolated components so replacements and test-owned values cannot mutate the
   production application's graph or leak between test cases. `HiltTestApplication` hosts those
   generated test components while `MyApplication` remains the normal app entry point.
2. Use `@TestInstallIn` when one module replacement should affect every Hilt test in a source set.
   Use `@UninstallModules` plus `@BindValue` when one test class needs a specific value that the
   test body can also inspect or change.
3. A binding key is type plus qualifier, so plain `WordsRepository` cannot answer a request for
   `@FancyWords WordsRepository`. Hilt 2.60 can read either an accessible `@JvmField` or an
   accessible Kotlin property getter; `@JvmField` is optional, but a private field/getter is not
   usable.
4. `HiltAndroidRule` prepares the test component, and `inject()` fills the test class's `@Inject`
   fields before the test reads them. Giving Hilt's rule the lower order lets it prepare the
   component before another rule launches an `@AndroidEntryPoint` Activity or Compose host.
5. Robolectric selects the test application with
   `@Config(application = HiltTestApplication::class, sdk = [34])`; instrumentation uses the
   configured custom runner. Direct construction is best for isolated ViewModel behavior. Hilt
   tests remain necessary when the subject is graph wiring, replacements, Android injection, or UI
   integration.
