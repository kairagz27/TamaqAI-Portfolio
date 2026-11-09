# Current approved design captures

Captured 2026-11-09 from the current local standalone build on the isolated emulator. Russian UI, 360 dp, 100% font scale; screenshots are 540 x 1200 PNG with nonvisual metadata removed. Original warm light/dark palette, inline drawer settings and smooth weight curve are retained. Rejected graphite/terracotta and layout experiments are not shown.

The records are synthetic. The bundled salad is a licensed CC0 sample; prepared nutrition is explicitly labeled DEMO / AI EXAMPLE. This is not arbitrary-photo inference or evidence of model accuracy. See [sample license](../../licenses/standalone-assets.json).

[Current video](../../media/tamaqai-current-standalone.mp4): 31.21 seconds, 720 x 1600, silent H.264. Recorded directly from the app, then remuxed without pixel edits to remove metadata/data tracks and enable fast-start playback. First, middle and final frames were inspected; the app and demo label are visible, with no desktop or personal records. Instrumentation advances Compose time, so this recording is not an animation-duration or frame-rate benchmark.

The bounded controller refuses physical-device serials and nonempty output directories. Build the standalone app and test APK, install on an isolated emulator, then run:

```sh
python tools/record_current_standalone.py --serial emulator-5556 --out .android/current-portfolio-recording
```

The recording journey passed (1 test, 43.848 seconds), including prepared component/portion edits, single diary confirmation, totals, history, profile, inline theme switching and weight-curve capture. Controller guard tests: 3 passed; existing recorder ownership/cleanup tests: 14 passed.

Video SHA-256: `c7eea2569be9ead7c9119b7e565c4a6477e123251b9d2590f6f7f930370a89b9`

The initial `editor-light.png` captures a real loading state, so the gallery uses the later `editor-portion-and-component.png`. The Russian half-portion chip wraps at this width; this known visual limitation is not hidden.
