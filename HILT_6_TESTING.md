# 6. Testing with Hilt — swapping the graph for fakes

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → **6 · Testing** → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → [9 · WorkManager](HILT_9_WORKMANAGER.md) → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

Parts 2–3 kept saying the payoff of depending on an *interface* is that you can swap the
implementation in tests. This is where you collect that payoff. By the end you can replace
`WordsRepositoryImpl` with a `FakeWordsRepository` for one test, or for the whole test suite, without
touching production code — and run it on the plain JVM with Robolectric.

---

## The one mental model to keep

**A Hilt test builds its own copy of the graph, and you get to edit that copy before it's built.**

In production Hilt assembles one component tree from your modules. Under test, Hilt assembles a
*separate* tree from a `HiltTestApplication`, and gives you three hooks to change it:

1. **Replace a module everywhere** — `@TestInstallIn` swaps a production module for a test module
   across the entire suite.
2. **Remove a module for one test** — `@UninstallModules` drops it, and you supply the missing
   binding inline.
3. **Bind a field's value directly** — `@BindValue` injects a value you hold in the test class.

Everything else — `@Inject`, scopes, qualifiers — behaves exactly as in production. A test class is
just one more injection site, marked `@HiltAndroidTest` instead of `@AndroidEntryPoint`.

---

## 1. Dependencies and the test application

Hilt tests need the testing artifact and a compiler for the test source set. Add both variants so
the graph is generated for local (`test/`) **and** instrumented (`androidTest/`) tests:

```kotlin
// app/build.gradle.kts
dependencies {
    // local JVM tests (Robolectric)
    testImplementation("com.google.dagger:hilt-android-testing:2.60")
    kspTest("com.google.dagger:hilt-android-compiler:2.60")

    // instrumented tests (on a device/emulator)
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.60")
}
```

Every Hilt test runs against `HiltTestApplication` (Hilt generates it), not your real
`@HiltAndroidApp` class. You select it differently per source set:

- **Local / Robolectric:** `@Config(application = HiltTestApplication::class)`.
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
// app/build.gradle.kts → android { defaultConfig { ... } }
testInstrumentationRunner = "com.example.myapp.HiltTestRunner"
```

Forgetting the runner is the #1 instrumented-test failure — see the error table.

---

## 2. The minimal test

```kotlin
package com.example.myapp

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class WordManagerTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)   // must run first

    @Inject lateinit var wordManager: WordManager     // filled by hiltRule.inject()

    @Before fun setUp() {
        hiltRule.inject()                             // performs field injection
    }

    @Test fun producesAWord() {
        assertTrue(wordManager.nextWord().isNotBlank())
    }
}
```

Two rules mirror the Activity case from part 2 §1:

1. **`HiltAndroidRule` must run before any rule that touches an injected field.** If you have other
   rules, order them — `@get:Rule(order = 0) val hiltRule = …`.
2. **Nothing injected is usable until `hiltRule.inject()`** (call it in `@Before`). Reading an
   `@Inject` field earlier throws `UninitializedPropertyAccessException` — the same failure as
   touching an Activity field before `super.onCreate()`.

This test uses the **real** graph — real `WordsRepositoryImpl`. That's a fine integration test. To
swap in a fake, use one of the next three techniques.

---

## 3. Swapping the whole suite — `@TestInstallIn`

Write the fake, then a test module that *replaces* the production `RepositoryModule`:

```kotlin
package com.example.myapp.core

class FakeWordsRepository : WordsRepository {
    override fun getWords(): List<String> = listOf("test-word")
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

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [RepositoryModule::class],   // production module this stands in for
)
object FakeRepositoryModule {

    @Provides
    fun provideWordsRepository(): WordsRepository = FakeWordsRepository()
}
```

Put it in `androidTest/` (or `test/`). Now **every** Hilt test in that source set gets
`FakeWordsRepository`, and you changed nothing in production. `replaces` must name the exact module
whose bindings you're overriding — it and the replacement must cover the same keys. This is the
right tool for a fake you want everywhere (a fake network layer, an in-memory database).

---

## 4. Swapping for one test — `@UninstallModules` + `@BindValue`

When only one test needs a different binding, uninstall the production module for that class and
bind a field instead:

```kotlin
@HiltAndroidTest
@UninstallModules(RepositoryModule::class)   // drop the real binding for this test class
class WordManagerFakeTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField
    val repository: WordsRepository = FakeWordsRepository()   // this fills the hole

    @Inject lateinit var wordManager: WordManager

    @Before fun setUp() = hiltRule.inject()

    @Test fun usesTheFake() {
        assertEquals("test-word", wordManager.nextWord())
    }
}
```

- `@BindValue` adds the field's current value to this test's graph under its declared type
  (`WordsRepository`). Because a `WordsRepository` binding now exists again, `WordManager` resolves.
- `@JvmField` is required — `@BindValue` reads the field directly, so it must not be behind a getter.
- The value is a normal test field: mutate it in the test, use a Mockito mock, whatever. That's the
  advantage over `@TestInstallIn` — the fake is *addressable from the test body*.

Qualified bindings work the same way: `@BindValue @FancyWords val repo: WordsRepository = …` (part
3). Sets and maps have `@BindValueIntoSet` / `@BindValueIntoMap`.

---

## 5. Running on the JVM — Robolectric

You don't need a device to exercise the graph. Robolectric runs the whole thing on the JVM:

```kotlin
package com.example.myapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)   // the Hilt-generated test app
class WordManagerRobolectricTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var wordManager: WordManager
    @Before fun setUp() = hiltRule.inject()

    @Test fun works() = assertTrue(wordManager.nextWord().isNotBlank())
}
```

with:

```kotlin
testImplementation("org.robolectric:robolectric:4.14.1")
testImplementation("androidx.test.ext:junit:1.2.1")
// android { testOptions { unitTests { isIncludeAndroidResources = true } } }
```

This is the fastest verification loop for DI wiring: no emulator, runs in `./gradlew testDebugUnitTest`.

---

## 6. Testing a `@HiltViewModel` and the UI

A `@HiltViewModel` is validated at compile time like any binding, so a graph test that injects its
dependencies already proves it can be built. To exercise it as a ViewModel, construct it directly
with a fake (constructor injection makes this trivial — part 2's payoff):

```kotlin
@Test fun viewModelExposesAWord() {
    val vm = WordViewModel(FakeWordsRepository())   // no Hilt needed — just a constructor
    assertEquals("test-word", vm.currentWord)
}
```

For Compose/Activity UI tests, Hilt has no built-in test Activity, so you add an empty
`@AndroidEntryPoint` one in `androidTest/` and point the Compose rule at it:

```kotlin
@AndroidEntryPoint class HiltTestActivity : ComponentActivity()   // androidTest/ + registered in a debug manifest

@get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
@get:Rule(order = 1) val composeRule = createAndroidComposeRule<HiltTestActivity>()
```

`HiltTestActivity` must be declared in an `AndroidManifest.xml` under `debug/` (or `androidTest/`) so
the instrumentation can launch it. This part needs a device/emulator; the graph itself is already
covered by the JVM tests above.

---

## Testing — error → cause

| Error | Cause |
|---|---|
| Instrumented test crashes at runtime: `ClassCastException: com.example.myapp.MyApplication cannot be cast to …TestApplicationComponentManagerHolder` | The test isn't running under the custom runner, so it launched your real `@HiltAndroidApp` app instead of `HiltTestApplication` (only the test application implements that interface) — set `testInstrumentationRunner = "…HiltTestRunner"` (§1) |
| `UninitializedPropertyAccessException` on an `@Inject` field | Forgot `hiltRule.inject()` in `@Before`, or read the field before it ran |
| `@BindValue` field … `must be … accessible` / not applied | Missing `@JvmField`, or the field is `private` |
| `WordsRepository is bound multiple times` in a test | A `@TestInstallIn` (or `@BindValue`) adds a binding without `replaces`/`@UninstallModules` removing the production one — they collide |
| `TestInstallIn … replaces module that is not installed` / bindings still real | `replaces` names the wrong module, or the test module sits in the wrong source set |
| Robolectric test: `Unable to resolve host` / real network hit | The real module wasn't replaced — Robolectric runs production bindings unless you swap them (§3/§4) |
| `Hilt tests cannot use @AndroidEntryPoint … use @HiltAndroidTest` | You annotated the test class with `@AndroidEntryPoint` instead of `@HiltAndroidTest` |

---

## Where to go next

**[7 · Entry points & `Lazy`/`Provider`](HILT_7_ENTRYPOINTS_LAZY.md)** — reaching the graph from
classes Hilt doesn't create, and deferring or repeating instance creation.

## Quick reference

| I want to… | Do this |
|---|---|
| Mark a test as an injection site | `@HiltAndroidTest` + `@get:Rule val hiltRule = HiltAndroidRule(this)` + `hiltRule.inject()` |
| Run against the right app | Robolectric: `@Config(application = HiltTestApplication::class)`; instrumented: custom `HiltTestRunner` |
| Replace a module for the whole suite | `@TestInstallIn(components = […], replaces = [RealModule::class])` |
| Replace a binding for one test | `@UninstallModules(RealModule::class)` + `@BindValue @JvmField val fake = …` |
| Test a ViewModel | Construct it directly with a fake — no Hilt |
| Test Compose UI with Hilt | Empty `@AndroidEntryPoint HiltTestActivity` + `createAndroidComposeRule<HiltTestActivity>()` |
