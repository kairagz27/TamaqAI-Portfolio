# Portfolio snapshot verification — 22 October 2026

This is the **preparation record**, retained with its original test results.
The owner authorized public publication on 23 October 2026; see [publication](PUBLICATION.md).
The release's unchanged `verification.json` describes the private/draft preparation
state, not current visibility. The release tag remains on tested commit
`b67f95bcdd59a59923e7364df662b91b2b2b463b`.

These checks ran in the **separate selected export**, not the original working
tree. Source: `4d1473f8a34e230f0b0203d5f036bdb955eed603`. No original Git directory,
production configuration, user database, ledger or keystore was copied. The local
development signer was used externally for in-place demo updates; its private key
is not distributed. [Selection and rights audit](export/README.md).

## Executed locally in the export

| Check | Actual result |
|---|---|
| Android builds | `assembleDebug assembleRelease assembleDemo` passed; first complete run 6m 3s, 153 executed tasks |
| Final demo/test APK | Rebuilt after adding licence assets: `assembleDemo assembleDemoAndroidTest -PtamaqDemoTest=true`, 50s |
| JVM | 67 tests / 13 suites, zero failures or skips |
| Lint debug/release/demo | Zero errors, **36 warnings per variant**; not warning-free |
| Backend | 148 tests, 76.749s; isolated PostgreSQL test databases |
| Host tools | 13 parser/safety + 4 history scanner + 14 recording helper + 5 snapshot gate tests = **36 passed** |
| Fonts/palette/locales | Font glyph coverage, contrast and complete EN/RU/KK resources passed; 555 translatable strings |
| Device core | 20 selected Android tests, zero failures/skips; XML cases total 3.304s |
| Full guarded UI journey | Passed, JUnit 32.246s, actual HTTP to exported fake backend |
| Independent cold start | Passed, JUnit 6.412s after explicit demo force-stop |
| Navigation probe | Five add-sheet → catalogue → back cycles passed, JUnit 15.599s |
| Docker | Fresh project `tamaq-portfolio`, no-cache image build, PostgreSQL migrations, healthy loopback gateway/backend |
| Account smoke | New synthetic registration, local Mailpit verification, login/logout and explicit guest link passed |
| Catalogue | First import added 39; unchanged repeat skipped 39, zero duplicates/rejections |
| Storage | PostgreSQL/backend restart retained identical fingerprints of requests, budget, users and catalogue tables; named volumes retained |
| Isolation | Export stack stopped after checks; original demo restored healthy, private config hashes unchanged; main port 8000 health unchanged |
| APK audit | Valid v2 debug signature; four fonts and all 13 licence/notice/index assets match source; no forbidden private artefact entries |

Environment: Windows, JDK 21, SDK 36, Gradle 9.1 / AGP 9.0.1, Docker Desktop,
PostgreSQL 18, isolated API 36.1 emulator (`com.tamaqai.demo`), 360 dp. This is
not a physical-phone or performance benchmark. The app under test is the separate demo, with its instrumentation package;
the real app is not uninstalled, cleared or updated.

The full journey used an original native synthetic plate image, not a real food
photo. It verified `FAKE-DEVELOPMENT`, local half-portion recalculation, a single
confirmed photo entry and **650 synthetic kcal** in totals. Cold start verified
the synthetic session and that same single entry. These are workflow assertions,
not recognition/calorie-accuracy evidence. Camera/photo-picker and physical
barcode scanning are not established by this journey.

Two host invocation issues were corrected without changing runtime code: the
demo-test variant selector cannot be combined with `testDebugUnitTest`, so builds
were separated; synthetic external-files evidence required the adb shell reader
rather than `run-as`. Compose restart lacks a `--wait` flag in the installed
version; restart was followed by `up --wait` before asserting persistence.

## Media and binary artefacts

All 18 distributed gallery screenshots were checked for metadata/privacy and
visually reviewed. The existing source-snapshot video was re-reviewed **in full**:
164 decoded frames, independent frame checksums, 328 English/Russian OCR passes,
no audio, no observed private data, visible token, unmasked password, real photo
or foreign window. It uses the same unchanged UI sources as the exported build.
The video is not claimed to be a recording of the new export's test run.
[Exact approved hashes, origins and limits](export/media-review.json).

Final demo APK: `com.tamaqai.demo`, version **1.3.1-portfolio-demo**, code **5**,
backend **http://127.0.0.1:8100**, persistent **FAKE AI · LOCAL BACKEND** banner.
The URL and fake-provider guards executed in the actual UI test. APK SHA-256:
`35431f55fbe566cc2ce83b0b74b55084866ffa5695f9ea9893d13ff32ad9507a`.
The local debug signer is unchanged; distribution is a development build, not a
production signing setup. APK/video/checksums are release attachments, not Git blobs.

## CI and release binding

The new repository has [its own Verify workflow](https://github.com/kairagz27/TamaqAI-Portfolio/actions/workflows/verify.yml),
with pinned actions, no production secrets, backend tests, source/media/docs
checks, builds/JVM/lint, and API 36 Android core tests. The instrumented job requires
nonempty XML and zero failures/skips. Guarded backend-dependent full UI probes run
locally and are distinct from CI core coverage.

Only runs whose `head_sha` matches this repository's final commit count. Actual
run URLs/conclusions and that SHA are bound in the preparation `verification.json`;
old private-repository runs are not reused as evidence. The [first release notes](RELEASE_NOTES.md)
state the APK prerequisites and unresolved limitations. The repository and release
were private/draft at preparation. The separately authorized public release uses
the [permanent release URL](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1).

## Unresolved and not verified

- **Known intermittent UI-test failure:** a source-stage full capture hit an
  Espresso idle timeout near add-sheet/catalogue navigation, followed by a secondary
  Compose `SlotWriter` failure during Activity teardown. Later runs and this export's
  run passed, but the cause is **not resolved**. No claim of a stable full UI suite.
- Lint warnings remain; no unmeasured coverage or frame-rate percentage is claimed.
- No physical phone/Xiaomi smoothness, fresh accessibility/full locale matrix,
  camera/picker, real OpenAI, hosted HTTPS, external email or production backup/
  restore certification was performed in this export task. Mailpit proves local
  test delivery only. Account/profile synchronization is not Room cloud backup.
- Legacy media and manufacturer/venue data with unconfirmed rights are excluded.
  Notice provenance identifies canonical libyuv texts; its exact linked native
  revision was not established. Python/container inventories have stated scope,
  not a blanket certification of every OS/build package.

**Paid AI calls: 0. API spend for this task: $0.** Existing pilot limits,
reservations and ledger were neither accessed for testing nor reset.
