# Public demo publication — 23 October 2026

The owner explicitly authorized public visibility for **TamaqAI-Portfolio** and
publication of the existing **v1.3.1-portfolio.1** release. The original development
repository remains private; its history and user state are not changed.

## Source and artefact binding

Before publication, local `main`, fetched `origin/main` and the release tag all
matched **b67f95bcdd59a59923e7364df662b91b2b2b463b**. No intervening changes were
present. Its [Verify run](https://github.com/kairagz27/TamaqAI-Portfolio/actions/runs/39771367419)
passed all three jobs. Source/media integrity and release digests were rechecked;
no new concrete rights or privacy blocker was found. Publication changes only
documentation and its explicit integrity manifest. App/backend code and the
release tag are unchanged.

The existing release retains its four prepared attachments, with SHA-256 checked
against local files and GitHub's reported digests:

| Attachment | SHA-256 |
|---|---|
| `tamaqai-portfolio-demo-debug.apk` | `35431f55fbe566cc2ce83b0b74b55084866ffa5695f9ea9893d13ff32ad9507a` |
| `tamaqai-demo-fake-ai.mp4` | `d04e531bd964564e7f84dea689724f67e76e699b41b3aa56cde903f75b5f3bdc` |
| `verification.json` | `d9251399c514c6413a9d70099295ed878c6f25ef14c30ca1024fc4a0648c6c71` |
| `SHA256SUMS.txt` | `adddc22eda0ea1dd8e6377eee03b2f3b221c298f22c6264aaea193527bbb83fb` |

`verification.json` is the unchanged **22 October preparation record**. Its
`repository_private` and `release_draft` fields document that earlier state;
they do not claim current visibility. Its tested commit, CI, usage and media
review evidence remain valid. This publication does not rerun the recorded
physical-device, real-model or hosted-service scenarios, nor claim they passed.

## Public entry points

**FAKE AI · LOCAL BACKEND. Before downloading the APK:** start the local Docker
backend on **http://127.0.0.1:8100** and run **`adb reverse tcp:8100 tcp:8100`**
on the authorized device/emulator. **The APK is not standalone and has no hosted
backend.** Follow [setup](DEVELOPMENT.md). A standalone demo is a separate future
option.

- [Repository](https://github.com/kairagz27/TamaqAI-Portfolio)
- [Permanent release](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1)
- [Video without Android installation](https://github.com/kairagz27/TamaqAI-Portfolio/releases/download/v1.3.1-portfolio.1/tamaqai-demo-fake-ai.mp4)
- [Demo APK](https://github.com/kairagz27/TamaqAI-Portfolio/releases/download/v1.3.1-portfolio.1/tamaqai-portfolio-demo-debug.apk)

Post-publication checks on **23 October 2026 passed**, using fresh HTTP requests
without Authorization, cookies, proxy credentials or native Git credentials:

| Anonymous check | Observed result |
|---|---|
| Repository page, release page, raw README, repository API and release API | All five returned HTTP 200; metadata says public / published |
| All 18 gallery PNGs | HTTP 200; exact approved hashes; valid 540×1200 PNGs without metadata |
| APK, MP4, `verification.json`, `SHA256SUMS.txt` | All downloaded with HTTP 200; complete byte hashes match the prepared files |
| Downloaded checksum list | All three listed payload hashes verified |
| Original development repository | Anonymous HTTP 404; authenticated metadata still says private |

The release tag and all four prepared attachments were preserved. The publication
follow-up changes documentation only. Updated-main README and remote SHA are
checked again after its ordinary commit/push; machine results remain ignored
operator evidence. Runtime/physical-device tests from preparation are not presented
as newly executed by this documentation update.

Known intermittent Espresso/Compose idle/teardown instability, 36 lint warnings
per variant and the [preparation limitations](PUBLICATION_VERIFICATION.md) remain
disclosed. Mailpit checks local email only. No paid AI calls or hosting resources
are needed for publication.
