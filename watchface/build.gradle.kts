plugins {
    id("com.android.application")
}

android {
    namespace = "it.mareggio.watchface"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.mareggio.watchface2"
        minSdk = 33
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
