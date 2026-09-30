import java.util.Properties
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val testSecrets = Properties().apply {
    val secretsFile = rootProject.file("test-secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}
val playBuild = providers.gradleProperty("playBuild").orNull?.toBoolean() == true ||
    providers.gradleProperty("playFeasibility").orNull?.toBoolean() == true
val buildNative = providers.gradleProperty("mhNativeBuild").orNull?.toBoolean() != false
val privacyPolicyUrl = providers.gradleProperty("privacyPolicyUrl").orNull
    ?: "https://github.com/techjarves/Mobile-Harness/blob/main/PRIVACY.md"
val uploadStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
// Base URL that runtime bundles are downloaded from when they are not embedded in
// the APK. Deliberately left pointing at the upstream release tag: the core/rootfs and
// the Claude/Python/Android/DSH/Antigravity bundles are only published there. The
// OpenCode bundle is shipped *embedded* in the APK instead (see the opencode entry in
// prepareBundledAgentAssets), so it never needs this URL. Override per build with
// -PruntimeReleaseBaseUrl=...
val runtimeReleaseBaseUrl = providers.gradleProperty("runtimeReleaseBaseUrl").orNull
    ?: "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"
val appUpdateManifestUrl =
    "https://github.com/techjarves/Mobile-Harness/releases/latest/download/mobile-harness-update.json"
val runtimeBundleDir = rootProject.layout.projectDirectory.dir("dist/runtime-bundles")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")

// Android app identity. applicationId is what decides whether a build *replaces* an
// existing install or *coexists* with it: a different id is treated as a separate app
// with its own icon, data dir and permissions. The literal default keeps the upstream
// package so tagged/upstream builds still behave identically; override
// -PmhApplicationId=... to install side-by-side with an existing PocketDev build.
// The label is set the same way so both icons are distinguishable on the launcher.
val appId = providers.gradleProperty("mhApplicationId").orNull ?: "com.jarves.mh"
val appLabel = providers.gradleProperty("mhAppLabel").orNull ?: "Mobile Harness"

val prepareBundledAgentAssets = tasks.register<Sync>("prepareBundledAgentAssets") {
    // Only bundles whose .tar.zst is actually present get embedded; the rest are
    // fetched from the release channel at first use. Sync ignores missing inputs
    // silently, so list what is actually going in to keep that visible.
    val embedded = listOf(
        runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-opencode-arm64-2026.09.1.tar.zst"),
    ).filter { it.asFile.isFile }
    logger.lifecycle("Embedding agent bundles: ${embedded.map { it.asFile.name }.ifEmpty { listOf("(none - all fetched at runtime)") }}")
    from(embedded)
    into(generatedRuntimeAssets.map { it.dir("shared/runtime") })
}

val prepareOfflineRuntimeAssets = tasks.register<Sync>("prepareOfflineRuntimeAssets") {
    from(
        runtimeBundleDir.file("pocketdev-core-arm64-2026.09.5.tar.zst"),
        runtimeBundleDir.file("pocketdev-claude-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-python-arm64-2026.09.2.tar.zst"),
        runtimeBundleDir.file("pocketdev-android-arm64-2026.09.1.tar.zst"),
        runtimeBundleDir.file("pocketdev-dsh-arm64-2026.09.1.tar.zst"),
    )
    into(generatedRuntimeAssets.map { it.dir("offline/runtime") })
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.jarves.mh"
    compileSdk = 36
    // F-Droid's r26b recipe installs 26.1.10909125. Keep AGP from selecting
    // its newer default NDK; local developers may override this explicitly.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "26.1.10909125"

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(checkNotNull(uploadStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = appId
        minSdk = 28
        // The direct APK retains the proven target-28 PRoot execution path. The
        // Play build targets current Android while its runtime path is validated.
        targetSdk = if (playBuild) 36 else 28
        // Keep literal defaults so F-Droid's static manifest parser can detect
        // the tagged release. Gradle properties may still override Play builds.
        versionCode = 5
        versionName = "1.0.4"
        providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }

        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "IS_PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))
        // Home-screen label for this build. Set through a build-generated resource so a
        // side-by-side build can be told apart from an existing PocketDev install.
        resValue("string", "app_label", appLabel)

        buildConfigField(
            "String",
            "TEST_OPENROUTER_API_KEY",
            "\"\"",
        )
    }

    flavorDimensions += "runtimeDelivery"
    productFlavors {
        create("online") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "false")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"online\"")
        }
        create("offline") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "true")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"offline\"")
        }
    }

    sourceSets.getByName("offline").assets.srcDir(generatedRuntimeAssets.map { it.dir("offline") })
    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets.map { it.dir("shared") })

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "TEST_OPENROUTER_API_KEY",
                buildConfigString(testSecrets.getProperty("openrouter.apiKey", "")),
            )
        }
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
                // NOTE: do not add cmake arguments here for the shmem data dir.
                // libandroid-shmem's compiled-in ashmem symlink dir is a best-effort
                // default; the app always overrides it at runtime via PROOT_TMP_DIR
                // (see RuntimeInstaller). Passing it from here would need
                // `arguments`, which is not exposed on this AGP's Cmake DSL.
            }
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.jniLibs.useLegacyPackaging = true
    androidResources.noCompress += "zst"
}

tasks.matching { it.name.startsWith("mergeOffline") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("Offline") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.register("playReadinessCheck") {
    group = "verification"
    description = "Checks configuration required before uploading a Mobile Harness Play bundle."
    doLast {
        check(playBuild) { "Run with -PplayBuild=true." }
        check(privacyPolicyUrl.startsWith("https://")) {
            "privacyPolicyUrl must be a public HTTPS URL."
        }
        check(hasUploadSigning) {
            "Set MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD."
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.6-9@aar")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
