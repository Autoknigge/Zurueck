plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// AdMob-IDs von Zurück (öffentliche Kennungen). Überschreibbar per GitHub-Secrets ADMOB_APP_ID / ADMOB_BANNER_ID / ADMOB_OPEN_ID.
// Überschreibbar per GitHub-Secrets ADMOB_APP_ID / ADMOB_BANNER_ID / ADMOB_OPEN_ID.
val admobAppId: String =
    (project.findProperty("ADMOB_APP_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671~3936295368"
val admobBannerId: String =
    (project.findProperty("ADMOB_BANNER_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671/8756930090"
val admobOpenId: String =
    (project.findProperty("ADMOB_OPEN_ID") as String?)?.takeIf { it.isNotBlank() }
        ?: "ca-app-pub-7178658520690671/4817685086"

val ksFile = project.findProperty("KEYSTORE_FILE") as String?
val ksPassword = project.findProperty("KEYSTORE_PASSWORD") as String?
val ksAlias = project.findProperty("KEY_ALIAS") as String?
val ksKeyPassword = project.findProperty("KEY_PASSWORD") as String?
val hasSigning = !ksFile.isNullOrBlank() && !ksPassword.isNullOrBlank() &&
    !ksAlias.isNullOrBlank() && !ksKeyPassword.isNullOrBlank()

android {
    namespace = "de.autoknigge.zurueck"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.autoknigge.zurueck"
        minSdk = 29
        targetSdk = 36
        // Version zählt mit jedem Commit auf main: 1.0, 1.1 … 1.9, 2.0 (Play Store verlangt steigende Codes).
        val commits = runCatching {
            providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
                .standardOutput.asText.get().trim().toInt()
        }.getOrDefault(0)
        val step = (commits - 1).coerceAtLeast(0)
        versionCode = 100 + commits
        versionName = "${1 + step / 10}.${step % 10}"

        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
        buildConfigField("String", "ADMOB_OPEN_ID", "\"$admobOpenId\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasSigning) {
            create("release") {
                storeFile = file(ksFile!!)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}
