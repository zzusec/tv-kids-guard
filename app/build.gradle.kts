import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProperties = Properties()
val signingPropertiesFile = providers.gradleProperty("tvguard.signingFile")
    .orElse(providers.environmentVariable("TVGUARD_SIGNING_PROPERTIES"))
    .orElse("${System.getProperty("user.home")}/.config/tv-kids-guard/signing.properties")
    .get().let { file(it) }
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}
val signingFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasStableSigning = signingFields.all { !signingProperties.getProperty(it).isNullOrBlank() }
if (!hasStableSigning && signingProperties.isNotEmpty()) {
    throw GradleException("签名配置不完整，需要 storeFile、storePassword、keyAlias、keyPassword")
}

android {
    namespace = "com.family.tvguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.family.tvguard"
        minSdk = 19
        targetSdk = 33
        versionCode = 36
        versionName = "2.5.5"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        debug {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(hasStableSigning) {
            "正式版需要私有签名配置。设置 TVGUARD_SIGNING_PROPERTIES；普通开发请用 assembleDebug。"
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Last Robolectric release that can exercise the Android 4.4 runtime.
    testImplementation("org.robolectric:robolectric:4.12.2")
}
