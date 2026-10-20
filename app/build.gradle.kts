import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.maiconerick.xumbra"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maiconerick.xumbra"
        minSdk = 29
        targetSdk = 35
        versionCode = 38
        versionName = "2.15.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // signed with the local debug key so it installs over debug builds
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")

    // SSH terminal to the Pi
    implementation("com.hierynomus:sshj:0.38.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}

// Every assembled APK is also copied to C:\DEV\Android\APKs as
// xUmbra-<version>-<variant>-<yyyyMMdd-HHmm>.apk; only this app's two newest copies are kept.
val apkArchiveDir = file("C:/DEV/Android/APKs")
val apkAppName = "xUmbra"
val apkVersionName = android.defaultConfig.versionName ?: "dev"
tasks.configureEach {
    val variant = when (name) { "assembleDebug" -> "debug"; "assembleRelease" -> "release"; else -> return@configureEach }
    val outDir = layout.buildDirectory.dir("outputs/apk/$variant")
    doLast {
        val apk = outDir.get().asFile.listFiles { f -> f.extension == "apk" }?.maxByOrNull { it.lastModified() } ?: return@doLast
        apkArchiveDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
        val target = File(apkArchiveDir, "$apkAppName-$apkVersionName-$variant-$stamp.apk")
        apk.copyTo(target, overwrite = true)
        apkArchiveDir.listFiles { f -> f.name.startsWith("$apkAppName-") && f.extension == "apk" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
        println("APK copied to $target")
    }
}
