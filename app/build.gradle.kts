import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

plugins {
    id("com.android.application")
}

abstract class DebugBuildId : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
        .withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().take(8)
}

val debugBuildId = providers.of(DebugBuildId::class.java) {}.get()
val virtualAudioExperiment = providers.gradleProperty("virtualAudioExperiment").orElse("false").get().also {
    require(it == "true" || it == "false") { "virtualAudioExperiment must be true or false" }
}.toBoolean()
val hybridAudioExperiment = providers.gradleProperty("hybridAudioExperiment").orElse("false").get().also {
    require(it == "true" || it == "false") { "hybridAudioExperiment must be true or false" }
}.toBoolean()
require(!(virtualAudioExperiment && hybridAudioExperiment)) { "Audio experiments are mutually exclusive" }

android {
    namespace = "dev.redrain.cast"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        applicationId = "dev.redrain.cast"
        minSdk = 30
        targetSdk = 37
        versionCode = 39
        versionName = "0.5.5"
        buildConfigField("boolean", "VIRTUAL_AUDIO_EXPERIMENT", "false")
        buildConfigField("boolean", "HYBRID_AUDIO_EXPERIMENT", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        prefab = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets["test"].resources.directories.add(rootProject.file("protocol/fixtures").path)
    lint { abortOnError = true }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug-${if (virtualAudioExperiment) "virtual-audio-" else if (hybridAudioExperiment) "hybrid-audio-" else ""}$debugBuildId"
            buildConfigField("boolean", "VIRTUAL_AUDIO_EXPERIMENT", virtualAudioExperiment.toString())
            buildConfigField("boolean", "HYBRID_AUDIO_EXPERIMENT", hybridAudioExperiment.toString())
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val ffmpegPackage = "dev.redraincast.native:ffmpeg-pcm-android:7.1.3-r2@aar"
val ffmpegVerification by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

abstract class VerifyFfmpegPackage : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedChecksum: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = expectedChecksum.get().asFile.readText().trim().lowercase()
        if (!expected.matches(Regex("[0-9a-f]{64}")))
            throw GradleException("The FFmpeg prebuilt package checksum must be pinned before building.")
        val digest = MessageDigest.getInstance("SHA-256")
        archive.get().asFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (actual != expected)
            throw GradleException("Pinned FFmpeg prebuilt SHA-256 mismatch: expected $expected, got $actual. Refusing unverified native libraries.")
    }
}

val verifyFfmpegPackage = tasks.register<VerifyFfmpegPackage>("verifyFfmpegPackage") {
    archive.set(layout.file(providers.provider { ffmpegVerification.singleFile }))
    expectedChecksum.set(rootProject.layout.projectDirectory.file("third_party/ffmpeg-pcm-android-7.1.3-r2.sha256"))
}
tasks.named("preBuild") { dependsOn(verifyFfmpegPackage) }
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(verifyFfmpegPackage)
}
tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}

dependencies {
    implementation(ffmpegPackage)
    add(ffmpegVerification.name, ffmpegPackage)
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-container:1.11.1")
    implementation("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.mockito:mockito-core:5.15.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
