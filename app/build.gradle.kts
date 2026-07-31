plugins {
    id("com.android.application")
}

android {
    namespace = "com.poolaim.overlay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.poolaim.overlay"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "2.10"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
}
