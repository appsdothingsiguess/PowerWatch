plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.joey.powerwatch"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.joey.powerwatch"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKey = rootProject.file("signing/powerwatch-release.p12")
    val releasePassword = rootProject.file("signing/password.txt")
    if (releaseKey.exists() && releasePassword.exists()) {
        signingConfigs {
            create("powerwatchRelease") {
                storeFile = releaseKey
                storePassword = releasePassword.readText().trim()
                keyAlias = "powerwatch"
                keyPassword = storePassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            if (releaseKey.exists() && releasePassword.exists()) {
                signingConfig = signingConfigs.getByName("powerwatchRelease")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}