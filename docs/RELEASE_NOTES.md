# TamaqAI Portfolio — v1.3.1-portfolio.1

**Public demo release · publication authorized on 23 October 2026.**

[Permanent release](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1)
· [Video — no Android installation required](https://github.com/kairagz27/TamaqAI-Portfolio/releases/download/v1.3.1-portfolio.1/tamaqai-demo-fake-ai.mp4).
The original development repository remains private.

TamaqAI is a Kotlin/Jetpack Compose food diary with a modular FastAPI/PostgreSQL
backend. Review an estimated meal, change components or eaten fraction, confirm
it, and inspect daily totals. This edition starts from the reviewed source
snapshot `4d1473f8a34e230f0b0203d5f036bdb955eed603`; earlier Git history was not
imported or recreated. Original authorship and third-party notices are retained.

## Before downloading the demo APK

The APK uses **FAKE AI** and **requires a local backend on `http://127.0.0.1:8100` and
`adb reverse tcp:8100 tcp:8100`** on an authorized emulator/USB device. Run
`portfolio_demo.py init`, Docker Compose, smoke and catalogue import first;
follow [setup](DEVELOPMENT.md). It is **not standalone**, has no hosted backend,
and displays **FAKE AI · LOCAL BACKEND**. Its synthetic results demonstrate the
workflow, not food recognition or calorie accuracy. An autonomous demo can be
prepared as a separate future improvement.

## Attachments

- `tamaqai-portfolio-demo-debug.apk`: debug-signed `com.tamaqai.demo`, version
  1.3.1-portfolio-demo / code 5, loopback port 8100. No API key or operating login
  is bundled. Generated local credentials stay in ignored `.demo/` files.
- `tamaqai-demo-fake-ai.mp4`: 79.62-second recording, view/download without Android
  installation or GitHub authentication.
  All 164 frames were reviewed; original native image and synthetic data only,
  password masked, no audio. Camera/picker operation is not demonstrated.
- `SHA256SUMS.txt`: hashes of the APK, video and verification record.
- `verification.json`: unchanged preparation record from **22 October 2026**,
  binding the exported commit, CI run/job results, local checks, media audit and
  artefact digests. Its private/draft flags describe that earlier preparation
  state; subsequent publication is recorded in [PUBLICATION.md](PUBLICATION.md).

The release tag and uploaded binaries remain bound to checked commit
`b67f95bcdd59a59923e7364df662b91b2b2b463b`. Publication documentation may advance
`main` without changing that tested APK or replacing the preparation evidence.

## Verification

New-export checks: debug/release/demo build, 67 JVM tests, 148 backend tests,
36 host tests, 20 Android core tests with zero skips/failures, local Docker and
Mailpit account smoke, 39-item repeatable import, storage persistence, full
guarded UI journey, independent cold start and five navigation cycles.
Lint: zero errors, 36 warnings per variant. Final CI is bound to the uploaded
commit in `verification.json` and the new repository's Actions page.

## Included sources and licences

Original code/native artwork/docs: MIT. Bodoni Moda, Lora, Inter and Noto Sans:
SIL OFL 1.1 with complete bundled notices. Android runtime components retain
Apache/BSD and upstream notices; supplemental libyuv and Concurrent Futures
notices are included in the APK. **39 Open Food Facts records** and qualification
evidence use ODbL 1.0 / DbCL 1.0, with attribution. No OFF product images,
manufacturer/menu seeds or unconfirmed Figma/reference artwork are included.
See [third-party notices](../THIRD_PARTY_NOTICES.md) and [export audit](export/README.md).

## Known limitations

The intermittent Espresso/Compose full-demo idle timeout and teardown failure
remain unresolved despite successful subsequent runs. No physical-device
performance, camera/picker, real AI, hosted HTTPS or external-email verification
is claimed. Mailpit is local delivery only. Registration/profile synchronization
does not back up the Room diary. Catalogue coverage and source nutrition are
limited community data, not verified national coverage. Photo analysis is an
estimate requiring user review. No paid AI calls or paid resources were used.
