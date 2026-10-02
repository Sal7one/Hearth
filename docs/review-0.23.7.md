# 0.23.7 caption history SQLite regression

The first history read or save failed during database initialization, before
the list/save SQL ran. `PRAGMA secure_delete=ON` returns a row even when setting
the value. Android's `SQLiteDatabase.execSQL` rejects row-returning statements;
the earlier desktop SQLite probe did not exercise that restriction. See the
[Android SQLiteDatabase API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#execSQL(java.lang.String)).

The repository now consumes and checks that result with `rawQuery`, uses the
Android version setter and closes failed initialization connections before
propagating the real error. It does not delete or reset user history. Schema,
privacy/default-off consent, storage limits and speech/TTS ownership are unchanged.

## Reproduction and regression coverage

Checked on the Mac on 2026-10-03. The pre-fix Android API regression on API 35
failed with the reported exception, verbatim:

```
android.database.sqlite.SQLiteException: unknown error (code 0 SQLITE_OK): Queries can be performed using SQLiteDatabase query or rawQuery methods only.
```

Command: `./gradlew :app:testPlayQaUnitTest --tests 'com.sal7one.transiber.caption.history.CaptionHistorySqliteTest.emptyFreshHistoryOpensWithoutSqliteError'`.
Log: `/tmp/hearth-0237-sqlite-red.log`. This is the expected failing reproduction.

After the fix, `CaptionHistorySqliteTest` passes all **12 cases** (six scenarios
on API 28 and API 36) with Robolectric 4.17 native SQLite and Android's framework
API, rather than mocked database methods. Covered paths: empty first open,
secure-delete/version state, first save, Unicode CC/translation patching,
rename/reopen, translated-only output, usage counters, individual/all deletion,
tombstones/pending ownership, consent rollback and initialization failure/retry.
Focused log: `/tmp/hearth-0237-sqlite-green.log`.

Test harness setup failures corrected before the passing run:

```
java.lang.AssertionError: expected android.database.sqlite.SQLiteException to be thrown, but nothing was thrown
java.lang.AssertionError: expected:<2> but was:<1>
java.lang.IllegalStateException: Saved caption session no longer exists
java.lang.UnsupportedOperationException: Failed to create a Robolectric sandbox: Android SDK 36 requires Java 21 (have Java 17)
```

The first three came from reuse of the app singleton between sandbox cases;
test teardown now closes and clears that singleton. The last is the Android 16
host framework requirement; app tests use the installed JDK 21 while production
Kotlin/Java compilation remains on JDK 17. Robolectric is a test-only dependency.

## Delivery checks

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`
  — **BUILD SUCCESSFUL in 3m 5s**. Log: `/tmp/hearth-0237-final-gradle.log`.
- App JVM suites: 449 tests per flavor/build (debug, qa, release), zero failures
  or errors. Existing skips: 1 per Play variant, 8 per FOSS variant. All 12 new
  database cases pass without skips in each variant.
- Common-jni JVM suites: 89 tests per debug/release, zero failures/errors/skips.
- `python3 scripts/verify-release.py --build-type qa` — **PASS** for both APKs,
  including native/vendor supply-chain hashes, dependency/ELF checks, 16 KiB
  alignment and flavor permissions. Log: `/tmp/hearth-0237-verify-release.log`.
- `aapt2 dump badging` confirms both APKs are 0.23.7 / code 80.
- `git diff --check` — **PASS**.

No adb/device UI, paid provider calls, document-picker/FileProvider grant checks
or physical streaming/performance tests were run. Native suites were not repeated
because no native source/runtime changed. Device integration checks remain in
[the history contract](caption-history.md). QA APKs are debug-signed and delivered
as a GitHub draft; existing releases and pinned model archives are retained.

Artifact SHA-256s:

```
10def48ff53afe42b26dc724f3c47a2cbf277d70cae6f694536001c38398f738  hearth-0.23.7-play-qa.apk
2700069d0956eb59d5e7f3bba5c501de866d60ae256c6f59730feb67cc8cf5b7  hearth-0.23.7-foss-qa.apk
```
