# Reviewed snapshot export

Prepared on **22 October 2026** from source commit
`4d1473f8a34e230f0b0203d5f036bdb955eed603` (`codex/publication-prep`).
This repository starts with a new snapshot commit. Earlier development commits,
branches, authors' email metadata and Git objects were **not imported**. The
original repository remains private and its history is unchanged. Authorship and
upstream notices are retained in [AUTHORS](../../AUTHORS.md), [LICENSE](../../LICENSE)
and [third-party notices](../../THIRD_PARTY_NOTICES.md).

## Exact selection and adaptations

The export was made by reading individual Git blobs from an explicit list of
**341 source paths**, not by copying a working directory or archiving an entire
tree. [Source allowlist](source-allowlist.json) records every selected path, mode,
Git blob and SHA-256. [Final manifest](snapshot-manifest.json) records the hashes
of all distributed source files except itself. `python tools/check_snapshot.py`
requires exact agreement with the tracked file set and current bytes; CI runs it.

Included: Android main/debug/demo/release/visual/performance sources and resources,
Room schemas 1–5, JVM/core-device/guarded-demo tests, the FastAPI modular monolith
and PostgreSQL migrations, backend tests, locked dependencies, required demo/test
helpers, Docker demo configuration, architecture decisions, current documentation,
licence inventories, 18 reviewed screenshots and separately licensed OFF data.

Export-only changes are documentation/links, repository instructions, a distinct
Compose project name (`tamaq-portfolio`), withholding the historical OFF retrieval
User-Agent contact, a snapshot integrity gate, and bundling the app MIT notice,
third-party summary and Android dependency inventory in the APK. Application
logic, API contracts, Room schemas, UI layout and calculations are unchanged.
The historical User-Agent was not relabelled as a request from this repository.

Excluded: all secret/local environment files, generated credentials, invitations,
databases, ledger, backups, keystores, local settings, builds, APK/video binaries,
old operator evidence, legacy media/design captures, all Figma reference files,
manufacturer/Coffee Boom seeds with unconfirmed redistribution terms, and old
private release/PR links. A safe production `.example` template remains for
configuration documentation and preflight regression tests. It is never loaded
by the isolated demo.

## Rights and media review

- Original Kotlin/Python, native Canvas plate/ceramic artwork, launcher vector and
  documentation: MIT; existing author and copyright notices retained.
- Four unmodified local fonts: Bodoni Moda, Lora, Inter, Noto Sans, **SIL OFL 1.1**.
  Source bytes match recorded official upstream hashes; complete font notices are
  bundled. This review checked hashes/notices, not a fresh upstream download.
- Android libraries: selected 159-coordinate runtime inventory with Apache-family
  notices and CameraX's additional BSD/libyuv notice. The final APK is checked for
  the required licence assets and preserved CameraX notice.
- **39 Open Food Facts records** plus qualification evidence: ODbL 1.0 database /
  DbCL 1.0 contents. [Data sources and attribution](../DATA_SOURCES.md). No product
  photography or venue/manufacturer dataset is redistributed. Country tags do
  not prove current shelf availability, and no calorie-accuracy claim is made.
- Python dependencies, build tools and Docker bases retain their own licences;
  [inventories](../../THIRD_PARTY_NOTICES.md) cover their stated scope. No Python
  wheels, container layers or FFmpeg executable are redistributed here.

[Media hashes and review](media-review.json) explicitly allow 18 real application
PNGs, four TTFs, the launcher vector and the release-only video. All 18 PNGs are
540×1200, metadata-free, visually reviewed and OCR-checked in English/Russian.
The 79.62-second video has **164 decoded variable-rate frames**: all were visually
inspected and OCR-checked in both languages (328 passes), with independent native
frame checksums. There is no audio. Password remains masked; no visible token,
invitation, real photograph, personal identity, foreign window or OS error was
observed. Only synthetic demo identity/example-domain email and native artwork
appear. OCR is imperfect; visual inspection supplemented it.

The video was captured from the source snapshot's unchanged UI, not newly invented
screens. The exported demo is rebuilt and its guarded journey is rerun separately.
Camera capture/photo-picker operation is not established by this video or the
synthetic-image journey. Legacy 38 PNGs, one video and all five Figma reference
files remain withheld; they do not acquire redistribution rights from omission
of old Git history.

## Verification and publication gates

[Verification](../PUBLICATION_VERIFICATION.md) distinguishes new-export checks
from inherited observations. The known intermittent Espresso/Compose full-demo
idle timeout and teardown failure remain unresolved. No physical-device smoothness,
real AI, hosted HTTPS, external email or production readiness is claimed here.

The portfolio repository and its [first release](https://github.com/kairagz27/TamaqAI-Portfolio/releases/tag/v1.3.1-portfolio.1)
are public with the owner's separate authorization of 23 October 2026. The original
development repository remains **private**, without history changes. The tag stays
on tested commit `b67f95bcdd59a59923e7364df662b91b2b2b463b`; publication documentation
is maintained in subsequent authentic commits. A successful CI run alone never
authorizes visibility changes. The release contains the unchanged prepared demo
APK, reviewed video, checksums and historical preparation `verification.json`.
Its private/draft flags describe 22 October, not current publication state;
see [publication checks](../PUBLICATION.md). The current manifest also covers the
reviewed publication documentation, while the original manifest remains available
at the release tag.
APK/video files are outside Git. The APK needs a local backend on port **8100**
and **adb reverse**; it is not a standalone demonstration.
