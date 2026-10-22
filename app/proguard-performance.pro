# Native self-targeted probe is discovered by Android through the manifest.
# Fixture creation is compiled inside the same target APK, so R8 sees and
# rewrites its model/DAO calls together with production. No test-library,
# DTO-wide, AndroidX, Kotlin, Compose or UI keep rules are needed.
-keep,allowoptimization class com.tamaqai.benchmark.WarmGlassFrameInstrumentation { public *; }
