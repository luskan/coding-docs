# Practice 1. Hilt setup on AGP 9

*Tutorial: [1 · Setup](HILT_1_SETUP.md) · **Practice 1 of 10***

Use the project in [`hilt-practice-app/`](hilt-practice-app/). Its Hilt graph deliberately has no
reachable user bindings or injection sites at this point: Part 1 configures Hilt and registers the
application, while Part 2 adds the first reachable real injection. Do the experiments on a
throwaway branch so every intentional failure is easy to discard.

## Self-check questions

1. Why must the first segment of the KSP version match the project's Kotlin version?
2. Why should an AGP 9 project not apply `org.jetbrains.kotlin.android`?
3. What does `android.disallowKotlinSourceSets=false` work around?
4. Which two changes make `MyApplication` the root of the Hilt application graph?
5. Why is `error_prone_annotations` a `compileOnly` dependency, and when does its absence first
   become visible?

Answer these before looking at the final section.

## Practical tasks

### 1. Audit the version matrix

Open `gradle/libs.versions.toml` and verify all four relationships:

- AGP is `9.2.1` and Hilt is `2.60`, not `2.60.0`.
- Kotlin is `2.2.10` and the KSP version starts with `2.2.10-`.
- The Hilt runtime and compiler use the same version key.
- The Android application, Hilt, and KSP plugin aliases are declared once and reused by the root
  and app build files. The Compose alias is intentionally left unapplied until Part 5.

Then trace the Android application, Hilt, and KSP aliases from declaration, through `apply false`,
to their application in `:app`. Do not add the old Kotlin Android plugin.

**Check:** `./gradlew :app:tasks` configures the project without a plugin-resolution error.

### 2. Trace the three different dependency roles

In `app/build.gradle.kts`, identify:

1. the Hilt runtime used by application code,
2. the KSP compiler that generates Hilt/Dagger code, and
3. the compile-only annotation needed by generated Dagger Java.

For each one, write down why changing it to one of the other configurations would be wrong. The
three lines support different phases of the build even though they all participate in Hilt setup.

**Check:** you can explain why the compiler uses `ksp(...)`, while
`error_prone_annotations` must not be packaged into the APK.

### 3. Prove that application registration has two halves

The launch screen evaluates `application is MyApplication` and currently displays `yes`.

1. Temporarily remove `android:name=".MyApplication"` from the manifest.
2. Rebuild, install, and launch the app. It should still open, but display `no` because Android
   created its default `Application` instead.
3. Restore the manifest attribute, rebuild, and confirm the screen returns to `yes`.

This controlled failure is harmless here because `MainActivity` is not yet an
`@AndroidEntryPoint` and performs no Hilt injection. Once Part 2 injects into the activity, the
missing registration becomes a runtime crash instead of a harmless `no`.

### 4. Reproduce the AGP 9 + KSP configuration failure

1. Temporarily remove `android.disallowKotlinSourceSets=false` from `gradle.properties`.
2. Run `./gradlew :app:assembleDebug` and find the message about adding Kotlin sources through the
   `kotlin.sourceSets` DSL while using built-in Kotlin.
3. Restore the property and verify that the same command succeeds.

Do not “fix” this by applying `org.jetbrains.kotlin.android`; that conflicts with AGP 9's built-in
Kotlin setup and hides the actual compatibility issue.

### 5. Build and verify the runtime result

From `hilt-practice-app/`, run:

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
adb shell am start -W \
  -n com.example.myapp.practice/com.example.myapp.MainActivity
```

**Check:** the device shows all three facts:

- `Part 1 · Hilt setup ready`
- `Hilt application registered: yes`
- `Hilt 2.60 · KSP 2.2.10-2.0.2`

A green build here validates the setup and the `@HiltAndroidApp` root. It does **not** yet exercise
the `error_prone_annotations` workaround: the first reachable real injection in Part 2 adds methods
annotated with `@CanIgnoreReturnValue`, which makes the generated Java file import it.

---

## Answers to the self-check questions

1. KSP is built against a specific Kotlin compiler version. The prefix identifies that compiler,
   so a different prefix makes the processor incompatible with the project's Kotlin toolchain.
2. AGP 9 compiles Kotlin itself. Reapplying `org.jetbrains.kotlin.android` uses the pre-AGP-9
   integration model and conflicts with built-in Kotlin.
3. This KSP release still registers generated Kotlin sources through `kotlin.sourceSets`; the flag
   temporarily permits that legacy integration under AGP 9.
4. Annotate the application class with `@HiltAndroidApp`, then register that exact class with
   `android:name` on the manifest's `<application>` element. The annotation generates the Hilt root;
   the manifest makes Android instantiate it.
5. Generated Dagger Java imports `@CanIgnoreReturnValue`, whose `CLASS` retention is needed only
   while compiling generated code. The missing artifact first fails when a reachable real
   injection adds annotated methods to the generated component; a graph with no reachable user
   bindings may still build.
