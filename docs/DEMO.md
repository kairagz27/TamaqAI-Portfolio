# Portfolio demo

Real Android screens and HTTP transport run against an explicitly fake local provider. This demonstrates a reviewable diary workflow, not recognition quality. The APK banner and analysis provenance remain visible.

## Before downloading or installing

The APK uses **FAKE AI** and **requires local backend services at `http://127.0.0.1:8100` and `adb reverse tcp:8100 tcp:8100`**. It is not standalone and has no hosted server. Follow [development setup](DEVELOPMENT.md), including Docker startup, account bootstrap and catalogue import, before installing it.

It installs as the separate debug-signed package `com.tamaqai.demo`. It does not contain an operating OpenAI key, real invitation or user diary. Keys differ between development machines; preserve your own signing key for in-place demo updates. Never uninstall or clear the user's main application for verification.

## What the scenario demonstrates

Login → photo draft → labelled fake analysis → component/half-portion review → confirmation → daily totals → catalogue → settings. Seeded entries and plans are explicitly DEMO. The catalogue contains 39 attributed Open Food Facts records; the venue list can honestly be empty because no redistributable menu seed is included.

Use only the generated synthetic account and isolated diary. The native synthetic food illustration is created by source code. The recording does **not** exercise physical camera capture or the system photo picker. It does not establish model access, external email delivery, calorie accuracy or physical-device performance. Mailpit is a local test receiver.

## Video and attachments

[Published release v1.3.1-portfolio.1](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1) contains the labelled demo APK, MP4, checksums and limitations. Public publication was authorized on **23 October 2026**. The [MP4](https://github.com/kairagz27/TamaqAI-Portfolio/releases/download/v1.3.1-portfolio.1/tamaqai-demo-fake-ai.mp4) can be viewed or downloaded without installing Android or signing in to GitHub. The original development repository remains private.

The existing source-snapshot recording is 79.62 seconds. The 18 actual captures in [the gallery](screenshots/portfolio/README.md) show both themes, editor, nutrients, history, profile, drawer, catalogue and plan. Review evidence and exact distributed hashes belong in the [export audit](export/README.md); checks repeated in the clean export are listed separately in [verification](PUBLICATION_VERIFICATION.md). Video and APK are release assets, not tracked source files.

The tag and binaries remain bound to checked commit `b67f95bcdd59a59923e7364df662b91b2b2b463b`. The attached `verification.json` remains the original preparation record from **22 October 2026**, including its then-private/draft state. See the [publication record](PUBLICATION.md) for the subsequent release state and anonymous-access checks.

A standalone demonstration can be considered as a separate future task. The current APK's backend requirement remains part of its distribution description.

## Reproduce the guarded journey

After setup and an authorized adb connection:

```sh
./gradlew assembleDemo assembleDemoAndroidTest -PtamaqDemoTest=true
adb -s DEVICE_ID reverse tcp:8100 tcp:8100
adb -s DEVICE_ID install -r app/build/outputs/apk/demo/app-demo.apk
adb -s DEVICE_ID install -r app/build/outputs/apk/androidTest/demo/app-demo-androidTest.apk
python tools/record_portfolio_demo.py --serial DEVICE_ID
```

Use `./gradlew.bat` on Windows. The bounded helper reads only generated `.demo/` credentials, uses stdin rather than argv, removes its remote credential file before submission and writes evidence under ignored `.android/`. It waits for the demo's active accessibility window, rejects a failed journey or overlong recording, and uses an owned child PID/output path for cleanup. Use a new empty `--out` directory each time. Review every proposed release recording for private data, failed states and external windows; successful instrumentation alone is not a media privacy review.

After a successful journey, verify persistence and navigation independently without reseeding or requesting AI:

```sh
adb -s DEVICE_ID shell am force-stop com.tamaqai.demo
adb -s DEVICE_ID shell am instrument -w -r -e class com.tamaqai.ui.PortfolioRestartTest -e portfolioRestartProbe true com.tamaqai.demo.test/androidx.test.runner.AndroidJUnitRunner
adb -s DEVICE_ID shell am instrument -w -r -e class com.tamaqai.ui.PortfolioNavigationTest -e portfolioNavigationProbe true com.tamaqai.demo.test/androidx.test.runner.AndroidJUnitRunner
```

The navigation probe repeats five add-sheet/catalogue/back cycles. Check that instrumentation reports status code 0; skipped guards are not passing execution. These backend-dependent UI probes run locally. CI selects independent core tests with a nonempty/zero-skip result gate.

## Known UI-test instability

A source-stage full capture hit an Espresso idle timeout around add-sheet/catalogue navigation, followed by a secondary Compose `SlotWriter` error during Activity teardown. A focused five-cycle navigation probe and later complete recording passed, but the root cause remains unestablished. Do not hide the failed attempt or describe the intermittent problem as fixed. Check the current export report for each newly executed result. No frame-rate or smoothness guarantee is made.
