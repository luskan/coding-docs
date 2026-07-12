# 9. WorkManager — injecting into a Worker

*Reading order: [1 · Setup](HILT_1_SETUP.md) → [2 · Basics](HILT_2_BASICS.md) → [3 · Qualifiers](HILT_3_QUALIFIERS.md) → [4 · Scopes](HILT_4_SCOPES.md) → [5 · ViewModels](HILT_5_VIEWMODELS.md) → [6 · Testing](HILT_6_TESTING.md) → [7 · Entry points & Lazy/Provider](HILT_7_ENTRYPOINTS_LAZY.md) → [8 · Coroutines](HILT_8_COROUTINES_DISPATCHERS.md) → **9 · WorkManager** → [10 · Multi-module](HILT_10_MULTIMODULE.md)*

A `Worker` is created by the WorkManager framework, not by you and not by Hilt — the same problem
ViewModels had in part 5, and it's solved the same way: **assisted injection**. `@HiltWorker`
is the WorkManager-shaped wrapper around the `@AssistedInject` machinery, and it comes with three
pieces of one-time wiring that, if skipped, fail at *runtime* rather than compile time.

---

## The one mental model to keep

WorkManager builds your `Worker` and supplies two arguments at runtime — a `Context` and a
`WorkerParameters`. Your own dependencies (a repository) come from the graph. That's the exact
"some args at runtime, some from Hilt" split of assisted injection:

- **`@Assisted`** marks the two framework-supplied arguments,
- **the rest** are normal injected dependencies,
- **`@HiltWorker`** generates the factory glue — so unlike part 5's ViewModel case, you do **not**
  write an `@AssistedFactory` yourself. `HiltWorkerFactory` is that factory, and you hand it to
  WorkManager once, in your `Application`.

---

## 1. Dependencies — two processors, not one

`@HiltWorker` lives in the **AndroidX** Hilt library, which has its *own* annotation processor —
separate from Dagger's `hilt-android-compiler` you added in part 1. You need both KSP processors:

```kotlin
// app/build.gradle.kts
dependencies {
    // from part 1 — Dagger/Hilt core
    implementation("com.google.dagger:hilt-android:2.60")
    ksp("com.google.dagger:hilt-android-compiler:2.60")

    // WorkManager + AndroidX Hilt integration
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")     // AndroidX processor for @HiltWorker
}
```

The two compilers are a classic confusion: `com.google.dagger:hilt-android-compiler` processes
`@Inject`/`@Module`/`@HiltViewModel`; `androidx.hilt:hilt-compiler` processes `@HiltWorker`. Missing
the second means `@HiltWorker` is silently ignored.

## 2. The worker

```kotlin
package com.example.myapp.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myapp.core.WordsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWordsWorker @AssistedInject constructor(
    @Assisted context: Context,               // supplied by WorkManager
    @Assisted params: WorkerParameters,       // supplied by WorkManager
    private val repository: WordsRepository,   // supplied by Hilt
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        repository.getWords()   // real dependency, injected
        return Result.success()
    }
}
```

Rules that are easy to get wrong:

- the constructor is **`@AssistedInject`**, not `@Inject` (part 5's assisted rule),
- the two `@Assisted` parameters must be exactly `Context` and `WorkerParameters` — that pair is how
  `HiltWorkerFactory` recognizes them,
- everything else is a normal injected dependency.

## 3. The one-time wiring in `Application`

This is the step that has no compile-time safety net. Three things must all be true, or the worker
fails to construct at runtime.

**(a) Provide the factory.** Make your `@HiltAndroidApp` class implement `Configuration.Provider` and
hand WorkManager the injected `HiltWorkerFactory`:

```kotlin
package com.example.myapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

(`workManagerConfiguration` is a **property** override in WorkManager 2.9+, not the old
`getWorkManagerConfiguration()` method.)

**(b) Disable WorkManager's default auto-initializer** in `AndroidManifest.xml`, so WorkManager waits
for your on-demand configuration instead of initializing itself with the *default* factory (which
can't build an injected worker):

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

(`xmlns:tools="http://schemas.android.com/tools"` must be on the `<manifest>` root.)

**(c) Enqueue as usual** — nothing Hilt-specific here:

```kotlin
WorkManager.getInstance(context)
    .enqueue(OneTimeWorkRequestBuilder<SyncWordsWorker>().build())
```

## 4. Why all three, and why runtime

WorkManager instantiates workers reflectively through a `WorkerFactory`. The default factory only
knows the `(Context, WorkerParameters)` constructor — it has no idea how to supply your
`repository`. `HiltWorkerFactory` does, but WorkManager only uses it if you (a) provide it and (b)
stop the default initializer from claiming WorkManager first with the default factory. Miss either
and you get a runtime `Could not instantiate …Worker`, never a compile error — which is why this
page belabors the wiring.

---

## WorkManager — error → cause

| Error | Cause |
|---|---|
| Runtime `Could not instantiate com.example.myapp.work.SyncWordsWorker` / `WorkerFactory returned null` | The default initializer wasn't removed (§3b), or `Application` doesn't implement `Configuration.Provider` / doesn't set the factory (§3a) |
| `@HiltWorker` seemingly ignored; worker built with default factory | Missing the **AndroidX** processor `ksp("androidx.hilt:hilt-compiler:…")` (§1) |
| Compile: `@HiltWorker … must be annotated with @AssistedInject` | Constructor is `@Inject` instead of `@AssistedInject` (§2) |
| Compile: `Expected @Assisted … Context` / `WorkerParameters` | The two `@Assisted` params are missing, reordered to a wrong type, or not exactly `Context` + `WorkerParameters` |
| `HiltWorkerFactory cannot be provided …` when injecting it | Missing `implementation("androidx.hilt:hilt-work:…")` (§1) |
| `Configuration.Provider` doesn't compile / wrong override | On WorkManager 2.9+ override the `workManagerConfiguration` **property**, not `getWorkManagerConfiguration()` |
| `IllegalStateException: WorkManager is already initialized` | You disabled the default initializer **and** still call `WorkManager.initialize()` manually — with `Configuration.Provider`, don't |

---

## Where to go next

**[10 · Hilt in a multi-module project](HILT_10_MULTIMODULE.md)** — the final part: where modules,
bindings, and `@InstallIn` live once the app is split across Gradle modules.

## Quick reference

| I want to… | Do this |
|---|---|
| Inject dependencies into a `Worker` | `@HiltWorker` + `@AssistedInject` constructor with `@Assisted Context, @Assisted WorkerParameters` |
| Wire the factory | `Application : Configuration.Provider`, inject `HiltWorkerFactory`, `setWorkerFactory(...)` |
| Stop the default init | Remove `WorkManagerInitializer` via `tools:node="remove"` in the manifest |
| Add the right processor | `ksp("androidx.hilt:hilt-compiler:…")` **and** `ksp("com.google.dagger:hilt-android-compiler:…")` |
| Enqueue it | `WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<MyWorker>().build())` |
