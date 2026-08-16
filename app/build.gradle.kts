import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("com.mikepenz.aboutlibraries.plugin")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}

aboutLibraries {
    export {
        // Disable build metadata i.e. the build timestamp, to allow reproducible builds
        includeMetaData = false
    }
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.LINK
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val shouldSign = keystorePropertiesFile.canRead()

val keystoreProperties = Properties()

if (shouldSign) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}


android {
    lint {
        baseline = file("lint-baseline.xml")
    }

    if (shouldSign) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }

    namespace = "tofu.gg.mitchy"
    compileSdk = 36

    defaultConfig {
        applicationId = "tofu.gg.mitchy"
        minSdk = 23
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The version name comes from -PversionName (set by the release workflow from the
        // commit message, e.g. "release 2.3.4"), or falls back to the default below.
        val releaseVersionName = (project.findProperty("versionName") as? String) ?: "2.3.3"
        versionName = releaseVersionName
        // Derive a monotonic version code from the version name, e.g. "2.3.4" -> 20304, so
        // version bumps stay installable as upgrades. Pre-release suffixes are folded in:
        // "2.3.8-beta" -> 20308, "2.3.8-beta2" -> 203082, so each pre-release build is a strict
        // upgrade over the previous one. Falls back to the default code when the
        // name isn't a <major>.<minor>.<patch> number.
        versionCode = run {
            val parts = releaseVersionName.trimStart('v').split('.').take(3)
            val numbers = parts.map { it.substringBefore('-').toIntOrNull() }
            if (numbers.size == 3 && numbers.all { it != null }) {
                val base = numbers[0]!! * 10000 + numbers[1]!! * 100 + numbers[2]!!
                // Append the pre-release build number (e.g. "-beta2" -> 2), if present.
                val suffixNumber = Regex("-(?:beta|alpha|rc)?(\\d+)").find(parts[2])
                    ?.groupValues?.get(1)?.toIntOrNull()
                if (suffixNumber != null) base * 10 + suffixNumber else base
            } else {
                20303
            }
        }
    }
    buildTypes {
        named("release").configure {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (shouldSign) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
        }
        named("debug").configure {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    packaging {
        resources.excludes.add("META-INF/atomicfu.kotlin_module")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
    implementation("androidx.paging:paging-runtime-ktx:3.5.1")
    //TODO: https://stackoverflow.com/questions/64290141/android-studio-class-file-for-com-google-common-util-concurrent-listenablefuture#64733418
    implementation("com.google.guava:guava:29.0-android")

    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material:material")
    // Android Studio Preview support
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.runtime:runtime-livedata")





    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")


    // Jsoup
    implementation("org.jsoup:jsoup:1.23.1")
    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")
    implementation("com.github.bumptech.glide:recyclerview-integration:4.16.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta01")
    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // FAB Speed Dial
    implementation("com.leinardi.android:speed-dial:3.3.0")
    // Material Progress Bar
    implementation("me.zhanghai.android.materialprogressbar:library:1.6.1")
    // Colormath (CSS color parsing)
    implementation("com.github.ajalt:colormath:1.4.1")
    // Application Crash Reports for Android (ACRA)
    implementation("ch.acra:acra-mail:5.13.1")
    implementation("ch.acra:acra-dialog:5.13.1")
    // AboutLibraries
    implementation("com.mikepenz:aboutlibraries-core:14.2.1")
    implementation("com.mikepenz:aboutlibraries:14.2.1")
    // Jodd Util (for mimetypes)
    implementation("org.jodd:jodd-util:6.3.0")
}
