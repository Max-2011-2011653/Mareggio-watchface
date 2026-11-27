plugins {
    id("com.android.application")
}

android {
    namespace = "it.mareggio.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.mareggio.watchface"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
