# Development

## Requirements

Python 3.12+, Docker with Compose, JDK 21, Android SDK platform/build-tools 36 and an emulator or authorized USB device. Set `JAVA_HOME` and `ANDROID_HOME` for your installation. Do not commit `local.properties`, signing keys or machine paths. The Gradle wrapper pins the build tool version.

## Isolated local demo

**The downloadable APK needs the local backend on port 8100 and adb reverse. It is not standalone.** From the repository root:

```sh
python backend/scripts/portfolio_demo.py init
docker compose -f compose.demo.yaml up --build -d --wait
python backend/scripts/portfolio_demo.py smoke
python backend/scripts/portfolio_demo.py catalog
adb devices
adb -s DEVICE_ID reverse tcp:8100 tcp:8100
./gradlew assembleDemo
adb -s DEVICE_ID install -r app/build/outputs/apk/demo/app-demo.apk
```

On Windows use your Python 3.12 launcher and `./gradlew.bat`. Init uses the standard library and creates ignored `.demo/backend.env` and `.demo/user.json`; no operating key is required. Open the user file privately for the synthetic login. Never paste its generated values into issues. Init preserves complete existing configuration and rejects a partial setup.

Smoke verifies the synthetic account through local Mailpit and explicitly links its generated invitation. Verified accounts are reused; an existing unverified account requests one new message and ignores older messages. A rate limit is reported without automatic retries. Health: `http://127.0.0.1:8100/health`; Mailpit: `http://127.0.0.1:8125`. This proves local test delivery only.

Named demo volumes preserve PostgreSQL and files. Stop with `docker compose -f compose.demo.yaml stop`; do not use `down -v` against existing data. Runtime forces fake AI, blank OpenAI credentials and local SMTP. Backend/PostgreSQL/Mailpit use an internal network without external routing; only the fixed loopback gateway exposes the local ports. Do not expose these development services publicly.

The separate `com.tamaqai.demo` package displays **FAKE AI · LOCAL BACKEND**. It retains Room data between launches. An authorized USB phone needs the reverse tunnel after reconnecting; disconnecting USB removes it. Signing keys differ between machines, so preserve your development key for in-place demo updates. Do not uninstall the user's main app or clear its data.

## Checks

```sh
python -m pip install -r backend/requirements.lock.txt -r tools/requirements-docs.txt
python backend/scripts/portfolio_demo.py test
python tools/test_warm_glass_tools.py
python tools/test_publication_history.py
python tools/test_record_portfolio_demo.py
python tools/test_snapshot.py
python tools/check_readme.py
python tools/check_publication_docs.py
python docs/design/presentation/verify_assets.py
./gradlew assembleDebug assembleRelease assembleDemo testDebugUnitTest lintDebug lintRelease lintDemo -PtamaqEnvironment=usb -PtamaqReleaseBackendUrl=https://pilot.example.com
./gradlew connectedDemoAndroidTest -PtamaqDemoTest=true -Pandroid.testInstrumentationRunnerArguments.class=com.tamaqai.data.NetworkCancellationTest,com.tamaqai.data.SecureSessionTest,com.tamaqai.data.DiaryPersistenceTest,com.tamaqai.data.DailyPersistenceTest,com.tamaqai.data.NutritionPersistenceTest,com.tamaqai.data.AccountIsolationTest,com.tamaqai.data.CatalogPersistenceTest
```

Backend tests use a disposable PostgreSQL service on loopback 8101 and isolated test databases. Runtime/pilot databases are not reset. Core Android tests use their own databases and do not call AI. The placeholder HTTPS host is a build check only, not a live endpoint. For an explicitly configured PostgreSQL fixture, CI invokes `python -m unittest discover -s backend/tests` with `PYTHONPATH=backend` and `TAMAQ_DATABASE_URL` set outside source.

CI requires nonempty Android core XML results with zero failures and zero skips. A passing task with a skipped guard is not executed coverage. [Guarded full journey and recording](DEMO.md) are separate from the selected CI core. Known intermittent Espresso/Compose instability remains documented there. See [actual checks](PUBLICATION_VERIFICATION.md); no unmeasured coverage or frame-rate percentage is claimed.

## Production is a separate setup

The safe `backend/.env.auth-production.example` template is not loaded automatically. Copy it to a private, ignored server configuration and fill values there, never in Android or Git. Android receives only an endpoint URL. Preserve the existing $1 verification cap within the $5 pilot cap, including pending/unknown reservations; demo setup must not reset or reuse a pilot ledger.

Production needs an owner-controlled HTTPS endpoint, persistent PostgreSQL and file storage, verified backup/restore and a configured sender. External mail needs sender verification, SPF/DKIM/DMARC and an actual delivery test. Mailpit, localhost and a placeholder HTTPS build do not satisfy these gates. No production resources or paid AI calls are created by these demo commands.
