# Current local portfolio delivery

Date: 2026-11-09. This is local source and verification work, not a newly published release.

## Approved application

The original warm light/dark design is retained. The account drawer has inline theme/language controls. Weight measurements are connected by a date-spaced, shape-preserving smooth curve with a 900 ms draw-in animation that honors system animation scale. Earlier graphite/terracotta and layout studies are archived experiments, not the selected application.

## What the demonstration means

The current recording uses the separate offline package `com.tamaqai.standalone`, synthetic local records and the licensed bundled CC0 food sample. The persistent demo banner is intentional. It shows prepared-sample review, component/portion edits, one confirmation, diary totals, theme choices and the weight curve. It does not show arbitrary-photo recognition, real model accuracy, a hosted server, real email or cloud diary synchronization.

In server-connected builds, a fresh photo with existing analysis access starts once. A validated, unambiguous food estimate for a new unedited entry saves automatically; unclear/no-food results remain drafts. Editing remains available later. Real-provider quality and live paid inference are not validated by the offline recording. See [photo workflow and recovery](PHOTO_QUICK_SAVE.md).

The existing public server-demo video is an earlier fake-provider release demonstration. It remains available, but is not a video of this current local revision. Existing release assets and tags are unchanged.

## Verification evidence

- Backend: 148 tests passed in 32.167 seconds, zero failures/skips, on disposable PostgreSQL plus SQLite. Providers were fake or mocked on an internal Docker network. No paid AI calls or existing data changes.
- Final combined Android rebuild completed successfully in 1 minute 52 seconds: both app and instrumentation APK variants built. JVM tests: 146 standalone and 140 demo passed, zero failures, errors or skips. Lint: zero errors and 38 warnings for each variant; warnings remain, so this is not a warning-free build.
- Emulator instrumentation: 24 standalone persistence/planning/recovery/drawer/curve cases passed on the final APK in 15.403 seconds. The demo/core suite, including all four loopback HTTP photo-flow cases, passed 24 cases on the final rebuild in 4.17 seconds. Both final instrumentation runs reported `OK (24 tests)`.
- Additional project tooling checks: 15 passed; repository whitespace validation passed.
- Current recording journey: 1 test passed in 43.848 seconds on the isolated emulator. [Video](media/tamaqai-current-standalone.mp4), 31.21 seconds, silent 720 x 1600 H.264; [13 current captures and conditions](screenshots/current-20261109/README.md). First, middle and final frames were inspected. No desktop, personal records or real AI calls.
- Recording controller safety tests: 3 passed; existing recorder ownership/cleanup tests: 14 passed. README validation: 38 local links valid, 12 gallery images at 540 x 1200, no image metadata or machine-local paths.

## Honest boundaries

A minor existing visual limitation remains: the Russian half-portion chip wraps its last letter at 360 dp. The recording precedes the final picker temporary-file cancellation fix; that fix does not change the demonstrated UI.

No production readiness, calorie-recognition accuracy, code-coverage percentage, frame-rate guarantee or full accessibility certification is claimed. Room diary/plans/shopping data remain local; account/profile synchronization is not a diary backup. Real service deployment, real email and broader physical-device/poor-network field testing require separate work.

No new phone installation or public publication is included in this delivery task.
