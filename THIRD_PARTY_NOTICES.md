# Third-party notices

The root MIT license applies to original TamaqAI code and documentation, not to
third-party fonts, libraries, catalogue records, trademarks or reference media.
This portfolio edition starts from the prepared source snapshot identified in
[AUTHORS](AUTHORS.md). Existing copyright and license notices are retained;
dependency and data licenses remain in effect.

## Bundled fonts and Android resources

| Material | Origin and license | Included notices |
|---|---|---|
| Bodoni Moda | [Google Fonts / Indestructible Type](https://github.com/google/fonts/tree/main/ofl/bodonimoda), SIL OFL 1.1 | `app/src/main/assets/font-licenses/` |
| Lora | [Google Fonts / Cyreal](https://github.com/google/fonts/tree/main/ofl/lora), SIL OFL 1.1; reserved name Lora | Same directory |
| Inter | [Google Fonts / Rasmus Andersson](https://github.com/google/fonts/tree/main/ofl/inter), SIL OFL 1.1 | Same directory |
| Noto Sans | [Google Fonts / Noto](https://github.com/google/fonts/tree/main/ofl/notosans), SIL OFL 1.1 | Same directory |
| Material icons / AndroidX | [AndroidX](https://android.googlesource.com/platform/frameworks/support/), Apache 2.0 | Library distributions and [Apache license](https://www.apache.org/licenses/LICENSE-2.0) |
| ZXing core | [ZXing](https://github.com/zxing/zxing), Apache 2.0 | `app/src/main/assets/third-party-licenses/zxing-*` |
| Haze | [Haze](https://github.com/chrisbanes/haze), Apache 2.0 | `app/src/main/assets/third-party-licenses/haze-LICENSE.txt` |
| CameraX native libyuv | [libyuv](https://chromium.googlesource.com/libyuv/libyuv.git/), BSD 3-Clause and additional patent grant | `app/src/main/assets/third-party-licenses/libyuv-LICENSE.txt` and `libyuv-PATENTS.txt` |
| AndroidX Concurrent Futures KTX 1.3.0 | [AndroidX](https://developer.android.com/jetpack/androidx/releases/concurrent), Apache 2.0 | Exact artifact `NOTICE.txt` retained as `app/src/main/assets/third-party-licenses/androidx-concurrent-futures-ktx-NOTICE.txt` |

All four TTFs matched official upstream files byte-for-byte on 2026-10-22;
see [font provenance](docs/licenses/fonts.json). They were not renamed, subsetted
or modified in this publication task. Georgia is not bundled. Local ceramic
drawings and the synthetic plate image are Kotlin Canvas/Bitmap code in this
repository; they are not downloaded food photography or AI-generated artwork.

### Standalone demo photograph

The separate `standaloneDemo` variant additionally bundles **Salad Plate** by
**Katja S. Verhoeven**, published by [ISO Republic](https://isorepublic.com/photo/salad-plate/)
under **CC0 1.0 Universal**. The source page's CC0 designation was reviewed on
2026-10-23. The image was converted to RGB and re-encoded as JPEG with EXIF removed.
No author endorsement is implied. Prepared example nutrients are synthetic, not
a measured or AI-derived assessment of this photograph.

[Asset provenance and checksum](docs/licenses/standalone-assets.json) records the
source, modifications and bundled bytes. The standalone APK includes
`assets/third-party-licenses/salad-NOTICE.txt` and the full
`CC0-1.0-legalcode.txt`. This photograph is separate from the MIT code and the
Open Food Facts dataset; it is not an OFF product photograph. The previous
server-demo APK and release assets are unchanged.

AndroidX, Compose, Room, CameraX, Kotlin and kotlinx libraries retain Apache 2.0
terms. JUnit is EPL 1.0; Android test libraries retain their own notices. The
Gradle wrapper is distributed under Gradle's Apache 2.0 license. It is tooling,
not application code. Do not remove required upstream copyright/NOTICE files.

CameraX camera-core 1.4.2 includes libyuv in its native image-processing library.
Its AAR's generic AndroidX license text does not include the libyuv BSD notice,
so that notice and the upstream patent grant are bundled separately. The
[notice provenance](docs/licenses/notice-provenance.json) records their sources
and byte hashes. The pinned libyuv revision identifies the notice texts; the
exact libyuv source revision linked into CameraX's binary is not established.

The APK also includes the application's MIT license, this summary and the
Android dependency inventory under `assets/third-party-licenses/`. These files
supplement the individual upstream licenses and notices.

## Python and containers

Dependencies are installed from pinned `backend/requirements.lock.txt`, not
relicensed by the root LICENSE. FastAPI, Starlette, Pydantic, AnyIO, Argon2 and
several utilities use permissive licenses; Uvicorn uses BSD, OpenAI Python uses
Apache 2.0, certifi uses MPL 2.0, and Psycopg uses LGPL 3.0. See the generated
[installed dependency inventory](docs/licenses/python-dependencies.json) and
each linked source/distribution for the exact version's license.

Psycopg binary distributions bundle client libraries. Their notices and source
obligations also apply when distributing a built container; keep its installed
dist-info licenses and allow replacement of the library. See [Psycopg's source
license](https://github.com/psycopg/psycopg/blob/master/LICENSE.txt).
This repository contains build instructions, not vendored Python wheels or
container layers. Building an image does not transfer ownership of its packages.

PostgreSQL has the [PostgreSQL license](https://www.postgresql.org/about/licence/),
Mailpit has [MIT](https://github.com/axllent/mailpit/blob/develop/LICENSE), and
Caddy has [Apache 2.0](https://github.com/caddyserver/caddy/blob/master/LICENSE).
Base images also contain OS packages with their respective licenses.

## Catalogue: separate data license

The snapshot bundles **39 Open Food Facts records**, separately under
ODbL 1.0 for the database and DbCL 1.0 for individual contents. It is not an MIT
dataset. Attribution: **Contains information from Open Food Facts, made available
under the Open Database License (ODbL).** Preserve attribution and the applicable
share-alike obligations when distributing a derived database. Do not combine
incompatible proprietary datasets into a publicly redistributed OFF database.

OFF product images have a separate Creative Commons Attribution ShareAlike 3.0
license and may also depict protected packaging/trademarks. **No OFF product
images are bundled or downloaded by this demo.** Product/brand names do not
imply endorsement. [Sources and reuse boundaries](docs/DATA_SOURCES.md).

Manufacturer and Coffee Boom seed files and third-party Figma/forest/VR references
with unconfirmed redistribution terms are **not included in this export**.
No previous Git objects or historical media are copied. This does not replace
review of the files that are included: the [export audit](docs/export/README.md)
records that boundary, provenance and media checks. Product/brand names identify
source facts and do not imply endorsement.

## Android dependency metadata

[Runtime graph inventory](docs/licenses/android-dependencies.json) records 159 selected graph coordinates, POM licence declarations and upstream exceptions. The three missing/inherited POM notices (Auto Value annotations, Guava ListenableFuture and ZXing Core) were checked against their upstream Apache 2.0 notices. This inventory covers the demo runtime graph, not every build tool or container OS package. Packaging a container or new binary distribution requires retaining its bundled upstream notices and source obligations.
