import java.net.URI

// Configuration is validated by the selected variant's pre-build task, including CI.
// No URL is echoed: a mistaken URL may itself contain private credentials.
fun checkBackendOrigin(value: String, local: Boolean) {
    require(value.isNotBlank()) { "Backend URL is required. Select -PtamaqEnvironment=usb for local USB, or set -PtamaqBackendUrl=https://YOUR_HOST for the pilot." }
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.host != null && uri.userInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath in listOf("", "/") && uri.port in listOf(-1, 443, 8000)) {
        "Backend URL must be an origin without credentials, path, query or fragment."
    }
    val host = uri.host.lowercase()
    if (local) {
        require(uri.scheme == "http" && host in listOf("127.0.0.1", "localhost", "10.0.2.2") && uri.port == 8000) {
            "USB configuration requires a local HTTP origin on port 8000."
        }
    } else {
        require(uri.scheme == "https" && uri.port in listOf(-1, 443) && host.contains('.') &&
            host.matches(Regex("[a-z0-9.-]+")) && !host.matches(Regex("[0-9.]+")) &&
            host.split('.').all { it.isNotBlank() && !it.startsWith('-') && !it.endsWith('-') } &&
            host !in listOf("localhost", "10.0.2.2", "127.0.0.1") &&
            listOf(".localhost", ".local", ".internal", ".test").none(host::endsWith)) {
            "Pilot configuration requires public DNS HTTPS on port 443; local, loopback and emulator origins are forbidden."
        }
    }
}

val backendEnvironment = providers.gradleProperty("tamaqEnvironment").getOrElse("pilot")
require(backendEnvironment in listOf("usb", "pilot")) { "tamaqEnvironment must be usb or pilot" }
val debugBackend = providers.gradleProperty("tamaqBackendUrl").getOrElse(if (backendEnvironment == "usb") "http://127.0.0.1:8000" else "")
val releaseBackend = providers.gradleProperty("tamaqReleaseBackendUrl").getOrElse(if (backendEnvironment == "pilot") debugBackend else "")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tamaqai"
    compileSdk = 36
    testBuildType = when {
        providers.gradleProperty("tamaqDemoTest").orNull == "true" -> "demo"
        providers.gradleProperty("tamaqPerformanceTest").orNull == "true" -> "performance"
        providers.gradleProperty("tamaqVisualTest").orNull == "true" -> "visual"
        else -> "debug"
    }

    defaultConfig {
        applicationId = "com.tamaqai"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.1"
        manifestPlaceholders["applicationName"] = ".TamaqApplication"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["backendUrl"] = providers.gradleProperty("tamaqTestBackendUrl").getOrElse("")
        buildConfigField("boolean", "PORTFOLIO_DEMO", "false")

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            manifestPlaceholders["applicationName"] = ".TamaqDebugApplication"
            buildConfigField("String", "BACKEND_URL", "\"$debugBackend\"")
        }
        release {
            buildConfigField("String", "BACKEND_URL", "\"$releaseBackend\"")
        }
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-portfolio-demo"
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "PORTFOLIO_DEMO", "true")
            val demoUrl = providers.gradleProperty("tamaqDemoBackendUrl").getOrElse("http://127.0.0.1:8100")
            val uri = runCatching { URI(demoUrl) }.getOrNull()
            require(uri != null && uri.scheme == "http" && uri.host in listOf("127.0.0.1", "10.0.2.2", "localhost") &&
                uri.port == 8100 && uri.userInfo == null && uri.rawPath in listOf("", "/") && uri.rawQuery == null && uri.rawFragment == null) {
                "Portfolio demo requires a loopback/emulator HTTP origin on port 8100. Start compose.demo.yaml."
            }
            buildConfigField("String", "BACKEND_URL", "\"$demoUrl\"")
        }
        create("visual") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".visual"
            matchingFallbacks += listOf("debug")
            val url=providers.gradleProperty("tamaqVisualBackendUrl").orNull
            if(url!=null) {
                require(url.matches(Regex("http://127\\.0\\.0\\.1:[0-9]+")))
                buildConfigField("String","BACKEND_URL","\"$url\"")
            }
        }
        // Dedicated synthetic-data package: identical optimized before/after render probes.
        create("performance") {
            initWith(getByName("release"))
            applicationIdSuffix = ".performance"
            matchingFallbacks += listOf("release")
            // Image capture is separately labelled; it is never timing evidence.
            val imagesOnly = providers.gradleProperty("tamaqPerformanceImages").orNull == "true"
            isDebuggable = imagesOnly
            isMinifyEnabled = !imagesOnly
            isShrinkResources = !imagesOnly
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-performance.pro")
            testProguardFiles("proguard-performance-test.pro")
            buildConfigField("String", "BACKEND_URL", "\"http://127.0.0.1:8004\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    bundle { language { enableSplit = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    sourceSets["visual"].java.srcDir("src/debug/java")
    sourceSets["demo"].java.srcDir("src/debug/java")
    sourceSets["demo"].res.srcDir("src/debug/res")
    sourceSets["visual"].res.srcDir("src/debug/res")
    sourceSets["performance"].res.srcDir("src/debug/res")
    sourceSets["performance"].java.srcDir("src/release/java")
}

tasks.configureEach {
    when (name) {
        "preDebugBuild" -> doFirst { checkBackendOrigin(debugBackend, backendEnvironment == "usb") }
        "preReleaseBuild" -> doFirst { checkBackendOrigin(releaseBackend, false) }
        "preVisualBuild" -> doFirst {
            val url = providers.gradleProperty("tamaqVisualBackendUrl").getOrElse(debugBackend)
            // Isolated UI fixtures may use a dynamically allocated loopback port.
            val uri = runCatching { URI(url) }.getOrNull()
            require(uri != null && uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                uri.userInfo == null && uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
                "Visual tests require an explicit loopback backend origin."
            }
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.navigation:navigation-compose:2.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Shared source-backed warm glass; no CPU bitmap processing.
    implementation("dev.chrisbanes.haze:haze:1.5.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // Local decoding only; uses the existing CameraX still capture, no service upload.
    implementation("com.google.zxing:core:3.5.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Align app/test runtime: AndroidX Test ViewCapture needs SuspendToFutureAdapter.
    implementation("androidx.concurrent:concurrent-futures-ktx:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
