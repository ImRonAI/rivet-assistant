import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hiron.ren"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hiron.ren"
        minSdk = 29
        targetSdk = 36
        // Phones only: the ElevenLabs SDK's WebRTC library is ~12 MB per ABI, and the x86/armv7 copies were 34 MB.
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 13
        versionName = "0.5.0"
        // Ready-to-use defaults from ren.local.properties (never committed); they stay editable in Settings.
        val local = Properties().apply {
            rootProject.file("ren.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun baked(key: String) = "\"" + local.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "DEFAULT_ANSWERING_NUMBER", baked("answeringNumber"))
        buildConfigField("String", "DEFAULT_BRAVE_SEARCH_KEY", baked("braveSearchKey"))
        buildConfigField("String", "DEFAULT_BRAVE_ANSWERS_KEY", baked("braveAnswersKey"))
        buildConfigField("String", "DEFAULT_GEMINI_TOKEN", baked("geminiToken"))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload builds are signed with the local debug key; a store build would use its own key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The APK is sideloaded, so nothing compresses it on the way: the WebRTC library and the dex files go in compressed.
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Real rendering for the screen tests' screenshots.
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            it.systemProperty("ren.screenshots", rootProject.layout.buildDirectory.dir("screens").get().asFile.path)
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        extraWarnings.set(true)
        progressiveMode.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Ren's bundled pages (android/web: the robot, and ren desktop's search page) are built by Vite into the app's assets,
// so an APK always carries the pages its sources describe. Needs Node and npm on the build machine.
val web = rootProject.layout.projectDirectory.dir("web")
val webInstall = tasks.register<Exec>("webInstall") {
    workingDir = web.asFile
    commandLine("npm", "ci")
    inputs.file(web.file("package-lock.json"))
    outputs.file(web.file("node_modules/.package-lock.json"))
}
val webBuild = tasks.register<Exec>("webBuild") {
    dependsOn(webInstall)
    workingDir = web.asFile
    commandLine("npm", "run", "build")
    inputs.dir(web.dir("src"))
    inputs.dir(web.dir("scripts"))
    inputs.files(
        web.file("package-lock.json"), web.file("vite.config.ts"), web.file("tailwind.config.ts"), web.file(".env"),
        web.file("tsconfig.json"), web.file("tsconfig.app.json"), web.file("tsconfig.cato.json"), web.file("tsconfig.ren.json"),
    )
    outputs.dir(layout.projectDirectory.dir("src/main/assets/ren/web"))
}
tasks.named("preBuild") { dependsOn(webBuild) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.elevenlabs.android)
    implementation(libs.mcp.server)
    implementation(libs.mcp.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
