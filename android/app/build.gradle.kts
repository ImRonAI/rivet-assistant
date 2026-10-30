import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hiron.emrys"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hiron.emrys"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "0.5.0"
        // Ready-to-use defaults from emrys.local.properties (never committed); all stay editable in Settings.
        val local = Properties().apply {
            rootProject.file("emrys.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun baked(key: String) = "\"" + local.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "DEFAULT_BRAIN_URL", baked("brainUrl"))
        buildConfigField("String", "DEFAULT_SESSION", baked("session"))
        buildConfigField("String", "DEFAULT_DEVICE_TOKEN", baked("deviceToken"))
        buildConfigField("String", "DEFAULT_ANSWERING_NUMBER", baked("answeringNumber"))
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
            it.systemProperty("emrys.screenshots", rootProject.layout.buildDirectory.dir("screens").get().asFile.path)
            // EMRYS_LIVE=1 also runs LiveBrainTest against the deployed brain this build is configured for.
            it.systemProperty("emrys.live", System.getenv("EMRYS_LIVE") ?: "0")
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.elevenlabs.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
