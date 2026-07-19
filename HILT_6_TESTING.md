# 6. Testing with Hilt -- swapping the graph for fakes

*Reading order: [1 - Setup](HILT_1_SETUP.md) -> [2 - Basics](HILT_2_BASICS.md) -> [3 - Qualifiers](HILT_3_QUALIFIERS.md) -> [4 - Scopes](HILT_4_SCOPES.md) -> [5 - ViewModels](HILT_5_VIEWMODELS.md) -> **6 - Testing** -> [7 - Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) -> [8 - Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) -> [9 - WorkManager](HILT_9_WORKMANAGER.md) -> [10 - Multi-module](HILT_10_MULTIMODULE.md)*

Parts 2-3 kept saying the payoff of depending on an *interface* is that you can swap the
implementation in tests. This is where you collect that payoff. By the end you can replace
the qualified `WordsRepository` bindings with fakes for one test, or for a whole test source set,
without touching production code -- and run them on the plain JVM with Robolectric.

---

## The one mental model to keep

**A Hilt test builds its own copy of the graph, and you get to edit that copy before it's built.**

In production Hilt assembles one component tree from your modules. Under test, Hilt assembles a
*separate* tree from a `HiltTestApplication`, and gives you three hooks to change it:

1. **Replace a module everywhere** -- `@TestInstallIn` swaps a production module for a test module
   across the entire suite.
2. **Remove a module for one test** -- `@UninstallModules` drops it, and you supply the missing
   binding inline.
3. **Bind a field's value directly** -- `@BindValue` injects a value you hold in the test class.

Everything else -- `@Inject`, scopes, qualifiers -- behaves exactly as in production. A test class is
just one more injection site, marked `@HiltAndroidTest` instead of `@AndroidEntryPoint`.

---

## 1. Dependencies and the test application

Hilt tests need the testing artifact and a compiler for the test source set. Add both variants so
the graph is generated for local (`test/`) **and** instrumented (`androidTest/`) tests:

```kotlin
// app/build.gradle.kts
dependencies {
    // local JVM tests (Robolectric)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.dagger:hilt-android-testing:2.60")
    kspTest("com.google.dagger:hilt-android-compiler:2.60")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    // instrumented tests (on a device/emulator)
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.60")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")

    // Needed only for the Compose UI example in section 6.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
```

Every Hilt test runs against `HiltTestApplication` (supplied by the Hilt testing artifact), not
your real `@HiltAndroidApp` class. Hilt generates and manages the test components behind that
application. You select it differently per source set:

- **Local / Robolectric:** `@Config(application = HiltTestApplication::class, sdk = [34])`.
- **Instrumented:** a custom runner that swaps the application in:

```kotlin
package com.example.myapp

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

```kotlin
// app/build.gradle.kts -> android { defaultConfig { ... } }
testInstrumentationRunner = "com.example.myapp.HiltTestRunner"
```

Forgetting the runner is the #1 instrumented-test failure -- see the error table.

---

## 2. The minimal test

This is the common Hilt-rule core of a test. As written, place it in `androidTest/` under the
custom runner from section 1. For a local `test/` version, add the `@Config` annotation from section 5.

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WordManagerTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)   // must run first

    @Inject lateinit var wordManager: WordManager     // filled by hiltRule.inject()

    @Before fun setUp() {
        hiltRule.inject()                             // performs field injection
    }

    @Test fun producesAWord() {
        assertTrue(wordManager.nextWord().isNotBlank())
    }
}
```

Two rules mirror the Activity case from part 2 section 1:

1. **`HiltAndroidRule` must run before any rule that touches an injected field.** If you have other
   rules, order them -- `@get:Rule(order = 0) val hiltRule = ...`.
2. **A test class's injected fields are not usable until `hiltRule.inject()`** (call it in
   `@Before`). Reading one earlier throws `UninitializedPropertyAccessException` -- the same failure
   as touching an Activity field before `super.onCreate()`. An `@AndroidEntryPoint` Activity is
   injected through its own lifecycle; ordering Hilt's rule first prepares its test component
   before an Activity/Compose rule launches it.

This test uses the **real** graph -- `WordManager` reaches the real qualified
`FancyWordsRepositoryImpl`. That's a fine integration test. To swap in a fake, use one of the next
two techniques.

---

## 3. Swapping the whole suite -- `@TestInstallIn`

Write the fake, then a test module that *replaces* the production `RepositoryModule`:

```kotlin
package com.example.myapp.core

class FakeWordsRepository(
    private val words: List<String> = listOf("test-word"),
) : WordsRepository {
    override val instanceId: Int = -1

    private var requests = 0
    override val requestCount: Int
        get() = requests

    override fun getWords(): List<String> {
        requests++
        return words
    }
}
```

```kotlin
package com.example.myapp.core.di

import com.example.myapp.core.FakeWordsRepository
import com.example.myapp.core.WordsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class],   // production module this stands in for
)
object FakeRepositoryModule {

    @BasicWords
    @Singleton
    @Provides
    fun provideBasicWordsRepository(): WordsRepository =
        FakeWordsRepository(listOf("test-basic-word"))

    @FancyWords
    @Singleton
    @Provides
    fun provideFancyWordsRepository(): WordsRepository =
        FakeWordsRepository(listOf("test-fancy-word"))
}
```

Put it in `androidTest/` (or `test/`). Now **every** Hilt test in that source set gets the fake
bindings, and you changed nothing in production. `replaces` must name the exact installed module.
The robust default is to reproduce all of that module's keys -- here both
`@BasicWords WordsRepository` and `@FancyWords WordsRepository` -- including scopes whose sharing
your tests rely on. Strictly, only removed keys reachable in that test graph have to be supplied.
This is the right tool for a fake you want everywhere (a fake network layer, an in-memory
database).

Source sets are independent. For example, keep this suite-wide module in `androidTest/` while a
real-graph test and the per-test replacement below live in `test/`. If both replacement techniques
sit in the same source set, the suite-wide test module is still present; uninstalling the
*production* module does not uninstall its `@TestInstallIn` replacement.

---

## 4. Swapping for one test -- `@UninstallModules` + `@BindValue`

When only one test needs a different binding, uninstall the production module for that class and
bind a field instead:

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.FakeWordsRepository
import com.example.myapp.core.WordManager
import com.example.myapp.core.WordsRepository
import com.example.myapp.core.di.FancyWords
import com.example.myapp.core.di.RepositoryModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
@UninstallModules(RepositoryModule::class)   // drop the real binding for this test class
class WordManagerFakeTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @field:BindValue
    @field:FancyWords
    @JvmField
    val fancyRepository: WordsRepository = FakeWordsRepository()   // fills the exact key

    @Inject lateinit var wordManager: WordManager

    @Before fun setUp() = hiltRule.inject()

    @Test fun usesTheFake() {
        assertEquals("test-word", wordManager.nextWord())
    }
}
```

- `@BindValue` adds the field's current value under its exact key. `WordManager` requests
  `@FancyWords WordsRepository`, so both the declared type and `@FancyWords` are required.
- Uninstalling `RepositoryModule` also removes `@BasicWords WordsRepository`. This test never
  reaches `WordComparison`, so only the fancy key needs filling. A test that requests both variants
  must bind both.
- `@JvmField` is optional with Hilt 2.60. It exposes the property as a Java field, as shown; without
  it, Hilt uses the property's non-private getter. A private property/getter is not accessible.
- The value is a normal test property: make it a `var` if a test must replace it, use a Mockito
  mock, and inspect it from the test body. That addressability is the advantage over
  `@TestInstallIn`.

Sets and maps have `@BindValueIntoSet` / `@BindValueIntoMap`.

---

## 5. Running on the JVM -- Robolectric

You don't need a device to exercise the graph. Robolectric runs the whole thing on the JVM:

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapp.core.WordManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class WordManagerRobolectricTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var wordManager: WordManager
    @Before fun setUp() = hiltRule.inject()

    @Test fun works() = assertTrue(wordManager.nextWord().isNotBlank())
}
```

with:

```kotlin
testImplementation("org.robolectric:robolectric:4.14.1")
testImplementation("androidx.test.ext:junit:1.2.1")

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}
```

This is the fastest verification loop for DI wiring: no emulator, runs in `./gradlew testDebugUnitTest`.

---

## 6. Testing a `@HiltViewModel` and the UI

A `@HiltViewModel` is validated at compile time like any binding, so a graph test that injects its
dependencies already proves it can be built. To exercise it as a ViewModel, construct it directly
with a fake (constructor injection makes this trivial -- part 2's payoff):

```kotlin
@Test fun viewModelExposesAWord() {
    val vm = WordViewModel(
        wordManager = WordManager(FakeWordsRepository()),
        savedStateHandle = SavedStateHandle(),
    )   // no Hilt needed -- just constructors

    assertEquals("test-word", vm.currentWord)
    vm.chooseAndSaveAnotherWord()
    assertEquals(1, vm.savedChanges)
}
```

For Compose/Activity UI tests, use an existing `@AndroidEntryPoint` Activity when that is the host
you want to verify. Hilt's rule must wrap the rule that launches it:

```kotlin
@get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
@get:Rule(order = 1) val composeRule = createAndroidComposeRule<MainActivity>()

@Before fun setUp() = hiltRule.inject()
```

If you need an otherwise empty test host, Hilt does not ship one. Put an
`@AndroidEntryPoint HiltTestActivity` in `src/debug/java` and declare it in
`src/debug/AndroidManifest.xml`, because the Activity class and manifest entry must belong to the
target debug APK--not only the test APK. Then point the Compose rule at it. This part needs a
device/emulator; the graph itself is already covered by the JVM tests above.

---

## Testing -- error -> cause

| Error | Cause |
|---|---|
| Instrumented test: `IllegalStateException: Hilt test, ... cannot use a @HiltAndroidApp application but found ...MyApplication` | The custom runner was not selected, so the test launched the real app instead of `HiltTestApplication` -- set `testInstrumentationRunner = "...HiltTestRunner"` (section 1) |
| `UninitializedPropertyAccessException` on an `@Inject` field | Forgot `hiltRule.inject()` in `@Before`, or read the field before it ran |
| `[Hilt] @BindValue fields cannot be private. Found: ...` | The annotated Kotlin property is private; expose a non-private property/getter or use an accessible `@JvmField` |
| `[Dagger/MissingBinding] @FancyWords WordsRepository cannot be provided...` | The test supplied plain `WordsRepository` or removed the module without filling the qualified key that a reachable consumer requests |
| `WordsRepository is bound multiple times` in a test | A `@TestInstallIn` (or `@BindValue`) adds the same qualified key without `replaces`/`@UninstallModules` removing the existing one -- they collide |
| `@TestInstallIn#replaces() can only contain @InstallIn modules, but found: ...` | `replaces` names a class that is not an installed Hilt module |
| Tests still use real values, or the fake keys collide with production keys | The replacement is in the wrong source set or names a different valid module, so the intended production module was not replaced |
| Robolectric test: `Unable to resolve host` / real network hit | The real module wasn't replaced -- Robolectric runs production bindings unless you swap them (sections 3 and 4) |
| `Tests cannot be annotated with @AndroidEntryPoint. Please use @HiltAndroidTest` | You annotated the test class with `@AndroidEntryPoint` instead of `@HiltAndroidTest` |

---

## Where to go next

**[7 - Entry points & `Lazy`/`Provider`](HILT_7_ENTRYPOINTS_LAZY.md)** -- reaching the graph from
classes Hilt doesn't create, and deferring or repeating instance creation.

## Quick reference

| I want to... | Do this |
|---|---|
| Mark a test as an injection site | `@HiltAndroidTest` + `@get:Rule(order = 0)` for `HiltAndroidRule` + `hiltRule.inject()` |
| Run against the right app | Robolectric: `@Config(application = HiltTestApplication::class, sdk = [34])`; instrumented: custom `HiltTestRunner` |
| Replace a module for the whole suite | `@TestInstallIn(components = [...], replaces = [RealModule::class])` |
| Replace a binding for one test | `@UninstallModules(RealModule::class)` + an accessible, qualifier-matching `@BindValue` property (`@JvmField` optional) |
| Test a ViewModel | Construct it directly with a fake -- no Hilt |
| Test Compose UI with Hilt | Hilt rule at order 0 + `createAndroidComposeRule<AnAndroidEntryPointActivity>()` at order 1 |
